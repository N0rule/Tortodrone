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
            Path npc=output.resolve("maps/advanced_training/npcs/konway.yml");Files.writeString(npc,Files.readString(npc)+"# owner edit\n");byte[] edited=Files.readAllBytes(npc);run("content",output,true);check(Arrays.equals(edited,Files.readAllBytes(npc)),"administrator edits survive repeated preparation");
            Path missing=output.resolve("items/misc.yml");Files.delete(missing);Path lockFile=output.getParent().resolve("data/server.lock");Files.createDirectories(lockFile.getParent());Files.writeString(lockFile,"");
            try(var channel=FileChannel.open(lockFile,StandardOpenOption.WRITE);var lock=channel.lock()){run("content",output,false);check(!Files.exists(missing),"held deployment lock blocks all planned copies");}
            run("content",output,true);
            Path old=home.resolve("old/content");Files.createDirectories(old.resolve("packs/vanilla"));run("content",old,false);check(!Files.exists(old.resolve("maps")),"legacy deployment needs explicit migration");
            Path maps=home.resolve("maps-only");run("maps",maps,true);check(Files.exists(maps.resolve("haven/map.yml"))&&!Files.exists(maps.resolve("items")),"maps-only preparation copies map-local content");
            System.out.println("PASS: offline unified deployment, configuration/plugins, owner edits, lock refusal, legacy upgrade refusal and maps-only output");
        }finally{ContentFixture.delete(home);}
    }
    private static void run(String command,Path output,boolean success)throws Exception {
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"scripts/BuildTools.java",command,output.toString()).redirectErrorStream(true).start();
        String result=new String(process.getInputStream().readAllBytes());check(process.waitFor()==(success?0:1),"preparation outcome: "+result);
    }
}
