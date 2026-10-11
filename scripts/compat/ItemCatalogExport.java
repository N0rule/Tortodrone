import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.projectx.dungeon.config.DungeonItemConfig;
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
            // A stock ID, display name/icon and a player-facing definition are required.
            boolean named=item.name!=null&&!item.name.isBlank()
                ||item instanceof ItemConfig.AccessoryTicket ticket&&ticket.accessory!=null&&manager.a(AccessoryConfig.class,ticket.accessory)!=null;
            boolean player=config.getConfigId()!=0&&named&&item.icon!=null
                &&item.expireDays==0&&(item.expireEvent==null||item.expireEvent.isBlank());
            boolean levelOther=item instanceof ItemConfig.MetaMineral||item instanceof DungeonItemConfig.Present;
            if(player&&levelOther)slot=WorldProfile.Slot.OTHER;
            else if(player&&item instanceof ItemConfig.Level&&!item.stackable) {
                if(item instanceof ItemConfig.HelmCostume&&item.h(3,false))slot=WorldProfile.Slot.HELM_COSTUME;
                else if(item instanceof ItemConfig.ArmorCostume&&item.h(4,false))slot=WorldProfile.Slot.ARMOR_COSTUME;
                else if(item instanceof ItemConfig.ShieldCostume&&item.h(15,false))slot=WorldProfile.Slot.SHIELD_COSTUME;
                else if(item instanceof ItemConfig.Trinket&&item.h(10,false)&&item.h(11,false))slot=WorldProfile.Slot.TRINKET;
                else if(item instanceof ItemConfig.Helm&&item.h(0,false))slot=WorldProfile.Slot.HELM;
                else if(item instanceof ItemConfig.Armor&&item.h(1,false))slot=WorldProfile.Slot.ARMOR;
                else if(item instanceof ItemConfig.Shield&&item.h(2,false))slot=WorldProfile.Slot.SHIELD;
                else if(item instanceof ItemConfig.Sword&&item.h(6,false))slot=WorldProfile.Slot.SWORD;
                else if(item instanceof ItemConfig.Handgun&&item.h(6,false))slot=WorldProfile.Slot.GUN;
                else if(item instanceof ItemConfig.Bomb&&item.h(6,false))slot=WorldProfile.Slot.BOMB;
            }else if(player&&plainPlayerType(item.getClass().getSimpleName()))slot=WorldProfile.Slot.OTHER;
            Map<String,Object> model=Map.of();
            if(slot!=null&&slot!=WorldProfile.Slot.OTHER) {
                var a=new ArgumentMap();var b=new ArgumentMap();item.a(256,a);item.a(257,b);
                try {model=convert(a,b);if(item instanceof ItemConfig.Weapon weapon) {
                    var values=new TreeMap<>(model);values.put("Weapon Idle",weapon.UU());model=Map.copyOf(values);
                }}catch(IOException unsupported){slot=null;}
                String parameter=switch(slot==null?WorldProfile.Slot.SWORD:slot){case HELM,HELM_COSTUME->"Helmet";case ARMOR,ARMOR_COSTUME->"Armor";case SHIELD,SHIELD_COSTUME->"Shield";case TRINKET,OTHER->null;default->"Weapon";};
                if(parameter!=null&&!(model.get(parameter) instanceof StarterCatalog.Model)) {slot=null;model=Map.of();}
            }
            boolean permanent=item instanceof ItemConfig.Level l&&l.isPermanent
                ||item instanceof ItemConfig.Artifact;
            boolean locked=permanent||item instanceof ItemConfig.Token||item instanceof ItemConfig.Mineral
                ||item instanceof ItemConfig.Craft craft&&craft.locked;
            ItemCatalog.Variant variant=item instanceof ItemConfig.Artifact?ItemCatalog.Variant.SEED_ITEM
                :levelOther?ItemCatalog.Variant.LEVEL_ITEM
                :item instanceof ItemConfig.Upgrade?ItemCatalog.Variant.UPGRADE_ITEM:ItemCatalog.Variant.ITEM;
            Long seed=item instanceof ItemConfig.Artifact artifact?artifact.seed:null;
            entries.add(new ItemCatalog.Entry(config.getName(),config.getConfigId(),item.getClass().getSimpleName(),
                item.name==null?"":item.name,item.rarity,item.stackable,slot,item instanceof ItemConfig.Level l&&l.lockOnEquip,
                permanent,locked,variant,seed,model));
        }
        Files.createDirectories(out);ItemCatalog.write(out.resolve("catalog.json"),entries);
        String facts="# Read-only stock metadata; not game assets.\n";
        for(String file:List.of("code/projectx-pcode.jar","rsrc/config/item.dat")) {
            var digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(game.resolve(file))){byte[] buf=new byte[65536];for(int n;(n=in.read(buf))!=-1;)digest.update(buf,0,n);}
            facts+=file+"\t"+HexFormat.of().formatHex(digest.digest())+"\n";
        }
        Files.writeString(out.resolve("provenance.tsv"),facts,StandardOpenOption.CREATE_NEW);
        var loaded=ItemCatalog.load(out.resolve("catalog.json"));
        System.out.println("PASS: indexed "+loaded.entries().size()+" definitions, "+loaded.entries().stream().filter(ItemCatalog.Entry::grantable).count()+" grantable player item references");
    }
    static boolean plainPlayerType(String type) {
        return Set.of("ActionItem","Craft","Recipe","Mineral","Token","Artifact","Ticket","AccessoryTicket","VariantTicket","InstantAction",
            "SpriteEgg","Harness","Color","Eye","Height","Lockbox","Pack","GuildBond","GuildHallGlobalTicket",
            "RenameGuild","RenameKnight","WrappingPaper","AnimatedAction","Lift","SpawnActor","TokenArtifact",
            "Capsule","Vial").contains(type);
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
        if(a instanceof com.threerings.opengl.renderer.config.ColorizationConfig.TransNormal c
                &&b instanceof com.threerings.opengl.renderer.config.ColorizationConfig.TransNormal d&&c.clazz==d.clazz&&c.colorization==d.colorization)
            return new StarterCatalog.TransColorization(c.clazz,c.colorization);
        if(Objects.equals(a,b)&&(a instanceof String||a instanceof Integer||a instanceof Boolean||a instanceof Float f&&Float.isFinite(f)))return a;
        throw new IOException("Unsupported item model value");
    }
}
