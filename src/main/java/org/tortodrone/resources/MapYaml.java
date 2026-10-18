package org.tortodrone.resources;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.world.*;
import static org.tortodrone.resources.Yaml.*;

/** Map-local editable intent combined with pinned native layout and source bindings. */
public final class MapYaml {
    private MapYaml() { }
    public record Prepared(Properties descriptor,Properties portals,Properties content,Path nativeDirectory) { }
    public static Map<Integer,Path> folders(Path maps)throws IOException {
        ContentFiles.safe(maps);if(!Files.isDirectory(maps))throw new IOException("Missing maps: "+maps);
        var result=new TreeMap<Integer,Path>();
        try(var paths=Files.list(maps)) {
            var list=paths.sorted().toList();if(list.size()>64)throw new IOException("Too many map folders");
            for(Path folder:list) {
                ContentFiles.safe(folder);if(!Files.isDirectory(folder)||!folder.getFileName().toString().matches("[a-z][a-z0-9_]{0,63}"))throw new IOException("Invalid map folder: "+folder);
                int id=integer(read(folder.resolve("map.yml")).get("id"),folder+": id");
                if(id<=0||result.putIfAbsent(id,folder)!=null)throw new IOException("Duplicate or invalid map ID: "+id);
            }
        }
        return Collections.unmodifiableMap(result);
    }
    public static Prepared prepare(Path folder,Path shared)throws IOException {return prepare(folder,shared,null);}
    public static Prepared prepare(Path folder,Path shared,org.tortodrone.character.ItemCatalog items)throws IOException {
        try{return prepareChecked(folder,shared,items);}catch(IOException|IllegalArgumentException invalid){throw new IOException(folder+": "+invalid.getMessage(),invalid);}
    }
    private static Prepared prepareChecked(Path folder,Path shared,org.tortodrone.character.ItemCatalog items)throws IOException {
        Path nativeDir=folder.resolve("native");
        var nativeMetadata=ContentFiles.properties(nativeDir.resolve("metadata.properties"));
        for(String key:nativeMetadata.stringPropertyNames())if(key.startsWith("sha256.")) {
            Path file=ContentFiles.relative(nativeDir,key.substring(7));
            if(!ContentFiles.digest(file).equals(nativeMetadata.getProperty(key)))throw new IOException(file+": native compatibility fingerprint mismatch; reimport this map");
        }
        for(String name:List.of("scene.dat","walkability.gz","bindings.json"))if(!nativeMetadata.containsKey("sha256."+name))throw new IOException(nativeDir+": missing native fingerprint "+name);
        for(String key:List.of("scene-id","zone-id","zone-name","name","image","kind","version")) {
            String value=nativeMetadata.getProperty(key);if(value==null||value.isBlank()||value.length()>256)throw new IOException("Missing or invalid native map identity: "+key);
        }
        if(Integer.parseInt(nativeMetadata.getProperty("zone-id"))<=0)throw new IOException("Native zone-id must be positive");
        if(Files.exists(nativeDir.resolve("static-collision.properties"))&&!nativeMetadata.containsKey("sha256.static-collision.properties"))throw new IOException("Static collision requires an internal native fingerprint");
        var bindings=map(Json.parseCatalog(Files.readString(nativeDir.resolve("bindings.json"))),"bindings");
        var p=new Properties();for(String key:nativeMetadata.stringPropertyNames())if(!key.startsWith("sha256."))p.setProperty(key,nativeMetadata.getProperty(key));
        var map=read(folder.resolve("map.yml"));fields(map,folder+" map","id","name","type","zone","defaultSpawn","instances");
        if(integer(map.get("id"),"id")!=Integer.parseInt(p.getProperty("scene-id")))throw new IOException(folder+": map ID differs from native scene; reimport required");
        String kind=text(map.get("type"),"type");if(!Set.of("town","training","level").contains(kind))throw new IOException("Unsupported native scene kind: "+kind);
        if(!kind.equals(p.getProperty("kind")))throw new IOException(folder+": native scene type cannot be changed");
        text(map.get("name"),"name");text(map.get("zone"),"zone");
        p.setProperty("instances",String.valueOf(integer(map.getOrDefault("instances",1),"instances")));
        p.setProperty("scene-sha256",nativeMetadata.getProperty("sha256.scene.dat"));p.setProperty("collision-sha256",nativeMetadata.getProperty("sha256.walkability.gz"));
        if(nativeMetadata.containsKey("sha256.static-collision.properties"))p.setProperty("static-collision-sha256",nativeMetadata.getProperty("sha256.static-collision.properties"));
        String spawn=text(map.get("defaultSpawn"),"defaultSpawn");
        var portals=portals(folder,bindings,p.getProperty("scene-sha256"),spawn);
        String prefix="entrance."+spawn+".";
        if(!portals.containsKey(prefix+"x")||!portals.containsKey(prefix+"y"))throw new IOException(folder+": defaultSpawn entrance is missing: "+spawn);
        for(String axis:List.of("x","y"))p.setProperty("spawn-"+axis,portals.getProperty(prefix+axis));p.setProperty("spawn-rotation",portals.getProperty(prefix+"rotation"));
        var content=new Properties();content.setProperty("format","1");content.setProperty("layout-sha256",p.getProperty("scene-sha256"));
        properties(content,"",map(bindings.get("mechanics"),"native mechanics"));
        var actors=read(folder.resolve("actors.yml"));fields(actors,folder+" actors","actors");
        var nativeActors=map(bindings.get("actors"),"native actors");var editable=map(actors.get("actors"),"actors");
        var defaults=map(bindings.getOrDefault("actorDefaults",Map.of()),"native actor defaults");
        if(!defaults.isEmpty()&&!defaults.keySet().equals(nativeActors.keySet()))throw new IOException("Native actor defaults are incomplete");
        var aliases=map(bindings.getOrDefault("actorAliases",Map.of()),"actor aliases");
        var aliasTargets=new HashSet<String>();
        for(var alias:aliases.entrySet())if(!alias.getKey().matches("[a-z][a-z0-9_]*")||nativeActors.containsKey(alias.getKey())||!nativeActors.containsKey(text(alias.getValue(),"alias target"))||!aliasTargets.add(text(alias.getValue(),"alias target")))throw new IOException("Invalid or colliding actor alias: "+alias.getKey());
        var overrides=new LinkedHashMap<String,Map<String,Object>>();
        for(var edit:editable.entrySet()) {
            String id=aliases.containsKey(edit.getKey())?text(aliases.get(edit.getKey()),"actor binding"):edit.getKey();
            if(!nativeActors.containsKey(id)||overrides.putIfAbsent(id,map(edit.getValue(),"actor override"))!=null)throw new IOException("Unknown or duplicate actor binding: "+edit.getKey());
        }
        if(defaults.isEmpty()&&!overrides.keySet().equals(nativeActors.keySet()))throw new IOException(folder+": legacy actor defaults missing; retain all actors or prepare an updated candidate");
        var ids=new ArrayList<String>();
        List<?> order=list(bindings.getOrDefault("actorOrder",new ArrayList<>(overrides.isEmpty()?nativeActors.keySet():overrides.keySet())),"native actor order");
        if(order.size()!=nativeActors.size()||!new HashSet<>(order).equals(nativeActors.keySet()))throw new IOException("Native actor order is incomplete or duplicated");
        for(Object rawId:order) {
            String id=text(rawId,"native actor ID");ids.add(id);var fixed=map(nativeActors.get(id),"native actor "+id);
            var values=new LinkedHashMap<>(map(defaults.getOrDefault(id,Map.of()),"actor defaults "+id));
            var edits=new LinkedHashMap<>(overrides.getOrDefault(id,Map.of()));
            // Only proven engine controls receive semantic names. Technical overrides remain advanced.
            semantic(edits,"position",Map.of("x","x","y","y"));
            semantic(edits,"monster",Map.of("health",values.containsKey("child.health")?"child.health":"health","attackIntervalMs",values.containsKey("child.attack-interval")?"child.attack-interval":"attack-interval"));
            values.putAll(edits);
            var allowed=map(bindings.get("actorFields"),"actor fields");var names=list(allowed.get(id),"actor fields "+id);
            for(String field:values.keySet())if(!names.contains(field))throw new IOException(folder+": unknown or immutable actor field "+id+"."+field);
            var types=map(map(bindings.get("actorTypes"),"actor types").get(id),"actor types "+id);
            for(var value:values.entrySet()) {
                String field=value.getKey(),type=text(types.get(field),"actor field type");
                if(type.equals("number")){float n=decimal(value.getValue(),folder+" actor "+id+"."+field);if((field.equals("health")||field.equals("child.health"))&&n<0)throw new IOException("Actor health must be nonnegative: "+id);}
                else if(type.equals("boolean"))flag(value.getValue(),field);else text(value.getValue(),field);
            }
            properties(content,"entry."+id+".",fixed);properties(content,"entry."+id+".",values);
            if(values.containsKey("facing")){content.remove("entry."+id+".facing");content.setProperty("entry."+id+".rotation",radians(values.get("facing")));}
            // Retain exact authored radians for inherited/unchanged headings.
            if(fixed.containsKey("rotation")&&!edits.containsKey("facing"))content.setProperty("entry."+id+".rotation",text(fixed.get("rotation"),"native rotation"));
        }
        content.setProperty("entries",String.join(",",ids));
        for(Object sharedSection:list(bindings.get("shared"),"shared definitions")) {
            String section=text(sharedSection,"shared section");
            if(!Set.of("trap-cycles","statuses","combat").contains(section))throw new IOException("Unknown shared section: "+section);
            var data=read(shared.resolve(section+".yml"));fields(data,section,section.equals("trap-cycles")?"cycles":"properties");
            var sharedValues=map(data.values().iterator().next(),section);
            Path statusDefaults=shared.getParent().resolve("_internal/statuses.json");
            if(section.equals("statuses")&&sharedValues.isEmpty()&&!Files.isRegularFile(statusDefaults))throw new IOException("Missing native status defaults: "+statusDefaults);
            if(section.equals("statuses")&&Files.exists(statusDefaults)) {
                var nativeValues=map(Json.parseCatalog(Files.readString(statusDefaults)),"native status defaults");
                var effective=new LinkedHashMap<>(nativeValues);
                for(var override:sharedValues.entrySet()) {
                    if(!(nativeValues.get(override.getKey()) instanceof Number))throw new IOException("Unknown or immutable status field: "+override.getKey());
                    decimal(override.getValue(),"advanced status override");effective.put(override.getKey(),override.getValue());
                }
                sharedValues=effective;
            }
            if(section.equals("combat")&&sharedValues.containsKey("gear")) {
                var gear=map(sharedValues.get("gear"),"gear");
                if(gear.keySet().stream().anyMatch(k->!k.contains("/")))sharedValues.put("gear",ItemReferences.gear(gear,items==null?ItemYaml.load(shared.getParent().resolve("items")):items,false,shared+" → gear"));
            }
            properties(content,section.equals("trap-cycles")?"cycle.":"",sharedValues);
        }
        var mechanics=Files.exists(folder.resolve("mechanics.yml"))?read(folder.resolve("mechanics.yml")):Map.<String,Object>of("properties",Map.of());fields(mechanics,folder+" mechanics","properties");
        var local=map(mechanics.get("properties"),"mechanics");var allowedMechanics=list(bindings.get("mechanicFields"),"mechanic fields");
        for(String field:local.keySet())if(!allowedMechanics.contains(field))throw new IOException(folder+": unknown mechanic field "+field);
        properties(content,"",local);
        var npcBindings=map(bindings.get("npcs"),"native NPCs");var seen=new HashSet<String>();
        Path npcDirectory=folder.resolve("npcs");ContentFiles.safe(npcDirectory);
        if(Files.exists(npcDirectory))try(var files=Files.list(npcDirectory)) {
            var paths=files.sorted().toList();if(paths.size()>256)throw new IOException("Too many NPC files: "+folder);
            for(Path file:paths) {
                if(!file.getFileName().toString().endsWith(".yml"))throw new IOException("Unexpected NPC file: "+file);
                var npc=read(file);fields(npc,file.toString(),"id","dialogue");String id=text(npc.get("id"),file+" id");
                if(!seen.add(id)||!npcBindings.containsKey(id))throw new IOException(file+": duplicate or unknown NPC binding "+id);
                npc(content,id,npc,map(npcBindings.get(id),file+" native binding"));
            }
        }
        if(!seen.equals(npcBindings.keySet()))throw new IOException(folder+": missing NPC definitions: "+npcBindings.keySet());
        content.setProperty("dialogues",String.join(",",new TreeSet<>(seen)));
        return new Prepared(p,portals,content,nativeDir);
    }
    private static void semantic(Map<String,Object> edits,String group,Map<String,String> fields)throws IOException {
        if(!edits.containsKey(group))return;var values=map(edits.remove(group),"actor "+group);
        for(var value:values.entrySet()) {
            String target=fields.get(value.getKey());if(target==null||edits.putIfAbsent(target,value.getValue())!=null)throw new IOException("Unknown or duplicate actor field "+group+"."+value.getKey());
        }
    }
    private static Properties portals(Path folder,Map<String,Object> bindings,String digest,String defaultSpawn)throws IOException {
        var root=read(folder.resolve("portals.yml"));fields(root,folder+" portals","entrances","portals");
        var p=new Properties();p.setProperty("format","2");p.setProperty("layout-sha256",digest);
        var entrances=map(root.get("entrances"),"entrances");var arrivals=new ArrayList<String>();
        for(var entry:entrances.entrySet()) {
            var v=map(entry.getValue(),"entrance");fields(v,"entrance "+entry.getKey(),"id","x","y","facing");
            if(v.containsKey("id"))arrivals.add(entry.getKey());else if(!entry.getKey().equals(defaultSpawn))throw new IOException("Named travel entrance requires a verified native ID: "+entry.getKey());
            properties(p,"entrance."+entry.getKey()+".",v);p.remove("entrance."+entry.getKey()+".facing");String rotation=radians(v.getOrDefault("facing",0));
            var nativeAngles=map(bindings.getOrDefault("entranceAngles",Map.of()),"native entrance angles");
            if(nativeAngles.containsKey(entry.getKey())) {
                var angle=map(nativeAngles.get(entry.getKey()),"entrance angle");
                if(decimal(v.getOrDefault("facing",0),"facing")==decimal(angle.get("displayDegrees"),"display degrees"))rotation=text(angle.get("radians"),"native radians");
            }
            p.setProperty("entrance."+entry.getKey()+".rotation",rotation);
        }
        p.setProperty("entrances",String.join(",",arrivals));
        var portals=map(root.get("portals"),"portals");var nativeIds=new ArrayList<String>();
        var aliases=map(bindings.getOrDefault("portalAliases",Map.of()),"portal aliases");
        var geometries=map(bindings.get("portals"),"native portals");
        for(var entry:portals.entrySet()) {
            String id=text(aliases.getOrDefault(entry.getKey(),entry.getKey()),"native portal ID"),prefix="trigger."+id+".";
            if(nativeIds.contains(id))throw new IOException("Duplicate native portal trigger: "+id);nativeIds.add(id);var v=map(entry.getValue(),"portal "+id);fields(v,"portal "+id,"destination","entrance");
            if(!geometries.containsKey(id))throw new IOException(folder+": portal has no verified native trigger: "+id);
            properties(p,prefix,map(geometries.get(id),"portal geometry"));
            Object destination=v.get("destination");int scene;
            if(destination instanceof Number)scene=integer(destination,"destination");
            else {String name=text(destination,"destination");if(!name.matches("[a-z][a-z0-9_]{0,63}"))throw new IOException("Invalid destination map name: "+name);
                scene=integer(read(folder.getParent().resolve(name).resolve("map.yml")).get("id"),"destination ID");}
            p.setProperty(prefix+"scene",Integer.toString(scene));p.setProperty(prefix+"entrance",text(v.get("entrance"),"entrance"));
        }
        p.setProperty("triggers",String.join(",",nativeIds));
        return p;
    }
    private static void npc(Properties p,String id,Map<String,Object> yaml,Map<String,Object> nativeNpc)throws IOException {
        String prefix="dialogue."+id+".";properties(p,prefix,map(nativeNpc.get("properties"),"NPC properties"));
        var dialogue=map(yaml.get("dialogue"),"dialogue "+id);fields(dialogue,"dialogue "+id,"initial","nodes");p.setProperty(prefix+"initial",text(dialogue.get("initial"),"initial"));
        var nodes=map(dialogue.get("nodes"),"nodes");var nativeNodes=map(nativeNpc.get("nodes"),"native nodes");var ids=new ArrayList<String>();int index=0;
        for(var entry:nodes.entrySet()) {
            String nodeId=entry.getKey(),np=prefix+"node."+index+".";ids.add(Integer.toString(index++));var node=map(entry.getValue(),"node "+nodeId);fields(node,"node "+nodeId,"text","responses");
            var nativeNode=map(nativeNodes.getOrDefault(nodeId,Map.of("properties",Map.of(),"responses",List.of())),"native node");
            properties(p,np,map(nativeNode.get("properties"),"node effects"));p.setProperty(np+"name",nodeId);
            p.setProperty(np+"message",line(node.get("text"),nativeNode,nativeNpc));
            var responses=list(node.getOrDefault("responses",List.of()),"responses");var nativeResponses=list(nativeNode.get("responses"),"native responses");p.setProperty(np+"responses",Integer.toString(responses.size()));
            for(int i=0;i<responses.size();i++) {
                var response=map(responses.get(i),"response");fields(response,"response","text","next","close","action");var nr=i<nativeResponses.size()?map(nativeResponses.get(i),"native response"):Map.<String,Object>of();
                String rp=np+"response."+i+".";p.setProperty(rp+"text",line(response.get("text"),nr,nativeNpc));
                boolean close=Boolean.TRUE.equals(response.get("close"));if(response.containsKey("close"))flag(response.get("close"),"close");
                if(close&&response.containsKey("next"))throw new IOException("Response cannot both close and jump");
                p.setProperty(rp+"destination",close?"":text(response.getOrDefault("next",""),"next"));
                if(response.containsKey("action")) {
                    int action=integer(response.get("action"),"action");if(action<0||action>=nativeResponses.size())throw new IOException("Unknown native response action");
                    var source=map(nativeResponses.get(action),"native action");properties(p,rp,map(source.getOrDefault("properties",Map.of()),"action properties"));
                } else if(nr.containsKey("properties")&&!map(nr.get("properties"),"native action").isEmpty())throw new IOException("NPC service action removed; retain action reference for "+id+" / "+nodeId);
            }
        }
        p.setProperty(prefix+"nodes",String.join(",",ids));
    }
    private static String line(Object value,Map<String,Object> original,Map<String,Object> nativeNpc)throws IOException {
        String text=text(value,"dialogue text");if(text.isBlank())throw new IOException("Dialogue text must not be empty");
        if(text.equals(original.get("defaultText")))return text(original.get("key"),"localization key");
        // The installed Conversation window calls MessageBundle.get(key, knightName)
        // for messages AND responses. That overload bypasses '~' literal decoding.
        for(Object raw:map(nativeNpc.get("nodes"),"native nodes").values()) {
            var node=map(raw,"node");if(text.equals(node.get("defaultText")))return text(node.get("key"),"localization key");
            for(Object r:list(node.get("responses"),"responses")){var response=map(r,"response");if(text.equals(response.get("defaultText")))return text(response.get("key"),"localization key");}
        }
        throw new IOException("Unmodified stock ConversationDialog formats messages and responses as localization keys with the knight name; arbitrary literal dialogue text is unsupported. Choose verified text already present in this NPC's native localization bindings.");
    }
    private static String radians(Object degrees)throws IOException {float d=decimal(degrees,"facing (degrees)");if(d< -180||d>180)throw new IOException("Facing must be from -180 to 180 degrees");return Float.toString((float)Math.toRadians(d));}
    public static void properties(Properties p,String prefix,Map<String,Object> values)throws IOException {
        for(var e:values.entrySet()) {
            if(e.getValue() instanceof Map<?,?>)properties(p,prefix+e.getKey()+".",map(e.getValue(),prefix));
            else {Object value=e.getValue();if(!(value instanceof String||value instanceof Number||value instanceof Boolean))throw new IOException("Expected scalar property: "+prefix+e.getKey());p.setProperty(prefix+e.getKey(),value instanceof Number?new java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString():value.toString());}
        }
    }
}
