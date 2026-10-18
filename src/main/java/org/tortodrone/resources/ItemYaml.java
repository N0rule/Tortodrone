package org.tortodrone.resources;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.world.*;
import static org.tortodrone.resources.Yaml.*;

/** Categorized administrator definitions compiled into the existing catalog types. */
public final class ItemYaml {
    public static final List<String> CATEGORIES=List.of("weapons","gear","costumes","accessories","materials","consumables","misc");
    public record Definitions(ItemCatalog items,WeaponCatalog weapons,EquipmentCatalog equipment) { }
    private ItemYaml() { }
    public static ItemCatalog load(Path directory)throws IOException{return read(directory,0).items();}
    public static Definitions read(Path directory,int dodgeCooldown)throws IOException {
        Path argumentFile=directory.resolve("../_internal/item-arguments.json").normalize();ContentFiles.regular(argumentFile);if(Files.size(argumentFile)>32_000_000)throw new IOException("Item arguments exceed size limit: "+argumentFile);
        var arguments=map(Json.parseCatalog(Files.readString(argumentFile)),"item arguments");
        var rows=new ArrayList<Object>();var weapons=new ArrayList<WeaponCatalog.Weapon>();var shields=new ArrayList<WeaponCatalog.Shield>();var equipment=new ArrayList<EquipmentCatalog.Entry>();
        var definitions=new LinkedHashMap<String,Object>();Path base=directory.resolve("../_internal/item-definitions.json").normalize();
        if(Files.exists(base))definitions.putAll(map(Json.parseCatalog(Files.readString(base)),"native item definitions"));
        var seen=new HashSet<String>();
        for(String category:CATEGORIES) {
            Path file=directory.resolve(category+".yml");var root=Yaml.read(file);fields(root,file.toString(),"items");
            for(var entry:map(root.get("items"),file+": items").entrySet()) {
                String config=entry.getKey(),where=file+": "+config;
                if(!seen.add(config.toLowerCase(Locale.ROOT)))throw new IOException(where+": duplicate item config");
                var edits=map(entry.getValue(),where);
                fields(edits,where,"stockId","type","nameKey","rarity","stackable","slot","bindOnEquip","permanent","locked","variant","seed","combat","shield","modifiers");
                var merged=new LinkedHashMap<>(map(definitions.getOrDefault(config,Map.of()),where));merged.putAll(edits);definitions.put(config,merged);
            }
        }
        for(var entry:definitions.entrySet()) {
            String config=entry.getKey();var row=new LinkedHashMap<>(map(entry.getValue(),config));
            Object combat=row.remove("combat"),shield=row.remove("shield"),modifiers=row.remove("modifiers");
            if(combat!=null)weapons.add(weapon(config,map(combat,config+" combat")));
            if(shield!=null)shields.add(shield(config,map(shield,config+" shield")));
            if(modifiers!=null&&!map(modifiers,config+" modifiers").isEmpty())equipment.add(equipment(config,row,map(modifiers,config+" modifiers")));
            // Empty modifier records carry zero-valued equipment membership too.
            else if(modifiers!=null)equipment.add(equipment(config,row,Map.of()));
            row.put("config",config);row.putIfAbsent("nameKey","");row.putIfAbsent("rarity",-1);row.putIfAbsent("stackable",false);
            row.putIfAbsent("slot",null);row.putIfAbsent("bindOnEquip",false);row.putIfAbsent("permanent",false);row.putIfAbsent("locked",false);
            row.putIfAbsent("variant","ITEM");row.putIfAbsent("seed",null);row.put("modelArguments",arguments.getOrDefault(config,Map.of()));rows.add(row);
        }
        var names=new LinkedHashMap<String,String>();Path nameFile=directory.resolve("../_internal/item-names.json").normalize();
        if(Files.exists(nameFile))for(var e:map(Json.parseCatalog(Files.readString(nameFile)),"item names").entrySet())names.put(e.getKey(),text(e.getValue(),"item name"));
        try{return new Definitions(ItemCatalog.fromData(Map.of("format","tortodrone-items","version",2,"items",rows)).withDisplayNames(names),new WeaponCatalog(weapons,shields,dodgeCooldown),new EquipmentCatalog(equipment));}
        catch(IllegalArgumentException bad){throw new IOException(directory+": "+bad.getMessage(),bad);}
    }
    private static WeaponCatalog.Weapon weapon(String config,Map<String,Object> m)throws IOException {
        fields(m,config+" combat","chargeTimeMs","maxChain","chainCooldownMs","cycle","blended","attackMovementSpeed","chargeMovementSpeed","attacks","incomplete","charged","cooldown");
        var attacks=new ArrayList<WeaponCatalog.Attack>();for(Object a:list(m.getOrDefault("attacks",List.of()),config+" attacks"))attacks.add(attack(a));
        return new WeaponCatalog.Weapon(config,i(m,"chargeTimeMs",0),i(m,"maxChain",0),i(m,"chainCooldownMs",0),b(m,"cycle",false),b(m,"blended",false),f(m,"attackMovementSpeed",0),f(m,"chargeMovementSpeed",0),attacks,attack(m.get("incomplete")),attack(m.get("charged")),attack(m.get("cooldown")));
    }
    private static WeaponCatalog.Shield shield(String config,Map<String,Object> m)throws IOException {
        fields(m,config+" shield","health","regenTimeMs","regenDelayMs","defendingSpeedModifier","bashHealthFraction","breakDelayMs","bash");
        return new WeaponCatalog.Shield(config,i(m,"health",0),i(m,"regenTimeMs",1),i(m,"regenDelayMs",0),f(m,"defendingSpeedModifier",0),f(m,"bashHealthFraction",0),attack(m.get("bash")),i(m,"breakDelayMs",7000));
    }
    private static WeaponCatalog.Attack attack(Object value)throws IOException {
        if(value==null)return null;var m=map(value,"attack");fields(m,"attack","landMs","rearmMs","clearMs","blended","impulseDelayMs","impulseDurationMs","impulseX","impulseY");
        return new WeaponCatalog.Attack(i(m,"landMs",0),i(m,"rearmMs",0),i(m,"clearMs",0),b(m,"blended",false),i(m,"impulseDelayMs",-1),i(m,"impulseDurationMs",-1),f(m,"impulseX",0),f(m,"impulseY",0));
    }
    private static EquipmentCatalog.Entry equipment(String config,Map<String,Object> row,Map<String,Object> m)throws IOException {
        var values=new EnumMap<EquipmentCatalog.WeaponClass,EquipmentCatalog.Modifiers>(EquipmentCatalog.WeaponClass.class);
        for(String key:m.keySet())try{EquipmentCatalog.WeaponClass.valueOf(key);}catch(IllegalArgumentException invalid){throw new IOException(config+": unknown modifier weapon class "+key);}
        for(var type:EquipmentCatalog.WeaponClass.values()) {
            var v=map(m.getOrDefault(type.name(),Map.of()),config+" modifiers");fields(v,config,"chargeTime","speed","attackSpeed","defendingSpeed");
            values.put(type,new EquipmentCatalog.Modifiers(f(v,"chargeTime",0),f(v,"speed",0),f(v,"attackSpeed",0),f(v,"defendingSpeed",0)));
        }
        return new EquipmentCatalog.Entry(config,WorldProfile.Slot.valueOf(text(row.get("slot"),config+" slot")),values);
    }
    private static int i(Map<String,Object> m,String k,int fallback)throws IOException{return integer(m.getOrDefault(k,fallback),k);}
    private static float f(Map<String,Object> m,String k,float fallback)throws IOException{return decimal(m.getOrDefault(k,fallback),k);}
    private static boolean b(Map<String,Object> m,String k,boolean fallback)throws IOException{return flag(m.getOrDefault(k,fallback),k);}
}
