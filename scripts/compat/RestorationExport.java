import java.nio.file.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.projectx.item.config.ItemConfig;
import org.tortodrone.world.FireCatalog;

/** Re-export only recovered legacy/catalyst graphs; merge by weapon/activity to preserve other repairs. */
public final class RestorationExport {
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("game-directory fresh-output-file");
        Path game=Path.of(args[0]).toRealPath(),out=Path.of(args[1]).toAbsolutePath();
        Path ancestor=out.getParent();while(!Files.exists(ancestor))ancestor=ancestor.getParent();
        if(Files.exists(out)||ancestor.toRealPath().startsWith(game))throw new IllegalArgumentException("Choose a fresh output file outside the installation");
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,game.resolve("rsrc").toString());
        var manager=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");manager.init();
        var entries=new ArrayList<FireCatalog.AttackFire>();
        var rarity=FireCatalogExport.class.getDeclaredField("damageRarity");rarity.setAccessible(true);
        for(var config:manager.bd(ItemConfig.class).kK()){
            if(config.getConfigId()==0||!(config.w(manager) instanceof ItemConfig.Weapon weapon))continue;
            String name=config.getName();
            if(!name.startsWith("Weapon/Handgun/")||!(name.contains("Catalyzer")||name.endsWith("/Neutralizer")||name.endsWith("/Biohazard")||name.endsWith("/Pollinator")||name.endsWith("/Fiery Diskgun")||name.endsWith("/Static Diskgun")||name.endsWith("/Overcharged Mixmaster (OLD)")||name.endsWith("/Celestial Orbitgun (OLD)")||name.startsWith("Weapon/Handgun/Slicer/")||name.endsWith("/Celestial Orbitgun")||name.endsWith("/Overcharged Mixmaster")))continue;
            rarity.setInt(null,weapon.rarity);
            for(int i=0;i<weapon.attacks.length;i++)FireCatalogExport.addAttack(manager,name,292+i,weapon.attacks[i].ref,entries);
            FireCatalogExport.addAttack(manager,name,289,weapon.incompleteChargedAttack,entries);
            FireCatalogExport.addAttack(manager,name,290,weapon.chargedAttack,entries);
            FireCatalogExport.addAttack(manager,name,291,weapon.cooldownAttack,entries);
            System.out.println("EXPORTED "+name);
        }
        FireCatalog.write(out,entries,Map.of());
        System.out.println("PASS: "+entries.size()+" recovered attack graphs");
    }
}
