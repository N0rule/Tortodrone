import java.nio.file.*;
import java.util.*;
import org.tortodrone.core.Yaml;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.Confetti;

/**
 * Re-labels the administrator-only item categories to the vanilla Arsenal tabs.
 * Only the YAML category/subcategory fields change; native bindings and names are read-only.
 *
 * Usage: java -cp dist/tortodrone.jar scripts/build/RelabelItemCategories.java [phase1|bases] [--content DIR]
 */
public final class RelabelItemCategories {
    private static final List<String> FAMILIES=List.of("weapons","gear","costumes","accessories","materials","consumables","misc");
    private static final Set<String> KEYS=Set.of("Silver Key","Shadow Key","Slime Key");
    private static final Set<String> KNOWN=Set.of(
        "Helm","Armor","HelmCostume","ArmorCostume","ShieldCostume","Gun","LockdownShield","Mineral","MetaMineral",
        "Lockbox","Upgrade","Eye","Pack","Height","Color","GiftBox","WrappingPaper","AnimatedAction","SpawnActor","Lift",
        "RenameGuild","RenameKnight","Prop","Actor","SpecialProp","ModelProp","Placeable","KnightStatue","Ticket",
        "VariantTicket","GuildHallGlobalTicket","GuildBond","SpriteEgg","Harness","Capsule","Vial","InstantAction","Dungeon",
        "Present","Recover","Remove","Dev","Parts","Sword","Bomb","Shield","Trinket","Material","Recipe","Token","Artifact");
    private record Label(String category,String subcategory) { }

