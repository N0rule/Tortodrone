package org.tortodrone.resources;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.world.*;
import static org.tortodrone.resources.Yaml.*;

/** Administrator YAML → normalized catalogs. Native inventory identities never leave the internal bindings. */
public final class ItemYaml {
    public static final List<String> CATEGORIES=List.of("weapons","gear","costumes","accessories","materials","consumables","misc");
    public record Definitions(ItemCatalog items,WeaponCatalog weapons,EquipmentCatalog equipment) { }
    private ItemYaml() { }
    public static ItemCatalog load(Path directory)throws IOException{return read(directory,0).items();}
    static Map<String,Object> json(Path path)throws IOException {
        ContentFiles.regular(path);if(Files.size(path)>32_000_000)throw new IOException("Item resource exceeds 32 MB: "+path);
        return map(Json.parseCatalog(Files.readString(path)),path.toString());
    }
    public static Definitions read(Path directory,int dodgeCooldown)throws IOException {
        Path internal=directory.resolve("../_internal").normalize(),bindingFile=internal.resolve("item-bindings.json");
        if(!Files.exists(bindingFile))throw new IOException(directory+": readable item bindings are missing; use ItemMigration /old-deployment /fresh-candidate");
        var root=json(bindingFile);fields(root,bindingFile.toString(),"format","version","items");
        if(!"tortodrone-item-bindings".equals(root.get("format"))||integer(root.get("version"),bindingFile.toString())!=1)throw new IOException(bindingFile+": unsupported item bindings version");
        if(Files.exists(internal.resolve("item-definitions.json")))throw new IOException(directory+": mixed legacy/readable item layouts; migrate a separate candidate");
        var bindings=map(root.get("items"),bindingFile+" → items");var arguments=json(internal.resolve("item-arguments.json"));
        var rows=new ArrayList<Object>();var weapons=new ArrayList<WeaponCatalog.Weapon>();var shields=new ArrayList<WeaponCatalog.Shield>();var equipment=new ArrayList<EquipmentCatalog.Entry>();
        var labels=new TreeMap<String,ItemCatalog.Labels>();var names=new TreeMap<String,String>();var ids=new TreeMap<String,String>();var seen=new HashSet<String>();
        for(var e:bindings.entrySet()) {
            String at=bindingFile+" → "+e.getKey();if(!e.getKey().matches("[a-z][a-z0-9_]{0,159}"))throw new IOException(at+": invalid administrator ID");
            var b=map(e.getValue(),at);fields(b,at,"config","stockId","type","slot","nameKey","variant","seed","equipment","expireDays","auctionGroup");
            String config=text(b.get("config"),at+" → config");if(ids.putIfAbsent(config,e.getKey())!=null)throw new IOException(at+": duplicate native binding");
        }
        for(String family:CATEGORIES) {
            Path file=directory.resolve(family+".yml");var yaml=Yaml.read(file);fields(yaml,file.toString(),"items");
            for(var e:map(yaml.get("items"),file+" → items").entrySet()) {
                String id=e.getKey(),at=file+" → "+id;
                if(!seen.add(id))throw new IOException(at+": duplicate item ID");
                if(!bindings.containsKey(id))throw new IOException(at+": unknown item ID; native bindings cannot be created in administrator YAML");
                var b=map(bindings.get(id),at);var m=map(e.getValue(),at);
                fields(m,at,"name","category","subcategory","rarity","bindsWhenEquipped","stackable","permanent","locked","combat","shield","modifiers");
                String name=ItemFields.label(m.get("name"),"name",at);String category=ItemFields.label(m.get("category"),"category",at);String subcategory=m.containsKey("subcategory")?ItemFields.label(m.get("subcategory"),"subcategory",at):"";
                String config=text(b.get("config"),at),type=text(b.get("type"),at);names.put(config,name);labels.put(config,new ItemCatalog.Labels(category,subcategory));
                var row=new LinkedHashMap<>(b);row.remove("equipment");
                row.put("rarity",ItemFields.integer(m,"rarity",at));row.put("bindOnEquip",ItemFields.bool(m,"bindsWhenEquipped",at));
                for(String k:List.of("stackable","permanent","locked"))row.put(k,ItemFields.bool(m,k,at));
                row.put("modelArguments",arguments.getOrDefault(config,Map.of()));rows.add(row);
                try {
                    if(m.containsKey("combat")&&!Set.of("Sword","Gun","Handgun","SwingingHandgun","Bomb","BlastNetworkBomb").contains(type))throw new IOException(at+" → combat: native item type does not support weapon controls");
                    if(m.containsKey("shield")&&!Set.of("Shield","LockdownShield").contains(type))throw new IOException(at+" → shield: native item type does not support shield controls");
                    if(m.containsKey("combat"))weapons.add(ReadableCombat.weapon(config,type,map(m.get("combat"),at+" → combat"),at+" → combat"));
                    WeaponCatalog.Shield shield=m.containsKey("shield")?ReadableCombat.shield(config,map(m.get("shield"),at+" → shield"),at+" → shield"):null;
                    if(shield!=null)shields.add(shield);
                    boolean member=flag(b.get("equipment"),at+" → equipment binding");
                    if(m.containsKey("modifiers")&&!member)throw new IOException(at+" → modifiers: no native equipment membership");
                    if(member)equipment.add(equipment(config,row,map(m.getOrDefault("modifiers",Map.of()),at+" → modifiers"),at+" → modifiers",shield));
                } catch(IllegalArgumentException bad){throw new IOException(at+": "+bad.getMessage(),bad);}
            }
        }
        if(seen.size()!=bindings.size())throw new IOException(directory+": missing item definitions: "+bindings.keySet().stream().filter(k->!seen.contains(k)).limit(5).toList());
        for(String config:arguments.keySet())if(!ids.containsKey(config))throw new IOException(internal+": arguments for unknown item "+config);
        return new Definitions(ItemCatalog.fromData(Map.of("format","tortodrone-items","version",2,"items",rows)).withDisplayNames(names).withReferenceIds(ids).withLabels(labels),new WeaponCatalog(weapons,shields,dodgeCooldown),new EquipmentCatalog(equipment));
    }
    private static EquipmentCatalog.Entry equipment(String config,Map<String,Object> row,Map<String,Object> m,String at,WeaponCatalog.Shield shield)throws IOException {
        var values=new EnumMap<EquipmentCatalog.WeaponClass,EquipmentCatalog.Modifiers>(EquipmentCatalog.WeaponClass.class);
        fields(m,at,Arrays.stream(EquipmentCatalog.WeaponClass.values()).map(Enum::name).toArray(String[]::new));
        for(var type:EquipmentCatalog.WeaponClass.values()) {
            String p=at+" → "+type;var v=new LinkedHashMap<>(map(m.getOrDefault(type.name(),Map.of()),p));if(shield!=null)v.putIfAbsent("defendingSpeed",shield.defendingSpeed());fields(v,p,"chargeTime","speed","attackSpeed","defendingSpeed");
            values.put(type,new EquipmentCatalog.Modifiers(ItemFields.number(v,"chargeTime",p),ItemFields.number(v,"speed",p),ItemFields.number(v,"attackSpeed",p),ItemFields.number(v,"defendingSpeed",p)));
        }
        return new EquipmentCatalog.Entry(config,WorldProfile.Slot.valueOf(text(row.get("slot"),at)),values);
    }
}
