package org.tortodrone.server.network;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.character.WorldProfile;

/** One metadata read per deployment every 250 ms, outside scene and session locks.
 * The existing account watcher/transactional permission checks remain authoritative.
 * Full inventory is read only for a changed revision. No idle polling thread survives. */
final class WorldStatePoller {
    private static final class Poller extends ScheduledThreadPoolExecutor {
        Poller(){super(1,r->{var thread=new Thread(r,"WorldStateRefresh");thread.setDaemon(true);return thread;});}
        @Override protected void terminated(){synchronized(WorldStatePoller.class){retiring.remove(this);}super.terminated();}
    }
    static final class Registration implements AutoCloseable {
        final WorldSession session;
        final CharacterStore characters;
        final int accountId,knightId,worldId;
        final long authRevision;
        final String name;
        Registration(WorldSession session,CharacterStore characters,WorldProfile profile,long revision,String name) {
            this.session=session;this.characters=characters;accountId=profile.accountId();knightId=profile.knightId();
            worldId=profile.worldId();authRevision=revision;this.name=name;
        }
        @Override public void close(){remove(this);}
    }
    private static final Set<Registration> registrations=new LinkedHashSet<>();
    private static final Set<ScheduledExecutorService> retiring=new LinkedHashSet<>();
    private static ScheduledExecutorService timer;
    static synchronized Registration join(WorldSession session,CharacterStore characters,WorldProfile profile,long revision,String name) {
        var registration=new Registration(session,characters,profile,revision,name);registrations.add(registration);
        if(timer==null) {
            timer=new Poller();
            timer.scheduleWithFixedDelay(WorldStatePoller::poll,250,250,TimeUnit.MILLISECONDS);
        }
        return registration;
    }
    private static synchronized void remove(Registration registration) {
        registrations.remove(registration);
        if(registrations.isEmpty()&&timer!=null){timer.shutdownNow();retiring.add(timer);timer=null;}
    }
    /** Test teardown can wait until an in-flight SQLite snapshot releases its connection. */
    static void awaitStopped() throws InterruptedException {
        List<ScheduledExecutorService> stopped;
        synchronized(WorldStatePoller.class){stopped=List.copyOf(retiring);}
        for(var executor:stopped)if(!executor.awaitTermination(5,TimeUnit.SECONDS))
            throw new IllegalStateException("World state poller did not stop during test cleanup");
        synchronized(WorldStatePoller.class){retiring.removeAll(stopped);}
    }
    private static void poll() {
        List<Registration> active;synchronized(WorldStatePoller.class){active=List.copyOf(registrations);}
        var deployments=new LinkedHashMap<Path,List<Registration>>();
        for(var registration:active)deployments.computeIfAbsent(registration.characters.worldStorageFile(),key->new ArrayList<>()).add(registration);
        for(var members:deployments.values()) {
            try {
                var states=members.getFirst().characters.worldStates();
                for(var member:members)try{member.session.refreshState(member,states.get(member.worldId));}
                    catch(IOException|RuntimeException failure){member.session.refreshFailed(member,failure);}
            }catch(IOException|RuntimeException failure){for(var member:members)member.session.refreshFailed(member,failure);}
        }
    }
}
