import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.threerings.presents.dobj.*;
import com.threerings.presents.net.*;
import com.threerings.projectx.data.PlayerObject;
import com.threerings.projectx.config.ActionDesc;
import com.threerings.projectx.uplink.data.ReceivedMail;
import com.threerings.tudey.data.EntityKey;
import com.threerings.tudey.dobj.SceneDeltaEvent;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.resources.*;
import org.tortodrone.town.*;
import org.tortodrone.world.*;

/** Focused retail-reader oracle. No sockets, client process, preferences or persistent state. */
public final class GameplayCompatibility {
    private static final class Dispatch extends com.threerings.presents.client.x {
        InvocationRequestEvent request;
        @Override public void a(int oid,int code,int method,Object[] args,Transport transport){request=new InvocationRequestEvent(oid,code,method,args);}
    }
    public static void main(String[] args)throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        Locale.setDefault(Locale.ENGLISH);
        var dispatch=new Dispatch();var itemService=new com.threerings.projectx.item.data.ItemMarshaller();itemService.a(3,9,dispatch);itemService.n("Battle",(com.threerings.presents.client.A.a)null);
        var requestBytes=new ByteArrayOutputStream();var nativeWriter=new com.threerings.io.w(requestBytes);nativeWriter.bf(new ForwardEventRequest(dispatch.request));nativeWriter.flush();
        var removal=(SessionDecoder.Invocation)new HandshakeCodec().sessionRequest(requestBytes.toByteArray()).event();
        check(removal.service()==9&&removal.method()==2&&removal.arguments().getFirst().equals("Battle")&&removal.arguments().getLast() instanceof SessionDecoder.Listener listener&&listener.type().endsWith("$ConfirmMarshaller"),"native loadout removal request and confirmation callback");
        var items=ItemYaml.load(Path.of("content/items"));var maps=SceneCatalog.load(Path.of("content/maps"));
        var codec=new SessionCodec(items);var bytes=new ByteArrayOutputStream();
        append(bytes,HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"));
        append(bytes,HandshakeCodec.authenticationResponse((short)2,true,"synthetic","Alice"));
        var empty=new WorldProfile(42,1,1,0,0,1,List.of());
        var saved=new TownState.Loadout("Battle",Collections.nCopies(16,0L),0,0,null);
        var added=profile(saved);var marked=profile(new TownState.Loadout("Battle",saved.equipment(),0,0,null,true));
        append(bytes,codec.loadoutChanges(1001,empty,added));
        append(bytes,codec.loadoutChanges(1001,added,marked));
        append(bytes,codec.loadoutChanges(1001,marked,empty));
        var entities=new SceneEntities(maps.scene(294).content(),maps.scene(294).collision());
        var station=entities.station("OpenArsenal");
        append(bytes,codec.equipPolicy(1001,true));
        append(bytes,codec.controllerAction(1001,"show_arsenal_station",station));
        append(bytes,codec.equipPolicy(1001,false));
        var prompt=new InteractionState(station.id(),null,"Shop","3733");
        var potStatus=new SceneStatus.State(List.of(new StarterCatalog.Model("Fire",Map.of("Status Chance",new FireCatalog.EnumValue("com.threerings.projectx.dungeon.data.StatusCodes$StatusChance","ULTRA",5),"Status Power",new FireCatalog.EnumValue("com.threerings.projectx.dungeon.data.StatusCodes$StatusPower","MODERATE",2),"Chance",1f,"Power",80))),List.of(9000),SceneStatus.Modifiers.NORMAL);
        var data=new SceneKnightState(null,0,potStatus,KnightProgress.RECRUIT,null,null,0,0,1000);
        var knight=new ActorState(42,1001,"Fixture",256,1,0,0,0,0,SceneHealth.INVULNERABLE_FLAG,274,1000,
            new StarterCatalog.Model("Character/PC/Level",Map.of()),false,null,null,null,prompt,data);
        append(bytes,codec.sceneDelta(1001,10,0,(short)0,0,1000,(short)50,List.of(knight),List.of(),new int[0],Map.of(),true));
        var normal=new ActorState(42,1001,"Fixture",256,1,4,0,0,0,1,0,2000,knight.config(),false,null,null,null,null,data);
        append(bytes,codec.sceneDelta(1001,10,0,(short)0,1000,2000,(short)50,List.of(),List.of(normal),new int[0],Map.of(42,knight),true));
        var conversation=maps.scene(1).content().conversations().values().stream().filter(c->c.name().equals("n.hav_clock")).findFirst().orElseThrow();
        append(bytes,codec.conversationDialog(1001,10,conversation,conversation.initial(),station,123));
        var def=items.require("Usable/Confetti, Leaf");
        // Existing persisted auction mail is normalized on publication as well as new mail.
        var mail=new AuctionState.Mail(1,42,1,"%auction:s.auction_cancelled|%item:"+def.nameKey(),"%auction:m.auction_cancelled",0,null,false,false,0,"");
        append(bytes,codec.townResult(1001,(short)3,mail,empty));
        var in=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));in.qe();in.qe();
        var player=new PlayerObject();player.loadouts=new DSet<>(List.of(new com.threerings.projectx.data.Loadout("")));
        apply(in,player);check(player.loadouts.size()==2&&!player.loadouts.f("battle").pvp,"native entry add refreshes live loadouts");
        apply(in,player);check(player.loadouts.f("battle").pvp,"native entry update carries PvP designation");
        apply(in,player);check(player.loadouts.size()==1,"native entry remove retains the active loadout");
        apply(in,player);check(player.equipPolicy==PlayerObject.EquipPolicy.ANY,"station grants native ANY equipment policy first");
        var action=(MessageEvent)((EventNotification)in.qe()).yj();
        var controller=(com.threerings.tudey.config.ClientActionConfig.ControllerAction)action.yd()[0];
        check("show_arsenal_station".equals(controller.command)&&"".equals(controller.argument),"native controller receives the command in command, not argument");
        check(action.yd()[1] instanceof EntityKey.Entry e&&e.getKey().equals(Integer.valueOf(station.entity().key())),"arsenal action uses its actual placed entry");
        apply(in,player);check(player.equipPolicy==PlayerObject.EquipPolicy.NORMAL,"leaving station restores NORMAL");
        var initial=(SceneDeltaEvent)((EventNotification)in.qe()).yj();
        var pawn=(com.threerings.projectx.dungeon.data.actor.Dungeoneer)initial.aej()[0];
        check(pawn.bR(com.threerings.projectx.dungeon.data.actor.Dungeoneer.INVULNERABLE)&&pawn.Rc()==1000,"native invulnerability flag and hit timestamp reach observers");
        check(pawn.Nr() instanceof ActionDesc.Shop&&pawn.Ns() instanceof EntityKey.Entry,"native crown icon and placed interaction key");
        var delta=(SceneDeltaEvent)((EventNotification)in.qe()).yj();pawn=(com.threerings.projectx.dungeon.data.actor.Dungeoneer)delta.aek()[0].apply(pawn);
        check(!pawn.bR(64)&&pawn.bR(1)&&pawn.tz().x==4&&pawn.Nr()==null&&pawn.Ns()==null,"delta ends blinking, warps position and clears stale interaction");
        apply(in,player);var dialog=(com.threerings.projectx.data.ConversationDialogInfo)player.sceneDialog;
        check(dialog.sourceKey instanceof EntityKey.Entry,"placed conversation keeps its entry identity without a synthetic actor");
        var messages=new com.threerings.util.R("rsrc.i18n");var bundle=messages.gw(dialog.bundle);
        for(var map:maps.scenes())for(var c:map.content().conversations().values()){
            var nativeBundle=messages.gw(c.bundle());check(c.name().startsWith("~")||nativeBundle.gu(c.name()),"supported NPC name resolves: "+c.name());
            for(var node:c.nodes().values()){
                check(nativeBundle.gu(node.message()),"supported dialogue resolves: "+node.message());
                for(var response:node.responses())check(nativeBundle.gu(response.text()),"supported response resolves: "+response.text());
            }
        }
        check(bundle.cP(dialog.name).equals("Plinkop")&&!bundle.f(dialog.message,"Fixture").contains("m.hav_clock"),"Haven speaker and dialogue resolve through the retail bundle");
        for(String response:dialog.responses)check(!bundle.f(response,"Fixture").startsWith("r."),"responses resolve while dialogue is open");
        var reply=(InvocationResponseEvent)((EventNotification)in.qe()).yj();var received=(ReceivedMail)reply.yd()[0];
        String name=messages.gw("item").cR(def.nameKey());
        for(String key:List.of("s.auction_cancelled","m.auction_cancelled","s.auction_incomplete","m.auction_incomplete","s.auction_complete","m.auction_complete","s.bid_complete","m.bid_complete","s.bid_cancelled","m.bid_cancelled","s.bid_outbid","m.bid_outbid"))check(messages.gw("uplink").gu(key),"original auction mail template exists: "+key);
        check(received.g(messages.gw("uplink")).equals("Your auction cancelled: "+name)&&!received.h(messages.gw("uplink")).contains("m.auction_cancelled"),"native auction mail resolves original subject, item name and body");
        check(in.available()==0,"no trailing bytes or missing native fields");
        System.out.println("PASS: retail loadout DSet events/PvP, arsenal entry/policy, interaction icons, warp/invulnerability deltas, dialogue and auction-mail localization");
    }
    private static WorldProfile profile(TownState.Loadout loadout){return new WorldProfile(42,1,1,0,0,2,List.of(),KnightProgress.RECRUIT,new TownState(List.of(),List.of(),0,List.of(),List.of(),List.of(loadout)));}
    private static void apply(com.threerings.io.v in,DObject object)throws Exception {var event=((EventNotification)in.qe()).yj();if(event instanceof CompoundEvent compound)for(var child:compound.xE())child.b(object);else event.b(object);}
    private static void append(ByteArrayOutputStream out,byte[] frame){out.write(frame,4,frame.length-4);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
