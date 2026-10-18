package org.tortodrone.server.network;

import java.net.*;
import java.util.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.protocol.*;
import static org.tortodrone.support.TestWire.check;

public final class ClientDiagnosticsTest {
    private static final class Log implements SessionLog {
        final List<String> lines=new ArrayList<>();boolean enabled;
        Log(boolean enabled){this.enabled=enabled;}
        public boolean debugEnabled(){return enabled;}
        public void debug(String text){lines.add(text);}
    }
    public static void main(String[] args)throws Exception {
        var values=new ArrayList<Object>(List.of("Linux","6.10","Fixture CPU","25.0.4","amd64","1920x1080","1280x720",false,1.25f));
        var log=new Log(true);var diagnostics=new ClientDiagnostics(log,1002,7,"Alice");diagnostics.hardware(values);
        String hardware=log.lines.getFirst();
        check(hardware.contains("accountId=7")&&hardware.contains("clientOid=1002")&&hardware.contains("account=\"Alice\"")&&hardware.contains("client-reported"),"report is bound to the authenticated session");
        for(String field:List.of("os=\"Linux\"","os-version=\"6.10\"","cpu=\"Fixture CPU\"","java=\"25.0.4\"","architecture=\"amd64\"","desktop=\"1920x1080\"","window=\"1280x720\"","fullscreen=false","ui-scale=1.25","gpu=unavailable","ram=unavailable"))check(hardware.contains(field),"correct native argument mapping: "+field);
        diagnostics.hardware(values);check(log.lines.size()==1,"repeated telemetry cannot flood logs");
        var second=new ClientDiagnostics(log,1004,8,"Bob");values.set(2,"Injected\n\r\u001b\u202e\u2028\"\\"+"x".repeat(2000));second.hardware(values);
        String sanitized=log.lines.getLast();check(sanitized.length()<1500&&!sanitized.contains("\n")&&!sanitized.contains("\r")&&!sanitized.contains("\u001b")&&!sanitized.contains("\u202e")&&!sanitized.contains("\u2028")&&sanitized.contains("\\\"\\\\"),"bounded quoted fields cannot inject terminal or log lines");
        check(sanitized.contains("accountId=8")&&!sanitized.contains("Alice"),"reports cannot reuse another account context");
        var quiet=new Log(false);new ClientDiagnostics(quiet,1006,9,"Normal").hardware(values);check(quiet.lines.isEmpty(),"normal mode emits no hardware report");
        values.set(2,null);new ClientDiagnostics(log,1008,10,"Nulls").hardware(values);check(log.lines.getLast().contains("cpu=unknown"),"nullable stock properties remain supported");
        var invalid=new ClientDiagnostics(log,1010,11,"Invalid");reject(()->invalid.hardware(List.of()));
        values.set(0,42);reject(()->invalid.hardware(values));values.set(0,"Linux");
        values.set(7,"false");reject(()->invalid.hardware(values));values.set(7,false);
        for(Object scale:List.of(Float.NaN,Float.POSITIVE_INFINITY,1)){values.set(8,scale);reject(()->invalid.hardware(values));}values.set(8,0f);invalid.hardware(values);
        try(var socket=new Socket(){public InetAddress getInetAddress(){return InetAddress.getLoopbackAddress();}public int getPort(){return 43210;}}){
            var request=new HandshakeCodec.Request((short)1,new byte[0],new byte[0],"fixture-build","Europe/Kyiv");
            var account=new AccountStore.Account(7,"Alice",true,1,null);
            ClientDiagnostics.joined(log,socket,12,account,request,"en","fixture-region",123);
            String joined=log.lines.getLast();
            for(String field:List.of("session=12","accountId=7","account=\"Alice\"","gm-at-login=true","port=43210","transport=TCP","client-build=\"fixture-build\"","timezone=\"Europe/Kyiv\"","language=\"en\"","reported-region=\"fixture-region\"","country=unavailable","login-ms=123"))check(joined.contains(field),"join field: "+field);
            ClientDiagnostics.joined(quiet,socket,12,account,request,"en","fixture-region",123);check(quiet.lines.isEmpty(),"normal mode suppresses join details");
        }
        System.out.println("PASS: authenticated debug context, verified hardware mapping, once-per-session output, normal suppression, sanitization, bounds and malformed reports");
    }
    private interface Action{void run()throws Exception;}
    private static void reject(Action action)throws Exception {try{action.run();throw new AssertionError("Malformed telemetry accepted");}catch(SessionDecoder.Rejected expected){}}
}
