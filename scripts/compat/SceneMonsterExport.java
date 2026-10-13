import java.util.*;
import com.threerings.config.ConfigManager;
import com.threerings.projectx.dungeon.config.*;
import org.tortodrone.world.FireCatalog;

/** Stock attack timings and action graphs; AI choices remain separate prepared rules. */
public final class SceneMonsterExport {
    private SceneMonsterExport() { }
    public static void export(ConfigManager cm,Map<String,String> out,String prefix,DungeonActorConfig.Monster monster)throws Exception {
        if(monster.attacks==null)return;
        var indexes=new ArrayList<String>();
        for(int i=0;i<monster.attacks.length;i++){
            var ref=monster.attacks[i];if(ref==null||ref.ref==null)continue;
            var attack=AttackConfig.b(cm,ref.ref);String p=prefix+"attack."+i+".";indexes.add(Integer.toString(i));
            out.put(p+"land",Integer.toString(attack.Qh()));out.put(p+"clear",Integer.toString(attack.Qf()));
            out.put(p+"fire-speed",Float.toString(attack.fireSpeed));
            var fires=new ArrayList<String>();
            if(attack.fireRefs!=null)for(int f=0;f<attack.fireRefs.length;f++){
                var fireRef=attack.fireRefs[f];if(fireRef==null||fireRef.ref==null)continue;
                var fire=cm.a(FireActionConfig.class,fireRef.ref);String fp=p+"fire."+f+".";fires.add(Integer.toString(f));
                out.put(fp+"delay",Integer.toString(fire.delay));
                out.put(fp+"action",FireCatalog.actionJson(FireCatalogExport.readFireAction(cm,fireRef.ref,fire)));
            }
            out.put(p+"fires",String.join(",",fires));
        }
        out.put(prefix+"attacks",String.join(",",indexes));
    }
}
