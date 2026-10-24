package org.tortodrone.server;

import java.net.SocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.tortodrone.server.network.SessionLog;

/** Operational identities only. Never owns authentication, sockets or gameplay state. */
public final class SessionRegistry {
    public record Snapshot(long id, String remote, Instant connectedAt, Instant lastActivity,
            String state, Integer accountId, String username, Integer rosterId, Integer knightId,
            String knight, Integer sceneId, Integer instanceId, String scene, String clientVersion) { }
    private final ConcurrentHashMap<Long, Snapshot> sessions = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.LongAdder connections = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder disconnections = new java.util.concurrent.atomic.LongAdder();
    public long connections() { return connections.sum(); }
    public long disconnections() { return disconnections.sum(); }
    public List<Snapshot> snapshots() { return sessions.values().stream().sorted(java.util.Comparator.comparingLong(Snapshot::id)).toList(); }
    public Snapshot find(long id) { return sessions.get(id); }
    public void connected(long id, SocketAddress remote) {
        var now=Instant.now();
        if(sessions.putIfAbsent(id,new Snapshot(id,String.valueOf(remote),now,now,"Connected",null,null,null,null,null,null,null,null,null))!=null)
            throw new IllegalStateException("Duplicate session ID");
        connections.increment();
    }
    public void disconnected(long id) { if (sessions.remove(id) != null) disconnections.increment(); }
    public SessionLog observe(long id, SessionLog delegate) {
        return new SessionLog() {
            public boolean debugEnabled() { return delegate.debugEnabled(); }
            public void debug(String message) { delegate.debug(message); }
            public void info(String message) { delegate.info(message); }
            public void warn(String message) { delegate.warn(message); }
            public void authenticating(String version) {
                sessions.computeIfPresent(id,(k,s)->new Snapshot(s.id(),s.remote(),s.connectedAt(),Instant.now(),"Authenticating",null,null,null,null,null,null,null,null,version));
            }
            public void authenticated(int account,String username) {
                var before=sessions.get(id);
                if(before!=null)delegate.debug("Authentication completed; duration-ms="+java.time.Duration.between(before.lastActivity(),Instant.now()).toMillis());
                sessions.computeIfPresent(id,(k,s)->new Snapshot(s.id(),s.remote(),s.connectedAt(),Instant.now(),"Authenticated",account,username,null,null,null,null,null,null,s.clientVersion()));
            }
            public void selected(int roster,int world,String name) {
                sessions.computeIfPresent(id,(k,s)->new Snapshot(s.id(),s.remote(),s.connectedAt(),Instant.now(),"Knight selected",s.accountId(),s.username(),roster,world,name,null,null,null,s.clientVersion()));
            }
            public void scene(int template,int instance,String name) {
                sessions.computeIfPresent(id,(k,s)->new Snapshot(s.id(),s.remote(),s.connectedAt(),Instant.now(),"In scene",s.accountId(),s.username(),s.rosterId(),s.knightId(),s.knight(),template,instance,name,s.clientVersion()));
            }
            public void activity() {
                sessions.computeIfPresent(id,(k,s)->new Snapshot(s.id(),s.remote(),s.connectedAt(),Instant.now(),s.state(),s.accountId(),s.username(),s.rosterId(),s.knightId(),s.knight(),s.sceneId(),s.instanceId(),s.scene(),s.clientVersion()));
            }
        };
    }
}
