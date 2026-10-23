package org.tortodrone.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.tortodrone.account.AccountStore;
import org.tortodrone.world.SceneRegistry;

/** Small lifecycle owner shared by the listener, terminal and optional WebUI. */
public final class ServerControl {
    public enum State { STARTING, RUNNING, STOPPING, STOPPED, FAILED }
    public record Snapshot(String version,State state,String endpoint,Instant startedAt,long uptimeSeconds,int maps,int items,
            List<SessionRegistry.Snapshot> sessions,List<SceneRegistry.Status> scenes,
            List<org.tortodrone.world.SceneCatalog.MapScene> templates,RuntimeMetrics metrics,String failure) { }
    private final String version;
    private final Path data;
    private final SessionRegistry sessions=new SessionRegistry();
    private final AtomicLong ids=new AtomicLong();
    private State state=State.STOPPED;
    private ServerSocket listener;
    private ServerConsole console;
    private SceneRegistry scenes;
    private Instant startedAt;
    private long startedNanos,stoppedNanos;
    private String endpoint="",failure="";
    private int itemCount;
    private boolean startRequested=true,exitRequested;
    private final java.util.concurrent.ConcurrentHashMap<Long,java.net.Socket> sockets=new java.util.concurrent.ConcurrentHashMap<>();
    void connection(long id,java.net.Socket socket){sockets.put(id,socket);}
    void disconnected(long id){sockets.remove(id);}
    public boolean kick(long id)throws IOException{var socket=sockets.get(id);if(socket==null)return false;socket.close();return true;}
    public synchronized void restart(){stop();startRequested=true;notifyAll();}
    public ServerControl(String version,Path data) {this.version=version;this.data=data;}
    public Path dataDirectory() {return data;}
    public SessionRegistry sessions() {return sessions;}
    long nextSessionId() {return ids.incrementAndGet();}
    public synchronized Snapshot snapshot() {
        long uptime=startedAt==null?0:Math.max(0,((stoppedNanos==0?System.nanoTime():stoppedNanos)-startedNanos)/1000000000);
        return new Snapshot(version,state,endpoint,startedAt,uptime,scenes==null?0:scenes.catalog().scenes().size(),itemCount,
            sessions.snapshots(),scenes==null?List.of():scenes.statuses(),scenes==null?List.of():scenes.catalog().scenes(),RuntimeMetrics.capture(),failure);
    }
    public synchronized boolean awaitStart(boolean persistent) throws InterruptedException {
        while(!startRequested&&!exitRequested&&persistent)wait();
        if(exitRequested||!startRequested)return false;
        startRequested=false;state=State.STARTING;failure="";startedAt=Instant.now();startedNanos=System.nanoTime();stoppedNanos=0;return true;
    }
    synchronized void resources(SceneRegistry scenes,int items) {this.scenes=scenes;this.itemCount=items;}
    synchronized void listening(ServerSocket socket,ServerConsole commands) throws IOException {
        if(exitRequested||state==State.STOPPING){socket.close();return;}
        listener=socket;console=commands;endpoint=socket.getInetAddress().getHostAddress()+":"+socket.getLocalPort();state=State.RUNNING;
    }
    synchronized void stopped() {listener=null;console=null;stoppedNanos=System.nanoTime();if(state!=State.FAILED)state=State.STOPPED;notifyAll();}
    synchronized void failed(Exception error) {failure=error.getClass().getSimpleName()+": "+error.getMessage();state=State.FAILED;}
    public synchronized void start() {
        if(state==State.STOPPED||state==State.FAILED){startRequested=true;notifyAll();}
    }
    public synchronized void stop() {
        startRequested=false;
        if(state==State.STARTING||state==State.RUNNING)state=State.STOPPING;
        if(listener!=null)try{listener.close();}catch(IOException ignored){ }
    }
    public synchronized void exit() {exitRequested=true;stop();notifyAll();}
    public void command(String text) throws IOException {
        ServerConsole target;
        synchronized(this){if(state!=State.RUNNING||console==null)throw new IOException("Server is not running");target=console;}
        target.executeLine(text);
    }
    public synchronized boolean exiting() {return exitRequested;}
}
