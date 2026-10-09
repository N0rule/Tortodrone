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
    private static ScheduledExecutorService timer;
    static synchronized Registration join(WorldSession session,CharacterStore characters,WorldProfile profile,long revision,String name) {
        var registration=new Registration(session,characters,profile,revision,name);registrations.add(registration);
        if(timer==null) {
            timer=Executors.newSingleThreadScheduledExecutor(r->{var thread=new Thread(r,"WorldStateRefresh");thread.setDaemon(true);return thread;});
            timer.scheduleWithFixedDelay(WorldStatePoller::poll,250,250,TimeUnit.MILLISECONDS);
        }
        return registration;
    }
    private static synchronized void remove(Registration registration) {
        registrations.remove(registration);
        if(registrations.isEmpty()&&timer!=null){timer.shutdownNow();timer=null;}
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
