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
            System.out.println("CONVERSATION "+name+" initial="+Json.stringify(data(conversation.initialAction,0)));
            for(var raw:conversation.nodes){var node=(ConversationConfig.SimpleNode)raw;System.out.println("NODE "+node.name+" "+node.message);
                for(var response:node.responses)System.out.println("RESPONSE "+Json.stringify(data(response,0)));
            }
        }
        System.out.println("PASS: five opening mission configs, current native prestige lookups and retained conversation footprints resolved (not a gameplay test)");
    }
    private static Object data(Object value,int depth)throws Exception {
        if(value==null||value instanceof Number||value instanceof Boolean||value instanceof String)return value;
        if(value instanceof Enum<?> e)return e.name();if(depth>=7)return value.getClass().getName();
        if(value instanceof ConfigReference<?> ref)return Map.of("reference",ref.getName(),"arguments",data(ref.kW(),depth+1));
        if(value instanceof Map<?,?> map){var result=new TreeMap<String,Object>();for(var e:map.entrySet())result.put(e.getKey().toString(),data(e.getValue(),depth+1));return result;}
        if(value.getClass().isArray()){var result=new ArrayList<>();for(int i=0;i<Array.getLength(value);i++)result.add(data(Array.get(value,i),depth+1));return result;}
        var result=new TreeMap<String,Object>();result.put("class",value.getClass().getName());
        for(var f:value.getClass().getFields())if(!Modifier.isStatic(f.getModifiers())&&!Modifier.isTransient(f.getModifiers()))result.put(f.getName(),data(f.get(value),depth+1));
        return result;
    }
}
