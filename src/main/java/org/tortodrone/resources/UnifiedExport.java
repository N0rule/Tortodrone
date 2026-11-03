package org.tortodrone.resources;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.tortodrone.world.*;
import org.tortodrone.config.ServerSettings;
import static org.tortodrone.resources.Yaml.*;

/** Explicit conversion of already validated legacy resources. No player state is read or written. */
final class UnifiedExport {
    private final List<String> report=new ArrayList<>();
    private final Path game;
    private final Map<String,Properties> bundles=new HashMap<>();
    public UnifiedExport(Path game){this.game=game;}
    public List<String> report(){return List.copyOf(report);}
    public void export(ContentRegistry registry,Path target,ServerSettings settings)throws IOException {
        ContentFiles.safe(target);if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("Candidate already exists: "+target);
        Files.createDirectories(target);
        Path content=target.resolve("content"),internal=content.resolve("_internal");Files.createDirectories(internal);
        // Keep machine fire/action graphs intact; YAML never pretends to replace engine behavior.
        for(String key:List.of("fires","pet-fires")) {
            var resource=registry.resources().get(key);
            if(resource!=null)Files.copy(resource.path(),internal.resolve(key+".json"));
            else if(key.equals("pet-fires")){FireCatalog.write(internal.resolve(key+".json"),List.of(),Map.of());}
        }
        exportItems(registry,content);
        var town=registry.resources().containsKey("town")?map(Json.parse(Files.readString(registry.resources().get("town").path())),"town"):new LinkedHashMap<String,Object>();
        var handled=new HashSet<String>(Set.of("format","depot"));
        if(town.containsKey("depot")) {
            // Legacy import artifact only; never loaded as a runtime catalog.
            Files.writeString(target.resolve("legacy-depot-import.json"),Json.stringify(Map.of("depot",town.get("depot")))+"\n",StandardOpenOption.CREATE_NEW);
            report.add("Legacy Depot preserved as legacy-depot-import.json; import explicitly with EconomyUpgrade, not YAML runtime configuration");
        }
        for(var section:TownYaml.SECTIONS.entrySet()) {var data=new LinkedHashMap<String,Object>();for(String key:section.getValue()){data.put(key,town.getOrDefault(key,List.of()));handled.add(key);}write(content.resolve("town/"+section.getKey()+".yml"),data);}
        if(!handled.containsAll(town.keySet()))throw new IOException("Unconverted Town sections: "+town.keySet().stream().filter(k->!handled.contains(k)).toList());
        var cycles=new LinkedHashMap<String,Object>();var statuses=new LinkedHashMap<String,Object>();var combat=new LinkedHashMap<String,Object>();
        var names=new HashMap<Integer,String>();var usedNames=new HashSet<String>();
        for(var e:registry.resources().entrySet())if(e.getKey().startsWith("scene.")) {
            int id=PackContent.sceneId(e.getKey());String name=e.getValue().path().getFileName().toString();if(!name.matches("[a-z][a-z0-9_]{0,63}"))name="scene_"+id;
            if(!usedNames.add(name))throw new IOException("Duplicate source map folder name requires review: "+name);names.put(id,name);
        }
        for(var entry:registry.resources().entrySet())if(entry.getKey().startsWith("scene."))exportMap(entry.getValue().path(),content.resolve("maps/"+names.get(PackContent.sceneId(entry.getKey()))),names,cycles,statuses,combat);
        write(content.resolve("shared/combat.yml"),Map.of("properties",group(combat)));
        write(content.resolve("shared/trap-cycles.yml"),Map.of("cycles",groupCycles(cycles)));write(content.resolve("shared/statuses.yml"),Map.of("properties",statuses));
        ContentPresentation.compact(content);
        var exportedItems=ItemYaml.load(content.resolve("items"));
        ItemReferences.exportTown(content.resolve("town"),exportedItems);
        ItemReferences.exportGear(content,exportedItems);
        var config=new LinkedHashMap<String,Object>();config.put("server-ip",settings.bind());config.put("server-port",settings.port());config.put("max-clients",settings.maxClients());config.put("log-mode",settings.logMode().name().toLowerCase(Locale.ROOT));config.put("log-max-mib",settings.logMaxMiB());config.put("log-retention",settings.logRetention());config.put("startup-map",names.get(registry.scenes().entry().id()));
        write(target.resolve("config/server.yml"),config);write(target.resolve("config/combat.yml"),Map.of("dodgeCooldownMs",registry.weapons().dodgeCooldown()));
        for(var resource:registry.resources().entrySet())report.add("Converted "+resource.getKey()+" from "+resource.getValue().pack()+" / "+resource.getValue().path()+" sha256="+resource.getValue().sha256());
        Files.writeString(target.resolve("migration-report.txt"),"Validated source snapshot; original files and player data remain unchanged.\n"+String.join("\n",report)+"\n",StandardOpenOption.CREATE_NEW);
    }
    private void exportItems(ContentRegistry registry,Path content)throws IOException {
        var names=new TreeMap<String,String>();
        for(var e:registry.items().entries()) {
            String name=registry.items().displayName(e);
            if(game!=null&&!e.nameKey().isBlank()) {String resolved=resolve("item",e.nameKey());if(!resolved.equals(e.nameKey())&&!resolved.contains("{")&&!resolved.contains("%")&&!resolved.isBlank())name=resolved;}
            names.put(e.config(),name);
        }
        ReadableItemExport.write(new ItemYaml.Definitions(registry.items().withDisplayNames(names),registry.weapons(),registry.equipment()),content);
    }
    public void exportMap(Path source,Path target,Map<Integer,String> names,Map<String,Object> cycles,Map<String,Object> statuses,Map<String,Object> combat)throws IOException {
        var supported=Set.of("scene.dat","walkability.gz","static-collision.properties","map.properties","content.properties","portals.properties","import-source-report.txt");
        try(var paths=Files.list(source)){for(Path file:paths.toList()){ContentFiles.regular(file);if(!supported.contains(file.getFileName().toString()))throw new IOException("Unsupported map file requires explicit migration review: "+file);}}
        var descriptor=ContentFiles.properties(source.resolve("map.properties"));Path nativeDir=target.resolve("native");Files.createDirectories(nativeDir);
        for(String name:List.of("scene.dat","walkability.gz","static-collision.properties"))if(Files.exists(source.resolve(name)))Files.copy(source.resolve(name),nativeDir.resolve(name));
        var original=Files.exists(source.resolve("content.properties"))?readProperties(source.resolve("content.properties")):new Properties();
        var bindings=new LinkedHashMap<String,Object>();var mechanics=new LinkedHashMap<String,Object>();var editableMechanics=new LinkedHashMap<String,Object>();
        var nativeActors=new LinkedHashMap<String,Object>();var actorFields=new LinkedHashMap<String,Object>();var actorTypes=new LinkedHashMap<String,Object>();var actors=new LinkedHashMap<String,Object>();
        for(String id:original.getProperty("entries","").split(","))if(!id.isBlank()) {
            var spec=section(original,"entry."+id+".");String key=slug(spec.get("config"))+"_"+slug(spec.get("source-key"))+"_"+spec.getOrDefault("handler-index","0");
            if(nativeActors.containsKey(key))throw new IOException("Ambiguous source actor binding: "+key);
            var fixed=new LinkedHashMap<String,Object>();var editable=new LinkedHashMap<String,Object>();
            for(var field:spec.entrySet()) {
                String name=field.getKey(),value=field.getValue();
                if(name.matches("(?:.*\\.)?(?:config|kind|native-type|source-[^.]+|handler-[^.]+)(?:\\..*)?")||value.startsWith("{")||value.startsWith("["))fixed.put(name,value);
                else if(name.equals("rotation"))editable.put("facing",Math.toDegrees(Float.parseFloat(value)));
                else editable.put(name,name.equals("cycle")?value.substring(value.lastIndexOf('|')+1):scalar(value));
            }
            nativeActors.put(key,fixed);actors.put(key,editable);actorFields.put(key,new ArrayList<>(editable.keySet()));var types=new LinkedHashMap<String,Object>();editable.forEach((k,v)->types.put(k,v instanceof Number?"number":v instanceof Boolean?"boolean":"text"));actorTypes.put(key,types);
        }
        for(String key:new TreeSet<>(original.stringPropertyNames())) {
            if(key.startsWith("entry.")||key.startsWith("dialogue.")||Set.of("entries","dialogues","format","layout-sha256").contains(key))continue;
            if(key.startsWith("cycle.")) {String name=key.substring(6);Object v=scalar(original.getProperty(key));if(cycles.containsKey(name)&&!same(cycles.get(name),v))editableMechanics.put(key,v);else cycles.putIfAbsent(name,v);}
            else if(key.startsWith("status.")||key.equals("statuses")){Object v=original.getProperty(key);if(statuses.containsKey(key)&&!same(statuses.get(key),v))editableMechanics.put(key,v);else statuses.putIfAbsent(key,v);}
            else if(key.startsWith("gear.")||key.startsWith("player.")||key.startsWith("scale.")||key.startsWith("status-power.")||key.startsWith("status-chance.")) {
                Object value=scalar(original.getProperty(key));
                // Sparse legacy bases can omit zero. Level tables must retain it:
                // zero is an explicit override and level-zero health marks a valid table.
                if(key.startsWith("gear.")&&!key.startsWith("gear.table.")&&!key.endsWith(".rarity")&&value instanceof Number n&&n.doubleValue()==0)continue;
                if(combat.containsKey(key)&&!same(combat.get(key),value))editableMechanics.put(key,value);else combat.putIfAbsent(key,value);
            }
            else if(key.equals("items")||original.getProperty(key).startsWith("{")||key.startsWith("item."))mechanics.put(key,original.getProperty(key));
            else editableMechanics.put(key,scalar(original.getProperty(key)));
        }
        bindings.put("actors",nativeActors);bindings.put("actorFields",actorFields);bindings.put("actorTypes",actorTypes);bindings.put("mechanics",mechanics);
        bindings.put("mechanicFields",new ArrayList<>(editableMechanics.keySet()));
        var shared=new ArrayList<String>();if(original.stringPropertyNames().stream().anyMatch(k->k.startsWith("cycle.")))shared.add("trap-cycles");if(original.containsKey("statuses"))shared.add("statuses");if(original.stringPropertyNames().stream().anyMatch(k->k.startsWith("gear.")||k.startsWith("player.")||k.startsWith("scale.")))shared.add("combat");bindings.put("shared",shared);
        write(target.resolve("actors.yml"),Map.of("actors",actors));if(!editableMechanics.isEmpty())write(target.resolve("mechanics.yml"),Map.of("properties",editableMechanics));
        var portals=Files.exists(source.resolve("portals.properties"))?ContentFiles.properties(source.resolve("portals.properties")):new Properties();
        var entrances=new LinkedHashMap<String,Object>();
        for(String tag:portals.getProperty("entrances","").split(","))if(!tag.isBlank()) {var raw=section(portals,"entrance."+tag+".");var v=new LinkedHashMap<String,Object>();for(var e:raw.entrySet())v.put(e.getKey().equals("rotation")?"facing":e.getKey(),e.getKey().equals("rotation")?Math.toDegrees(Float.parseFloat(e.getValue())):scalar(e.getValue()));entrances.put(tag,v);}
        String defaultSpawn=entrances.keySet().stream().filter(tag->{var e=mapUnchecked(entrances.get(tag));return Float.parseFloat(e.get("x").toString())==Float.parseFloat(descriptor.getProperty("spawn-x"))&&Float.parseFloat(e.get("y").toString())==Float.parseFloat(descriptor.getProperty("spawn-y"))&&Math.abs(Double.parseDouble(e.get("facing").toString())-Math.toDegrees(Float.parseFloat(descriptor.getProperty("spawn-rotation","0"))))<.00001;}).findFirst().orElse(null);
        if(defaultSpawn==null){defaultSpawn="default_spawn";if(entrances.containsKey(defaultSpawn))throw new IOException("Ambiguous default_spawn entrance");entrances.put(defaultSpawn,Map.of("x",Float.parseFloat(descriptor.getProperty("spawn-x")),"y",Float.parseFloat(descriptor.getProperty("spawn-y")),"facing",Math.toDegrees(Float.parseFloat(descriptor.getProperty("spawn-rotation","0")))));}
        var triggers=new LinkedHashMap<String,Object>();var geometry=new LinkedHashMap<String,Object>();
        for(String id:portals.getProperty("triggers","").split(","))if(!id.isBlank()){var raw=section(portals,"trigger."+id+".");int destination=Integer.parseInt(raw.remove("scene"));String entrance=raw.remove("entrance");triggers.put(id,Map.of("destination",names.containsKey(destination)?names.get(destination):destination,"entrance",entrance));geometry.put(id,raw);}
        bindings.put("portals",geometry);write(target.resolve("portals.yml"),Map.of("entrances",entrances,"portals",triggers));
        var npcs=new LinkedHashMap<String,Object>();for(String id:original.getProperty("dialogues","").split(","))if(!id.isBlank())npcs.put(id,exportNpc(original,id,target));bindings.put("npcs",npcs);
        Files.writeString(nativeDir.resolve("bindings.json"),Json.stringify(bindings),StandardOpenOption.CREATE_NEW);
        var metadata=new Properties();for(String key:List.of("scene-id","zone-id","zone-name","name","image","kind","version"))metadata.setProperty(key,descriptor.getProperty(key));
        for(String name:List.of("scene.dat","walkability.gz","static-collision.properties","bindings.json"))if(Files.exists(nativeDir.resolve(name)))metadata.setProperty("sha256."+name,ContentFiles.digest(nativeDir.resolve(name)));
        store(nativeDir.resolve("metadata.properties"),metadata);
        var map=new LinkedHashMap<String,Object>();map.put("id",Integer.parseInt(descriptor.getProperty("scene-id")));map.put("name",readable(descriptor.getProperty("name")));map.put("type",descriptor.getProperty("kind"));map.put("zone",names.getOrDefault(Integer.parseInt(descriptor.getProperty("zone-id")),descriptor.getProperty("zone-id")));map.put("defaultSpawn",defaultSpawn);map.put("instances",Integer.parseInt(descriptor.getProperty("instances")));write(target.resolve("map.yml"),map);
    }
    private Map<String,Object> exportNpc(Properties original,String id,Path target)throws IOException {
        String prefix="dialogue."+id+".";var properties=section(original,prefix);String bundle=properties.get("bundle");if(bundle.equals("npc"))bundle="conversation";
        var fixed=new LinkedHashMap<String,Object>();for(String key:List.of("actor","bundle","name","model","source-close-animation","music","initial-action"))if(properties.containsKey(key))fixed.put(key,properties.get(key));fixed.put("bundle",bundle);
        String speaker=properties.get("name");
        if(game!=null&&speaker!=null&&!speaker.startsWith("~")&&!speaker.startsWith("%")&&!bundle(bundle).containsKey(speaker)){
            String found=null;for(String candidate:List.of("shop","conversation","mission"))if(bundle(candidate).containsKey(speaker)){if(found!=null){found=null;break;}found=candidate;}
            if(found!=null)fixed.put("name","%"+found+":"+speaker);
        }
        var nodes=new LinkedHashMap<String,Object>();var nativeNodes=new LinkedHashMap<String,Object>();
        for(String index:properties.get("nodes").split(",")) {
            String np="node."+index+".";var node=section(original,prefix+np);String name=node.get("name"),key=node.get("message"),text=resolve(bundle,key);
            var effects=new LinkedHashMap<String,Object>();for(String field:List.of("animation","transient","interface"))if(node.containsKey(field))effects.put(field,node.get(field));
            var nr=new LinkedHashMap<String,Object>();nr.put("properties",effects);nr.put("key",key);nr.put("defaultText",text);
            var responses=new ArrayList<Object>();var nativeResponses=new ArrayList<Object>();
            for(int i=0;i<Integer.parseInt(node.get("responses"));i++) {String rp="response."+i+".";String rk=node.get(rp+"text"),rt=resolve(bundle,rk);var r=new LinkedHashMap<String,Object>();r.put("text",rt);String next=node.getOrDefault(rp+"destination","");if(next.isEmpty())r.put("close",true);else r.put("next",next);var action=new LinkedHashMap<String,Object>();for(String field:List.of("station","command","action"))if(!node.getOrDefault(rp+field,"").isEmpty())action.put(field,node.get(rp+field));if(!action.isEmpty())r.put("action",i);responses.add(r);nativeResponses.add(Map.of("key",rk,"defaultText",rt,"properties",action));}
            nr.put("responses",nativeResponses);nativeNodes.put(name,nr);nodes.put(name,Map.of("text",text,"responses",responses));
        }
        String file=id;
        if(game!=null&&fixed.get("name") instanceof String nameKey&&!nameKey.equals("~")) {
            String name=resolve(bundle,nameKey);if(!name.equals(nameKey)&&!name.isBlank()&&!name.contains("{")){file=slug(name);if(Files.exists(target.resolve("npcs/"+file+".yml")))file+="_"+slug(id);}
        }
        write(target.resolve("npcs/"+file+".yml"),Map.of("id",id,"dialogue",Map.of("initial",properties.get("initial"),"nodes",nodes)));
        return Map.of("properties",fixed,"nodes",nativeNodes);
    }
    private static boolean same(Object a,Object b) {
        if(Objects.equals(a,b))return true;
        try{return new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString()))==0;}catch(NumberFormatException invalid){return a.toString().equals(b.toString());}
    }
    static Map<String,Object> groupCycles(Map<String,Object> flat)throws IOException {
        var result=new LinkedHashMap<String,Object>();for(var e:flat.entrySet()){int dot=e.getKey().indexOf('.');String root=e.getKey().substring(0,dot);var fields=map(result.computeIfAbsent(root,k->new LinkedHashMap<>()),root);fields.put(e.getKey().substring(dot+1),e.getValue());}return result;
    }
    static Map<String,Object> group(Map<String,Object> flat)throws IOException {
        var result=new LinkedHashMap<String,Object>();
        for(var e:flat.entrySet()) {
            int dot=e.getKey().indexOf('.');String root=e.getKey().substring(0,dot),rest=e.getKey().substring(dot+1);var fields=map(result.computeIfAbsent(root,k->new LinkedHashMap<>()),root);
            if(root.equals("gear")) {
                int split=rest.indexOf(".levels.");if(split<0)split=rest.indexOf(".defense.");if(split<0)split=rest.indexOf(".status-resist.");if(split<0)split=rest.indexOf(".tagged.");if(split<0)split=rest.lastIndexOf(".health-bonus");
                if(split<0&&rest.endsWith(".table"))split=rest.length()-6;
                if(split<0&&rest.endsWith(".rarity"))split=rest.length()-7;
                if(split<0)throw new IOException("Unknown gear stat: "+e.getKey());String config=rest.substring(0,split),field=rest.substring(split+1);var gear=map(fields.computeIfAbsent(config,k->new LinkedHashMap<>()),config);
                int sub=field.indexOf('.');if(sub<0)gear.put(field,e.getValue());else map(gear.computeIfAbsent(field.substring(0,sub),k->new LinkedHashMap<>()),field).put(field.substring(sub+1),e.getValue());
            }else fields.put(rest,e.getValue());
        }
        return result;
    }
    private Properties bundle(String bundle)throws IOException {
        Properties strings=bundles.get(bundle);
        if(strings==null){strings=new Properties();if(game!=null){ContentFiles.regular(game.resolve("code/projectx-config.jar"));try(var zip=new ZipFile(game.resolve("code/projectx-config.jar").toFile())){var entry=zip.getEntry("rsrc/i18n/"+bundle+".properties");if(entry!=null)try(var in=zip.getInputStream(entry)){strings.load(in);}}}bundles.put(bundle,strings);}
        return strings;
    }
    private String resolve(String bundle,String key)throws IOException {
        if(key.startsWith("~"))return key.substring(1);
        if(key.startsWith("%")&&key.contains(":")){int colon=key.indexOf(':');return resolve(key.substring(1,colon),key.substring(colon+1));}
        Properties strings=bundle(bundle);
        String result=strings.getProperty(key);
        if(result==null&&game!=null&&bundle.equals("npc")) {
            try(var zip=new ZipFile(game.resolve("code/projectx-config.jar").toFile())) {
                var entry=zip.getEntry("rsrc/i18n/conversation.properties");if(entry!=null){var fallback=new Properties();try(var in=zip.getInputStream(entry)){fallback.load(in);}result=fallback.getProperty(key);}
            }
        }
        if(result==null){report.add("Unresolved localization rsrc/i18n/"+bundle+".properties : "+key+"; original client key retained");return key;}return result;
    }
    private String readable(String value)throws IOException {if(value.startsWith("%")&&value.contains(":")){int colon=value.indexOf(':');return resolve(value.substring(1,colon),value.substring(colon+1));}return value;}
    private static String slug(String value){String s=value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","_").replaceAll("^_|_$","");return s.isEmpty()?"actor":s;}
    private static Object scalar(String value){if(value.equals("true")||value.equals("false"))return Boolean.parseBoolean(value);try{return new java.math.BigDecimal(value);}catch(NumberFormatException bad){return value;}}
    private static Map<String,String> section(Properties p,String prefix){var out=new LinkedHashMap<String,String>();for(String key:new TreeSet<>(p.stringPropertyNames()))if(key.startsWith(prefix))out.put(key.substring(prefix.length()),p.getProperty(key));return out;}
    @SuppressWarnings("unchecked")private static Map<String,Object> mapUnchecked(Object v){return (Map<String,Object>)v;}
    private static Properties readProperties(Path file)throws IOException {ContentFiles.regular(file);int limit=file.getFileName().toString().equals("content.properties")?org.tortodrone.world.SceneContent.MAX_BYTES:4_000_000;if(Files.size(file)>limit)throw new IOException("Scene content exceeds size limit");var p=new Properties();try(var in=Files.newInputStream(file)){p.load(in);}return p;}
    public static void store(Path file,Properties values)throws IOException {Files.createDirectories(file.getParent());var out=new ByteArrayOutputStream();values.store(out,null);var lines=out.toString(java.nio.charset.StandardCharsets.ISO_8859_1).lines().filter(line->!line.startsWith("#")).sorted().toList();Files.writeString(file,String.join("\n",lines)+"\n",java.nio.charset.StandardCharsets.ISO_8859_1,StandardOpenOption.CREATE_NEW);}
}
