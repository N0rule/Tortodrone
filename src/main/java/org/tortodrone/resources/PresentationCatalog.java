package org.tortodrone.resources;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Immutable offline tooltip metadata; never participates in game mutations or combat. */
public record PresentationCatalog(Map<String,Object> variants,Map<String,Object> items,
        Map<String,Object> sprites,Map<String,Object> perks) {
    public static PresentationCatalog empty(){return new PresentationCatalog(Map.of(),Map.of(),Map.of(),Map.of());}
    public static PresentationCatalog load(Path path)throws IOException {
        if(!Files.exists(path))return empty();
        var root=Yaml.read(path);Yaml.fields(root,path.toString(),"version","variants","items","tables","sprites","perks");
        if(Yaml.integer(root.get("version"),"version")!=1)throw new IOException("Unsupported presentation version");
        var tables=Yaml.list(root.get("tables"),"tables");
        for(Object table:tables)for(Object value:Yaml.list(table,"level table")) {
            var level=Yaml.map(value,"level");Yaml.fields(level,"level","effects","stats");
            for(Object label:Yaml.list(level.get("effects"),"effects"))Yaml.text(label,"effect");
            for(Object n:Yaml.map(level.get("stats"),"stats").values())if(!(n instanceof Number number)||!Float.isFinite(number.floatValue()))throw new IOException("Invalid presentation statistic");
        }
        for(Object value:Yaml.map(root.get("variants"),"variants").values()) {
            var effect=Yaml.map(value,"variant");Yaml.fields(effect,"variant","label","category","rank","strength");Yaml.text(effect.get("label"),"label");Yaml.text(effect.get("category"),"category");
            if(effect.containsKey("rank"))Yaml.integer(effect.get("rank"),"rank");if(effect.containsKey("strength"))Yaml.text(effect.get("strength"),"strength");
        }
        for(Object value:Yaml.map(root.get("perks"),"perks").values())Yaml.text(value,"perk");
        for(Object value:Yaml.map(root.get("sprites"),"sprites").values()) {
            var sprite=Yaml.map(value,"sprite");Yaml.fields(sprite,"sprite","name","skills");Yaml.text(sprite.get("name"),"sprite name");
            for(Object skillValue:Yaml.list(sprite.get("skills"),"skills")){
                var skill=Yaml.map(skillValue,"skill");Yaml.fields(skill,"skill","name","upgrades","ultimates");Yaml.text(skill.get("name"),"skill name");
                for(String key:List.of("upgrades","ultimates"))for(Object label:Yaml.list(skill.get(key),key))Yaml.text(label,key);
            }
        }
        var frozenTables=tables.stream().map(PresentationCatalog::freezeValue).toList();var items=new TreeMap<String,Object>();
        for(var entry:Yaml.map(root.get("items"),"items").entrySet()){int index=Yaml.integer(entry.getValue(),entry.getKey());if(index<0||index>=tables.size())throw new IOException("Invalid presentation table");items.put(entry.getKey(),frozenTables.get(index));}
        return new PresentationCatalog(section(root,"variants"),Map.copyOf(items),section(root,"sprites"),section(root,"perks"));
    }
    private static Map<String,Object> section(Map<String,Object> root,String key)throws IOException{return freeze(Yaml.map(root.get(key),key));}
    private static Map<String,Object> freeze(Map<String,Object> source){var out=new TreeMap<String,Object>();source.forEach((k,v)->out.put(k,freezeValue(v)));return Collections.unmodifiableMap(out);}
    @SuppressWarnings("unchecked") private static Object freezeValue(Object value){if(value instanceof Map<?,?> m)return freeze((Map<String,Object>)m);if(value instanceof List<?> l)return l.stream().map(PresentationCatalog::freezeValue).toList();return value;}
    @SuppressWarnings("unchecked") public Map<String,Object> variant(String name){return (Map<String,Object>)variants.getOrDefault(name,Map.of("label",name,"category","Unknown"));}
    @SuppressWarnings("unchecked") public Map<String,Object> level(String config,int level){var levels=(List<?>)items.getOrDefault(config,List.of());return level<0||level>=levels.size()?Map.of():(Map<String,Object>)levels.get(level);}
    public String perk(String name){return (String)perks.getOrDefault(name,name);}
}
