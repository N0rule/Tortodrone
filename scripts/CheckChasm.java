import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.tortodrone.world.*;

/** Original content and isolated control-chain regression; never a full gameplay acceptance. */
public final class CheckChasm {
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Usage: java -cp DEPLOYMENT/tortodrone.jar scripts/CheckChasm.java RAW_314_EXPORT");
        var map=SceneCatalog.loadTemplate(314,Path.of(args[0]));
        try{SceneCatalog.load(Map.of(314,Path.of(args[0])),314);throw new IllegalStateException("Level accepted as a town startup entry");}
        catch(java.io.IOException expected){if(!expected.getMessage().equals("Entry scene must be a town"))throw expected;}
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(map.layout()));
        require(hash.equals("b19ac080fbba6c517634868d8ba03023f888c1adceeabbf9f04f6196375380c3")&&map.version()==-1986376641,"Unverified original Chasm revision");
        require(map.spawnX()==.5f&&map.spawnY()==-3.5f&&!map.staticCollision().isEmpty()
            &&map.collision().walkable(map.spawnX(),map.spawnY())&&map.staticCollision().floorSupported(map.spawnX(),map.spawnY()),"Original entrance/collision missing");
        var entrance=map.portals().entrances().values().stream().filter(e->e.id()==13).findFirst().orElseThrow();
        require(entrance.x()==map.spawnX()&&entrance.y()==map.spawnY(),"Original entrance transform changed");
        var rules=new ArrayList<SceneActions.Rule>();
        for(var entry:map.content().entries())if(entry.key().equals("3352")&&entry.spec().text("kind","").equals("SceneTrigger"))rules.add(SceneActions.rule(entry));
        var expected=new SceneActions.Sequence(List.of(new SceneActions.Signal("door 1d","open"),new SceneActions.Signal("door 2d","close"),new SceneActions.Destroy("music_fight"),new SceneActions.Signal("battle_lightswitch","activate"),new SceneActions.Signal("tut_important_obj","next")),false);
        require(rules.size()==2&&rules.stream().allMatch(r->r.all()&&r.removedGroup().equals(List.of("wave 5d","wave 4d"))&&r.action().equals(expected)),"Original two-wave action graph incomplete");
        require(rules.stream().map(SceneActions.Rule::tag).collect(java.util.stream.Collectors.toSet()).equals(Set.of("wave 5d","wave 4d")),"Native wave observers changed");
        require(map.content().actorTemplates().size()==9,"Original deferred actor closure changed");
        var world=new SceneEntities(map.content(),map.collision(),map.staticCollision(),new Random(0));
        require(world.actors().stream().anyMatch(a->a.entity().kind().equals("Door")&&Arrays.asList(world.specification(a.id()).text("tags","").split(",")).contains("door 1d")),"Authored destination door unavailable");
        controls(map,world);
        System.out.println("PASS: original 314 production scene/content/entity load, entrance 13, pinned layout/collision, nine actor templates and both complete native wave observers; actors="+world.actors().size());
        System.out.println("NOT A PLAYTHROUGH: player poses and damage regions are explicit control fixtures; legal player traversal, encounter combat, mission routing and graphical verification remain separate acceptance requirements.");
    }
    private static void controls(SceneCatalog.MapScene map,SceneEntities world)throws Exception {
        int stamp=world.actors().getFirst().created()+100;
        var first=entry(world,"2934");var second=entry(world,"2935");var door=entry(world,"77");
        require(world.specification(door.id()).integer("door-increments",0)==2,"Original two-button door counter missing");
        world.advance(stamp,List.of(player(first,stamp)));require((entry(world,"77").flags()&2)==0,"Capture first button: door must stay closed");
        world.advance(stamp+50,List.of(player(first,stamp+50)));require((entry(world,"77").flags()&2)==0,"Holding one button cannot satisfy two controls");
        world.advance(stamp+100,List.of(player(second,stamp+100)));require((entry(world,"77").flags()&6)==6&&entry(world,"77").entity().stateEntered()==stamp+100,"Second native button opens the actual authored door on its timestamp");
        require(!world.walkable(door.x(),door.y(),219),"Opening animation still blocks the doorway");
        world.advance(stamp+701,List.of());require(world.walkable(door.x(),door.y(),219),"First native doorway collision opens after animation");
        var lever=entry(world,"701");var other=entry(world,"2896");var gate=tag(world,"door 2a");
        require(world.specification(lever.id()).text("switch-mode","").equals("damage-once")&&world.specification(other.id()).text("switch-mode","").equals("damage-once"),"Both original one-time levers must accept native weapon damage");
        var hit=new org.tortodrone.character.StarterCatalog.Model("DamageRegion",Map.of("shape",new FireCatalog.DataValue("com.threerings.tudey.shape.config.ShapeConfig$Circle",Map.of("radius",.1f)),"damage",new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Normal",Map.of("flags",2,"amount",1f))));
        world.damage(hit,lever.x(),lever.y(),0,stamp+800,219);world.advance(stamp+800,List.of());require((tag(world,"door 2a").flags()&2)==0,"First lever leaves the second gate closed");
        world.damage(hit,lever.x(),lever.y(),0,stamp+2400,219);world.advance(stamp+2400,List.of());require((tag(world,"door 2a").flags()&2)==0&&entry(world,"701").entity().state()==1,"Repeated hits never replace the distinct second lever");
        world.damage(hit,other.x(),other.y(),0,stamp+2500,219);world.advance(stamp+2500,List.of());require((tag(world,"door 2a").flags()&6)==6,"Second distinct lever drives original marker 714 and opens its authored door");
        world.advance(stamp+3101,List.of());require(world.walkable(gate.x(),gate.y(),219),"Second gate collision opens after animation");
        var platform=entry(world,"3486");world.advance(stamp+3200,List.of(player(platform,stamp+3200)));
        require(entry(world,"3486").entity().state()==1&&(tag(world,"door 1b").flags()&6)==6,"Native party platform -> Repeat 3770 -> one-time trigger 3562 -> third gate");
        System.out.println("PASS: original 314 two buttons -> door 1a; two damage-activated one-time levers -> marker 714 -> door 2a; party platform -> repeat/one-time chain -> door 1b; duplicate controls and native animation/collision");
    }
    private static ActorState entry(SceneEntities world,String key){return world.actors().stream().filter(a->a.entity()!=null&&a.entity().key().equals(key)).findFirst().orElseThrow(()->new IllegalStateException("Missing original actor entry "+key));}
    private static ActorState tag(SceneEntities world,String tag){return world.actors().stream().filter(a->Arrays.asList(world.specification(a.id()).text("tags","").split(",")).contains(tag)).findFirst().orElseThrow(()->new IllegalStateException("Missing original tag "+tag));}
    private static ActorState player(ActorState at,int stamp){return new ActorState(219,1001,"ControlFixture",0,stamp,at.x(),at.y(),0,0,0,new org.tortodrone.character.StarterCatalog.Model("Character/PC/Default",Map.of()));}
    private static void require(boolean valid,String message){if(!valid)throw new IllegalStateException(message);}
}
