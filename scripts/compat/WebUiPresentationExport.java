import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.projectx.item.config.*;
import com.threerings.projectx.item.data.*;
import com.threerings.projectx.sprites.config.BattleSpriteConfig;
import com.threerings.projectx.dungeon.util.DamageSummary;
import org.tortodrone.resources.*;

/** Offline, read-only native tooltip facts. Emits data, never client classes or artwork. */
public final class WebUiPresentationExport {
    static com.threerings.util.Q itemText,spriteText;
    static String plain(String text){return text.replaceAll("<[^>]*>", "");}
    static String effect(String key,float value,ItemCodes.ItemValueKey scale){return plain(itemText.f(key,itemText.cP(scale.key(value))));}
    public static void main(String[] args)throws Exception {
        Locale.setDefault(Locale.ENGLISH);
        Path game=Path.of(args[0]).toRealPath(),out=Path.of(args[2]).toAbsolutePath();
        if(Files.exists(out)||out.startsWith(game))throw new IOException("Choose fresh output outside client");
        var messages=new com.threerings.util.R("rsrc.i18n");itemText=messages.gw("item");spriteText=messages.gw("sprites");
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,game.resolve("rsrc").toString());
        var cm=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,messages,"config/");cm.init();
        var variants=new TreeMap<String,Object>();
        for(var v:cm.bd(VariantConfig.class).kK()) {
            if(v.Vj().isEmpty()||v.getName().equals("Message/Message"))continue;
            var facts=new TreeMap<String,Object>();facts.put("label",plain(v.d(itemText)));facts.put("category",v.getClass().getSimpleName());
            for(var field:v.getClass().getFields())if(field.getName().equals("bonus")&&field.get(v) instanceof Enum<?> rank){facts.put("rank",rank.ordinal());facts.put("strength",rank.name());}
            variants.put(v.getName(),facts);
        }
        var items=new TreeMap<String,Object>();
        for(var entry:ItemYaml.load(Path.of(args[1]).resolve("items")).entries()) {
            var config=cm.d(ItemConfig.class,entry.config());if(config==null||!(config.w(cm) instanceof ItemConfig.Level level))continue;
            var levels=new ArrayList<Object>();
            for(int n=0;n<Math.max(1,level.K(cm));n++) {
                var current=level.c(cm,n);var effects=new ArrayList<String>();var stats=new TreeMap<String,Float>();
                var mods=new EquipmentModifiers();current.a(mods,(LevelConfig.Weapons)null);
                modifiers(effects,mods,"");
                for(var cls:LevelConfig.Weapons.values()) {
                    var specific=new EquipmentModifiers();current.a(specific,cls);
                    specific.chargeTime-=mods.chargeTime;specific.attackSpeed-=mods.attackSpeed;specific.speed=0;
                    modifiers(effects,specific,cls.name()+": ");
                }
                if(level instanceof ItemConfig.Weapon weapon) {
                    var damage=new DamageSummary();weapon.a(cm,damage);current.a(damage,30,(LevelConfig.Weapons)null,false);
                    for(var type:DamageSummary.DamageType.values()) {float value=damage.RT().a(type);if(value!=0)stats.put("damage."+type.name().toLowerCase(Locale.ROOT),value);}
                    stats.put("attack-speed",(float)weapon.displaySpeed);stats.put("attack-modifier",mods.attackSpeed);
                    for(var tagged:damage.RU().entrySet())if(tagged.getValue().value!=0)effects.add(plain(itemText.i("m.tagged_damage_"+(tagged.getValue().value<0?"penalty":"bonus"),itemText.cP(ItemCodes.ItemValueKey.DAMAGE.key(tagged.getValue().value)),itemText.cP("t."+tagged.getKey()))));
                }
                levels.add(Map.of("effects",effects,"stats",stats));
            }
            items.put(entry.config(),levels);
        }
        var sprites=new TreeMap<String,Object>();
        for(var s:cm.bd(BattleSpriteConfig.class).kK()) {
            var skills=new ArrayList<Object>();
            for(var skill:s.skills)skills.add(Map.of("name",name(skill.name),"upgrades",Arrays.stream(skill.powerNames).map(WebUiPresentationExport::name).toList(),"ultimates",List.of(name(skill.ultimateNameA),name(skill.ultimateNameB))));
            sprites.put(s.getName(),Map.of("name",plain(spriteText.cR(s.spriteName)),"skills",skills));
        }
        var tables=new ArrayList<Object>();var refs=new TreeMap<String,Integer>();
        for(var entry:items.entrySet()){int index=tables.indexOf(entry.getValue());if(index<0){index=tables.size();tables.add(entry.getValue());}refs.put(entry.getKey(),index);}
        var perks=new TreeMap<String,String>();
        for(var perk:com.threerings.projectx.sprites.data.SpritePerk.values())perks.put(perk.name(),plain(spriteText.cP(perk.nameKey())));
        Files.writeString(out,Json.stringify(Map.of("version",1,"variants",variants,"items",refs,"tables",tables,"sprites",sprites,"perks",perks))+"\n",StandardOpenOption.CREATE_NEW);
        System.out.println("PASS: native presentation "+items.size()+" items, "+variants.size()+" effects, "+sprites.size()+" sprites");
    }
    static String name(BattleSpriteConfig.NamedIcon n){return n==null?"":plain(spriteText.cP(n.name));}
    static void modifiers(List<String> out,EquipmentModifiers m,String prefix) {
        if(m.chargeTime!=0)out.add(prefix+effect("m.charge_"+(m.chargeTime>0?"reduction":"increase"),m.chargeTime,ItemCodes.ItemValueKey.CHARGE));
        if(m.speed!=0)out.add(prefix+effect("m.speed_"+(m.speed>0?"increase":"decrease"),m.speed,ItemCodes.ItemValueKey.SPEED));
        if(m.attackSpeed!=0)out.add(prefix+effect("m.attack_speed_"+(m.attackSpeed>0?"increase":"decrease"),m.attackSpeed,ItemCodes.ItemValueKey.SPEED));
    }
}
