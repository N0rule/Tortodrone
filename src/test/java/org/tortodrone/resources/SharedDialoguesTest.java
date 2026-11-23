package org.tortodrone.resources;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.support.ContentFixture;
import static org.tortodrone.support.TestWire.check;
import org.tortodrone.core.Yaml;

/** Opt-in shared NPC dialogues resolve exactly and stay behind strict field checks. */
public final class SharedDialoguesTest {
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("tortodrone-shared-dialogues-");
        try {
            ContentFixture.copy(Path.of("content"),root.resolve("content"));
            Path shared=root.resolve("content/shared/dialogues.yml");
            Path bazaar=root.resolve("content/maps/town/bazaar");
            Path haven=root.resolve("content/maps/town/haven");
            Path npc=bazaar.resolve("npcs/energetic_knight.yml");
            var prepared=prepare(root,bazaar);
            check(prepared.getProperty("dialogue.town11.initial").equals("part 1a"),"shared dialogue supplies the initial node");
            check(prepared.getProperty("dialogue.town11.node.0.name").equals("part 1a"),"shared dialogue keeps node names for native binding");
            check(prepared.getProperty("dialogue.town11.node.4.name").equals("part 3c"),"shared dialogue keeps every node");
            check(prepare(root,haven).getProperty("dialogue.town35.node.0.name").equals("part 1a"),"a second map expands the same body under its own native id");
            Files.writeString(npc,"dialogue: {shared: missing}\nid: town11\n");
            refused(()->prepare(root,bazaar));
            Files.writeString(npc,"dialogue: {shared: energetic_knight, initial: part 2a}\nid: town11\n");
            refused(()->prepare(root,bazaar));
            Files.writeString(npc,"dialogue: {shared: energetic_knight}\nid: town11\n");
            var data=Yaml.read(shared);
            @SuppressWarnings("unchecked") var dialogues=(Map<String,Object>)data.get("dialogues");
            @SuppressWarnings("unchecked") var body=(Map<String,Object>)dialogues.get("energetic_knight");
            var broken=new LinkedHashMap<>(body);broken.put("unknown",true);
            Files.delete(shared);
            Yaml.write(shared,Map.of("dialogues",Map.of("energetic_knight",broken)));
            refused(()->prepare(root,bazaar));
            Files.delete(shared);
            refused(()->prepare(root,bazaar));
            System.out.println("PASS: shared NPC dialogues are opt-in, resolve under each native id, and refuse missing, mixed or malformed definitions");
        }finally{ContentFixture.delete(root);}
    }
    private static Properties prepare(Path root,Path map)throws Exception {return MapYaml.prepare(map,root.resolve("content/shared")).content();}
    private interface Work {void run()throws Exception;}
    private static void refused(Work work)throws Exception {
        try {work.run();throw new AssertionError("Expected refusal");}catch(IOException expected){}
    }
}
