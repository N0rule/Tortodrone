package org.tortodrone.web;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.tortodrone.server.ServerControl;
import static org.tortodrone.support.TestWire.check;

public final class MetricHistoryTest {
    public static void main(String[] args)throws Exception {
        var clock=new AtomicLong(10_000_000);var control=new ServerControl("fixture",Path.of("/tmp/unused-metric-history"));
        try(var history=new MetricHistory(control::snapshot,control.sessions(),clock::get,false)) {
            var sessions=control.sessions();sessions.connected(1,new java.net.InetSocketAddress("127.0.0.1",1234));
            try{sessions.connected(1,new java.net.InetSocketAddress("127.0.0.1",1234));throw new AssertionError("duplicate session accepted");}catch(IllegalStateException expected){}
            history.sample();var first=history.read(5).getLast();
            check(first.connected()==1&&first.connections()==1&&first.players()==0,"connections are measured, duplicate IDs and unauthenticated clients do not inflate players");
            sessions.disconnected(1);sessions.disconnected(1);history.sample();check(history.read(5).getLast().disconnected()==1,"disconnect counted only once");
            for(int i=0;i<740;i++){clock.addAndGet(5000);history.sample();}
            check(history.read(60).size()<=MetricHistory.CAPACITY,"history is bounded");
            check(history.read(5).size()==61&&history.read(15).size()==181,"history ranges select real sampling times");
            check(history.read(60).getFirst().time()>=clock.get()-3_600_000,"old samples expire");
            check(history.read(5).getLast().runtime().heapUsed()>0,"runtime values are measured");
            try{history.read(120);throw new AssertionError("unbounded range accepted");}catch(IllegalArgumentException expected){}
        }
        System.out.println("PASS: bounded server-side monitoring history and exact session counters");
    }
}
