package org.tortodrone.world;

import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.storage.*;
import org.tortodrone.resources.Json;
import org.tortodrone.town.TownCatalog;
import static org.tortodrone.support.TestWire.check;

/** A configured death graph runs through the shared scene and the persistent reward transaction. */
public final class DeathLootPipelineTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-death-loot-");
        try{
            var db=new Database(home,true);int account=db.create("Hunter",new Passwords.Protection(new byte[16],new byte[32]),false);
            var items=ItemCatalog.load(org.tortodrone.support.ContentFixture.path("items/catalog.json"));
            new CharacterStore(db,account,"Hunter",items).create(new Knight(0,"Hunter-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0));
            var profile=db.worldProfile(account,1,1);int now=SceneClock.now();
            // Native Crown identity and denomination; the deterministic fixture table is not an official drop rate.
            var pickup=Map.of("native-type","com.threerings.projectx.dungeon.data.actor.Pickup","config","Item/Consumable/Crowns/Copper Crown","radius",".2","pickup-item","Instant/Crown/Copper Crown","lifespan","30000");
            var spawn=new FireCatalog.Action(FireCatalog.Kind.SPAWN_PICKUP,new StarterCatalog.Model("Pickup",Map.of("fields",Json.stringify(pickup))),null,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(),"");
            var table=new FireCatalog.Action(FireCatalog.Kind.RANDOM_WEIGHTED,new StarterCatalog.Model("Weighted",Map.of("weights",new FireCatalog.ListValue("float",List.of(1f)))),null,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(spawn),"");
            var spec=new SceneContent.Spec(Map.of("native-type","Monster","config","Character/NPC/Advanced Training Gym/Training Cube","health","1","damage-mask","2","radius",".4","death-action",FireCatalog.actionJson(table)));
            var world=new SceneEntities(new SceneContent(List.of(new SceneContent.Entry("bot",0,0,0,spec)),Map.of()),SceneWeaponsTest.openTerrain());
            var weapons=new SceneWeapons(SceneWeaponsTest.openTerrain());weapons.targets(world);world.attacks((source,action)->weapons.fire(action,source,now));
            var player=new ActorState(profile.worldId(),1001,"Hunter",0,now,0,0,0,0,0,new StarterCatalog.Model("Character/PC/Default",Map.of()));
            world.advance(now,List.of(player));
            var damage=new StarterCatalog.Model("DamageRegion",Map.of("damage",new FireCatalog.DataValue("Damage$Normal",Map.of("amount",10f,"flags",2))));
            world.damage(damage,0,0,0,now,player.id());world.damage(damage,0,0,0,now,player.id());
            check(world.actors().stream().filter(a->a.entity().kind().equals("Pickup")).count()==1,"one killing blow evaluates the loot graph exactly once");
            world.advance(now+1,List.of(player));var claims=world.takePickups(player.id());check(claims.size()==1,"the spawned native actor can be picked up");
            var claim=claims.getFirst();String receipt=UUID.randomUUID()+":"+claim.entityId();
            var store=new TownStore(db,items,TownCatalog.empty());var paid=store.pickup(account,1,1,receipt,claim.item());
            check(paid.profile().crowns()==profile.crowns()+1,"the death reward awards the original Copper Crown denomination");
            world.completePickup(player.id(),claim.entityId(),true);
            check(world.actor(claim.entityId())==null&&world.takePickups(player.id()).isEmpty(),"shared removal follows the committed award");
            check(store.pickup(account,1,1,receipt,claim.item()).profile().equals(paid.profile()),"a repeated death reward receipt cannot pay twice");
            System.out.println("PASS: monster death -> weighted loot graph -> shared native pickup -> atomic currency award -> removal, with duplicate safety");
        }finally{try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
}
