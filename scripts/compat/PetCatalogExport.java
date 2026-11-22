import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.projectx.dungeon.config.*;
import com.threerings.projectx.sprites.config.BattleSpriteConfig;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.character.StarterCatalog;

/** Read-only stock skill-graph extraction. The archive supplies additional stripped server fields. */
public final class PetCatalogExport {
    public static void main(String[] args)throws Exception{
        Path game=Path.of(args[0]).toRealPath(),out=Path.of(args[1]).toAbsolutePath().normalize();
        if(Files.exists(out)||out.startsWith(game))throw new IOException("Choose a fresh directory outside the installation");
        var manager=NativeConfig.load(game);
        if(args.length==3){
            var previous=FireCatalog.load(Path.of(args[2]));var updated=new ArrayList<FireCatalog.AttackFire>();
            for(var entry:previous.entries()){
                var reference=new ConfigReference<AttackConfig>(entry.attack().name());
                for(var argument:entry.attack().arguments().entrySet()){
                    Object value=argument.getValue();if(Set.of("land","rearm","clear").contains(argument.getKey()))continue;
                    if(value instanceof FireCatalog.EnumValue e&&e.type().endsWith("$DamageType"))value=com.threerings.projectx.dungeon.util.DamageSummary.DamageType.valueOf(e.name());
                    reference.ld().put(argument.getKey(),value);
                }
                var attack=AttackConfig.b(manager,reference);if(attack==null)throw new IOException("Unresolved Sprite attack: "+reference.getName());
                var metadata=new TreeMap<>(entry.attack().arguments());
                if(!Objects.equals(metadata.get("land"),attack.QP())||!Objects.equals(metadata.get("clear"),attack.QN()))
                    throw new IOException("Existing Sprite attack timing differs: "+entry.weaponConfig());
                metadata.put("rearm",attack.QO());
                updated.add(new FireCatalog.AttackFire(entry.weaponConfig(),entry.activity(),new org.tortodrone.core.character.StarterCatalog.Model(entry.attack().name(),metadata),entry.fireSpeed(),entry.fireRefs()));
            }
            Files.createDirectories(out);FireCatalog.write(out.resolve("fires.json"),updated,previous.unsupportedTypes());
            System.out.println("PASS: refreshed native rearm timing for "+updated.size()+" Sprite attacks; preserved all action graphs");return;
        }
        var names=new TreeSet<String>();
        for(var config:manager.be(BattleSpriteConfig.class).kR())for(var skill:config.skills)
            for(var ref:new ConfigReference[]{skill.skill,skill.uSkillA,skill.uSkillB})if(ref!=null)names.add(ref.getName());
        var entries=new ArrayList<FireCatalog.AttackFire>();
        for(String name:names)for(int p1=0;p1<4;p1++)for(int p2=0;p2<4;p2++)for(var damage:com.threerings.projectx.dungeon.util.DamageSummary.DamageType.values()){
            if(damage.ordinal()>3)continue;
            var ref=new ConfigReference<AttackConfig>(name);ref.ld().put("Power1",Integer.toString(p1));ref.ld().put("Power2",Integer.toString(p2));
            ref.ld().put("Damage Type",damage);ref.ld().put("Damage Type Str",damage.name());
            FireCatalogExport.addAttack(manager,name+"|"+p1+"|"+p2+"|"+damage.name(),292,ref,entries);
        }
        Files.createDirectories(out);FireCatalog.write(out.resolve("fires.json"),entries,Map.of());
        System.out.println("PASS: exported "+entries.size()+" pet skill graphs");
    }
}
