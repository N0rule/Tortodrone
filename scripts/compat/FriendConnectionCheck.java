import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import com.samskivert.util.at;
import com.threerings.presents.client.A;
import com.threerings.projectx.social.data.*;
import com.threerings.projectx.uplink.data.*;
import com.threerings.projectx.uplink.client.UplinkService;
import com.threerings.util.Name;

/** Stock network check. Seed and run only against a disposable deployment; never an owner database. */
public final class FriendConnectionCheck {
    static final class Result implements A.c {
        boolean done;String reason;Object value;
        public void br(Object value){this.value=value;done=true;}
        public void aT(String reason){this.reason=reason;done=true;}
    }
    static void confirm(Consumer<WorldConnectionCheck.Reply> call,String refusal)throws Exception {
        var reply=new WorldConnectionCheck.Reply();call.accept(reply);WorldConnectionCheck.waitFor(()->reply.done);
        if(refusal==null?reply.reason!=null:reply.reason==null||!reply.reason.contains(refusal))throw new AssertionError("Unexpected friend RPC result: "+reply.reason);
    }
    @SuppressWarnings("unchecked") static List<ReceivedMail> mail(GameMasterConnectionCheck.Endpoint endpoint)throws Exception {
        var result=new Result();endpoint.client.bU(UplinkService.class).a((long[])null,result);WorldConnectionCheck.waitFor(()->result.done);
        if(result.reason!=null)throw new AssertionError(result.reason);return (List<ReceivedMail>)result.value;
    }
    static FriendEntry friend(GameMasterConnectionCheck.Endpoint endpoint,String name){return endpoint.player.friends.f(new Name(name));}
    static ReceivedMail pending(GameMasterConnectionCheck.Endpoint endpoint)throws Exception {
        return mail(endpoint).stream().filter(m->m.type==MailType.FRIEND&&!m.aaK()).findFirst().orElseThrow();
    }
    public static void main(String[] args)throws Exception {
        if(args.length==2&&args[0].equals("--seed")) {
            Path data=Path.of(args[1]);if(Files.exists(data.resolve("tortodrone.db")))throw new IllegalArgumentException("Seed requires a fresh disposable database");
            var content=org.tortodrone.resources.ContentManager.load(data.toAbsolutePath().getParent());
            var db=new org.tortodrone.storage.Database(data,true);
            for(String[] entry:List.of(new String[]{"LanPlayer","Friend-Alice"},new String[]{"PeerPlayer","Friend-Bob"})) {
                int id=db.create(entry[0],org.tortodrone.account.Passwords.protect("lan-test-password".toCharArray()),false);
                new org.tortodrone.character.CharacterStore(db,id,entry[0],content.items()).create(new org.tortodrone.character.Knight(0,entry[1],"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            }
            return;
        }
        if(args.length!=2)throw new IllegalArgumentException("Usage: FriendConnectionCheck --seed <fresh-disposable-data-dir> | <port> <public-key>");
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");Thread dispatch=Thread.currentThread();
        at queue=new at(){public void postRunnable(Runnable task){WorldConnectionCheck.tasks.add(task);}public boolean isDispatchThread(){return Thread.currentThread()==dispatch;}public boolean isRunning(){return true;}};
        try(var alice=new GameMasterConnectionCheck.Endpoint(args,queue,"LanPlayer","Friend-Alice")) {
            var social=alice.client.bU(SocialMarshaller.class);
            confirm(c->social.b(new Name("Friend-Bob"),"Friend request","Play together? 🌍\nMeet in Haven.",c),null);
            confirm(c->social.b(new Name("Friend-Bob"),"Again","",c),"already pending");
            confirm(c->social.b(new Name("Friend-Alice"),"Self","",c),"yourself");
            if(!alice.player.friends.isEmpty())throw new AssertionError("Request added an unaccepted friend");
            try(var bob=new GameMasterConnectionCheck.Endpoint(args,queue,"PeerPlayer","Friend-Bob")) {
                WorldConnectionCheck.waitFor(()->!bob.player.newMailInfo.isEmpty());
                var info=bob.player.newMailInfo.iterator().next();
                if(info.type!=MailType.FRIEND||!info.name.toString().equals("Friend-Alice"))throw new AssertionError("Native unread friend-mail sender/type mismatch");
                var request=pending(bob);
                if(!(request.attachment instanceof FriendRequest attachment)||attachment.knightId!=alice.player.knightId||!request.body.contains("🌍"))throw new AssertionError("Native FriendRequest attachment/Unicode mismatch");
                confirm(c->bob.client.bU(UplinkService.class).c(request.id,c),null);
                WorldConnectionCheck.waitFor(()->friend(alice,"Friend-Bob")!=null&&friend(bob,"Friend-Alice")!=null);
                if(!(friend(alice,"Friend-Bob").whereabouts instanceof Whereabouts.Online online)||!online.YL()||online.YM())throw new AssertionError("Native online state or private join restriction mismatch");
                String location=friend(alice,"Friend-Bob").whereabouts.getDescription();bob.enter(2);
                WorldConnectionCheck.waitFor(()->friend(alice,"Friend-Bob").whereabouts instanceof Whereabouts.Online current&&!current.location.equals("~")&&!current.location.equals(location));
                if(!mail(bob).stream().filter(m->m.id==request.id).findFirst().orElseThrow().aaK())throw new AssertionError("Native accepted-mail flag missing");
            }
            WorldConnectionCheck.waitFor(()->friend(alice,"Friend-Bob").whereabouts instanceof Whereabouts.Offline);
            try(var bob=new GameMasterConnectionCheck.Endpoint(args,queue,"PeerPlayer","Friend-Bob")) {
                WorldConnectionCheck.waitFor(()->friend(bob,"Friend-Alice")!=null&&friend(alice,"Friend-Bob").whereabouts instanceof Whereabouts.Online);
                confirm(c->social.p(new Name("fRiEnD-bOb"),c),null);
                WorldConnectionCheck.waitFor(()->alice.player.friends.isEmpty()&&bob.player.friends.isEmpty());
                var accepted=mail(bob).stream().filter(m->m.type==MailType.FRIEND).findFirst().orElseThrow();
                confirm(c->bob.client.bU(UplinkService.class).c(accepted.id,c),"accepted");
                confirm(c->social.b(new Name("Friend-Bob"),"Decline","No problem",c),null);
                WorldConnectionCheck.waitFor(()->!bob.player.newMailInfo.isEmpty());var declined=pending(bob);
                confirm(c->bob.client.bU(UplinkService.class).d(declined.id,c),null);
                if(mail(bob).stream().anyMatch(m->m.id==declined.id)||!bob.player.friends.isEmpty()||!alice.player.friends.isEmpty())throw new AssertionError("Decline changed friendship or retained request");
            }
        }
        System.out.println("PASS: unmodified native clients request an offline friend, read/accept/decline mail, observe mutual online/offline/travel/reconnect updates, remove friends and refuse duplicate/self/replayed requests");
    }
}
