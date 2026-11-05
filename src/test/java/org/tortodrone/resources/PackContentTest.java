package org.tortodrone.resources;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.tortodrone.support.TestWire.check;

/** Isolated versioned-content resolution, adversarial pack validation and read-only resolution. */
public final class PackContentTest {
    private static Path VANILLA;
    private static Path BAZAAR;

    public static void main(String[] args) throws Exception {
        VANILLA=org.tortodrone.support.ContentFixture.path("");BAZAAR=VANILLA.resolve("maps/bazaar");
        Path home=Files.createTempDirectory("tortodrone-content-test-").toRealPath();
        try {
            Path base=deployment(home,"base");
            var registry=PackContent.load(base);
            check(registry.packs().stream().anyMatch(p->p.id().equals("vanilla")),"default selection resolves vanilla");
            check(registry.scenes().scenes().size()==9&&registry.scenes().entry().id()==1,"all stock scenes and the authored entry load");
            check(registry.items().entries().size()==9348&&registry.weapons().weapons().size()>300
                &&registry.equipment().entries().size()>100&&registry.fires().entries().size()>1000,
                "all four stock catalogs load with substantial indexed definitions");
            check(!Files.exists(base.resolve("data"))&&!Files.exists(base.resolve("cache")),
                "content loading does not create persistent data or a cache");

            additiveAndOverride(home,registry);
            ignoredPacks(home);
            dependencies(home);
            declarationsAndIds(home);
            checksumsAndFormats(home);
            resourcePaths(home);
            boundedInputs(home);
            semanticReferences(home);
            readOnlyResolution(home,base,registry);
            System.out.println("PASS: vanilla resolution, explicit additive and override packs, dependency and ID conflicts, adversarial manifests, semantic references, path safety and read-only resolution");
        } finally {
            try(var paths=Files.walk(home)) {
                for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);
            }
        }
    }

    private static void additiveAndOverride(Path home,ContentRegistry baseline)throws Exception {
        Path deployment=deployment(home,"additive");Path addon=deployment.resolve("content/custom/packs/addon");
        Path replacement=addon.resolve("maps/bazaar-replacement"),extra=addon.resolve("maps/extra");
        copyTree(BAZAAR,replacement);copyTree(BAZAAR,extra);
        setMap(replacement,2,"Bazaar replacement",false);setMap(extra,905,"Test addition",true);
        Map<String,String> resources=Map.of("scene.2","maps/bazaar-replacement","scene.905","maps/extra");
        String vanillaVersion=packVersion(deployment.resolve("content/packs/vanilla/pack.properties"));
        writePack(addon,"addon","1.0.0",Map.of("vanilla",vanillaVersion),Map.of("scene.2","vanilla"),resources);
        selection(deployment,"vanilla,addon","disabled");
        ContentRegistry loaded=PackContent.load(deployment);
        check(loaded.scenes().scenes().size()==10&&loaded.scenes().scene(905).id()==905,
            "an explicitly enabled additive scene joins the vanilla catalog");
        check(loaded.scenes().scene(2).name().equals("Bazaar replacement")
            &&loaded.resources().get("scene.2").pack().equals("addon"),
            "an exact declared override replaces the vanilla scene resource");
        check(loaded.packs().stream().map(ContentRegistry.Pack::id).toList().equals(List.of("vanilla","addon")),
            "dependency order is retained in the resolved snapshot");

        Path undeclared=deployment(home,"undeclared-override");
        Path bad=undeclared.resolve("content/custom/packs/addon");copyTree(replacement,bad.resolve("maps/bazaar-replacement"));
        writePack(bad,"addon","1.0.0",Map.of(),Map.of(),Map.of("scene.2","maps/bazaar-replacement"));
        selection(undeclared,"vanilla,addon","disabled");
        expectFailure(()->PackContent.load(undeclared),"same-key resources cannot silently shadow another pack");

        Path wrongProvider=deployment(home,"wrong-override-provider");
        Path wrong=wrongProvider.resolve("content/custom/packs/addon");copyTree(replacement,wrong.resolve("maps/bazaar-replacement"));
        writePack(wrong,"addon","1.0.0",Map.of("vanilla",vanillaVersion),Map.of("scene.2","other-pack"),Map.of("scene.2","maps/bazaar-replacement"));
        selection(wrongProvider,"vanilla,addon","disabled");
        expectFailure(()->PackContent.load(wrongProvider),"override must name the current resource provider");
        check(baseline.scenes().scenes().size()==9,"resolving another deployment leaves the published baseline immutable");
    }

    private static void ignoredPacks(Path home)throws Exception {
        Path deployment=deployment(home,"ignored");
        Files.createDirectories(deployment.resolve("content/custom/packs/not-enabled"));
        Files.writeString(deployment.resolve("content/custom/packs/not-enabled/pack.properties"),"invalid=ignored\n");
        selection(deployment,"vanilla","disabled");
        check(PackContent.load(deployment).packs().size()==1,"disabled custom packs are not inspected or auto-enabled");
        selection(deployment,"vanilla,missing-pack","disabled");
        expectFailure(()->PackContent.load(deployment),"an explicitly enabled missing pack is rejected");
    }

    private static void dependencies(Path home)throws Exception {
        Path missing=deployment(home,"missing-dependency");
        addScenePack(missing,"addon",905,Map.of("absent","1.0.0"),Map.of(),Map.of());
        selection(missing,"vanilla,addon","disabled");
        expectFailure(()->PackContent.load(missing),"a missing exact dependency is rejected");

        Path incompatible=deployment(home,"incompatible-dependency");
        String current=packVersion(incompatible.resolve("content/packs/vanilla/pack.properties"));
        String other=current.equals("0.29.0")?"0.29.1":"0.29.0";
        addScenePack(incompatible,"addon",905,Map.of("vanilla",other),Map.of(),Map.of());
        selection(incompatible,"vanilla,addon","disabled");
        expectFailure(()->PackContent.load(incompatible),"a dependency with the wrong version is rejected");

        Path cycle=deployment(home,"dependency-cycle");
        addScenePack(cycle,"alpha",905,Map.of("beta","1.0.0"),Map.of(),Map.of());
        addScenePack(cycle,"beta",906,Map.of("alpha","1.0.0"),Map.of(),Map.of());
        selection(cycle,"vanilla,alpha,beta","disabled");
        expectFailure(()->PackContent.load(cycle),"dependency cycles are rejected before resource publication");
    }

    private static void declarationsAndIds(Path home)throws Exception {
        Path missingTarget=deployment(home,"missing-override-target");
        addScenePack(missingTarget,"addon",905,Map.of("vanilla",packVersion(missingTarget.resolve("content/packs/vanilla/pack.properties"))),Map.of("scene.905","vanilla"),Map.of("scene.905","maps/extra"));
        selection(missingTarget,"vanilla,addon","disabled");
        expectFailure(()->PackContent.load(missingTarget),"an override cannot claim a resource absent from earlier packs");

        Path mismatched=deployment(home,"mismatched-scene-id");
        addScenePack(mismatched,"addon",905,Map.of(),Map.of(),Map.of("scene.905","maps/extra"),false);
        selection(mismatched,"vanilla,addon","disabled");
        expectFailure(()->PackContent.load(mismatched),"scene resource key must match its native map scene ID");

        Path duplicate=deployment(home,"duplicate-scene-id");
        Path addon=duplicate.resolve("content/custom/packs/addon");copyTree(BAZAAR,addon.resolve("maps/duplicate"));
        writePack(addon,"addon","1.0.0",Map.of(),Map.of(),Map.of("scene.905","maps/duplicate"));
        selection(duplicate,"vanilla,addon","disabled");
        expectFailure(()->PackContent.load(duplicate),"two resource keys cannot publish one native scene ID");
    }

    private static void checksumsAndFormats(Path home)throws Exception {
        Path tampered=deployment(home,"tampered-resource");
        Files.writeString(tampered.resolve("content/packs/vanilla/items/catalog.json"),"tampered\n",StandardOpenOption.APPEND);
        expectFailure(()->PackContent.load(tampered),"tampering with a pinned vanilla resource is rejected");

        mutateVanilla(home,"bad-checksum",p->p.setProperty("sha256.items","0".repeat(64)));
        mutateVanilla(home,"unsupported-pack-version",p->p.setProperty("format","2"));
        mutateVanilla(home,"unsupported-engine",p->p.setProperty("engine-format","99"));
        mutateVanilla(home,"unknown-resource",p->p.setProperty("resource.quests","quests.json"));
        mutateVanilla(home,"unknown-property",p->p.setProperty("surprise","true"));
        mutateVanilla(home,"malformed-version",p->p.setProperty("version","latest"));
        mutateVanilla(home,"invalid-scene-key",p->p.setProperty("resource.scene.0","maps/bazaar"));
        mutateVanilla(home,"unpaired-checksum",p->p.setProperty("sha256.unused","0".repeat(64)));
        Path unknownSetting=deployment(home,"unknown-selection-setting");
        Files.createDirectories(unknownSetting.resolve("data"));
        Files.writeString(unknownSetting.resolve("data/server.properties"),"content-packs=vanilla\nlegacy-content=disabled\nimplicit=true\n");
        expectFailure(()->PackContent.load(unknownSetting),"unknown operator selection settings fail closed");
    }

    private static void resourcePaths(Path home)throws Exception {
        Path traversal=deployment(home,"path-traversal");
        mutatePack(traversal,p->p.setProperty("resource.items","../outside.json"));
        Path absolute=deployment(home,"absolute-path");
        mutatePack(absolute,p->p.setProperty("resource.items",absolute.resolve("elsewhere.json").toString()));

        Path symlink=deployment(home,"symlink-resource");Path vanilla=symlink.resolve("content/packs/vanilla");
        Path linked=vanilla.resolve("items/linked.json");boolean available=false;
        Files.delete(vanilla.resolve("items/provenance.tsv"));
        try {
            Files.createSymbolicLink(linked,vanilla.resolve("items/catalog.json"));available=true;
            Properties manifest=properties(vanilla.resolve("pack.properties"));
            manifest.setProperty("resource.items","items/linked.json");
            manifest.setProperty("sha256.items",ContentFiles.digest(vanilla.resolve("items/catalog.json")));
            writeProperties(vanilla.resolve("pack.properties"),manifest);
        } catch(UnsupportedOperationException|IOException unavailable) {
            System.out.println("SKIP: content symlink test (symlinks unavailable)");
        }
        if(available)expectFailure(()->PackContent.load(symlink),"a resource path cannot traverse a symlink");
    }

    private static void boundedInputs(Path home)throws Exception {
        Path root=home.resolve("too-many-content-files");Files.createDirectory(root);
        for(int i=0;i<513;i++)Files.writeString(root.resolve("file-"+i),"");
        expectFailure(()->ContentFiles.digest(root),"content trees have a bounded file count before hashing");
        Path duplicate=deployment(home,"duplicate-property");
        Files.writeString(duplicate.resolve("content/packs/vanilla/pack.properties"),"id=other\n",StandardOpenOption.APPEND);
        expectFailure(()->PackContent.load(duplicate),"duplicate manifest properties are refused rather than silently overwritten");
    }

    private static void semanticReferences(Path home)throws Exception {
        Path invalidEntry=deployment(home,"invalid-entry");
        Path entry=invalidEntry.resolve("content/packs/vanilla/maps/catalog.properties");
        Properties catalog=properties(entry);catalog.setProperty("entry-scene","999999");writeProperties(entry,catalog);
        refreshResource(invalidEntry,"entry","maps/catalog.properties");
        expectFailure(()->PackContent.load(invalidEntry),"entry scene must reference an installed town resource");

        Path badCombat=deployment(home,"cross-catalog-reference");Path weapons=badCombat.resolve("content/packs/vanilla/combat/weapons.tsv");
        List<String> lines=Files.readAllLines(weapons);boolean changed=false;
        for(int i=0;i<lines.size();i++)if(lines.get(i).startsWith("W\t")) {
            String[] fields=lines.get(i).split("\t",-1);fields[1]="Weapon/Unknown/Unindexed";lines.set(i,String.join("\t",fields));changed=true;break;
        }
        check(changed,"fixture has at least one stock weapon control row");Files.write(weapons,lines);
        refreshResource(badCombat,"weapons","combat/weapons.tsv");
        expectFailure(()->PackContent.load(badCombat),"combat controls cannot reference an absent item definition");
    }

    private static void readOnlyResolution(Path home,Path base,ContentRegistry initial)throws Exception {
        Path data=base.resolve("data");Files.createDirectories(data);Path sentinel=data.resolve("owner-state");
        byte[] original="keep player data byte-for-byte".getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(sentinel,original);
        var reloaded=PackContent.load(base);
        check(reloaded.resources().equals(initial.resources()),"reloading content validates the same resources");
        check(!Files.exists(base.resolve("cache")),"content resolution creates no diagnostic cache");
        check(Arrays.equals(original,Files.readAllBytes(sentinel)),"content resolution preserves persistent data bytes");
    }

    private static void addScenePack(Path deployment,String id,int sceneId,Map<String,String> requires,
            Map<String,String> overrides,Map<String,String> extra)throws Exception {
        addScenePack(deployment,id,sceneId,requires,overrides,extra,true);
    }
    private static void addScenePack(Path deployment,String id,int sceneId,Map<String,String> requires,
            Map<String,String> overrides,Map<String,String> extra,boolean changeId)throws Exception {
        Path pack=deployment.resolve("content/custom/packs").resolve(id);Path scene=pack.resolve("maps/extra");
        copyTree(BAZAAR,scene);setMap(scene,changeId?sceneId:2,"Test addition",true);
        var resources=new TreeMap<String,String>(extra);resources.put("scene."+sceneId,"maps/extra");
        writePack(pack,id,"1.0.0",requires,overrides,resources);
    }

    private static void setMap(Path folder,int id,String name,boolean emptyPortals)throws Exception {
        Path descriptor=folder.resolve("map.properties");Properties p=properties(descriptor);
        p.setProperty("scene-id",Integer.toString(id));p.setProperty("name",name);writeProperties(descriptor,p);
        if(emptyPortals) {
            Properties portals=new Properties();portals.setProperty("format","2");
            portals.setProperty("layout-sha256",p.getProperty("scene-sha256"));portals.setProperty("entrances","");portals.setProperty("triggers","");
            writeProperties(folder.resolve("portals.properties"),portals);
        }
    }

    private static void writePack(Path root,String id,String version,Map<String,String> requires,
            Map<String,String> overrides,Map<String,String> resources)throws Exception {
        Files.createDirectories(root);Properties p=new Properties();p.setProperty("format","1");p.setProperty("engine-format","1");
        p.setProperty("id",id);p.setProperty("version",version);
        requires.forEach((key,value)->p.setProperty("require."+key,value));
        overrides.forEach((key,value)->p.setProperty("override."+key,value));
        for(var resource:resources.entrySet()) {
            p.setProperty("resource."+resource.getKey(),resource.getValue());
            p.setProperty("sha256."+resource.getKey(),ContentFiles.digest(root.resolve(resource.getValue())));
        }
        writeProperties(root.resolve("pack.properties"),p);
    }

    private static void mutateVanilla(Path home,String name,Mutation mutation)throws Exception {
        Path deployment=deployment(home,name);mutatePack(deployment,mutation);expectFailure(()->PackContent.load(deployment),name+" manifest is rejected");
    }
    private static void mutatePack(Path deployment,Mutation mutation)throws Exception {
        Path file=deployment.resolve("content/packs/vanilla/pack.properties");Properties p=properties(file);mutation.apply(p);writeProperties(file,p);
    }
    private static void refreshResource(Path deployment,String key,String relative)throws Exception {
        Path root=deployment.resolve("content/packs/vanilla"),manifest=root.resolve("pack.properties");
        Properties p=properties(manifest);p.setProperty("sha256."+key,ContentFiles.digest(root.resolve(relative)));writeProperties(manifest,p);
    }
    private static String packVersion(Path manifest)throws Exception {return properties(manifest).getProperty("version");}
    private static Path deployment(Path home,String name)throws Exception {
        Path target=home.resolve(name);copyTree(VANILLA,target.resolve("content/packs/vanilla"));return target;
    }
    private static void selection(Path deployment,String enabled,String legacy)throws Exception {
        Path file=deployment.resolve("data/server.properties");Files.createDirectories(file.getParent());
        Files.writeString(file,"content-packs="+enabled+"\nlegacy-content="+legacy+"\n");
    }
    private static Properties properties(Path file)throws IOException {Properties p=new Properties();try(var in=Files.newInputStream(file)){p.load(in);}return p;}
    private static void writeProperties(Path file,Properties p)throws IOException {Files.createDirectories(file.getParent());try(var out=Files.newOutputStream(file)){p.store(out,"isolated content test fixture");}}
    private static void copyTree(Path source,Path target)throws IOException {
        try(var paths=Files.walk(source)) {
            for(Path path:paths.toList()) {
                Path copy=target.resolve(source.relativize(path));
                if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))Files.createDirectories(copy);
                else {Files.createDirectories(copy.getParent());Files.copy(path,copy,StandardCopyOption.COPY_ATTRIBUTES);}
            }
        }
    }
    private static void expectFailure(Action action,String message)throws Exception {
        try {action.run();throw new AssertionError(message+" (accepted unexpectedly)");}
        catch(IOException expected) { }
    }
    @FunctionalInterface private interface Action {void run()throws Exception;}
    @FunctionalInterface private interface Mutation {void apply(Properties properties)throws Exception;}
}
