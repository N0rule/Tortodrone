import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.resources.Json;
import org.tortodrone.world.*;

/** Original 337 NPC script/collision check with two explicit synthetic activation signals.
 * Does not traverse the player's floor, defeat its enemies or claim mission acceptance. */
public final class GeneratorScriptCompatibility {
    public static void main(String[] args)throws Exception {
        Path folder=Path.of(args[1]);var descriptor=new Properties();try(var in=Files.newInputStream(folder.resolve("map.properties"))){descriptor.load(in);}
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(folder.resolve("scene.dat"))));
        check(descriptor.getProperty("scene-id").equals("337")&&descriptor.getProperty("version").equals("-1986376641")&&hash.equals("48fba600280b77d97edc991619340a576aaeeea347476f003f3c6d93c3b91630"),"pinned original Generator revision");
        var properties=new Properties();Path metadata=folder.resolve("content.properties");if(Files.size(metadata)>8*1024*1024)throw new java.io.IOException("Oversized exported metadata");
        try(var input=Files.newInputStream(metadata)){properties.load(input);}
        // This raw migration export exceeds the legacy file reader's 4 MB limit;
        // unified maps compile the same properties in memory through this production loader.
        var content=SceneContent.load(properties,hash);var terrain=CollisionMap.read(folder.resolve("walkability.gz"));var collision=SceneCollision.load(folder,hash);
        String intro="Character/Scripted/Missions/Tutorial/Ancient Generator/Mini Boss/Scorcher, Intro Conversation",outro="Character/Scripted/Missions/Tutorial/Ancient Generator/Mini Boss/Scorcher, Outro Conversation";
        var binding=content.entries().stream().filter(e->e.spec().text("config","").equals(intro)&&e.spec().text("kind","").equals("Monster")).findFirst().orElseThrow();
        var program=SceneScript.read(binding.spec());check(program.steps().size()==19,"complete original intro program");
        var entries=new ArrayList<>(content.entries());
        var start=content.entries().stream().filter(e->e.key().equals("3568")&&e.spec().text("kind","").equals("SceneTrigger")).findFirst().orElseThrow();
        // Controlled activations only, at the native start entry. The second,
        // 60-second fixture signal substitutes for the UNTESTED battle/party
        // platform phase. Every subsequent NPC action/transform is authored data.
        var signals=Map.of("kind","sequence","stopOnFailure",false,"actions",List.of(Map.of("kind","signal","tag","intro_start","name","activate"),Map.of("kind","delay","millis",60000,"action",Map.of("kind","signal","tag","outro_start","name","activate"))));
        entries.add(new SceneContent.Entry("999999",start.x(),start.y(),0,new SceneContent.Spec(Map.of("config","Fixture/Start","kind","SceneTrigger","event","signal-start","watch-tag","fixture-start","signal","intersection","scene-sensor","true","sensor-mask","4","radius",".25","action",Json.stringify(signals)))));
        var fixture=new SceneContent(entries,content.scales(),content.conversations(),content.stats(),content.items(),content.cycles(),content.statuses(),content.mapIcons());fixture.validateConversations();
        var scene=new SceneEntities(fixture,terrain,collision,new Random(1));var isolated=new SceneEntities(content,terrain,collision,new Random(1));
        var elevator=scene.actors().stream().filter(a->a.config().name().equals("Dynamic/Elevator/Next Floor")).findFirst().orElseThrow();
        check(elevator.entity().kind().equals("EntryState")&&elevator.entity().key().equals("2930"),"native elevator keeps its entry-state actor and original entry identity (travel behavior not verified)");
        var panels=new ArrayList<String>();var clears=new ArrayList<Integer>();scene.conversations((definition,source,stamp,target)->{panels.add(definition.actor());return true;});scene.dialogClears((target,stamp)->{clears.add(stamp);return true;});
        int stamp=SceneClock.now()+100;var player=new ActorState(219,1001,"ScriptFixture",0,stamp,start.x(),start.y(),0,0,0,new StarterCatalog.Model("Character/PC/Default",Map.of()));
        boolean moved=false,spawned=false,finished=false;int oldStep=-1,oldOutro=-1;
        for(int tick=0;tick<2400;tick++,stamp+=50){
            scene.advance(stamp,List.of(player));var actor=scene.actors().stream().filter(a->a.config().name().equals(intro)).findFirst().orElse(null);
            if(actor!=null){moved|=Math.hypot(actor.x()-binding.x(),actor.y()-binding.y())>.1;
                int step=step(scene,actor.id());if(step!=oldStep){System.out.println("STEP "+step+" t="+tick*50+" at="+actor.x()+","+actor.y());oldStep=step;}
            }
            var replacement=scene.actors().stream().filter(a->a.config().name().equals(outro)).findFirst().orElse(null);
            if(replacement!=null){
                if(!spawned){check(Arrays.asList(scene.specification(replacement.id()).text("tags","").split(",")).contains("outro_miniboss1"),"native shutdown preserves the authored outro wave tag");spawned=true;}
                int step=step(scene,replacement.id());if(step!=oldOutro){System.out.println("OUTRO "+step+" t="+tick*50+" at="+replacement.x()+","+replacement.y());oldOutro=step;}
            }
            if(actor==null&&spawned&&replacement==null){finished=true;break;}
        }
        check(moved&&spawned&&panels.contains("@conversation/Mission/Rank/Ancient Generator A")&&!clears.isEmpty(),"original intro must walk, talk, clear the native dialog and replace itself with the held outro; panels="+panels+", clears="+clears+", step="+oldStep);
        check(finished&&panels.contains("@conversation/Mission/Rank/Ancient Generator B")&&clears.size()>=2,"outro must reach its original exit waypoint and remove itself after cleanup; step="+oldOutro+", panels="+panels+", clears="+clears);
        isolated.advance(stamp,List.of());check(isolated.actors().stream().anyMatch(a->a.config().name().equals(intro)&&a.x()==binding.x()),"NPC script activation stays within its instance");
        System.out.println("PASS: real original Generator intro/outro -> native waits/attacks/dialogue -> authored waypoint movement -> cleanup/self-removal -> held replacement and bound wave tag; TWO SYNTHETIC ACTIVATION SIGNALS, no player traversal/battle or full mission acceptance");
    }
    private static int step(SceneEntities scene,int id)throws Exception {
        var field=SceneEntities.class.getDeclaredField("entities");field.setAccessible(true);var entity=((Map<?,?>)field.get(scene)).get(id);var step=entity.getClass().getDeclaredField("scriptStep");step.setAccessible(true);return step.getInt(entity);
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
