package org.tortodrone.server.network;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import org.tortodrone.core.party.PartyView;
import org.tortodrone.world.SceneRegistry;
import org.tortodrone.world.SceneSimulation;

/** The session's bounded scene/party publication mailbox and its single delivery worker.
 *
 * <p>The mailbox only coalesces and bounds frames; encoding and socket writes stay with the
 * owning session through the two sinks. Ordinary scene snapshots are replaced by the newest
 * one, while native data-dependent activity boundaries keep their first record in a small
 * ordered queue. Party events keep their own 64-entry queue. A full queue stops the delivery
 * worker before it can grow without bound.
 */
final class SessionPublications {
    @FunctionalInterface interface SceneSink { void publish(Publication publication) throws IOException; }
    @FunctionalInterface interface PartySink { void deliver(PartyView.Event event) throws IOException; }
    @FunctionalInterface interface FailureSink { void failed(Throwable failure); }

    record Publication(SceneRegistry.Instance scene,int generation,SceneSimulation.Snapshot snapshot) { }

    private final Object monitor=new Object();
    private Publication pending,lastOffered;
    private final ArrayDeque<Publication> activityPublications=new ArrayDeque<>();
    private boolean publicationOverflow,deliveryStopped;
    private Thread worker;
    private final ArrayDeque<PartyView.Event> partyEvents=new ArrayDeque<>();
    private boolean partyRefreshQueued,partyOverflow;

    /** Start the delivery worker once per session; a later call keeps the existing worker. */
    void start(String name,SceneSink scenes,PartySink parties,FailureSink failure) {
        synchronized(monitor) {
            if(worker!=null||deliveryStopped)return;
            worker=Thread.ofVirtual().name(name).start(()->deliver(scenes,parties,failure));
        }
    }

    /** Coalesce ordinary frames while retaining native data-dependent activity boundaries. */
    void offer(int currentGeneration,SceneRegistry.Instance place,int generation,
            SceneSimulation.Snapshot snapshot) {
        synchronized(monitor) {
            if(deliveryStopped||generation!=currentGeneration)return;
            if(lastOffered!=null&&lastOffered.generation()!=generation){lastOffered=pending=null;activityPublications.clear();}
            if(lastOffered!=null&&snapshot.timestamp()-lastOffered.snapshot().timestamp()<=0)return;
            var next=new Publication(place,generation,snapshot);
            if(lastOffered!=null&&snapshot.startsDataActivity(lastOffered.snapshot())){
                // Native interpolation requires the first data-bearing record, even if
                // the delivery thread is busy while later movement records arrive.
                if(activityPublications.size()>=16)publicationOverflow=true;else activityPublications.addLast(next);
                pending=null;
            }else pending=next;
            lastOffered=next;monitor.notifyAll();
        }
    }

    void offerParty(PartyView.Event event) {
        synchronized(monitor) {
            if(deliveryStopped)return;
            if(event instanceof PartyView.Refresh) {
                if(partyRefreshQueued)return;
                partyRefreshQueued=true;
            }
            if(partyEvents.size()>=64)partyOverflow=true;else partyEvents.addLast(event);
            monitor.notifyAll();
        }
    }

    /** Stop accepting frames and wake the worker so it can exit after its current delivery. */
    void stop() {
        synchronized(monitor) {
            deliveryStopped=true;pending=lastOffered=null;activityPublications.clear();monitor.notifyAll();
        }
    }

    private void deliver(SceneSink scenes,PartySink parties,FailureSink failure) {
        try {
            while(true) {
                Publication publication;List<PartyView.Event> events;
                synchronized(monitor) {
                    while(pending==null&&activityPublications.isEmpty()&&partyEvents.isEmpty()
                            &&!partyOverflow&&!publicationOverflow&&!deliveryStopped)monitor.wait();
                    if(deliveryStopped)return;
                    if(partyOverflow||publicationOverflow)throw new IOException("Scene/party publication queue full");
                    events=List.copyOf(partyEvents);partyEvents.clear();partyRefreshQueued=false;
                    publication=activityPublications.pollFirst();
                    if(publication==null){publication=pending;pending=null;}
                }
                for(var event:events)parties.deliver(event);
                if(publication!=null)scenes.publish(publication);
            }
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(IOException|RuntimeException problem){failure.failed(problem);}
    }
}
