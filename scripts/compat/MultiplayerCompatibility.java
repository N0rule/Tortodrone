import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.world.*;
import com.threerings.presents.net.*;
import com.threerings.presents.dobj.*;
import com.threerings.projectx.data.actor.Knight;
import com.threerings.tudey.data.TudeySceneObject;
import com.threerings.tudey.dobj.SceneDeltaEvent;

/** Retail-reader regression for two connection identities, native membership events and private reveals. */
public final class MultiplayerCompatibility {
    public static void main(String[] args)throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        var registry=new SceneRegistry(SceneCatalog.load(Path.of("content/maps")));
        var model=new StarterCatalog.Model("Character/PC/Default",Map.of());
        var a=new ActorState(42,1002,"Fixture-A",256,1,0,0,0,0,0,0,1,model,false);
        var b=new ActorState(43,1004,"Fixture-B",256,1,2,0,0,0,0,0,1,model,false);
        var reveal=List.of(new StarterCatalog.Model("Upgrade/Prize Box/Surge Prize Box",Map.of()),new StarterCatalog.Model("Material/Brimstone",Map.of()));
        var opening=new ActorState(a.id(),a.clientOid(),a.name(),a.color(),a.created(),0,0,0,0,0,265,1050,model,false,null,null,null,null,new SceneKnightState(reveal,0));
        for(int observer:new int[]{1002,1004}){
            var codec=new SessionCodec();var bytes=new ByteArrayOutputStream();
            append(bytes,HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"));
            append(bytes,HandshakeCodec.authenticationResponse((short)2,true,"synthetic","Fixture"));
            append(bytes,codec.scene((short)3,registry.entry(),List.of(b,a)));
            append(bytes,codec.occupantChanges(registry.entry().oid(),Map.of(a.id(),a.clientOid(),b.id(),b.clientOid()),List.of(b)));
            append(bytes,codec.occupantChanges(registry.entry().oid(),Map.of(b.id(),b.clientOid()),List.of(b,a)));
            append(bytes,codec.sceneDelta(observer,registry.entry().oid(),0,(short)0,0,1000,(short)50,List.of(a,b),List.of(),new int[0],Map.of(),false));
            append(bytes,codec.sceneDelta(observer,registry.entry().oid(),0,(short)0,1000,1050,(short)50,List.of(),List.of(opening),new int[0],Map.of(a.id(),a),false));
            append(bytes,codec.occupantChanges(registry.entry().oid(),Map.of(a.id(),a.clientOid(),b.id(),b.clientOid()),List.of(b)));
            var knight=new org.tortodrone.character.Knight(1,"Fixture-A","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0);
            var participant=new PartyRegistry.Participant(1002,42,0,knight,new WorldProfile(42,1,1,0,0,1,List.of()),new SceneHealth.State(200,200,0),SceneStatus.State.NORMAL);
            // A public Training party can be subscribed while the client still displays Haven.
            // Its one-time HUD inputs must already describe the destination party.
            append(bytes,codec.party((short)4,new PartyRegistry.View(1600000000,1,List.of(participant),true),registry.entry()));
            append(bytes,codec.sceneDelta(observer,140,0,(short)0,0,1050,(short)50,List.of(opening),List.of(),new int[0],Map.of(),true));
            var in=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));in.qe();in.qe();
            var scene=(TudeySceneObject)((ObjectResponse<?>)in.qe()).yk();
            check(scene.gE(1002)==42&&scene.gE(1004)==43,"snapshot DSet sorts by native key instead of join order");
            apply(in,scene);check(scene.gE(1002)==0,"native leave removes the departed connection");
            apply(in,scene);
            check(scene.occupantInfo.size()==2&&scene.gE(1002)==42&&scene.gE(1004)==43,"native key lookup works even when join order reverses OIDs");
            for(var info:scene.occupantInfo){
                var occupant=(com.threerings.tudey.data.TudeyOccupantInfo)info;
                int body=(Integer)info.getClass().getField("bodyOid").get(info);
                check(occupant.pawnId==(body==1002?42:43),"connection maps only to its own pawn");
            }
            var initial=(SceneDeltaEvent)((EventNotification)in.qe()).yj();
            var own=(Knight)Arrays.stream(initial.aej()).filter(actor->actor.vw()==42).findFirst().orElseThrow();
            var delta=(SceneDeltaEvent)((EventNotification)in.qe()).yj();
            var old=own;
            own=(Knight)delta.aek()[0].apply(own);
            var interpolated=(Knight)old.a(own,1000,1050,1049,(Knight)old.copy(null));
            check(interpolated.adU()!=265,"native interpolation cannot start a data-dependent action before its data record");
            check(own.vw()==42&&own.adU()==265,"activity delta retains source actor identity");
            var data=(List<?>)field(own,"_activityData");
            check(data.size()==2,"native opening always has two config references");
            check(!data.get(0).equals(data.get(1)),"owner and observer receive actual reward appearance");
            apply(in,scene);
            check(scene.occupantInfo.size()==1&&scene.occupants.size()==1,"native departure removes info and OID together");
            var party=(com.threerings.projectx.dungeon.data.DungeonPartyObject)((ObjectResponse<?>)in.qe()).yk();
            check(party.getClass()==com.threerings.projectx.dungeon.data.LevelPartyObject.class&&party.memberLimit==8&&party.hasPickups,"public Training enables the native quickbar and revive-capable level HUD");
            var nativeMember=party.members.iterator().next();
            check(nativeMember.portrait!=null&&nativeMember.portrait.getName().equals("character/pc/model.dat")&&nativeMember.weapons.length==4,"native HUD portrait and four equipped weapon references");
            var revealed=(SceneDeltaEvent)((EventNotification)in.qe()).yj();
            var initialReferences=(List<?>)field(revealed.aej()[0],"_activityData");
            check(initialReferences.equals(data),"initial subscription and deltas share the same actual reward icons");
            check(in.available()==0,"complete framing");
        }
        System.out.println("PASS: stock two-player occupant mapping, public Training quickbar/level party and shared Prize Box reward appearances");
    }
    private static Object field(Object object,String name)throws Exception{for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass())try{var field=type.getDeclaredField(name);field.setAccessible(true);return field.get(object);}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
    private static void apply(com.threerings.io.v in,DObject object)throws Exception{var event=((EventNotification)in.qe()).yj();if(event instanceof CompoundEvent compound)for(var child:compound.xE())child.b(object);else event.b(object);}
    private static void append(ByteArrayOutputStream out,byte[] frame){out.write(frame,4,frame.length-4);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
