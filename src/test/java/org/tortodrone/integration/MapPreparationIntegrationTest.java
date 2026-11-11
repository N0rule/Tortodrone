package org.tortodrone.integration;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.resources.*;
import org.tortodrone.support.ContentFixture;
import static org.tortodrone.support.TestWire.check;

/** Offline preparation must preserve edits and refuse live or legacy targets. */
public final class MapPreparationIntegrationTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-prepare-");
        try {
            Path output=home.resolve("deployment/content");run("content",output,true);run("content",output,true);
            check(Files.exists(output.resolve("maps/advanced_training/npcs/konway.yml"))&&Files.exists(output.getParent().resolve("config/server.yml"))&&Files.exists(output.getParent().resolve("plugins/README.md")),"deployment mirrors unified source layout");
            check(!Files.exists(output.resolve("packs"))&&!Files.exists(output.getParent().resolve("data")),"preparation creates no packs or player data");
            ContentManager.load(output.getParent());
            Path metadata=output.resolve("_internal/default-source.properties");
            String olderMetadata="format=1\n# Previous generated ownership metadata, without a self checksum.\n";
            Files.writeString(metadata,olderMetadata);run("content",output,true);
            check(Files.mismatch(metadata,Path.of("content/_internal/default-source.properties"))==-1,"older generated manifest refreshes on an existing deployment");
            run("content",output,true);
            previousItemBindings(output);
            Path npc=output.resolve("maps/advanced_training/npcs/konway.yml");String original=Files.readString(npc);Files.writeString(npc,original+"# owner edit\n");byte[] edited=Files.readAllBytes(npc);
            Path withheld=output.resolve("items/misc.yml");Files.delete(withheld);
            Files.writeString(metadata,olderMetadata);
            run("content",output,false);check(Arrays.equals(edited,Files.readAllBytes(npc))&&!Files.exists(withheld),"customized conflict refuses every planned copy");
            check(Files.readString(metadata).equals(olderMetadata),"content conflict also leaves generated metadata unchanged");
            check(!Files.exists(output.getParent().resolve("content-updates")),"no proposal directory is needed or created");Files.writeString(npc,original);run("content",output,true);
            Path missing=output.resolve("items/misc.yml");Files.delete(missing);Path lockFile=output.getParent().resolve("data/server.lock");Files.createDirectories(lockFile.getParent());Files.writeString(lockFile,"");
            Files.writeString(metadata,olderMetadata);
            try(var channel=FileChannel.open(lockFile,StandardOpenOption.WRITE);var lock=channel.lock()){run("content",output,false);check(!Files.exists(missing)&&Files.readString(metadata).equals(olderMetadata),"held deployment lock blocks content and generated metadata copies");}
            run("content",output,true);
            check(Files.mismatch(metadata,Path.of("content/_internal/default-source.properties"))==-1,"metadata refresh succeeds after releasing deployment lease");
            Path legacyDepot=home.resolve("legacy-depot/content");Files.createDirectories(legacyDepot.resolve("town"));Files.createDirectories(legacyDepot.getParent().resolve("data"));Files.writeString(legacyDepot.getParent().resolve("data/tortodrone.db"),"fixture marker, never opened");Files.writeString(legacyDepot.resolve("town/shops.yml"),"catalogs: []\ndepot: []\n");run("content",legacyDepot,false);check(Files.readString(legacyDepot.resolve("town/shops.yml")).contains("depot:"),"preparation cannot erase the legacy catalog before explicit migration");
            Path retired=output.resolve("maps/haven/npcs/town21.yml");Files.copy(output.resolve("maps/haven/npcs/plinkop.yml"),retired);run("content",output,true);check(!Files.exists(retired),"known unchanged renamed NPC defaults retire without duplicate bindings");
            Files.copy(output.resolve("maps/haven/npcs/plinkop.yml"),retired);Files.writeString(retired,Files.readString(retired)+"# customized retired NPC\n");byte[] retiredEdit=Files.readAllBytes(retired);run("content",output,false);check(Arrays.equals(retiredEdit,Files.readAllBytes(retired))&&!Files.exists(output.getParent().resolve("content-updates")),"customized renamed NPC refuses replacement without review files");Files.delete(retired);
            Path old=home.resolve("old/content");Files.createDirectories(old.resolve("packs/vanilla"));run("content",old,false);check(!Files.exists(old.resolve("maps")),"legacy deployment needs explicit migration");
            Path maps=home.resolve("maps-only");run("maps",maps,true);check(Files.exists(maps.resolve("haven/map.yml"))&&!Files.exists(maps.resolve("items")),"maps-only preparation copies map-local content");
            System.out.println("PASS: offline unified deployment, generated manifest refresh, owner edits, lock refusal, legacy upgrade refusal and maps-only output");
        }finally{ContentFixture.delete(home);}
    }
    private static void run(String command,Path output,boolean success)throws Exception {
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),"BuildTools",command,output.toString()).redirectErrorStream(true).start();
        String result=new String(process.getInputStream().readAllBytes());check(process.waitFor()==(success?0:1),"preparation outcome: "+result);
    }
    private static void previousItemBindings(Path output)throws Exception {
        var ownership=new Properties();
        try(var input=Files.newInputStream(Path.of("content/_internal/default-source.properties"))){ownership.load(input);}
        for(var entry:Map.of(
            "_internal/item-bindings.json","3c32e999f4d12ddb1d4bcb0441c7de8a1ef0acbf027eb8619873f0370692fd62",
            "_internal/provenance/catalog-baseline.json","660605bc9acd03687e0cba17bf0974136e52093f599cd3c09bddb0d1ac0707b7",
            "_internal/town-definitions.json","e948ba4d66dd3914912ddd3a517062b280dd42070fe2b6f8aef668e5531cbfe6",
            "items/misc.yml","11326cba90c476dbb8b17812f60afe5b8be2c67bd29e5aafd473bf318604c45a",
            "town/recipes.yml","11c508ef49d7055fbd6e750a124ec18449c08693a97ced903dd31c9a55ab7240").entrySet())
            check(Arrays.asList(ownership.getProperty("owned."+entry.getKey(),"").split(",")).contains(entry.getValue()),"pre-slicer default checksum retained: "+entry.getKey());
        Path file=output.resolve("_internal/item-bindings.json");
        String previous=Files.readString(file);
        // Retain the published field order; a JSON reserialization changes its checksum.
        for(String id:List.of("botulent_slicer","botulent_slicer_recipe","toxic_slicer","toxic_slicer_recipe","queasinart","queasinart_recipe","ice_axe_tech_azure")) {
            String removed=previous.replaceFirst("\""+id+"\":\\{[^{}]*\\},","");
            check(!removed.equals(previous),"new binding exists: "+id);previous=removed;
        }
        String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(previous.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        check(digest.equals("3c32e999f4d12ddb1d4bcb0441c7de8a1ef0acbf027eb8619873f0370692fd62"),"fixture exactly reconstructs the published pre-slicer bindings");
        Files.writeString(file,previous);
        try {ContentManager.load(output.getParent());throw new AssertionError("Mixed old bindings and new YAML accepted");}
        catch(IOException expected){check(expected.getMessage().contains("botulent_slicer: unknown item ID"),"reproduces reported startup failure");}
        run("content",output,true);
        check(Files.mismatch(file,Path.of("content/_internal/item-bindings.json"))==-1,"unchanged previous defaults upgrade automatically");
        check(ContentManager.load(output.getParent()).items().inspect("botulent_slicer")!=null,"upgraded content compiles with new weapons");
        Files.writeString(file,previous+" ");byte[] edited=Files.readAllBytes(file);
        run("content",output,false);
        check(Arrays.equals(edited,Files.readAllBytes(file)),"customized old bindings are never overwritten");
        check(!Files.exists(output.getParent().resolve("content-updates")),"customized binding conflicts create no proposals");
        Files.copy(Path.of("content/_internal/item-bindings.json"),file,StandardCopyOption.REPLACE_EXISTING);
    }
}
