package org.tortodrone.server.network;

import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.character.WorldProfile;
import static org.tortodrone.support.TestWire.check;

/** Deterministically checks shutdown's lock ordering; no SQLite or sockets are needed. */
public final class WorldStatePollerTest {
    public static void main(String[] args)throws Exception {
        var field=WorldStatePoller.class.getDeclaredField("timer");field.setAccessible(true);
        check(field.get(null)==null,"isolated poller starts stopped");
        boolean[] shutdown={false};
        var executor=new ScheduledThreadPoolExecutor(1){
            @Override public List<Runnable> shutdownNow(){
                check(!Thread.holdsLock(WorldStatePoller.class),"shutdown must not hold the poller monitor needed by terminated()");
                shutdown[0]=true;return super.shutdownNow();
            }
        };
        field.set(null,executor);
        try {
            var registration=WorldStatePoller.join(null,new CharacterStore(),new WorldProfile(1,1,1,0,0,0,List.of()),1,"fixture");
            registration.close();WorldStatePoller.awaitStopped();
            check(shutdown[0]&&executor.isTerminated(),"last registration shuts down and drains its executor");
            check(field.get(null)==null,"retired executor does not block a new lifecycle");
        }finally{field.set(null,null);executor.shutdownNow();}
        System.out.println("PASS: poller shutdown runs outside the registry monitor and drains cleanly");
    }
}
