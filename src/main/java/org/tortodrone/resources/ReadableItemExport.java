package org.tortodrone.resources;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.world.*;
import static org.tortodrone.resources.Yaml.*;

/** Deterministic export into a fresh item directory; no administrator files are replaced. */
final class ReadableItemExport {
    private ReadableItemExport() { }
    static ItemCatalog write(ItemYaml.Definitions source,Path content)throws IOException {
        Path itemDir=content.resolve("items"),internal=content.resolve("_internal");
        if(Files.exists(itemDir)||Files.exists(internal.resolve("item-bindings.json")))throw new IOException("Item export destination already exists: "+itemDir);
        Files.createDirectories(itemDir);Files.createDirectories(internal);
        var items=source.items().withReferenceIds(ItemIds.assign(source.items()));
        Path temporary=Files.createTempFile(content,".item-export-",".json");
        Map<String,Object> raw;
        try{Files.delete(temporary);ItemCatalog.write(temporary,items.entries());raw=ItemYaml.json(temporary);}finally{Files.deleteIfExists(temporary);}
        var families=new LinkedHashMap<String,Map<String,Object>>();for(String f:ItemYaml.CATEGORIES)families.put(f,new TreeMap<>());
        var bindings=new TreeMap<String,Object>();var arguments=new TreeMap<String,Object>();var gear=new HashMap<String,EquipmentCatalog.Entry>();for(var e:source.equipment().entries())gear.put(e.config(),e);
        for(Object o:list(raw.get("items"),"items")) {
            var nativeRow=map(o,"item");String config=text(nativeRow.get("config"),"config");var e=items.find(config);String id=items.referenceId(e);
            var b=new LinkedHashMap<String,Object>();for(String k:List.of("config","stockId","type","slot","nameKey","variant","seed"))b.put(k,nativeRow.get(k));b.put("equipment",gear.containsKey(config));bindings.put(id,b);
            var args=map(nativeRow.get("modelArguments"),"arguments");if(!args.isEmpty())arguments.put(config,args);
            var row=new LinkedHashMap<String,Object>();row.put("name",items.displayName(e));row.put("category",items.hasLabels(e)?items.category(e):category(e));
            if(items.hasLabels(e)) {if(!items.subcategory(e).isEmpty())row.put("subcategory",items.subcategory(e));}
            else if(e.type().equals("AccessoryTicket")) {String[] parts=config.split("/");if(parts.length>3)row.put("subcategory",parts[2]);}
            ItemFields.put(row,"rarity",e.rarity());ItemFields.put(row,"bindsWhenEquipped",e.bindOnEquip());ItemFields.put(row,"stackable",e.stackable());ItemFields.put(row,"permanent",e.permanent());ItemFields.put(row,"locked",e.locked());
            var weapon=source.weapons().find(config);if(weapon!=null)row.put("combat",ReadableCombat.weapon(weapon,e.type()));
            var shield=source.weapons().findShield(config);if(shield!=null)row.put("shield",ReadableCombat.shield(shield));
            var equipment=gear.get(config);if(equipment!=null) {
                var mods=new LinkedHashMap<String,Object>();for(var entry:equipment.byWeaponClass().entrySet()) {
                    var v=entry.getValue();var m=new LinkedHashMap<String,Object>();ItemFields.put(m,"chargeTime",v.chargeTime());ItemFields.put(m,"speed",v.speed());ItemFields.put(m,"attackSpeed",v.attackSpeed());if(Float.compare(v.defendingSpeed(),shield==null?0f:shield.defendingSpeed())!=0)m.put("defendingSpeed",v.defendingSpeed());if(!m.isEmpty())mods.put(entry.getKey().name(),m);
                }if(!mods.isEmpty())row.put("modifiers",mods);
            }
            families.get(family(e)).put(id,row);
        }
        for(var f:families.entrySet())Yaml.write(itemDir.resolve(f.getKey()+".yml"),Map.of("items",f.getValue()));
        Files.writeString(internal.resolve("item-bindings.json"),Json.stringify(Map.of("format","tortodrone-item-bindings","version",1,"items",bindings))+"\n",StandardOpenOption.CREATE_NEW);
        Files.writeString(internal.resolve("item-arguments.json"),Json.stringify(arguments)+"\n",StandardOpenOption.CREATE_NEW);return items;
    }
    private static String category(ItemCatalog.Entry e) {
        if(e.type().equals("AccessoryTicket")) {String[] p=e.config().split("/");if(p.length>2&&p[0].equals("Accessory"))return p[1];}
        return switch(e.type()) {case "Handgun","SwingingHandgun"->"Gun";case "BlastNetworkBomb"->"Bomb";case "Craft"->"Material";case "TokenArtifact","SanctuaryArtifact"->"Artifact";default->e.type();};
    }
    private static String family(ItemCatalog.Entry e) {
        String t=e.type();return Set.of("Sword","Gun","Handgun","SwingingHandgun","Bomb","BlastNetworkBomb").contains(t)?"weapons":t.contains("Costume")?"costumes":t.contains("Accessory")?"accessories":Set.of("Helm","Armor","Shield","LockdownShield","Trinket").contains(t)?"gear":Set.of("Material","Craft","Mineral","MetaMineral").contains(t)?"materials":Set.of("Consumable","Usable","Capsule","Health","Energy","Vial","InstantAction").contains(t)?"consumables":"misc";
    }
}
