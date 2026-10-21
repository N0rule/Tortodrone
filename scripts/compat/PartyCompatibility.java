import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.world.*;
import com.threerings.projectx.dungeon.data.*;
import com.threerings.presents.net.*;

/** Stock reader validates stable party snapshots, notification keys and incremental HUD updates. */
public class PartyCompatibility {
    public static void main(String[] args)throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        var scenes=new SceneRegistry(SceneCatalog.load(Path.of("content/maps")));
        var registry=scenes.parties();var alice=knight("Party-Alice");var bob=knight("Party-Bob");
        var events=new ArrayList<PartyRegistry.Event>();
        try(var a=registry.join(1001,alice,profile(1),e->{});var b=registry.join(1002,bob,profile(2),events::add)){
            var before=registry.view(a);registry.invite(a,bob.name());var invite=(PartyRegistry.Invitation)events.stream().filter(e->e instanceof PartyRegistry.Invitation i&&i.add()).findFirst().orElseThrow();
            var codec=new SessionCodec();var bytes=new ByteArrayOutputStream();
            append(bytes,HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"));append(bytes,HandshakeCodec.authenticationResponse((short)2,true,"synthetic","Fixture"));
            append(bytes,codec.player((short)3,1002,2,"Fixture"));append(bytes,codec.partyInvitation(1002,invite));
            append(bytes,codec.party((short)4,before,scenes.entry()));
            registry.respond(b,"PARTY_INV:"+before.oid(),1);var joined=registry.view(a);append(bytes,codec.partyChanges(before,joined));
            registry.state(b,bob,profile(2),new SceneHealth.State(17,200,0,0),SceneStatus.State.NORMAL,false);var hurt=registry.view(a);append(bytes,codec.partyChanges(joined,hurt));
            registry.boot(a,alice.name());var remaining=registry.view(b);append(bytes,codec.partyChanges(hurt,remaining));
            append(bytes,codec.partyInvitation(1002,new PartyRegistry.Invitation(before.oid(),List.of(),0,false)));
            var input=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));input.qe();input.qe();
            var player=(com.threerings.projectx.data.PlayerObject)((ObjectResponse<?>)input.qe()).yk();apply(input,player);
            var nativeInvite=(com.threerings.projectx.social.data.Notification.PartyInvite)player.notifications.iterator().next();
            if(nativeInvite.key.partyOid!=before.oid()||nativeInvite.key.node!=null||!nativeInvite.getKey().equals("PARTY_INV:"+before.oid())||nativeInvite.members.length!=1||nativeInvite.inviterIndex!=0)throw new AssertionError("Native invite layout/key differs");
            var party=(LevelPartyObject)((ObjectResponse<?>)input.qe()).yk();
            if(party.members.iterator().next().portrait==null||party.members.iterator().next().weapons.length!=4||party.memberLimit!=8||party.restricted||!party.OD()||party.members.size()!=1||party.QN().Qu()!=1001)throw new AssertionError("Native party subtype/capacity/leader/invite controls differ");
            apply(input,party);if(party.members.size()!=2||party.health.size()!=2||party.status.size()!=2)throw new AssertionError("Native join HUD sets differ");
            apply(input,party);if(party.D(new com.threerings.util.Name(bob.name())).current!=17)throw new AssertionError("Native peer health not updated");
            apply(input,party);if(party.members.size()!=1||party.health.size()!=1||party.status.size()!=1||party.QN().Qu()!=1002)throw new AssertionError("Native leave/leader succession differs");
            apply(input,player);if(!player.notifications.isEmpty()||input.available()!=0)throw new AssertionError("Native notification removal or framing differs");
        }
        System.out.println("PASS: stock party invitation, LevelPartyObject, capacity/leader, member join/leave, peer health and notification removal");
    }
    private static Knight knight(String name){return new Knight(1,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0);}
    private static WorldProfile profile(int id){return new WorldProfile(id,id,1,0,0,1,List.of());}
    private static void append(ByteArrayOutputStream out,byte[] frame){out.write(frame,4,frame.length-4);}
    private static void apply(com.threerings.io.v input,com.threerings.presents.dobj.DObject object)throws Exception{var event=((EventNotification)input.qe()).yj();if(event instanceof com.threerings.presents.dobj.CompoundEvent compound)for(var child:compound.xE()){
            if(object instanceof PartyObject party&&child instanceof com.threerings.presents.dobj.EntryAddedEvent<?> added&&added.getName().equals("members")){
                if(party.health.f((Integer)added.lj())==null||party.status.f((Integer)added.lj())==null)throw new AssertionError("Member HUD notified before health/status entries");
            }
            child.b(object);
        }else event.b(object);}
}
