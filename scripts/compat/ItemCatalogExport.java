import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.projectx.item.config.*;
import org.tortodrone.character.*;

/** Read-only stock oracle: references and basic facts, no assets or gameplay implementations. */
public final class ItemCatalogExport {
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Usage: ItemCatalogExport game-directory fresh-output-directory");
        Path game=Path.of(args[0]).toRealPath(),out=Path.of(args[1]).toAbsolutePath().normalize();
        Path ancestor=out;while(!Files.exists(ancestor))ancestor=ancestor.getParent();
        if(ancestor.toRealPath().startsWith(game)||Files.exists(out))throw new IOException("Choose a fresh output directory outside the installation");
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,game.resolve("rsrc").toString());
        var manager=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");manager.init();
        var entries=new ArrayList<ItemCatalog.Entry>();
        for(var config:manager.bd(ItemConfig.class).kK()) {
            var item=config.w(manager);WorldProfile.Slot slot=null;
            // Costumes, recipes, parts and non-Level types are indexed, never sent as LevelItem.
            if(config.creatable&&item instanceof ItemConfig.Level level&&!level.isPermanent&&!item.stackable) {
                if(item instanceof ItemConfig.Helm&&item.h(0,false))slot=WorldProfile.Slot.HELM;
                else if(item instanceof ItemConfig.Armor&&item.h(1,false))slot=WorldProfile.Slot.ARMOR;
                else if(item instanceof ItemConfig.Shield&&item.h(2,false))slot=WorldProfile.Slot.SHIELD;
                else if(item instanceof ItemConfig.Sword&&item.h(6,false))slot=WorldProfile.Slot.SWORD;
                else if(item instanceof ItemConfig.Handgun&&item.h(6,false))slot=WorldProfile.Slot.GUN;
                else if(item instanceof ItemConfig.Bomb&&item.h(6,false))slot=WorldProfile.Slot.BOMB;
            }
            Map<String,Object> model=Map.of();
            if(slot!=null) {
                var a=new ArgumentMap();var b=new ArgumentMap();item.a(256,a);item.a(257,b);
                try {model=convert(a,b);if(item instanceof ItemConfig.Weapon weapon) {
                    var values=new TreeMap<>(model);values.put("Weapon Idle",weapon.UU());model=Map.copyOf(values);
                }}catch(IOException unsupported){slot=null;}
                String parameter=switch(slot==null?WorldProfile.Slot.SWORD:slot){case HELM->"Helmet";case ARMOR->"Armor";case SHIELD->"Shield";default->"Weapon";};
                if(!(model.get(parameter) instanceof StarterCatalog.Model)) {slot=null;model=Map.of();}
            }
            entries.add(new ItemCatalog.Entry(config.getName(),config.getConfigId(),item.getClass().getSimpleName(),
                item.name==null?"":item.name,item.rarity,item.stackable,slot,item instanceof ItemConfig.Level l&&l.lockOnEquip,model));
        }
        Files.createDirectories(out);ItemCatalog.write(out.resolve("catalog.tsv"),entries);
        String facts="# Read-only stock metadata; not game assets.\n";
        for(String file:List.of("code/projectx-pcode.jar","rsrc/config/item.dat")) {
            var digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(game.resolve(file))){byte[] buf=new byte[65536];for(int n;(n=in.read(buf))!=-1;)digest.update(buf,0,n);}
            facts+=file+"\t"+HexFormat.of().formatHex(digest.digest())+"\n";
        }
        Files.writeString(out.resolve("provenance.tsv"),facts,StandardOpenOption.CREATE_NEW);
        var loaded=ItemCatalog.load(out.resolve("catalog.tsv"));
        System.out.println("PASS: indexed "+loaded.entries().size()+" definitions, "+loaded.entries().stream().filter(ItemCatalog.Entry::grantable).count()+" grantable equipment references");
    }
    static Map<String,Object> convert(Map<String,Object> a,Map<String,Object> b)throws IOException {
        if(!a.keySet().equals(b.keySet()))throw new IOException("Color-dependent argument keys");
        var result=new TreeMap<String,Object>();for(var e:a.entrySet())result.put(e.getKey(),convert(e.getValue(),b.get(e.getKey())));return Map.copyOf(result);
    }
    static Object convert(Object a,Object b)throws IOException {
        if(a instanceof Integer n&&n==256&&b instanceof Integer m&&m==257)return ItemCatalog.PlayerColor.VALUE;
        if(a instanceof ConfigReference<?> r&&b instanceof ConfigReference<?> s&&r.getName().equals(s.getName()))
            return new StarterCatalog.Model(r.getName(),convert(r.kW(),s.kW()));
        if(a instanceof com.threerings.opengl.renderer.config.ColorizationConfig.Normal c
                &&b instanceof com.threerings.opengl.renderer.config.ColorizationConfig.Normal d&&c.colorization==d.colorization)
            return new StarterCatalog.Colorization(c.colorization);
        if(Objects.equals(a,b)&&(a instanceof String||a instanceof Integer||a instanceof Boolean))return a;
        throw new IOException("Unsupported item model value");
    }
}
