import java.nio.file.*;
import java.util.*;
import com.samskivert.util.at;
import com.threerings.presents.client.*;
import com.threerings.presents.dobj.*;
import com.threerings.projectx.client.aU;
import com.threerings.projectx.data.*;
import com.threerings.projectx.town.data.TownSceneObject;
import com.threerings.crowd.chat.data.UserMessage;
import com.threerings.tudey.dobj.SceneDeltaEvent;
import com.threerings.util.Name;

/** Live roles and original staff broadcast RPC; all data belongs to CheckClientWorld. */
public final class GameMasterConnectionCheck {
    static final class Endpoint implements AutoCloseable {
        final Client client;
        final PlayerObject player;
        TownSceneObject scene;
        com.threerings.presents.dobj.t<TownSceneObject> subscription;
        final List<UserMessage> messages=new ArrayList<>();
        com.threerings.tudey.data.actor.Actor actor;
        int roleUpdates;
        PlayerObject.Type oldRole,newRole;
        Endpoint(String[] args,at queue,String user,String knight)throws Exception {
            client=new Client(new ProjectXCredentials(new Name(user),"lan-test-password"),queue);
            client.b("127.0.0.1",new int[]{Integer.parseInt(args[0])},new int[0]);
            client.dr(Files.readString(Path.of(args[1])).trim());client.ds("gm-check");client.dt("projectx");client.xg();
            WorldConnectionCheck.waitFor(client::isLoggedOn);player=(PlayerObject)client.xc();
            Class<?> receiver=Class.forName("com.threerings.whirled.zone.a.f");
            Object handler=java.lang.reflect.Proxy.newProxyInstance(receiver.getClassLoader(),new Class[]{receiver},(proxy,method,values)->null);
            client.xd().a((w)Class.forName("com.threerings.whirled.zone.a.a").getConstructor(receiver).newInstance(handler));
            var reply=new WorldConnectionCheck.Reply();((aU)client.bU(aU.class)).b(new Name(knight),null,reply);
            WorldConnectionCheck.waitFor(()->reply.done);if(reply.reason!=null)throw new AssertionError(reply.reason);
            player.a(new com.threerings.tudey.dobj.a(){public void c(SceneDeltaEvent event){
                if(event.aej()!=null)for(var added:event.aej())if(added.vw()==player.pawnId)actor=added;
                if(event.aek()!=null)for(var update:event.aek())if(update.vw()==player.pawnId)actor=(com.threerings.tudey.data.actor.Actor)update.apply(actor);
            }});
            enter(1);
        }
        void enter(int map)throws Exception {
            if(scene!=null)client.xb().b(scene.xL(),subscription);
            actor=null;scene=null;
            var place=WorldConnectionCheck.moveScene(client,map);
            subscription=new com.threerings.presents.dobj.t<TownSceneObject>(){
                public void a(TownSceneObject object){scene=object;WorldConnectionCheck.listenChat(object,messages);
                    object.a(new com.threerings.presents.dobj.s<ProjectXOccupantInfo>(){
                        public void a(EntryAddedEvent<ProjectXOccupantInfo> event){}
                        public void a(EntryRemovedEvent<ProjectXOccupantInfo> event){}
                        public void a(EntryUpdatedEvent<ProjectXOccupantInfo> event){
                            if(event.getName().equals("occupantInfo")){roleUpdates++;oldRole=event.ya().type;newRole=event.xZ().type;}
                        }});
                }
                public void a(int oid,ObjectAccessException error){throw new AssertionError(error);}};
            client.xb().a(place.oid,subscription);WorldConnectionCheck.waitFor(()->scene!=null);
            scene.tudeySceneService.adB();WorldConnectionCheck.waitFor(()->actor!=null);
        }
        ProjectXOccupantInfo occupant(int pawn) {
            for(var entry:scene.occupantInfo)if(((ProjectXOccupantInfo)entry).pawnId==pawn)return (ProjectXOccupantInfo)entry;
            return null;
        }
        void broadcast(String text,A.b listener){client.bU(com.threerings.crowd.chat.client.j.class).a(text,listener);}
        public void close(){if(client.isLoggedOn())client.Q(false);}
    }
    public static void main(String[] args)throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        Thread thread=Thread.currentThread();at queue=new at(){
            public void postRunnable(Runnable task){WorldConnectionCheck.tasks.add(task);}
            public boolean isDispatchThread(){return Thread.currentThread()==thread;}public boolean isRunning(){return true;}};
        Path data=Path.of(args[1]).getParent().getParent();
        try(var tools=new org.tortodrone.account.AccountStore(data)) {
            tools.setGameMaster("LanPlayer",true);
            try(var staff=new Endpoint(args,queue,"LanPlayer","Town-Knight");var peer=new Endpoint(args,queue,"PeerPlayer","Peer-Knight")) {
                if(staff.player.Nb()!=PlayerObject.Type.GAME_MASTER||!staff.player.tokens.Nl()||staff.player.tokens.isAdmin()
                    ||peer.player.Nb()!=PlayerObject.Type.NORMAL||peer.player.tokens.Nl())throw new AssertionError("GM login role/token mismatch");
                WorldConnectionCheck.waitFor(()->peer.occupant(staff.player.pawnId)!=null);
                var info=peer.occupant(staff.player.pawnId);
                if(info.type!=PlayerObject.Type.GAME_MASTER||!info.getIcon().endsWith("support.png")||info.pr!=ProjectXCodes.PrestigeRank.RANK_0)
                    throw new AssertionError("Verified GM support icon/type missing or prestige changed");
                var config=staff.actor.Mv();float x=staff.actor.tz().x,y=staff.actor.tz().y;
                String[] denied={null};peer.broadcast("forbidden",reason->denied[0]=reason);
                WorldConnectionCheck.waitFor(()->denied[0]!=null);
                if(!staff.messages.isEmpty()||!peer.messages.isEmpty())throw new AssertionError("Normal account broadcast delivered");
                peer.enter(2); // Broadcast crosses maps, unlike scene-local chat.
                staff.broadcast("GM announcement",reason->{throw new AssertionError(reason);});
                WorldConnectionCheck.waitFor(()->staff.messages.size()==1&&peer.messages.size()==1);
                if(peer.messages.get(0).mode!=4||!peer.messages.get(0).speaker.toString().equals("Town-Knight"))throw new AssertionError("Broadcast identity/mode mismatch");
                peer.enter(1);
                tools.setGameMaster("LanPlayer",false);
                WorldConnectionCheck.waitFor(()->staff.player.Nb()==PlayerObject.Type.NORMAL&&!staff.player.tokens.Nl()
                    &&peer.occupant(staff.player.pawnId)!=null&&peer.occupant(staff.player.pawnId).type==PlayerObject.Type.NORMAL&&peer.roleUpdates>0);
                if(peer.oldRole!=PlayerObject.Type.GAME_MASTER||peer.newRole!=PlayerObject.Type.NORMAL)
                    throw new AssertionError("Stock occupant observers did not receive the role transition");
                if(staff.actor.Mv()!=config||staff.actor.tz().x!=x||staff.actor.tz().y!=y)throw new AssertionError("Role change reset model or position");
                String[] revoked={null};staff.broadcast("revoked",reason->revoked[0]=reason);
                WorldConnectionCheck.waitFor(()->revoked[0]!=null);
                if(staff.messages.size()!=1||peer.messages.size()!=1)throw new AssertionError("Revoked GM broadcast delivered");
                tools.setGameMaster("LanPlayer",true);
                WorldConnectionCheck.waitFor(()->staff.player.Nb()==PlayerObject.Type.GAME_MASTER
                    &&peer.occupant(staff.player.pawnId).type==PlayerObject.Type.GAME_MASTER);
                System.out.println("PASS: stock GM role/support icon, current permissions, cross-map broadcast, live grant/revocation and stable actor model/position");
            }
        }finally{try(var tools=new org.tortodrone.account.AccountStore(data)){tools.setGameMaster("LanPlayer",false);}}
    }
}
