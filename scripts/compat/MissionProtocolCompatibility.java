import java.io.*;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import com.threerings.presents.net.*;
import com.threerings.projectx.data.*;
import com.threerings.projectx.mission.data.*;

/** Installed native reader/application oracle; no live gameplay is claimed. */
public final class MissionProtocolCompatibility {
    private static void append(ByteArrayOutputStream out,byte[] frame){out.write(frame,4,frame.length-4);}
    private static void apply(com.threerings.presents.dobj.DEvent event,PlayerObject player){if(event instanceof com.threerings.presents.dobj.CompoundEvent compound){for(var child:compound.xE())apply(child,player);}else event.b(player);}
    public static void main(String[] args)throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        var bytes=new ByteArrayOutputStream();var codec=new SessionCodec();
        append(bytes,HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"));append(bytes,HandshakeCodec.authenticationResponse((short)2,true,"synthetic","MissionUser"));
        append(bytes,codec.bootstrap(1,1001,"127.0.0.1",47624));append(bytes,codec.missionObject((short)3));append(bytes,codec.player((short)4,1001,1,"MissionUser"));
        append(bytes,codec.completedMissions(1001,List.of()));
        var before=new WorldProfile(1,1,1,0,0,1,List.of(),new KnightProgress(1,1,0,1));var after=new WorldProfile(1,1,1,0,0,2,List.of(),new KnightProgress(1,2,10,1));
        var receipt=new OpeningCompletion(OpeningMission.SECURING_CAMP,1770000000000L,null);
        append(bytes,codec.missionSettlement(1001,before,after,receipt,1770000000001L));
        append(bytes,codec.completedMissions(1001,List.of(receipt,new OpeningCompletion(OpeningMission.ANCIENT_GENERATOR,1770000000002L,"EASY"))));
        var source=new org.tortodrone.world.ActorState(21,1,"ReaderFixture",0,1000,0,0,0,0,0,new StarterCatalog.Model("Character/PC/Default",Map.of()));
        var panels=new ArrayList<org.tortodrone.world.SceneConversation.Node>();var dialogs=new ArrayList<org.tortodrone.world.SceneConversation>();
        for(var mission:List.of(OpeningMission.FIRST_CONTACT,OpeningMission.WELCOME_HAVEN)){
            var conversation=org.tortodrone.world.OpeningComlinks.get(mission);String node=conversation.initial();
            while(!node.isEmpty()){
                var panel=conversation.nodes().get(node);panels.add(panel);dialogs.add(conversation);append(bytes,codec.conversationDialog(1001,10,conversation,node,source,1770000000010L+panels.size()));
                node=panel.responses().get(panel.responses().size()-1).destination();
            }
        }
        var input=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));input.qe();input.qe();
        Object bootstrap=((BootstrapNotification)input.qe()).yh();
        var missionOid=bootstrap.getClass().getField("missionOid");if(missionOid.getInt(bootstrap)!=SessionCodec.MISSION_OID)throw new AssertionError("Bootstrap must name the served mission object");
        var object=(MissionObject)((ObjectResponse)input.qe()).yk();if(object.xL()!=SessionCodec.MISSION_OID||object.expansionPrices==null||!object.expansionPrices.isEmpty())throw new AssertionError("Native MissionObject shape differs");
        var player=(PlayerObject)((ObjectResponse)input.qe()).yk();apply(((EventNotification)input.qe()).yj(),player);apply(((EventNotification)input.qe()).yj(),player);
        var dialog=(MissionCompleteDialogInfo)player.sceneDialog;
        if(player.rank.rank!=1||player.rank.subrank!=2||player.prestige!=10||player.completedMissions.size()!=1||!player.completedMissions.iterator().next().id.equals("securing_camp")||dialog.prevRank.rank!=1||dialog.prevRank.subrank!=1||!dialog.newRank||!dialog.immediate||dialog.prestige!=10||!dialog.icon.equals("ui/icon/difficulty/hard.png")||dialog.rewards.size()!=20)throw new AssertionError("Native mission settlement fields differ");
        var counts=new HashMap<String,Integer>();for(var reward:dialog.rewards){if(!Boolean.TRUE.equals(reward.kW().get("_createLocked")))throw new AssertionError("Completion reward lost binding argument");counts.merge(reward.getName(),1,Integer::sum);}
        if(!counts.equals(Map.of("Rarity/Fire Crystal/Cracked",15,"Rarity/Spark of Life",5)))throw new AssertionError("Native reward grouping differs");
        apply(((EventNotification)input.qe()).yj(),player);
        var generator=player.completedMissions.f("ancient_generator");
        if(generator==null||!generator.difficulty.name().equals("EASY")||player.completedMissions.size()!=2)throw new AssertionError("Reconnect receipt/difficulty framing differs");
        for(int i=0;i<panels.size();i++){
            apply(((EventNotification)input.qe()).yj(),player);var panel=panels.get(i);var definition=dialogs.get(i);var opened=(ConversationDialogInfo)player.sceneDialog;
            var image=panel.userInterface().arguments().get("image");if(image==org.tortodrone.world.FireCatalog.NullValue.INSTANCE)image=null;
            if(!opened.bundle.equals("mission")||!opened.name.equals(definition.name())||!opened.message.equals(panel.message())||!opened.model.getName().equals("character/npc/crew/model_kora.dat")||!opened.model.kW().get("Variant").equals("comlink")
                ||!opened.userInterface.getName().equals(panel.userInterface().name())||!Objects.equals(opened.userInterface.kW().get("image"),image)||!opened.music.getName().equals("Custom/Mission/Comlink")||opened.responses.length!=panel.responses().size())throw new AssertionError("Native comlink panel differs: "+panel.id());
            for(int r=0;r<opened.responses.length;r++)if(!opened.responses[r].equals(panel.responses().get(r).text()))throw new AssertionError("Native choice differs");
        }
        if(input.available()!=0)throw new AssertionError("Unread comlink bytes");
        System.out.println("PASS: stock reader accepts all twelve First Contact B / Welcome to Haven Kora panels, comlink UI/images/music and native choices");
        System.out.println("PASS: stock bootstrap Mission OID, served MissionObject, rank/prestige/native completion entry/dialog, exact bound reward references and reconnect difficulty snapshot (serialization only)");
    }
}
