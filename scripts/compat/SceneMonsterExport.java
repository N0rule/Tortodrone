import java.util.*;
import com.threerings.config.ConfigManager;
import com.threerings.projectx.dungeon.config.*;
import org.tortodrone.world.FireCatalog;

/** Stock attack timings and action graphs; AI choices remain separate prepared rules. */
public final class SceneMonsterExport {
    private SceneMonsterExport() { }
    @FunctionalInterface public interface ScriptedTalks {Map<String,Object> prepare(com.threerings.projectx.config.ProjectXActionConfig.StartConversation start)throws java.io.IOException;}
    public static void export(ConfigManager cm,Map<String,String> out,String prefix,DungeonActorConfig.Monster monster)throws Exception {
        export(cm,out,prefix,monster,null);
    }
    public static void export(ConfigManager cm,Map<String,String> out,String prefix,DungeonActorConfig.Monster monster,ScriptedTalks talks)throws Exception {
        if(monster.attacks==null)return;
        var indexes=new ArrayList<String>();
        for(int i=0;i<monster.attacks.length;i++){
            var ref=monster.attacks[i];if(ref==null||ref.ref==null)continue;
            var attack=AttackConfig.b(cm,ref.ref);String p=prefix+"attack."+i+".";indexes.add(Integer.toString(i));
            out.put(p+"land",Integer.toString(attack.Qh()));out.put(p+"clear",Integer.toString(attack.Qf()));
            if(attack instanceof AttackConfig.Chain chain){
                boolean unconditional=chain.condition instanceof com.threerings.tudey.config.ConditionConfig.Always;
                // The native default Action condition executes a null SpawnActor, which returns true.
                if(chain.condition instanceof com.threerings.tudey.config.ConditionConfig.Action condition
                        &&condition.action!=null&&condition.action.getClass()==com.threerings.tudey.config.ActionConfig.SpawnActor.class
                        &&((com.threerings.tudey.config.ActionConfig.SpawnActor)condition.action).actor==null)unconditional=true;
                if(unconditional&&chain.attack>=0&&chain.attack<monster.attacks.length&&monster.attacks[chain.attack]!=null){
                    out.put(p+"chain-next",Integer.toString(chain.attack));out.put(p+"chain-rearm",Integer.toString(attack.Qg()));
                }else out.put(p+"chain-unsupported",chain.condition==null?"null":chain.condition.getClass().getName());
            }
            out.put(p+"blended",Boolean.toString(attack.blended));
            out.put(p+"monster-movement",Boolean.toString(attack.monsterMovement));
            var impulse=attack.impulseReceived;
            if(impulse!=null){
                if(impulse.translation==null)throw new java.io.IOException("Missing native attack motion translation");
                new org.tortodrone.world.SceneAttackMotion(impulse.delay,impulse.duration,impulse.translation.x,impulse.translation.y);
                out.put(p+"impulse-delay",Integer.toString(impulse.delay));out.put(p+"impulse-duration",Integer.toString(impulse.duration));
                out.put(p+"impulse-x",Float.toString(impulse.translation.x));out.put(p+"impulse-y",Float.toString(impulse.translation.y));
            }
            out.put(p+"fire-speed",Float.toString(attack.fireSpeed));
            var fires=new ArrayList<String>();
            if(attack.fireRefs!=null)for(int f=0;f<attack.fireRefs.length;f++){
                var fireRef=attack.fireRefs[f];if(fireRef==null||fireRef.ref==null)continue;
                var fire=cm.a(FireActionConfig.class,fireRef.ref);String fp=p+"fire."+f+".";fires.add(Integer.toString(f));
                out.put(fp+"delay",Integer.toString(fire.delay));
                if(talks!=null&&ref.ref.getName().equals("Character/NPC/Scripted/Knights/Talk, Conversation")){
                    if(!(fire.action instanceof com.threerings.projectx.config.ProjectXActionConfig.StartConversation start)||!(start.target instanceof com.threerings.tudey.config.TargetConfig.Activator)||start.conversation==null)throw new java.io.IOException("Native scripted Talk footprint changed");
                    // Archive check_npc_scripts.py/talk: attack activators are the NPC,
                    // not a knight; the recorded Talk target is its PartyMembers.
                    var restored=(com.threerings.projectx.config.ProjectXActionConfig.StartConversation)start.clone();restored.target=new DungeonTargetConfig.PartyMembers();
                    out.put(fp+"scene-action",org.tortodrone.resources.Json.stringify(talks.prepare(restored)));
                }else out.put(fp+"action",FireCatalog.actionJson(FireCatalogExport.readFireAction(cm,fireRef.ref,fire)));
            }
            out.put(p+"fires",String.join(",",fires));
        }
        out.put(prefix+"attacks",String.join(",",indexes));
    }
}
