import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.world.*;

/** Original 337 key/door component test with explicit player poses and carry calls.
 * Does not traverse the floor, fight its enemies or settle a mission. */
public final class CheckGeneratorControls {
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("RAW337");
        var map=SceneCatalog.loadTemplate(337,Path.of(args[0]));
        check(map.version()==-1986376641&&HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(map.layout())).equals("48fba600280b77d97edc991619340a576aaeeea347476f003f3c6d93c3b91630"),"pinned original Generator");
        var scene=new SceneEntities(map.content(),map.collision(),map.staticCollision(),new Random(1));var isolated=new SceneEntities(map.content(),map.collision(),map.staticCollision(),new Random(1));
        var key=actor(scene,"3427");var door=actor(scene,"604");int stamp=key.created()+100;
        check(scene.specification(key.id()).text("actionable-signal","").equals("gold_unlock")&&!scene.canThrow(key.id())&&scene.specification(door.id()).text("door-lock","").equals("gold"),"prepared native gold lock and key capability");
        check(scene.specification(actor(scene,"3563").id()).integer("door-increments",0)==2,"original final two-control door prepared");
        check(scene.carry(key.id(),player(key.x(),key.y(),0,stamp),stamp),"controlled native key pickup fixture");
        ActorState approach=null;
        // Find a legal interaction pose using original collision, never change the layout/spawn.
        for(int x=-12;x<=12&&approach==null;x++)for(int y=-12;y<=12;y++){
            float px=door.x()+x*.25f,py=door.y()+y*.25f;
            if(!map.collision().walkable(px,py)||!map.staticCollision().floorSupported(px,py)||map.staticCollision().penetration(43,px,py,ScenePortals.PAWN_RADIUS).present()||!scene.walkable(px,py,219))continue;
            float facing=(float)Math.atan2(door.y()-py,door.x()-px);var prompt=scene.interaction(px,py,facing,219,key.id());
            if(prompt!=null&&prompt.actorId()==door.id()&&prompt.action().equals("UnlockGold")){approach=player(px,py,facing,stamp);break;}
        }
        check(approach!=null,"original geometry must offer a reachable-side native gold-unlock prompt");
        check(!scene.walkable(door.x(),door.y(),219),"native gold doorway starts physically blocked");
        scene.dropLift(key.id(),approach,stamp);
        check(scene.actor(key.id())==null&&(actor(scene,"604").flags()&6)==6&&!scene.walkable(door.x(),door.y(),219),"actual owned drop consumes the original scene key and starts the door animation");
        scene.advance(stamp+scene.specification(door.id()).integer("door-speed",0)+1,List.of());
        check((actor(scene,"604").flags()&6)==2&&scene.walkable(door.x(),door.y(),219),"original gold doorway collision opens after its authored animation");
        check((actor(isolated,"604").flags()&2)==0&&actor(isolated,"3427")!=null,"other original scene instances retain their door and key");
        System.out.println("PASS: original 337 key 3427 -> gold door 604, legal-side interaction geometry, exact scene consumption, animation/collision, isolation and prepared final counter 3563");
        System.out.println("CONTROL FIXTURE ONLY: explicit player poses/carry calls; floor traversal, combat, mission travel and graphics are not established. Native minimap icon removal remains unimplemented.");
    }
    private static ActorState actor(SceneEntities scene,String key){return scene.actors().stream().filter(a->a.entity().key().equals(key)).findFirst().orElseThrow();}
    private static ActorState player(float x,float y,float face,int stamp){return new ActorState(219,1001,"ControlFixture",0,stamp,x,y,face,face,0,new StarterCatalog.Model("Character/PC/Default",Map.of()));}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
