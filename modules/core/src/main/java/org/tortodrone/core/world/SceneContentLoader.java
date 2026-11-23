package org.tortodrone.core.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Prepared-properties ingestion for {@link SceneContent}.
 *
 * <p>The runtime record keeps only the immutable actor/item/status tables and the {@code Spec}
 * lookups. This class owns the exported properties format, the sorted-key range scans and the
 * binding/table limits, and refuses content whose pinned layout hash does not match.
 */
public final class SceneContentLoader {
    private SceneContentLoader() { }

    public static SceneContent load(Path folder,String layoutHash)throws IOException {
        Path file=folder.resolve("content.properties");if(!Files.exists(file))return SceneContent.empty();
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file)||Files.size(file)>SceneContent.MAX_BYTES)throw new IOException("Invalid scene content");
        var p=new Properties();try(var in=Files.newInputStream(file)){p.load(in);}
        return load(p,layoutHash);
    }
    /** Every property whose key starts with {@code prefix}, with the prefix removed. */
    private static TreeMap<String,String> fields(java.util.NavigableSet<String> names,Properties p,String prefix){
        var fields=new TreeMap<String,String>();
        for(String key:names.subSet(prefix,true,prefix+'\uffff',true))fields.put(key.substring(prefix.length()),p.getProperty(key));
        return fields;
    }
    public static SceneContent load(Properties p,String layoutHash)throws IOException {
        if(!"1".equals(p.getProperty("format"))||!layoutHash.equals(p.getProperty("layout-sha256")))throw new IOException("Scene content does not match layout");
        var entries=new ArrayList<SceneContent.Entry>();var scales=new TreeMap<String,Float>();var stats=new TreeMap<String,Float>();var keys=new HashSet<String>();
        // One sorted name snapshot: Properties.stringPropertyNames() rebuilds a set of
        // every key, and the per-entry/per-item scans below would otherwise repeat that
        // allocation thousands of times on the largest imported maps. The sorted set turns
        // each "keys starting with this prefix" scan into a range lookup instead.
        var names=new TreeSet<>(p.stringPropertyNames());
        try {
            for(String id:p.getProperty("entries","").split(",")) {
                if(id.isBlank())continue;if(entries.size()>=2048)throw new IllegalArgumentException("Too many actors");
                var fields=fields(names,p,"entry."+id+".");
                var spec=new SceneContent.Spec(fields);String key=spec.text("source-key","");
                if(key.isBlank()||!keys.add(key+":"+spec.text("handler-index",""))||spec.text("config","").isBlank()||spec.text("kind","").isBlank())throw new IllegalArgumentException("Invalid binding");
                float x=finite(spec.number("x",Float.NaN)),y=finite(spec.number("y",Float.NaN)),rotation=finite(spec.number("rotation",Float.NaN));
                spec.config();if(!spec.child().fields().isEmpty())spec.child().config();
                if(spec.text("kind","").equals("SceneTrigger"))SceneActions.rule(new SceneContent.Entry(key,x,y,rotation,spec));
                entries.add(new SceneContent.Entry(key,x,y,rotation,spec));
            }
            for(String key:names)if(key.startsWith("scale."))scales.put(key.substring(6),finite(Float.parseFloat(p.getProperty(key))));
            for(String key:names)if(key.startsWith("destination.")||key.startsWith("player.")||key.startsWith("gear.")||key.startsWith("monster.")||key.startsWith("status-power.")||key.startsWith("status-chance."))stats.put(key,finite(Float.parseFloat(p.getProperty(key))));
            var items=new TreeMap<String,SceneContent.Spec>();
            for(String id:p.getProperty("items","").split(",")){
                if(id.isBlank())continue;if(items.size()>=256)throw new IllegalArgumentException("Too many scene items");
                var fields=fields(names,p,"item."+id+".");
                var spec=new SceneContent.Spec(fields);String name=spec.text("config","");if(name.isBlank()||items.putIfAbsent(name,spec)!=null)throw new IllegalArgumentException("Invalid scene item");
            }
            var cycles=new TreeMap<String,SceneContent.Spec>();
            for(String key:names)if(key.startsWith("cycle.")){
                String[] split=key.split("\\.",3);if(split.length!=3)throw new IllegalArgumentException("Invalid trap cycle");
                var fields=new TreeMap<String,String>(cycles.getOrDefault(split[1],new SceneContent.Spec(Map.of())).fields());fields.put(split[2],p.getProperty(key));cycles.put(split[1],new SceneContent.Spec(fields));
            }
            var statuses=new TreeMap<String,SceneContent.Spec>();
            for(String id:p.getProperty("statuses","").split(",")){
                if(id.isBlank())continue;if(statuses.size()>=256)throw new IllegalArgumentException("Too many status configs");
                var fields=fields(names,p,"status."+id+".");
                var spec=new SceneContent.Spec(fields);String name=spec.text("config","");if(name.isBlank()||statuses.putIfAbsent(name,spec)!=null)throw new IllegalArgumentException("Invalid status config");
            }
            var icons=new LinkedHashSet<SceneContent.MapIcon>();
            for(var entry:entries){String icon=entry.spec().text("minimap-icon","");if(!icon.isEmpty())icons.add(new SceneContent.MapIcon(icon,entry.x(),entry.y()));}
            for(String key:names)if(key.startsWith("minimap.")&&key.endsWith(".icon")){
                String prefix=key.substring(0,key.length()-4);String icon=p.getProperty(key);
                icons.add(new SceneContent.MapIcon(icon,finite(Float.parseFloat(p.getProperty(prefix+"x"))),finite(Float.parseFloat(p.getProperty(prefix+"y")))));
            }
            if(icons.size()>2048)throw new IllegalArgumentException("Too many minimap icons");
            var templates=new TreeMap<String,SceneContent.Spec>();
            for(String id:p.getProperty("actor-templates","").split(",")){
                if(id.isBlank())continue;
                if(templates.size()>=128||!id.matches("spawn_[0-9]{1,3}"))throw new IllegalArgumentException("Invalid or oversized scene actor template table");
                var fields=fields(names,p,"actor-template."+id+".");
                if(templates.putIfAbsent(id,new SceneContent.Spec(fields))!=null)throw new IllegalArgumentException("Duplicate scene actor template");
            }
            var content=new SceneContent(entries,scales,SceneConversation.load(p),stats,items,cycles,statuses,List.copyOf(icons),templates);content.validateConversations();return content;
        }catch(IllegalArgumentException invalid){throw new IOException("Invalid prepared scene content",invalid);}
    }
    private static float finite(float f){if(!Float.isFinite(f)||Math.abs(f)>1_000_000)throw new IllegalArgumentException("Invalid content number");return f;}
}
