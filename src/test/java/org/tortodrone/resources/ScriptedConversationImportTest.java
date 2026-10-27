package org.tortodrone.resources;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.support.ContentFixture;
import org.tortodrone.world.*;
import static org.tortodrone.support.TestWire.check;

/** A synthetic response/presentation graft on the existing verified training pack tests import only. */
public final class ScriptedConversationImportTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-scripted-import-");
        try{
            Path source=home.resolve("source"),output=home.resolve("output");ContentFixture.copy(ContentFixture.path("maps/advanced_training"),source);
            var properties=new Properties();try(var input=Files.newInputStream(source.resolve("content.properties"))){properties.load(input);}
            var close=new StarterCatalog.Model("Fixture/Close",Map.of());var music=new StarterCatalog.Model("Fixture/Music",Map.of());
            properties.setProperty("dialogue.konway.source-close-animation",FireCatalog.modelJson(close));properties.setProperty("dialogue.konway.music",FireCatalog.modelJson(music));
            var actions=new ArrayList<Object>();actions.add(Map.of("kind","signal","tag","start tut_miniboss","name","activate"));
            for(int i=0;i<255;i++)actions.add(Map.of("kind","signal","tag","x".repeat(256),"name","activate"));
            String graph=Json.stringify(Map.of("kind","sequence","stopOnFailure",false,"actions",actions));
            check(graph.length()>65536,"fixture covers a native graph string above the ordinary JSON limit");
            properties.setProperty("dialogue.konway.node.1.response.0.action",graph);
            ContentFixture.store(source.resolve("content.properties"),properties);
            new UnifiedExport(null).exportMap(source,output,Map.of(294,"fixture"),new LinkedHashMap<>(),new LinkedHashMap<>(),new LinkedHashMap<>());
            var prepared=MapYaml.prepare(output,Path.of("content/shared"));var content=SceneContent.load(prepared.content(),prepared.descriptor().getProperty("scene-sha256"));
            var dialogue=content.conversations().get(properties.getProperty("dialogue.konway.actor"));
            check(close.equals(dialogue.sourceCloseAnimation())&&music.equals(dialogue.music()),"unified map import retains native conversation presentation fields");
            check(SceneActions.parse(graph).equals(dialogue.nodes().get("part 2a").responses().getFirst().action()),"unified map import retains the complete server-authored terminal action above 64 KiB");
            String large=Json.stringify("x".repeat(65537));try{Json.parse(large);throw new AssertionError("Ordinary JSON string limit widened");}catch(IOException expected){}
            check(Json.parseCatalog(large).equals("x".repeat(65537)),"catalog strings use an explicit larger bound");
            try{Json.parseCatalog(Json.stringify("x".repeat(262145)));throw new AssertionError("Unbounded catalog string accepted");}catch(IOException expected){}
            Path npc=output.resolve("npcs/konway.yml");var yaml=Yaml.read(npc);var nodes=Yaml.map(Yaml.map(yaml.get("dialogue"),"dialogue").get("nodes"),"nodes");var last=Yaml.map(nodes.get("part 2a"),"node");var responses=Yaml.list(last.get("responses"),"responses");
            Yaml.map(responses.getFirst(),"response").remove("action");Files.delete(npc);Yaml.write(npc,yaml);
            try{MapYaml.prepare(output,Path.of("content/shared"));throw new AssertionError("Authored response action was silently removable");}catch(IOException expected){check(expected.getMessage().contains("action removed"),"missing native action is explicitly rejected");}
            System.out.println("PASS: unified map conversion/compilation retains scripted dialogue actions, music and close animation; missing native action references are refused (synthetic graft, not tutorial gameplay)");
        }finally{ContentFixture.delete(home);}
    }
}
