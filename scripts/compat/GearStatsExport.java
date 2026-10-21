import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.projectx.item.config.*;
import com.threerings.projectx.item.data.*;
import com.threerings.projectx.dungeon.util.DamageSummary.DamageType;
import org.tortodrone.resources.*;

/** Read-only numeric level/variant extraction. No models, textures or client classes are emitted. */
public final class GearStatsExport {
    private static final Map<String,Object> OUT=new TreeMap<>();
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("Usage: GearStatsExport game-directory content-directory fresh-output-file");
        Path game=Path.of(args[0]).toRealPath(),content=Path.of(args[1]).toRealPath(),out=Path.of(args[2]).toAbsolutePath().normalize();
        Path ancestor=out;while(!Files.exists(ancestor))ancestor=ancestor.getParent();
        if(Files.exists(out)||ancestor.toRealPath().startsWith(game))throw new IOException("Choose a fresh output outside the installation");
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,game.resolve("rsrc").toString());
        var cm=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");cm.init();
        var items=ItemYaml.load(content.resolve("items"));int count=0;
        for(var item:items.entries()) {
            if(!item.grantable()||!Set.of(org.tortodrone.character.WorldProfile.Slot.HELM,org.tortodrone.character.WorldProfile.Slot.ARMOR,org.tortodrone.character.WorldProfile.Slot.SHIELD,org.tortodrone.character.WorldProfile.Slot.TRINKET,org.tortodrone.character.WorldProfile.Slot.SWORD,org.tortodrone.character.WorldProfile.Slot.GUN,org.tortodrone.character.WorldProfile.Slot.BOMB).contains(item.slot()))continue;
            var config=cm.d(ItemConfig.class,item.config());if(config==null)continue;
            var original=config.w(cm);if(!(original instanceof ItemConfig.Level level))continue;
            if(!(original instanceof ItemConfig.Gear||original instanceof ItemConfig.Weapon))continue;
            String prefix="gear."+items.referenceId(item)+".";
            put(prefix+"rarity",level.rarity);
            for(int n=0;n<Math.max(1,level.K(cm));n++) {
                var current=level.c(cm,n);String p=prefix+"levels."+n+".";
                put(p+"health-bonus",current.fr(0));
                if(original instanceof ItemConfig.Gear gear) {
                    for(var type:DamageType.values())if(type!=DamageType.UNBLOCKABLE)
                        put(p+"defense."+type.name().toLowerCase(Locale.ROOT),current.a(gear.defense.a(cm,type,0,gear.rarity),type,cm,0,gear.rarity));
                    for(int mask=1;mask<=1<<21;mask<<=1) {
                        int resist=current.eK(gear.defense.ei(mask));
                        if(resist!=0||level.c(cm,0).eK(gear.defense.ei(mask))!=0)put(p+"status-resist."+mask,resist);
                    }
                }
                for(var weapon:org.tortodrone.world.EquipmentCatalog.WeaponClass.values()) {
                    var cls=weapon==org.tortodrone.world.EquipmentCatalog.WeaponClass.NONE?null:LevelConfig.Weapons.valueOf(weapon.name());
                    var mods=new EquipmentModifiers();current.a(mods,cls);
                    var base=new EquipmentModifiers();level.c(cm,0).a(base,cls);
                    String m=p+"modifiers."+weapon+".";
                    delta(m+"chargeTime",mods.chargeTime-base.chargeTime);delta(m+"speed",mods.speed-base.speed);delta(m+"attackSpeed",mods.attackSpeed-base.attackSpeed);
                    var rules=new ArrayList<Rule>();damage(current,cls,rules);
                    rules(p+"damage."+weapon+".",rules);
                }
            }
            count++;
        }
        for(var config:cm.bd(VariantConfig.class).kK()) {
            String prefix="gear.variant."+config.getName()+".";
            for(var variant:VariantConfig.a(cm,List.of(new ConfigReference<VariantConfig>(config.getName())))) {
                if(variant instanceof VariantConfig.DefenseTypeBonus defense)
                    for(int rarity=0;rarity<=5;rarity++)put(prefix+"defense."+rarity+"."+defense.type.name().toLowerCase(Locale.ROOT),defense.a(0,defense.type,cm,0,rarity));
                if(variant instanceof VariantConfig.TaggedDamage tagged)put(prefix+"tagged."+tagged.tag,ItemCodes.ItemValueKey.DAMAGE.value(tagged.bonus,tagged.positive,tagged.damage));
                if(variant instanceof VariantConfig.StatusResist resist){put(prefix+"status-resist.mask",resist.conditionMask);put(prefix+"status-resist.value",resist.statusResist.resist(resist.resist,0));put(prefix+"status-resist.relative",resist.statusResist.name().equals("CUSTOM")?0:1);}
            }
        }
        // One flat, numeric map is merged into the canonical shared combat YAML.
        Files.writeString(out,Json.stringify(OUT)+"\n",StandardOpenOption.CREATE_NEW);
        System.out.println("PASS: "+count+" leveled equipment definitions; "+OUT.size()+" numeric stats");
    }
    private record Rule(int kind,float value,int depth,String tag) { }
    private static void damage(LevelConfig level,LevelConfig.Weapons weapon,List<Rule> out) {
        if(level instanceof LevelConfig.Compound compound){for(var child:compound.levels)damage(child,weapon,out);}
        else if(level instanceof LevelConfig.WeaponClass specific){if(specific.weaponClass==weapon)damage(specific.level,weapon,out);}
        else if(level instanceof LevelConfig.DamageBonus bonus){
            float value=ItemCodes.ItemValueKey.DAMAGE.value(bonus.bonus,bonus.positive,bonus.damage);
            int kind=0,depth=0;String tag="";
            if(bonus instanceof LevelConfig.RelativeDamageBonus relative){kind=relative.capped?2:1;depth=relative.startDepth;}
            if(bonus instanceof LevelConfig.TaggedDamageBonus tagged){kind=3;tag=tagged.tag;}
            if(value!=0)out.add(new Rule(kind,value,depth,tag));
        }
    }
    private static void rules(String prefix,List<Rule> rules){
        if(rules.isEmpty())return;put(prefix+"count",rules.size());
        for(int i=0;i<rules.size();i++){var r=rules.get(i);String p=prefix+i+".";put(p+"kind",r.kind);put(p+"value",r.value);put(p+"depth",r.depth);if(!r.tag.isEmpty())put(p+"tagged."+r.tag,r.value);}
    }
    private static void delta(String key,float value){if(value!=0)put(key,value);}
    private static void put(String key,Number value){if(!Float.isFinite(value.floatValue()))throw new IllegalArgumentException(key);OUT.put(key,value);}
}
