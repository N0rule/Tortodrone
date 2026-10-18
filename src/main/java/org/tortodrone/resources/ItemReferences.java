package org.tortodrone.resources;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.ItemCatalog;
import static org.tortodrone.resources.Yaml.*;

/** Schema-aware reference translation. Non-item namespaces (recipes, actors, accessories, fires) stay distinct. */
final class ItemReferences {
    private ItemReferences() { }
    static void town(String section,List<?> rows,ItemCatalog items,boolean exporting,String at)throws IOException {
        for(Object raw:rows) {
            var row=map(raw,at);String where=at+" → "+row.getOrDefault("config",section);
            if(Set.of("items","gearProgression","blockers").contains(section)) {
                // Native property tables include non-inventory definitions; they are a separate namespace.
                Object config=row.get("config");boolean nativeProperty=section.equals("items")&&config instanceof String s&&s.contains("/")&&items.find(s)==null;
                if(!nativeProperty)ref(row,"config",items,exporting,where);
            }
            if(section.equals("catalogs"))for(Object g:list(row.getOrDefault("goods",List.of()),where+" → goods")) {var good=map(g,where);ref(good,"item",items,exporting,where+" → goods");ref(good,"token",items,exporting,where+" → goods");}
            if(section.equals("recipes")) {
                ref(row,"item",items,exporting,where);
                for(Object i:list(row.get("ingredients"),where+" → ingredients")) {var ingredient=map(i,where);ref(ingredient,"item",items,exporting,where+" → ingredients");refs(ingredient,"options",items,exporting,where+" → ingredients");}
            }
            if(section.equals("sprites"))refs(row,"preferredFood",items,exporting,where);
        }
    }
    private static void refs(Map<String,Object> row,String key,ItemCatalog items,boolean out,String at)throws IOException {
        if(!row.containsKey(key))return;var values=new ArrayList<String>();for(Object raw:list(row.get(key),at+" → "+key))values.add(resolve(text(raw,at+" → "+key),items,out,at+" → "+key));row.put(key,values);
    }
    private static void ref(Map<String,Object> row,String key,ItemCatalog items,boolean out,String at)throws IOException {
        if(row.containsKey(key))row.put(key,resolve(text(row.get(key),at+" → "+key),items,out,at+" → "+key));
    }
    private static String resolve(String ref,ItemCatalog items,boolean out,String at)throws IOException {
        if(ref.isEmpty())return ref;
        try {String config=items.resolveConfig(ref);return out?items.referenceId(items.find(config)):config;}
        catch(IOException invalid){throw new IOException(at+": missing item or ambiguous reference '"+ref+"': "+invalid.getMessage(),invalid);}
    }
    static void exportTown(Path directory,ItemCatalog items)throws IOException {
        for(String name:TownYaml.SECTIONS.keySet()) {
            Path file=directory.resolve(name+".yml");var root=Yaml.read(file);
            for(var e:root.entrySet())town(e.getKey(),list(e.getValue(),file.toString()),items,true,file+" → "+e.getKey());
            Files.delete(file);Yaml.write(file,root);
        }
    }
    static Map<String,Object> gear(Map<String,Object> rows,ItemCatalog items,boolean out,String at)throws IOException {
        var result=new LinkedHashMap<String,Object>();for(var e:rows.entrySet()) {String key=e.getKey().startsWith("variant.")?e.getKey():resolve(e.getKey(),items,out,at);if(result.putIfAbsent(key,e.getValue())!=null)throw new IOException(at+": duplicate item reference "+key);}return result;
    }
    static void exportGear(Path content,ItemCatalog items)throws IOException {
        Path file=content.resolve("shared/combat.yml");var root=Yaml.read(file);var properties=map(root.get("properties"),file.toString());
        if(properties.containsKey("gear"))properties.put("gear",gear(map(properties.get("gear"),"gear"),items,true,file+" → gear"));
        Files.delete(file);Yaml.write(file,root);
    }
}
