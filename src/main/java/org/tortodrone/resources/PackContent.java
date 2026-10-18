package org.tortodrone.resources;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.ItemCatalog;
import org.tortodrone.world.*;

/** Offline startup resolution. No downloads, data writes, implicit custom packs or partial publication. */
final class PackContent {
    private static final Set<String> CATALOGS=Set.of("entry","items","weapons","equipment","fires");
    private record Manifest(ContentRegistry.Pack pack,Map<String,ContentRegistry.Resource> resources,
            Map<String,String> requires,Map<String,String> overrides) { }
    private PackContent() { }
    public static ContentRegistry load(Path deployment) throws IOException {
        return load(deployment, org.tortodrone.config.ServerSettings.read(
            org.tortodrone.config.ServerSettings.defaultPath(deployment)));
    }
    public static ContentRegistry load(Path deployment,org.tortodrone.config.ServerSettings settings) throws IOException {
        deployment=deployment.toAbsolutePath().normalize();ContentFiles.safe(deployment);
        var declarations=new LinkedHashMap<Path,String>();
        String legacy=settings.legacyContent();
        if(!Set.of("auto","disabled").contains(legacy))throw new IOException("legacy-content must be auto or disabled");
        List<String> enabled=names(settings.contentPacks());
        if(enabled.isEmpty()||enabled.size()>32)throw new IOException("Enable between 1 and 32 content packs");
        var warnings=new ArrayList<String>();
        Map<String,ContentRegistry.Resource> resources=new TreeMap<>();
        var packs=new ArrayList<ContentRegistry.Pack>();
        if(legacy.equals("auto")&&LegacyContent.present(deployment)) {
            if(!enabled.equals(List.of("vanilla")))throw new IOException("Legacy resources cannot be mixed with versioned custom packs; migrate them or set legacy-content=disabled");
            resources.putAll(LegacyContent.resolve(deployment,warnings));
            packs.add(new ContentRegistry.Pack("legacy","unversioned"));
        } else {
            var manifests=new LinkedHashMap<String,Manifest>();
            for(String id:enabled) {
                Path root=deployment.resolve(id.equals("vanilla")?"content/packs/vanilla":"content/custom/packs/"+id);
                declarations.put(root.resolve("pack.properties"),ContentFiles.digest(root.resolve("pack.properties")));
                Manifest manifest=manifest(root);
                if(!manifest.pack().id().equals(id))throw new IOException("Content pack folder/ID mismatch: "+id);
                manifests.put(id,manifest);
            }
            var ordered=new ArrayList<Manifest>();var visiting=new HashSet<String>();var visited=new HashSet<String>();
            for(String id:enabled)order(id,manifests,visiting,visited,ordered);
            for(Manifest m:ordered) {
                for(var entry:m.resources().entrySet()) {
                    String key=entry.getKey();var previous=resources.get(key);String expected=m.overrides().get(key);
                    if(previous!=null) {
                        if(!previous.pack().equals(expected)||!m.requires().containsKey(previous.pack()))
                            throw new IOException("Undeclared content override: "+m.pack().id()+" / "+key+" (owned by "+previous.pack()+")");
                    } else if(expected!=null)throw new IOException("Override target is missing: "+key);
                    resources.put(key,entry.getValue());
                }
                packs.add(m.pack());
            }
        }
        try {
            var result=validate(packs,resources,warnings);
            for(var declaration:declarations.entrySet())if(!ContentFiles.digest(declaration.getKey()).equals(declaration.getValue()))
                throw new IOException("Content declaration changed during loading: "+declaration.getKey());
            return result;
        }
        catch(IllegalArgumentException invalid){throw new IOException("Invalid resolved content: "+invalid.getMessage(),invalid);}
    }
    private static Manifest manifest(Path root) throws IOException {
        var p=ContentFiles.properties(root.resolve("pack.properties"));
        if(!"1".equals(p.getProperty("format"))||!"1".equals(p.getProperty("engine-format")))throw new IOException("Unsupported content pack/engine format: "+root);
        String id=p.getProperty("id","");name(id);
        String version=p.getProperty("version","");version(version);
        var requires=new TreeMap<String,String>();var overrides=new TreeMap<String,String>();
        var resources=new TreeMap<String,ContentRegistry.Resource>();
        for(String property:p.stringPropertyNames()) {
            if(Set.of("format","engine-format","id","version").contains(property))continue;
            String value=p.getProperty(property).trim();
            if(property.startsWith("require.")) {String dependency=property.substring(8);name(dependency);version(value);if(dependency.equals(id))throw new IOException("Self dependency: "+id);requires.put(dependency,value);}
            else if(property.startsWith("override.")) {String key=property.substring(9);resourceKey(key);name(value);overrides.put(key,value);}
            else if(property.startsWith("resource.")) {
                String key=property.substring(9);resourceKey(key);Path path=ContentFiles.relative(root,value);
                String expected=p.getProperty("sha256."+key,"");
                if(!expected.matches("[0-9a-f]{64}")||!ContentFiles.digest(path).equals(expected))throw new IOException("Content checksum mismatch: "+id+" / "+key);
                if(key.startsWith("scene.")!=Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid content resource type: "+key);
                resources.put(key,new ContentRegistry.Resource(id,path,expected));
            } else if(!property.startsWith("sha256."))throw new IOException("Unknown pack property: "+property);
        }
        if(resources.isEmpty()||resources.size()>512)throw new IOException("Invalid content resource count: "+id);
        for(String key:overrides.keySet())if(!resources.containsKey(key))throw new IOException("Override has no resource: "+key);
        for(String key:p.stringPropertyNames())if(key.startsWith("sha256.")&&!resources.containsKey(key.substring(7)))throw new IOException("Checksum has no resource: "+key);
        return new Manifest(new ContentRegistry.Pack(id,version),resources,requires,overrides);
    }
    private static void order(String id,Map<String,Manifest> all,Set<String> visiting,Set<String> visited,List<Manifest> ordered) throws IOException {
        if(visited.contains(id))return;
        if(!visiting.add(id))throw new IOException("Content dependency cycle: "+id);
        Manifest m=all.get(id);
        for(var requirement:m.requires().entrySet()) {
            Manifest dependency=all.get(requirement.getKey());
            if(dependency==null||!dependency.pack().version().equals(requirement.getValue()))throw new IOException("Missing or incompatible content dependency: "+id+" requires "+requirement.getKey()+"@"+requirement.getValue());
            order(requirement.getKey(),all,visiting,visited,ordered);
        }
        visiting.remove(id);visited.add(id);ordered.add(m);
    }
    static ContentRegistry validate(List<ContentRegistry.Pack> packs,Map<String,ContentRegistry.Resource> resources,List<String> warnings) throws IOException {
        for(String key:CATALOGS)if(!resources.containsKey(key))throw new IOException("Required content resource is missing: "+key);
        var folders=new TreeMap<Integer,Path>();
        for(var resource:resources.entrySet())if(resource.getKey().startsWith("scene."))folders.put(sceneId(resource.getKey()),resource.getValue().path());
        var entry=ContentFiles.properties(resources.get("entry").path());
        int entryId;
        try {entryId=Integer.parseInt(entry.getProperty("entry-scene",""));}catch(NumberFormatException invalid){throw new IOException("Invalid entry-scene",invalid);}
        var scenes=SceneCatalog.load(folders,entryId);
        var items=ItemCatalog.load(resources.get("items").path());
        var weapons=WeaponCatalog.load(resources.get("weapons").path());
        var equipment=EquipmentCatalog.load(resources.get("equipment").path());
        var fires=FireCatalog.load(resources.get("fires").path());
        if(resources.containsKey("pet-fires"))FireCatalog.load(resources.get("pet-fires").path());
        if(resources.containsKey("town"))org.tortodrone.town.TownCatalog.load(resources.get("town").path());
        for(var weapon:weapons.weapons())requireItem(items,weapon.config());
        for(var shield:weapons.shields())requireItem(items,shield.config());
        for(var item:equipment.entries())requireItem(items,item.config());
        var controls=new HashSet<String>();weapons.weapons().forEach(w->controls.add(w.config().toLowerCase(Locale.ROOT)));weapons.shields().forEach(s->controls.add(s.config().toLowerCase(Locale.ROOT)));
        for(var fire:fires.entries())if(!controls.contains(fire.weaponConfig().toLowerCase(Locale.ROOT)))throw new IOException("Fire graph has no weapon/shield controls: "+fire.weaponConfig());
        // Recheck fingerprints after parsing, so changing inputs cannot publish a mixed startup snapshot.
        for(var resource:resources.values())if(!ContentFiles.digest(resource.path()).equals(resource.sha256()))throw new IOException("Content changed during loading: "+resource.path());
        return new ContentRegistry(packs,resources,scenes,items,weapons,equipment,fires,warnings,
            resources.containsKey("town")?org.tortodrone.town.TownCatalog.load(resources.get("town").path()):org.tortodrone.town.TownCatalog.empty(),
            resources.containsKey("pet-fires")?FireCatalog.load(resources.get("pet-fires").path()):FireCatalog.empty(),
            org.tortodrone.news.NewsCatalog.disabled());
    }
    private static void requireItem(ItemCatalog items,String config) throws IOException {if(items.find(config)==null)throw new IOException("Combat reference has no item definition: "+config);}
    static int sceneId(String key) throws IOException {
        try {int id=Integer.parseInt(key.substring(6));if(id>0&&key.equals("scene."+id))return id;}catch(NumberFormatException ignored){ }
        throw new IOException("Invalid content scene ID: "+key);
    }
    private static void resourceKey(String key) throws IOException {if(key.startsWith("scene."))sceneId(key);else if(!CATALOGS.contains(key)&&!key.equals("town")&&!key.equals("pet-fires"))throw new IOException("Unsupported content resource: "+key);}
    private static void name(String name) throws IOException {if(!name.matches("[a-z][a-z0-9-]{0,63}"))throw new IOException("Invalid content pack ID: "+name);}
    private static void version(String version) throws IOException {if(!version.matches("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}(?:-[a-zA-Z0-9.-]{1,64})?"))throw new IOException("Invalid content pack version: "+version);}
    private static List<String> names(String text) throws IOException {
        var result=new ArrayList<String>();
        for(String name:text.split(",",-1)){name=name.trim();name(name);if(result.contains(name))throw new IOException("Duplicate enabled pack: "+name);result.add(name);}
        return List.copyOf(result);
    }
}
