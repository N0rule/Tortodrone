import java.lang.reflect.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.projectx.mission.config.MissionConfig;
import com.threerings.projectx.config.ConversationConfig;
import org.tortodrone.resources.Json;

/** Read-only current-client opening mission/response oracle; never samples reward tables. */
public final class OpeningMissionCompatibility {
    public static void main(String[] args)throws Exception {
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,args[0]+"/rsrc");
        var cm=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");cm.init();
        for(String name:List.of("Rank/01/1.1/Rhendon Mission Intro","Rank/01/1.2/The Ancient Generator","Rank/01/1.3/First Contact B","Rank/01/1.4/Crossing the Chasm B","Rank/02/2.1/Welcome to Haven!")){
            var mission=(MissionConfig.Rank)cm.d(MissionConfig.class,name).S(cm);
            var prepared=org.tortodrone.character.OpeningMission.byId(mission.id);
            if(prepared==null||!prepared.config().equals(name)||prepared.rank()!=mission.rank.rank||prepared.subrank()!=mission.rank.subrank||prepared.prestige()!=mission.W(cm)||mission.V(cm)!=0)throw new AssertionError("Prepared opening identity/rank/prestige differs from installed config");
            System.out.println("MISSION "+name+" "+mission.id+" rank="+mission.rank.rank+"-"+mission.rank.subrank+" prestige="+mission.V(cm)+"/"+mission.W(cm)+" requirement="+Json.stringify(data(mission.requirement,0)));
            var rewards=new ArrayList<org.tortodrone.character.OpeningMission.Reward>();
            for(var reward:mission.rewards){var table=reward.rewards.a(cm,com.threerings.projectx.dungeon.config.SpawnTableConfig.class);System.out.println("REWARD "+reward.rewards.getName()+" "+Json.stringify(data(table,0)));
                if(!(table instanceof com.threerings.projectx.dungeon.config.SpawnTableConfig.Original original)||!(original.root instanceof com.threerings.projectx.dungeon.config.SpawnTableConfig.CompoundEntry compound))throw new AssertionError("Opening reward table shape changed");
                for(var entry:compound.entries){
                    if(entry instanceof com.threerings.projectx.dungeon.config.SpawnTableConfig.VariantItemEntry variant&&variant.locked&&variant.variantTable==null&&variant.item.kW().isEmpty())rewards.add(new org.tortodrone.character.OpeningMission.Reward(variant.item.getName(),variant.count));
                    else if(entry instanceof com.threerings.projectx.dungeon.config.SpawnTableConfig.LockedItemEntry locked&&locked.item.kW().isEmpty())rewards.add(new org.tortodrone.character.OpeningMission.Reward(locked.item.getName(),locked.count));
                    else throw new AssertionError("Unimplemented reward subtype, variant table or arguments");
                }
            }
            if(!rewards.equals(prepared.rewards()))throw new AssertionError("Prepared locked reward references/counts differ from installed table");
        }
        for(String name:List.of("Mission/Rank/Rhendon RC Intro","Mission/Rank/First Contact B","Mission/Rank/Welcome to Haven")){
            var conversation=cm.d(ConversationConfig.class,name);
            if(!name.endsWith("Rhendon RC Intro"))verifyComlink(cm,conversation,name.endsWith("First Contact B")?org.tortodrone.character.OpeningMission.FIRST_CONTACT:org.tortodrone.character.OpeningMission.WELCOME_HAVEN);
            System.out.println("CONVERSATION "+name+" initial="+Json.stringify(data(conversation.initialAction,0)));
            for(var raw:conversation.nodes){var node=(ConversationConfig.SimpleNode)raw;System.out.println("NODE "+node.name+" "+node.message);
                for(var response:node.responses)System.out.println("RESPONSE "+Json.stringify(data(response,0)));
            }
        }
        System.out.println("PASS: five opening mission configs, current native prestige lookups and retained conversation footprints resolved (not a gameplay test)");
    }
    private static void verifyComlink(ConfigManager cm,ConversationConfig nativeConversation,org.tortodrone.character.OpeningMission mission)throws Exception {
        var prepared=org.tortodrone.world.OpeningComlinks.get(mission);
        var config=(MissionConfig.Conversation)((MissionConfig.Rank)cm.d(MissionConfig.class,mission.config()).S(cm)).requirement;
        var start=config.conversation;
        if(!(nativeConversation.initialAction instanceof com.threerings.projectx.config.ProjectXActionConfig.ConversationJump jump)||!jump.node.equals(mission==org.tortodrone.character.OpeningMission.FIRST_CONTACT?"node_1":"")||!prepared.bundle().equals(nativeConversation.bundle)||!nativeConversation.speakerName.isEmpty()&&!prepared.name().equals(nativeConversation.speakerName)
            ||!prepared.model().equals(model(start.model))||!prepared.music().equals(model(start.music))||start.sourceCloseAnimation!=null)throw new AssertionError("Opening comlink initial footprint/presentation changed: speaker="+nativeConversation.speakerName);
        for(var node:prepared.nodes().values()){
            var original=Arrays.stream(nativeConversation.nodes).filter(n->n.name.equals(node.id())).map(n->(ConversationConfig.SimpleNode)n).findFirst().orElseThrow();
            if(!node.message().equals(original.message)||node.responses().size()!=original.responses.length||original.animation!=null||original.sourceTransient!=null||!node.userInterface().equals(model(original.userInterface)))throw new AssertionError("Opening comlink panel/presentation changed: "+node.id());
            for(int i=0;i<original.responses.length;i++){
                var response=(ConversationConfig.SimpleResponse)original.responses[i];
                if(!response.text.equals(node.responses().get(i).text()))throw new AssertionError("Opening comlink choice changed");
                if(org.tortodrone.world.OpeningComlinks.terminal(mission,prepared,node.id(),i)){
                    if(!(response.action instanceof com.threerings.tudey.config.ActionConfig.Compound compound)||compound.actions.length!=0||compound.stopOnFailure)throw new AssertionError("Opening comlink terminal footprint changed");
                }else if(!(response.action instanceof com.threerings.projectx.config.ProjectXActionConfig.ClearDialog clear)||!(clear.target instanceof com.threerings.tudey.config.TargetConfig.Activator))throw new AssertionError("Opening comlink stripped response footprint changed");
            }
        }
        System.out.println("PASS: installed "+mission.id()+" opener, all six localized panels/choices, exact Kora portrait, music, image/UI references and stripped response footprints");
    }
    private static org.tortodrone.character.StarterCatalog.Model model(ConfigReference<?> reference){
        var args=new HashMap<String,Object>();reference.kW().forEach((key,value)->{if(value!=null&&!(value instanceof String))throw new AssertionError("Unimplemented comlink reference argument");args.put(key,value==null?org.tortodrone.world.FireCatalog.NullValue.INSTANCE:value);});
        return new org.tortodrone.character.StarterCatalog.Model(reference.getName(),args);
    }
    static Object data(Object value,int depth)throws Exception {
        if(value==null||value instanceof Number||value instanceof Boolean||value instanceof String)return value;
        if(value instanceof Enum<?> e)return e.name();if(depth>=12)return value.getClass().getName();
        if(value instanceof ConfigReference<?> ref)return Map.of("reference",ref.getName(),"arguments",data(ref.kW(),depth+1));
        if(value instanceof Map<?,?> map){var result=new TreeMap<String,Object>();for(var e:map.entrySet())result.put(e.getKey().toString(),data(e.getValue(),depth+1));return result;}
        if(value.getClass().isArray()){var result=new ArrayList<>();for(int i=0;i<Array.getLength(value);i++)result.add(data(Array.get(value,i),depth+1));return result;}
        var result=new TreeMap<String,Object>();result.put("class",value.getClass().getName());
        for(var f:value.getClass().getFields())if(!Modifier.isStatic(f.getModifiers())&&!Modifier.isTransient(f.getModifiers()))result.put(f.getName(),data(f.get(value),depth+1));
        return result;
    }
}
