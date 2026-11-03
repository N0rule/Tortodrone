package org.tortodrone.world;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.StarterCatalog;

/** Prepared native actor bindings. Runtime instances never depend on client resources. */
public record SceneContent(List<Entry> entries, Map<String,Float> scales,Map<String,SceneConversation> conversations,Map<String,Float> stats,Map<String,Spec> items,Map<String,Spec> cycles,Map<String,Spec> statuses,List<MapIcon> mapIcons,Map<String,Spec> actorTemplates) {
    public record MapIcon(String icon,float x,float y) { }
    public SceneContent(List<Entry> entries,Map<String,Float> scales,Map<String,SceneConversation> conversations,Map<String,Float> stats,Map<String,Spec> items,Map<String,Spec> cycles,Map<String,Spec> statuses,List<MapIcon> mapIcons){this(entries,scales,conversations,stats,items,cycles,statuses,mapIcons,Map.of());}
    public SceneContent(List<Entry> entries,Map<String,Float> scales,Map<String,SceneConversation> conversations,Map<String,Float> stats,Map<String,Spec> items,Map<String,Spec> cycles,Map<String,Spec> statuses){this(entries,scales,conversations,stats,items,cycles,statuses,List.of());}
    public SceneContent(List<Entry> entries,Map<String,Float> scales){this(entries,scales,Map.of(),Map.of(),Map.of(),Map.of(),Map.of());}
    public static final class Spec {
        private final Map<String,String> fields;
        // Definitions are shared by instances; decoded immutable references are safe to reuse.
        private final java.util.concurrent.ConcurrentMap<String,StarterCatalog.Model> models=new java.util.concurrent.ConcurrentHashMap<>();
        public Spec(Map<String,String> fields) { this.fields=Map.copyOf(fields); }
        public Map<String,String> fields(){return fields;}
        @Override public boolean equals(Object other){return other instanceof Spec spec&&fields.equals(spec.fields);}
        @Override public int hashCode(){return fields.hashCode();}
        @Override public String toString(){return "Spec[fields="+fields+"]";}
        public String text(String key,String fallback){return fields.getOrDefault(key,fallback);}
        public float number(String key,float fallback){return fields.containsKey(key)?Float.parseFloat(fields.get(key)):fallback;}
        public int integer(String key,int fallback){return fields.containsKey(key)?Integer.parseInt(fields.get(key)):fallback;}
        public boolean flag(String key,boolean fallback){return fields.containsKey(key)?Boolean.parseBoolean(fields.get(key)):fallback;}
        public StarterCatalog.Model model(String key){
            String value=fields.get(key);if(value==null||value.equals("null"))return null;
            return models.computeIfAbsent(key,k->{try{return FireCatalog.modelJson(value);}catch(IOException invalid){throw new IllegalArgumentException("Invalid prepared reference: "+key,invalid);}});
        }
        public StarterCatalog.Model config(){
            if(fields.containsKey("config-json"))return model("config-json");
            var args=new TreeMap<String,Object>();
            for(var e:fields.entrySet())if(e.getKey().startsWith("config.argument.")) {
                String[] v=e.getValue().split("\\|",3);Object value=switch(v[0]){
                    case "float" -> Float.parseFloat(v[1]);case "int" -> Integer.parseInt(v[1]);
                    case "boolean" -> Boolean.parseBoolean(v[1]);case "string" -> e.getValue().substring(7);
                    case "model" -> {try{yield FireCatalog.modelJson(e.getValue().substring(6));}catch(IOException invalid){throw new IllegalArgumentException(invalid);}}
                    case "data" -> {try{yield FireCatalog.modelJson(e.getValue().substring(5)).arguments().get("value");}catch(IOException invalid){throw new IllegalArgumentException(invalid);}}
                    case "enum" -> {String[] bits=e.getValue().split("\\|",4);yield new FireCatalog.EnumValue(bits[1],bits[3],Integer.parseInt(bits[2]));}
                    default -> throw new IllegalArgumentException("Unsupported prepared actor argument");};
                args.put(e.getKey().substring(16),value);
            }
            return new StarterCatalog.Model(text("config",""),args);
        }
        public Spec child(){var child=new TreeMap<String,String>();fields.forEach((k,v)->{if(k.startsWith("child."))child.put(k.substring(6),v);});return new Spec(child);}
        public Spec section(String prefix){var section=new TreeMap<String,String>();fields.forEach((k,v)->{if(k.startsWith(prefix))section.put(k.substring(prefix.length()),v);});return new Spec(section);}
        public boolean contains(float cx,float cy,float x,float y,float radius){
            return contains(cx,cy,0,x,y,radius);
        }
        public boolean contains(float cx,float cy,float rotation,float x,float y,float radius){
            float dx=x-cx,dy=y-cy,c=(float)Math.cos(rotation),s=(float)Math.sin(rotation);
            float tx=dx*c+dy*s,ty=-dx*s+dy*c;
            if(flag("interaction-area",false)||text("shape","").equals("Compound")){
                var shape=model("source-shape");if(shape!=null)return SceneShape.intersects(shape.arguments().get("value"),tx,ty,radius);
            }
            if(fields.containsKey("radius"))return Math.hypot(cx-x,cy-y)<=number("radius",0)+radius;
            float gapX=Math.max(number("bounds-min-x",-number("width",0)/2)-tx,Math.max(0,tx-number("bounds-max-x",number("width",0)/2)));
            float gapY=Math.max(number("bounds-min-y",-number("height",0)/2)-ty,Math.max(0,ty-number("bounds-max-y",number("height",0)/2)));
            return gapX*gapX+gapY*gapY<=radius*radius;
        }
    }
    public record Entry(String key,float x,float y,float rotation,Spec spec) { }
    public SceneContent {
        entries=List.copyOf(entries);scales=Map.copyOf(scales);conversations=Map.copyOf(conversations);stats=Map.copyOf(stats);items=Map.copyOf(items);cycles=Map.copyOf(cycles);statuses=Map.copyOf(statuses);mapIcons=List.copyOf(mapIcons);actorTemplates=Map.copyOf(actorTemplates);
        SceneActorTemplates.validate(entries,actorTemplates);
    }
    public static SceneContent empty(){return new SceneContent(List.of(),Map.of());}
    /** Placed NPCs own one actor binding; scene-started dialogues require reachable authored actions. */
    public void validateConversations()throws IOException {
        var pending=new ArrayDeque<String>();var reached=new HashSet<String>();
        for(var entry:entries)if(entry.spec().text("kind","").equals("SceneTrigger"))pending.addAll(SceneActions.conversationIds(SceneActions.rule(entry).action()));
        else pending.addAll(SceneScript.conversations(entry.spec()));
        for(var conversation:conversations.values())if(!conversation.actor().startsWith("@conversation/")){
            long count=entries.stream().filter(e->!Set.of("SceneTrigger","SceneAnchor").contains(e.spec().text("kind",""))&&e.spec().text("dialogue",e.spec().text("config","")).equals(conversation.actor())).count();
            if(count!=1)throw new IOException("Missing or ambiguous NPC actor binding: "+conversation.actor());
            pending.add(conversation.actor());
        }
        while(!pending.isEmpty()){
            String id=pending.removeFirst();if(!reached.add(id))continue;
            var definition=conversations.get(id);if(definition==null)throw new IOException("Missing scripted conversation: "+id);
            if(definition.initialAction()!=null)pending.addAll(SceneActions.conversationIds(definition.initialAction()));
            for(var node:definition.nodes().values())for(var response:node.responses())if(response.action()!=null)pending.addAll(SceneActions.conversationIds(response.action()));
        }
        for(var conversation:conversations.values())if(conversation.actor().startsWith("@conversation/")&&!reached.contains(conversation.actor()))throw new IOException("Unreferenced scripted conversation: "+conversation.actor());
    }
    public static SceneContent load(Path folder,String layoutHash)throws IOException {
        Path file=folder.resolve("content.properties");if(!Files.exists(file))return empty();
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file)||Files.size(file)>4_000_000)throw new IOException("Invalid scene content");
        var p=new Properties();try(var in=Files.newInputStream(file)){p.load(in);}
        return load(p,layoutHash);
    }
    public static SceneContent load(Properties p,String layoutHash)throws IOException {
        if(!"1".equals(p.getProperty("format"))||!layoutHash.equals(p.getProperty("layout-sha256")))throw new IOException("Scene content does not match layout");
        var entries=new ArrayList<Entry>();var scales=new TreeMap<String,Float>();var stats=new TreeMap<String,Float>();var keys=new HashSet<String>();
        try {
            for(String id:p.getProperty("entries","").split(",")) {
                if(id.isBlank())continue;if(entries.size()>=2048)throw new IllegalArgumentException("Too many actors");
                String prefix="entry."+id+".";var fields=new TreeMap<String,String>();
                for(String key:p.stringPropertyNames())if(key.startsWith(prefix))fields.put(key.substring(prefix.length()),p.getProperty(key));
                var spec=new Spec(fields);String key=spec.text("source-key","");
                if(key.isBlank()||!keys.add(key+":"+spec.text("handler-index",""))||spec.text("config","").isBlank()||spec.text("kind","").isBlank())throw new IllegalArgumentException("Invalid binding");
                float x=finite(spec.number("x",Float.NaN)),y=finite(spec.number("y",Float.NaN)),rotation=finite(spec.number("rotation",Float.NaN));
                spec.config();if(!spec.child().fields().isEmpty())spec.child().config();
                if(spec.text("kind","").equals("SceneTrigger"))SceneActions.rule(new Entry(key,x,y,rotation,spec));
                entries.add(new Entry(key,x,y,rotation,spec));
            }
            for(String key:p.stringPropertyNames())if(key.startsWith("scale."))scales.put(key.substring(6),finite(Float.parseFloat(p.getProperty(key))));
            for(String key:p.stringPropertyNames())if(key.startsWith("destination.")||key.startsWith("player.")||key.startsWith("gear.")||key.startsWith("status-power.")||key.startsWith("status-chance."))stats.put(key,finite(Float.parseFloat(p.getProperty(key))));
            var items=new TreeMap<String,Spec>();
            for(String id:p.getProperty("items","").split(",")){
                if(id.isBlank())continue;if(items.size()>=256)throw new IllegalArgumentException("Too many scene items");
                var fields=new TreeMap<String,String>();String prefix="item."+id+".";
                for(String key:p.stringPropertyNames())if(key.startsWith(prefix))fields.put(key.substring(prefix.length()),p.getProperty(key));
                var spec=new Spec(fields);String name=spec.text("config","");if(name.isBlank()||items.putIfAbsent(name,spec)!=null)throw new IllegalArgumentException("Invalid scene item");
            }
            var cycles=new TreeMap<String,Spec>();
            for(String key:p.stringPropertyNames())if(key.startsWith("cycle.")){
                String[] split=key.split("\\.",3);if(split.length!=3)throw new IllegalArgumentException("Invalid trap cycle");
                var fields=new TreeMap<String,String>(cycles.getOrDefault(split[1],new Spec(Map.of())).fields());fields.put(split[2],p.getProperty(key));cycles.put(split[1],new Spec(fields));
            }
            var statuses=new TreeMap<String,Spec>();
            for(String id:p.getProperty("statuses","").split(",")){
                if(id.isBlank())continue;if(statuses.size()>=256)throw new IllegalArgumentException("Too many status configs");
                var fields=new TreeMap<String,String>();String prefix="status."+id+".";
                for(String key:p.stringPropertyNames())if(key.startsWith(prefix))fields.put(key.substring(prefix.length()),p.getProperty(key));
                var spec=new Spec(fields);String name=spec.text("config","");if(name.isBlank()||statuses.putIfAbsent(name,spec)!=null)throw new IllegalArgumentException("Invalid status config");
            }
            var icons=new LinkedHashSet<MapIcon>();
            for(var entry:entries){String icon=entry.spec().text("minimap-icon","");if(!icon.isEmpty())icons.add(new MapIcon(icon,entry.x(),entry.y()));}
            for(String key:new TreeSet<>(p.stringPropertyNames()))if(key.startsWith("minimap.")&&key.endsWith(".icon")){
                String prefix=key.substring(0,key.length()-4);String icon=p.getProperty(key);
                icons.add(new MapIcon(icon,finite(Float.parseFloat(p.getProperty(prefix+"x"))),finite(Float.parseFloat(p.getProperty(prefix+"y")))));
            }
            if(icons.size()>2048)throw new IllegalArgumentException("Too many minimap icons");
            var templates=new TreeMap<String,Spec>();
            for(String id:p.getProperty("actor-templates","").split(",")){
                if(id.isBlank())continue;
                if(templates.size()>=128||!id.matches("spawn_[0-9]{1,3}"))throw new IllegalArgumentException("Invalid or oversized scene actor template table");
                var fields=new TreeMap<String,String>();String prefix="actor-template."+id+".";
                for(String key:p.stringPropertyNames())if(key.startsWith(prefix))fields.put(key.substring(prefix.length()),p.getProperty(key));
                if(templates.putIfAbsent(id,new Spec(fields))!=null)throw new IllegalArgumentException("Duplicate scene actor template");
            }
            var content=new SceneContent(entries,scales,SceneConversation.load(p),stats,items,cycles,statuses,List.copyOf(icons),templates);content.validateConversations();return content;
        }catch(IllegalArgumentException invalid){throw new IOException("Invalid prepared scene content",invalid);}
    }
    private static float finite(float f){if(!Float.isFinite(f)||Math.abs(f)>1_000_000)throw new IllegalArgumentException("Invalid content number");return f;}
}