    public static void main(String[] args)throws Exception {
        String mode="phase1";Path content=Path.of("content");
        for(int i=0;i<args.length;i++) {
            if(args[i].equals("--content")&&i+1<args.length)content=Path.of(args[++i]);
            else if(args[i].equals("phase1")||args[i].equals("bases"))mode=args[i];
            else throw new IllegalArgumentException("Usage: RelabelItemCategories [phase1|bases] [--content DIR]");
        }
        if(!mode.equals("phase1")&&!mode.equals("bases"))throw new IllegalArgumentException("Unknown mode: "+mode);
        Path bindingsFile=content.resolve("_internal/item-bindings.json");
        Map<String,Object> root=Yaml.read(bindingsFile);
        Map<String,Object> bindings=map(root.get("items"),bindingsFile.toString());
        int changed=0,total=0,skipped=0;var categories=new TreeMap<String,Integer>();
        for(String family:FAMILIES) {
            Path file=content.resolve("items/"+family+".yml");
            Map<String,Object> yaml=Yaml.read(file);
            Map<String,Object> items=map(yaml.get("items"),file.toString());
            boolean dirty=false;
            for(var entry:items.entrySet()) {
                String id=entry.getKey();Map<String,Object> row=map(entry.getValue(),file+" → "+id);
                Map<String,Object> binding=map(bindings.get(id),bindingsFile+" → "+id);
                if(binding==null)throw new IllegalArgumentException("Missing native binding: "+id);
                String current=text(row.get("category"),id+" → category");
                Label label=mode.equals("phase1")?phase1(row,binding,current):bases(row,binding);
                if(label==null) {skipped++;category(categories,current);total++;continue;}
                if(!label.category().equals(current)||!text(row.getOrDefault("subcategory",""),id+" → subcategory").equals(label.subcategory())) {
                    relabel(row,label);dirty=true;changed++;
                }
                category(categories,label.category());total++;
            }
            if(dirty)write(file,yaml);
        }
        System.out.println("Mode: "+mode);
        System.out.println("Items: "+total+", changed: "+changed+", left unchanged: "+skipped);
        for(var e:categories.entrySet())System.out.println("  "+e.getKey()+": "+e.getValue());
    }
    private static Label phase1(Map<String,Object> row,Map<String,Object> binding,String category) {
        if(!KNOWN.contains(category))return null;
        String type=text(binding.get("type"),"type"),config=text(binding.get("config"),"config");
        String name=text(row.get("name"),"name"),sub=text(row.getOrDefault("subcategory",""),"subcategory");
        if(category.equals("Helm"))return type.equals("AccessoryTicket")?new Label("Accessory","Helm: "+sub):new Label("Helmet","");
        if(category.equals("Armor"))return type.equals("AccessoryTicket")?new Label("Accessory","Armor: "+sub):new Label("Armor","");
        if(category.equals("HelmCostume")||category.equals("ArmorCostume")||category.equals("ShieldCostume"))
            return new Label("Costume",category.substring(0,category.length()-"Costume".length()));
        if(category.equals("Gun"))return new Label("Handgun",sub);
        if(category.equals("LockdownShield"))return new Label("Shield","Lockdown");
        if(category.equals("Mineral"))return new Label("Mineral",sub);
        if(category.equals("MetaMineral"))return new Label("Mineral","Deposit");
        switch(category) {
            case "Lockbox":return new Label("Usable",config.startsWith("Upgrade/Prize Box")?"Prize Box":"Lockbox");
            case "Upgrade":return new Label("Usable","Upgrade");
            case "Eye":return new Label("Usable","Eye");
            case "Pack":return new Label("Usable","Eye Pack");
            case "Height":return new Label("Usable","Height");
            case "Color":return new Label("Usable","Personal Color");
            case "GiftBox":return new Label("Usable","Gift Box");
            case "WrappingPaper":return new Label("Usable","Wrapping Paper");
            case "AnimatedAction":return new Label("Usable","Confetti / Emote");
            case "SpawnActor":case "Lift":return new Label("Usable","Prop Spawner");
            case "RenameGuild":case "RenameKnight":return new Label("Usable","Name Change");
            case "Prop":case "Actor":case "SpecialProp":case "ModelProp":case "Placeable":case "KnightStatue":
                if(category.equals("KnightStatue"))return new Label("Furnishing","Knight Statue");
                String[] part=config.split("/");return new Label("Furnishing",config.startsWith("Furni/")&&part.length>1?part[1]:"");
            case "Ticket":
                if(config.startsWith("Rarity/"))return new Label("Rarity",sub);
                if(KEYS.contains(name))return new Label("Key",sub);
                return new Label("Ticket",sub);
            case "VariantTicket":return new Label("Ticket","Variant");
            case "GuildHallGlobalTicket":return new Label("Ticket","Guild Hall Theme");
            case "GuildBond":return new Label("Ticket","Guild Bond");
            case "SpriteEgg":return new Label("Battle Sprite","Egg");
            case "Harness":return new Label("Battle Sprite","Harness");
            case "Capsule":return new Label("Pickup","Capsule");
            case "Vial":return new Label("Pickup","Vial");
            case "InstantAction":return new Label("Pickup","Crown/Heart/Heat");
            case "Dungeon":return new Label("Pickup","Vitapod");
            case "Present":return new Label("Pickup","Present");
            case "Recover":return new Label("Internal","Accessory Recover");
            case "Remove":return new Label("Internal","Accessory Remove");
            case "Dev":return new Label("Internal","Dev");
            case "Parts":return new Label("Internal","Parts");
            default:return new Label(category,sub);
        }
    }
    private static Label bases(Map<String,Object> row,Map<String,Object> binding) {
        String name=text(row.get("name"),"name"),config=text(binding.get("config"),"config");
        return name.equals("Base")||name.startsWith("Base (")||config.contains("/Parts/")?new Label("Internal","Base"):null;
    }
    private static void relabel(Map<String,Object> row,Label label) {
        var updated=new LinkedHashMap<String,Object>();boolean found=false;
        for(var entry:row.entrySet()) {
            if(entry.getKey().equals("subcategory"))continue;
            updated.put(entry.getKey(),entry.getValue());
            if(entry.getKey().equals("category")) {
                found=true;updated.put("category",label.category());
                if(!label.subcategory().isEmpty())updated.put("subcategory",label.subcategory());
            }
        }
        if(!found)throw new IllegalArgumentException("Item has no category field");
        row.clear();row.putAll(updated);
    }
    private static void write(Path file,Map<String,Object> yaml)throws Exception {
        Path temp=Files.createTempFile(file.getParent(),".relabel-",".tmp");Files.delete(temp);
        try{Yaml.write(temp,yaml);Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        finally{Files.deleteIfExists(temp);}
    }
    private static void category(Map<String,Integer> counts,String category){counts.merge(category,1,Integer::sum);}
    @SuppressWarnings("unchecked")private static Map<String,Object> map(Object value,String where) {
        if(!(value instanceof Map<?,?>))throw new IllegalArgumentException(where+": expected a mapping");
        return (Map<String,Object>)value;
    }
    private static String text(Object value,String where) {
        if(!(value instanceof String text))throw new IllegalArgumentException(where+": expected text");
        return text;
    }
}
