package org.tortodrone.resources;

import java.nio.file.*;
import java.util.*;
import java.security.*;
final class ContentPresentation {
 static String digest(Path p)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
 static void yaml(Path p,Map<String,Object> m)throws Exception{Files.deleteIfExists(p);Yaml.write(p,m);}
 static String slug(String s){return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","_").replaceAll("^_|_$","");}
 @SuppressWarnings("unchecked") static Map<String,Object> obj(Object o){return (Map<String,Object>)o;}
 static void compactPortals(Path folder,Map<String,Object> bindings,Properties before)throws Exception {
    Path file=folder.resolve("portals.yml");var root=Yaml.read(file);var angles=new TreeMap<String,Object>();
    for(var entry:obj(root.get("entrances")).entrySet()) {
        var row=obj(entry.getValue());double degrees=((Number)row.getOrDefault("facing",0)).doubleValue();
        double display=Math.round(degrees*10000d)/10000d;
        angles.put(entry.getKey(),Map.of("radians",before.getProperty("entrance."+entry.getKey()+".rotation"),"displayDegrees",display));row.put("facing",display);
    }
    var aliases=new TreeMap<String,Object>();var portals=new LinkedHashMap<String,Object>();var counters=new HashMap<String,Integer>();
    for(var entry:obj(root.get("portals")).entrySet()) {
        var row=obj(entry.getValue());String stem="to_"+slug(row.get("destination").toString());String alias=stem+"_"+counters.merge(stem,1,Integer::sum);
        if(aliases.putIfAbsent(alias,entry.getKey())!=null)throw new java.io.IOException("Portal alias collision");portals.put(alias,row);
    }
    bindings.put("entranceAngles",angles);bindings.put("portalAliases",aliases);root.put("portals",portals);yaml(file,root);
 }
 static void compact(Path content)throws java.io.IOException {
  try {
  for(var e:MapYaml.folders(content.resolve("maps")).entrySet()) {
   Path folder=e.getValue(),file=folder.resolve("native/bindings.json");var before=MapYaml.prepare(folder,content.resolve("shared"));
   var b=obj(Json.parseCatalog(Files.readString(file)));var actors=Yaml.map(Yaml.read(folder.resolve("actors.yml")).get("actors"),"actors");b.put("actorDefaults",actors);b.put("actorOrder",new ArrayList<>(actors.keySet()));
   var aliases=new TreeMap<String,Object>();var counts=new HashMap<String,Integer>();
   for(String id:actors.keySet()) {
    var fixed=obj(obj(b.get("actors")).get(id));String config=fixed.get("config").toString();String name=slug(config.substring(config.lastIndexOf('/')+1));if(name.isEmpty()||!Character.isLetter(name.charAt(0)))name="actor_"+name;
    String alias=name+"_"+counts.merge(name,1,Integer::sum);if(aliases.putIfAbsent(alias,id)!=null)throw new Exception("alias collision");
    String rotation=before.content().getProperty("entry."+id+".rotation");if(rotation!=null)fixed.put("rotation",rotation);
   }
   b.put("actorAliases",aliases);
   compactPortals(folder,b,before.portals());
   Path mechanics=folder.resolve("mechanics.yml");if(Files.exists(mechanics)) {
    var m=Yaml.read(mechanics);var properties=obj(m.get("properties"));var iterator=properties.entrySet().iterator();while(iterator.hasNext()){var property=iterator.next();if(property.getKey().startsWith("destination.")){obj(b.get("mechanics")).put(property.getKey(),before.content().getProperty(property.getKey()));((List<?>)b.get("mechanicFields")).remove(property.getKey());iterator.remove();}}
    yaml(mechanics,m);
   }
   Files.writeString(file,Json.stringify(b)+"\n");var props=new Properties();props.putAll(ContentFiles.properties(folder.resolve("native/metadata.properties")));props.setProperty("sha256.bindings.json",digest(file));Files.delete(folder.resolve("native/metadata.properties"));UnifiedExport.store(folder.resolve("native/metadata.properties"),props);
   String example=aliases.entrySet().stream().filter(a->obj(actors.get(a.getValue())).containsKey("child.health")).map(a->a.getKey()).findFirst().orElse(aliases.isEmpty()?"existing_actor":aliases.keySet().iterator().next());
   Files.writeString(folder.resolve("actors.yml"),"# Overrides only. Native actor identities/defaults: native/bindings.json (actorAliases).\n# Example existing actor alias: "+example+"\n# Position uses game units; facing uses degrees; attackIntervalMs uses milliseconds.\nactors: {}\n");
  }
  var nativeTown=new TreeMap<String,Object>();
  for(String name:List.of("shops","recipes","stations","progression","variants")) {
   Path file=content.resolve("town/"+name+".yml");var root=Yaml.read(file);
   if(name.equals("progression"))nativeTown.put("items",root.remove("items"));
   if(name.equals("variants"))nativeTown.put("variantTables",root.remove("variantTables"));
   if(name.equals("shops"))for(Object raw:(List<?>)root.get("catalogs")) {
    var r=obj(raw);for(var d:Map.<String,Object>of("chance",0,"min",1,"max",1,"kind","Original").entrySet())if(d.getValue().equals(r.get(d.getKey())))r.remove(d.getKey());
    if(List.of("").equals(r.get("includes"))||List.of().equals(r.get("includes")))r.remove("includes");
    if(List.of().equals(r.get("goods")))r.remove("goods");
    for(Object good:(List<?>)r.getOrDefault("goods",List.of()))for(var d:Map.<String,Object>of("count",1,"energy",0,"limit",0,"price",-1,"token","","tokenCount",0).entrySet())if(d.getValue().equals(obj(good).get(d.getKey())))obj(good).remove(d.getKey());
   }
   if(name.equals("recipes"))for(Object raw:(List<?>)root.get("recipes"))for(var d:Map.<String,Object>of("count",1,"locked",false,"oneTime",false,"maxHours",0,"variantTable","").entrySet())if(d.getValue().equals(obj(raw).get(d.getKey())))obj(raw).remove(d.getKey());
   if(name.equals("stations"))for(Object raw:(List<?>)root.get("stations")) {var r=obj(raw);for(var d:Map.<String,Object>of("level",10,"preparer","BASIC","cycle","CONSTANT","perKnight",false,"recipes",List.of()).entrySet())if(d.getValue().equals(r.get(d.getKey())))r.remove(d.getKey());for(String field:List.of("catalog","type","group","name","title","model","description"))if("".equals(r.get(field)))r.remove(field);}
   yaml(file,root);
  }
  Files.writeString(content.resolve("_internal/town-definitions.json"),Json.stringify(nativeTown)+"\n");
  Path statuses=content.resolve("shared/statuses.yml");
  Files.writeString(content.resolve("_internal/statuses.json"),Json.stringify(Yaml.read(statuses).get("properties"))+"\n");
  yaml(statuses,Map.of("properties",Map.of()));
  } catch(java.io.IOException failure){throw failure;}catch(Exception failure){throw new java.io.IOException("Cannot compact validated content",failure);}
 }
}
