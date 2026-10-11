package org.tortodrone.server;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import org.tortodrone.server.network.SessionLog;
import static org.tortodrone.support.TestWire.check;

public final class MonitoringTest {
    public static void main(String[] args) throws Exception {
        var registry=new SessionRegistry();registry.connected(1,new InetSocketAddress("127.0.0.1",1234));
        check(registry.find(1).accountId()==null&&registry.find(1).knightId()==null,"TCP connection has no verified identity");
        var initial=registry.snapshots();SessionLog events=registry.observe(1,message->{});
        events.authenticating("fixture-build");events.authenticated(7,"Alice");events.selected(2,42,"Jane");events.scene(1,10,"Haven");events.activity();
        var snapshot=registry.find(1);
        check(snapshot.accountId()==7&&snapshot.rosterId()==2&&snapshot.knightId()==42&&snapshot.sceneId()==1&&snapshot.instanceId()==10,"identifier scopes preserved");
        check(initial.getFirst().accountId()==null,"published snapshots immutable");
        registry.connected(2,new InetSocketAddress("127.0.0.1",1235));registry.disconnected(1);events.authenticated(7,"Alice");
        check(registry.find(1)==null&&registry.find(2).accountId()==null,"late callbacks cannot resurrect disconnected session");
        var control=new ServerControl("fixture",Path.of("/tmp"));
        check(control.awaitStart(false)&&control.snapshot().state()==ServerControl.State.STARTING,"initial lifecycle starts once");
        control.stop();control.stopped();check(!control.awaitStart(false),"terminal completion exits");
        check(control.snapshot().uptimeSeconds()>=0,"monotonic run duration remains available after stopping");
        control.start();check(control.awaitStart(false),"desktop start can restart stopped core");control.exit();control.stopped();check(!control.awaitStart(false),"exit prevents restart");
        check(ServerOptions.parse(new String[]{"--nogui","--config","/tmp/server file.properties"},Path.of("default")).mode()==ServerOptions.Mode.TERMINAL,"flags/path parse without desktop");
        check(!ServerOptions.parse(new String[]{"--nogui"},Path.of("default")).desktopRequested(),"nogui bypasses desktop availability");
        try{ServerOptions.parse(new String[]{"--gui","--nogui"},Path.of("default"));throw new AssertionError("Conflicting modes accepted");}catch(java.io.IOException expected){ }
        var metrics=RuntimeMetrics.capture();check(metrics.processors()>0&&metrics.heapUsed()>=0&&metrics.heapMax()>0,"real JVM metrics");
        System.out.println("PASS: immutable session identity, late-event cleanup, lifecycle restart/exit and launch arguments");
    }
}
