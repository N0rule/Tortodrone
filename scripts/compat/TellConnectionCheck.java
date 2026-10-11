import java.nio.file.*;
import java.util.*;
import com.samskivert.util.at;
import com.threerings.crowd.chat.client.j;
import com.threerings.crowd.chat.data.UserMessage;
import com.threerings.presents.dobj.*;
import com.threerings.util.Name;

/** Original tell marshaller/reader, private player-object delivery and lifecycle checks. */
public final class TellConnectionCheck {
    static final class Reply implements j.a {
        boolean done;String reason,away;long idle=-1;
        public void a(long duration,String message){idle=duration;away=message;done=true;}
        public void aT(String failure){reason=failure;done=true;}
    }
    static Reply tell(GameMasterConnectionCheck.Endpoint sender,String target,String text)throws Exception {
        var reply=new Reply();sender.client.bU(j.class).a(new Name(target),text,reply);
        WorldConnectionCheck.waitFor(()->reply.done);return reply;
    }
    static List<UserMessage> listen(GameMasterConnectionCheck.Endpoint endpoint) {
        var messages=new ArrayList<UserMessage>();
        endpoint.player.a(new com.threerings.presents.dobj.l(){public void a(MessageEvent event){
            if(event.getName().equals("crowd.chat")&&event.yd()[0] instanceof UserMessage message)messages.add(message);
        }});return messages;
    }
    static void success(Reply reply) {
        if(reply.reason!=null||reply.idle!=0||reply.away!=null)throw new AssertionError("Unexpected tell result: "+reply.reason);
    }
    static void failure(Reply reply,String expected) {
        if(reply.reason==null||!reply.reason.contains(expected)||reply.idle!=-1)throw new AssertionError("Expected tell refusal: "+expected+", got reason="+reply.reason+", idle="+reply.idle);
    }
    public static void main(String[] args)throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        Thread thread=Thread.currentThread();at queue=new at(){
            public void postRunnable(Runnable task){WorldConnectionCheck.tasks.add(task);}
            public boolean isDispatchThread(){return Thread.currentThread()==thread;}public boolean isRunning(){return true;}};
        try(var sender=new GameMasterConnectionCheck.Endpoint(args,queue,"LanPlayer","Town-Knight");
                var target=new GameMasterConnectionCheck.Endpoint(args,queue,"PeerPlayer","Peer-Knight")) {
            var incoming=listen(target);var outgoing=listen(sender);
            target.enter(2);
            success(tell(sender,"pEeR-kNiGhT","private 🌍"));
            WorldConnectionCheck.waitFor(()->incoming.size()==1);
            var message=incoming.getFirst();
            if(!message.speaker.toString().equals("Town-Knight")||!message.message.equals("private 🌍")||message.mode!=0
                    ||!outgoing.isEmpty()||!sender.messages.isEmpty()||!target.messages.isEmpty())throw new AssertionError("Tell privacy/identity/mode mismatch");
            success(tell(target,"Town-Knight","reply"));WorldConnectionCheck.waitFor(()->outgoing.size()==1);
            if(!outgoing.getFirst().speaker.toString().equals("Peer-Knight"))throw new AssertionError("Reply speaker mismatch");
            failure(tell(sender,"LanPlayer","account is not a knight"),"not online");
            failure(tell(sender,"Peer-Knight","x".repeat(257)),"256 characters");
            target.enter(1);success(tell(sender,"Peer-Knight","after travel"));WorldConnectionCheck.waitFor(()->incoming.size()==2);
            target.client.xb().b(target.scene.xL(),target.subscription);
            // Stock unsubscribe queues a local object action. Dispatch it before
            // posting the RPC that acts as a server-side ordering barrier.
            boolean[] dispatched={false};queue.postRunnable(()->dispatched[0]=true);
            WorldConnectionCheck.waitFor(()->dispatched[0]);
            String[] barrier={null};target.broadcast("unsubscribe barrier",reason->barrier[0]=reason);
            WorldConnectionCheck.waitFor(()->barrier[0]!=null);
            failure(tell(sender,"Peer-Knight","unsubscribed"),"not online");
            target.enter(2);success(tell(sender,"Peer-Knight","after re-entry"));WorldConnectionCheck.waitFor(()->incoming.size()==3);
            // Spend the remainder of this burst, then verify refusal without successful feedback.
            boolean limited=false;
            for(int i=0;i<6;i++){var result=tell(sender,"Peer-Knight","burst");if(result.reason!=null){failure(result,"slow down");limited=true;break;}success(result);}
            if(!limited||!sender.messages.isEmpty()||!target.messages.isEmpty())throw new AssertionError("Tell budget/privacy mismatch");
            System.out.println("PASS: stock tell RPC/success listener, private player-object Unicode delivery, reply, cross-map travel/re-entry, offline names, bounds and shared burst limit");
        }
        try(var sender=new GameMasterConnectionCheck.Endpoint(args,queue,"LanPlayer","Town-Knight")) {
            failure(tell(sender,"Peer-Knight","disconnected"),"not online");
            System.out.println("PASS: disconnected knight receives no tell and sender gets stock failure callback");
        }
    }
}
