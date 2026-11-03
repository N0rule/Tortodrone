import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.util.zip.GZIPOutputStream;
import com.threerings.config.*;
import com.threerings.projectx.dungeon.config.DungeonActionConfig;
import com.threerings.tudey.config.TargetConfig;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.resources.Json;
import org.tortodrone.world.*;

/** Real native heat table; synthetic trigger/terrain/anchors, not mission traversal. */
public final class SceneTableCompatibility {
    public static void main(String[] args)throws Exception {
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,args[0]+"/rsrc");
        var cm=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");cm.init();
        var nativeAction=new DungeonActionConfig.TableSpawn();nativeAction.spawnTable=new ConfigReference<>("Treasure/Heat/Scaled Ember");nativeAction.topLevel=false;nativeAction.warp=false;
        var tag=new TargetConfig.Tagged();tag.tag="heat spawn";nativeAction.location=tag;
        String json=Json.stringify(SceneContentExport.sceneAction(nativeAction,0,cm));var graph=SceneActions.parse(json);
        check(graph instanceof SceneActions.Targeted targeted&&targeted.target().mode().equals("tagged")&&targeted.action() instanceof SceneActions.Choice choice&&choice.weights().equals(List.of(100f,10f,1f))&&choice.actions().size()==3,"native depth-zero weights stay as runtime choices inside location iteration");
        var entries=List.of(entry("1",0,Map.of("kind","SceneTrigger","config","Fixture/Trigger","event","signal-start","watch-tag","startup:1","signal","startup","scene-startup","true","action",json)),
            entry("2",2,Map.of("kind","SceneAnchor","config","Fixture/Heat","source-tags","heat spawn")),entry("3",4,Map.of("kind","SceneAnchor","config","Fixture/Heat","source-tags","heat spawn")));
        var content=new SceneContent(entries,Map.of());var terrain=open();
        // Different independent draws at the two actual resolved locations.
        int[] draws={0};var scene=new SceneEntities(content,terrain,SceneCollision.empty(),new Random(1){@Override public double nextDouble(){return draws[0]++==0?0:.999999;}});
        var actors=scene.actors();check(draws[0]==2&&actors.size()==2,"one independent table draw per tagged location");
        check(actors.get(0).config().name().equals("Item/Consumable/Heat/Ember 1")&&actors.get(0).x()==2&&actors.get(1).config().name().equals("Item/Consumable/Heat/Ember 3")&&actors.get(1).x()==4,"original denominations spawn at their own marker transforms");
        int now=SceneClock.now()+1100;var pawn=new ActorState(219,1001,"HeatFixture",0,now,2,0,0,0,0,new StarterCatalog.Model("Character/PC/Level",Map.of()));scene.advance(now,List.of(pawn));
        var claims=scene.takePickups(219);check(claims.size()==2&&claims.stream().mapToInt(SceneEntities.Pickup::heat).sum()==11,"native spawned Ember actors enter real pickup/heat claims");
        check(scene.takePickups(219).isEmpty()&&scene.takePickups(220).isEmpty(),"pickup claims cannot be duplicated or taken by a foreign knight");
        var isolated=new SceneEntities(content,terrain);check(isolated.actors().stream().noneMatch(a->a.entity().pickup()!=null),"another scene owns fresh pickup state");
        nativeAction.rotation=1;try{SceneContentExport.sceneAction(nativeAction,0,cm);throw new AssertionError("Unsupported table transform accepted");}catch(IOException expected){}
        nativeAction.rotation=0;nativeAction.spawnTable=new ConfigReference<>("Treasure/Item Loot/AutoItem Materials");try{SceneContentExport.sceneAction(nativeAction,0,cm);throw new AssertionError("Unsupported item table silently accepted");}catch(IOException expected){}
        System.out.println("PASS: installed Scaled Ember depth-zero selection -> retained 100/10/1 runtime weights -> independent tagged-location draws -> native Ember actors and real owned heat claims; transform/unknown table refusal and scene isolation (synthetic trigger/terrain)");
    }
    private static SceneContent.Entry entry(String key,float x,Map<String,String> fields){return new SceneContent.Entry(key,x,0,0,new SceneContent.Spec(fields));}
    private static CollisionMap open()throws Exception {
        Path file=Files.createTempFile("tortodrone-table-",".gz");
        try{try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))){out.writeInt(0x54445731);out.writeInt(64);out.writeInt(64);out.writeFloat(-8);out.writeFloat(-8);out.writeFloat(.5f);byte[] cells=new byte[512];Arrays.fill(cells,(byte)255);out.write(cells);}return CollisionMap.read(file);}finally{Files.delete(file);}
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
