import java.nio.file.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.projectx.item.config.*;
import com.threerings.projectx.item.data.*;
import com.threerings.projectx.dungeon.util.DamageSummary;
import org.tortodrone.character.*;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.world.*;

/** Representative family/level comparisons against the unchanged client's calculations. */
public final class GearStatsCompatibility {
    public static void main(String[] args)throws Exception {
        Path game=Path.of(args[0]).toRealPath();var content=ContentManager.load(Path.of("."));
        var stats=content.scenes().scene(294).content().stats();var equipment=content.createScenes().equipment();
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,game.resolve("rsrc").toString());
        var cm=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");cm.init();
        int checked=0;
        for(String name:List.of("Weapon/Sword/Proto Sword","Weapon/Sword/Cutter","Weapon/Handgun/Magnus","Weapon/Bomb/Blast Bomb","Gear/Armor/Wolver Coat","Gear/Armor/Vog Cub Coat","Gear/Helm/Spiral Sallet (Blue)","Gear/Shield/Swiftstrike Buckler")){
            var original=(ItemConfig.Level)cm.d(ItemConfig.class,name).w(cm);var definition=content.items().require(name);
            for(int level:new int[]{0,4,9}){
                var item=new WorldProfile.Item(1,definition.slot(),name,null,0,definition.slot().initialIndex(),true,true,false,1,false,level,0,List.of());
                var nativeLevel=original.c(cm,level);near(GearStats.value(stats,item,"health-bonus"),nativeLevel.fr(0),name+" health");
                if(original instanceof ItemConfig.Gear gear){
                    for(var type:DamageSummary.DamageType.values())if(type.ordinal()<4){
                        float base=gear.defense.a(cm,type,0,gear.rarity);
                        near(GearStats.defense(stats,item,type.name().toLowerCase(Locale.ROOT),content.town()),nativeLevel.a(base,type,cm,0,gear.rarity),name+" defense "+type);
                    }
                }
                if(original instanceof ItemConfig.Weapon weapon){
                    var profile=new WorldProfile(10,1,1,0,0,1,List.of(item));
                    var summary=new DamageSummary().a(2,DamageSummary.DamageType.NORMAL,100,true,false);
                    nativeLevel.a(summary,0,weapon,false);
                    near(GearStats.damage(stats,profile,item,content.town()).apply(100,""),summary.RT().a(DamageSummary.DamageType.NORMAL),name+" damage");
                    var nativeMods=new EquipmentModifiers();original.a(nativeMods);nativeLevel.a(nativeMods,weapon);
                    new LevelItem(0,new ConfigReference<ItemConfig>(name)).a(cm,nativeMods);
                    var actual=equipment.forKnight(profile,item).weapon();
                    near(actual.chargeTime(),nativeMods.chargeTime,name+" charge");near(actual.attackSpeed(),nativeMods.attackSpeed,name+" attack speed");near(actual.speed(),nativeMods.speed,name+" speed");
                }
                checked++;
            }
        }
        System.out.println("PASS: "+checked+" native group/level comparisons for sword, handgun, bomb, armor, helm and shield stats");
    }
    private static void near(float actual,float expected,String what){if(Math.abs(actual-expected)>.001)throw new AssertionError(what+": "+actual+" != native "+expected);}
}
