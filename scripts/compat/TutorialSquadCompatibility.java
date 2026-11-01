package org.tortodrone.world;

import java.nio.file.*;
import java.util.*;
import org.tortodrone.world.*;
import org.tortodrone.character.StarterCatalog;

/** Real original scene/encounter fixture; pawn starts at the native ambush strip. */
public class TutorialSquadCompatibility {
    record Dialog(SceneConversation definition,ActorState source){}
    public static void main(String[] args)throws Exception {
        var defaults=org.tortodrone.resources.ContentManager.load(Path.of(args[1]));var map=defaults.scenes().scene(398);
        if(map.version()!=2008859103)throw new AssertionError("Unexpected original scene revision");
        var world=new SceneEntities(map.content(),map.collision(),map.staticCollision());
        var weapons=new SceneWeapons(map.collision());weapons.staticCollision(map.staticCollision());weapons.catalog(defaults.fires());weapons.targets(world);
        int[] stamp={SceneClock.now()+1000};var dialogs=new ArrayList<Dialog>();var hits=new HashSet<String>();var attacks=new HashSet<String>();
        world.conversations((definition,source,time,player)->{dialogs.add(new Dialog(definition,source));return true;});
        var health=new SceneHealth(map.content());world.pawnStatuses((id,operation,time)->operation.clear()?health.clearStatus(operation.clearMask()):health.applyCondition(operation.condition(),operation.data(),time));
        world.playerHits(hit->health.hit(hit.damage(),hit.timestamp(),hit.multiplier()));
        var refusals=new HashSet<String>();
        world.effects(weapons::effect);world.attacks((actor,fire)->{
            attacks.add(actor.config().name());
            if(!weapons.fire(fire,actor,stamp[0])){
                if(actor.config().name().startsWith("Character/Scripted/Missions/Tutorial/Crashsite/Mini Boss/Knights/"))throw new AssertionError("Unsupported helper attack "+actor.config().name());
                if(refusals.add(actor.config().name()))System.out.println("NOT VERIFIED: non-helper action refused for "+actor.config().name()+"; this fixture verifies squad combat only");
            }
        });
        world.feedback(event->{var source=world.actor(event.source());var target=world.actors().stream().filter(a->a.id()==event.actor()).findFirst().orElse(null);if(source!=null&&target!=null&&target.config().name().contains("Ironwood Giant")&&event.amount()>0)hits.add(source.config().name());});
        var pawn=new ActorState(219,1001,"Squad-Fixture",0,stamp[0],11.5f,57.5f,0,0,0,new StarterCatalog.Model("Character/PC/Level",Map.of()));
        for(int tick=0;tick<60;tick++){stamp[0]+=50;world.advance(stamp[0],List.of(pawn));weapons.advance(stamp[0],List.of(pawn));}
        var opening=dialogs.stream().filter(d->d.definition().actor().endsWith("Ambushed Knights A")).findFirst().orElseThrow();
        for(String node:List.of("part 1a","part 2a"))if(!world.conversationResponse(opening.definition(),node,0,opening.source(),219,stamp[0]))throw new AssertionError("Authored response refused");
        String prefix="Character/Scripted/Missions/Tutorial/Crashsite/Mini Boss/Knights/";
        for(int tick=0;tick<400&&!hits.containsAll(List.of(prefix+"Bygs",prefix+"Warden",prefix+"Wend"));tick++){stamp[0]+=50;world.advance(stamp[0],List.of(pawn));weapons.advance(stamp[0],List.of(pawn));}
        for(String role:List.of("Bygs","Warden","Wend")){
            if(!hits.contains(prefix+role)){
                for(var actor:world.actors())if(actor.config().name().startsWith(prefix))System.out.println("HELPER: "+actor.config().name()+" pose="+actor.x()+","+actor.y()+" activity="+actor.activity()+" flags="+actor.flags());
                throw new AssertionError("Original encounter helper never damaged Giant: "+role+" hits="+hits+" attacks="+attacks);
            }
        }
        System.out.println("PASS: original scene 398 -> native ambush conversation responses -> authored helper spawns -> Bygs/Warden/Wend collision-safe approach and real Giant damage (held pawn fixture at ambush strip; not full tutorial acceptance)");
    }
}
