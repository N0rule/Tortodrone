import java.nio.file.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.tudey.config.*;
import com.threerings.projectx.dungeon.config.*;
import org.tortodrone.resources.Json;

/** Stock attack configuration plus a read-only captured activity ledger; no behavior is invented. */
public final class MonsterChainCompatibility {
    private static final String GIANT="Character/NPC/Monster/Boss/Tutorial, Ironwood Giant/Ironwood Giant";
    public static void main(String[] args)throws Exception {
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,args[0]+"/rsrc");
        var cm=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");cm.init();
        var monster=(DungeonActorConfig.Monster)new ConfigReference<ActorConfig>(GIANT).a(cm,ActorConfig.class).ai(cm);
        var fields=new TreeMap<String,String>();SceneMonsterExport.export(cm,fields,"",monster);
        var head=(AttackConfig.Chain)AttackConfig.b(cm,monster.attacks[0].ref);var follow=AttackConfig.b(cm,monster.attacks[1].ref);
        if(head.attack!=1||!fields.get("attack.0.chain-next").equals("1")||!fields.get("attack.0.chain-rearm").equals(Integer.toString(head.Qg()))
                ||!fields.get("attack.1.land").equals(Integer.toString(follow.Qh()))||!fields.get("attack.1.clear").equals(Integer.toString(follow.Qf())))throw new AssertionError("Native chain fields lost");
        fields.put("config",GIANT);fields.put("kind","Monster");fields.put("native-type","com.threerings.projectx.dungeon.data.actor.Monster");fields.put("health",Float.toString(monster.health));
        new org.tortodrone.world.SceneActions.Spawn(fields,true);
        if(head.Qg()!=1000)throw new AssertionError("Captured Giant rearm differs from supported client: "+head.Qg());
        if(Files.size(Path.of(args[1]))>8_000_000)throw new AssertionError("Capture ledger exceeds bound");
        var ledger=(Map<?,?>)Json.parse(Files.readString(Path.of(args[1])));int pairs=0;
        for(Object floor:(List<?>)ledger.get("floors"))for(Object raw:((Map<?,?>)((Map<?,?>)floor).get("actors")).values()){
            var actor=(Map<?,?>)raw;if(!GIANT.equals(actor.get("name")))continue;
            var attacks=(List<?>)actor.get("attacks");
            for(int i=0;i+1<attacks.size();i+=2){
                var first=(Map<?,?>)attacks.get(i);var second=(Map<?,?>)attacks.get(i+1);
                long gap=((Number)second.get("started")).longValue()-((Number)first.get("started")).longValue();
                if(((Number)first.get("index")).intValue()!=0||((Number)second.get("index")).intValue()!=1||gap<=head.Qg()||gap>head.Qg()+100)throw new AssertionError("Captured Giant pair contradicts authored chain: "+first+" -> "+second);
                pairs++;
            }
        }
        if(pairs<3)throw new AssertionError("Insufficient captured Giant attack pairs");
        System.out.println("PASS: native Giant chain 0 -> 1, strict 1000 ms rearm and follow-up timing; "+pairs+" captured pairs corroborate the chain (targeting AI not covered)");
    }
}
