package org.tortodrone.resources;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.ItemCatalog;
import static org.tortodrone.resources.Yaml.*;

/** One owner per Town section; existing transaction rules consume the compiled catalog. */
public final class TownYaml {
    public static final Map<String,List<String>> SECTIONS;
    static {var m=new LinkedHashMap<String,List<String>>();
        m.put("shops",List.of("catalogs","depot"));m.put("recipes",List.of("recipes"));m.put("stations",List.of("stations"));
        m.put("sprites",List.of("sprites","perks","harnesses"));m.put("variants",List.of("variantTables","bonuses"));
        m.put("accessories",List.of("accessories","blockers"));m.put("progression",List.of("items","forgeTables","gearProgression"));SECTIONS=Collections.unmodifiableMap(m);}
    private static final Map<String,String> FIELDS=Map.ofEntries(
        Map.entry("accessories","blocks config destroy location model parameter remove"),Map.entry("blockers","config slots"),
        Map.entry("bonuses","attack charge config health speed weapon"),Map.entry("catalogs","chance config goods includes kind max min"),
        Map.entry("depot","count energy featured item limit price"),Map.entry("forgeTables","config rows"),
        Map.entry("gearProgression","baseHeat config forge levels maxHeat"),Map.entry("harnesses","config damageType rechargeModifier"),
        Map.entry("items","accessory buyPrices config crowns giftBox harness itemProp levelProp rarity recipe sellPrices sprite spriteFoodRole type variant variantGroups"),
        Map.entry("perks","name previous tier variant"),Map.entry("recipes","config count crowns ingredients item locked maxHours oneTime useOrbs variantTable"),
        Map.entry("sprites","config preferredFood restrictDamageType skills"),Map.entry("stations","actor catalog cycle description group kind level model name perKnight preparer recipes title type"),
        Map.entry("variantTables","config root"));
    private TownYaml() { }
    public static Map<String,Object> data(Path directory)throws IOException {
        var result=new LinkedHashMap<String,Object>();result.put("format",1);
        for(var section:SECTIONS.entrySet()) {
            Path path=directory.resolve(section.getKey()+".yml");var yaml=read(path);fields(yaml,path.toString(),section.getValue().toArray(String[]::new));
            for(String field:section.getValue()) {
                var values=list(yaml.getOrDefault(field,List.of()),path+": "+field);var ids=new HashSet<Object>();
                for(Object raw:values) {var row=map(raw,path+": "+field);fields(row,path+": "+field,FIELDS.get(field).split(" "));validate(field,row,path.toString());Object id=row.get(field.equals("stations")?"actor":field.equals("perks")?"name":"config");
                    if(id!=null&&!ids.add(id))throw new IOException(path+": duplicate "+field+" ID "+id);}
                result.put(field,values);
            }
        }
        return result;
    }
    private static void validate(String section,Map<String,Object> row,String path)throws IOException {
        String where=path+": "+row.getOrDefault("config",row.getOrDefault("actor",section));
        if(section.equals("depot"))good(row,where,true);
        if(section.equals("catalogs")) {
            for(Object value:list(row.get("goods"),where+" goods"))good(map(value,where),where,false);
            for(String field:List.of("min","max"))if(integer(row.get(field),where+" "+field)<0)throw new IOException(where+": "+field+" must be nonnegative");
            float chance=decimal(row.get("chance"),where+" chance");if(chance<0||chance>1)throw new IOException(where+": chance must be from 0 to 1");
        }
        if(section.equals("recipes")) {
            if(integer(row.get("count"),where+" count")<1)throw new IOException(where+": recipe output count must be positive");
            for(Object price:list(row.get("crowns"),where+" crowns"))if(integer(price,where+" crowns")<0)throw new IOException(where+": Crown cost must be nonnegative");
            for(Object raw:list(row.get("ingredients"),where+" ingredients")) {
                var i=map(raw,where+" ingredient");fields(i,where+" ingredient","kind","item","count","rarity","group","level","upgrade","keepHeat","options");
                if(integer(i.get("count"),where+" ingredient count")<1)throw new IOException(where+": ingredient count must be positive");
                integer(i.get("rarity"),where+" ingredient rarity");integer(i.get("level"),where+" ingredient level");flag(i.get("keepHeat"),where+" keepHeat");
            }
        }
        if(section.equals("stations")){int level=integer(row.getOrDefault("level",10),where+" level");if(level<0||level>100)throw new IOException(where+": station level must be from 0 to 100");}
    }
    private static void good(Map<String,Object> row,String where,boolean depot)throws IOException {
        if(!depot)fields(row,where+" good","item","count","price","energy","limit","token","tokenCount");
        int count=integer(row.get("count"),where+" count"),price=integer(row.get("price"),where+" price"),energy=integer(row.get("energy"),where+" energy"),limit=integer(row.get("limit"),where+" limit");
        if(count<1||count>9999||price< -1||energy< -1||(price<0&&energy<0)||limit<0)throw new IOException(where+": invalid shop quantity, currency selector, price or limit");
        if(!depot&&integer(row.get("tokenCount"),where+" tokenCount")<0)throw new IOException(where+": tokenCount must be nonnegative");
    }
    public static void references(Map<String,Object> data,ItemCatalog items)throws IOException {
        var catalogs=new HashSet<String>();for(Object raw:list(data.get("catalogs"),"catalogs"))catalogs.add(text(map(raw,"catalog").get("config"),"catalog config"));
        var recipes=new HashSet<String>();for(Object raw:list(data.get("recipes"),"recipes"))recipes.add(text(map(raw,"recipe").get("config"),"recipe config"));
        for(Object raw:list(data.get("stations"),"stations")) {
            var station=map(raw,"station");String catalog=text(station.getOrDefault("catalog",""),"station catalog");
            if(!catalog.isEmpty()&&!catalogs.contains(catalog))throw new IOException("Station references missing shop catalog: "+catalog);
            for(Object recipe:list(station.get("recipes"),"station recipes"))if(!recipes.contains(text(recipe,"recipe")))throw new IOException("Station references missing recipe: "+recipe);
        }
        for(Object raw:list(data.get("catalogs"),"catalogs"))for(Object included:list(map(raw,"catalog").get("includes"),"includes")) {
            String name=text(included,"catalog include");if(!name.isEmpty()&&!catalogs.contains(name))throw new IOException("Missing included shop catalog: "+name);
        }
        for(String field:List.of("recipes","catalogs","depot"))for(Object value:list(data.get(field),field)) {
            var row=map(value,field);
            if(field.equals("catalogs")) {for(Object good:list(row.get("goods"),"goods"))item(map(good,"good").get("item"),items);}
            else {item(row.get("item"),items);if(field.equals("recipes"))for(Object raw:list(row.get("ingredients"),"ingredients")) {
                var ingredient=map(raw,"ingredient");Object ref=ingredient.get("item");if(ref instanceof String s&&!s.isEmpty())item(ref,items);
            }}
        }
    }
    private static void item(Object ref,ItemCatalog items)throws IOException {
        String config=text(ref,"Town item reference");if(items.find(config)==null)throw new IOException("Town references missing item: "+config);
    }
}
