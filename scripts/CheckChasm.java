import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.tortodrone.world.*;

/** Validate a local original export with production readers; never a gameplay acceptance. */
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
        System.out.println("PASS: original 314 production scene/content/entity load, entrance 13, pinned layout/collision, nine actor templates and both complete native wave observers; actors="+world.actors().size());
        System.out.println("NOT A PLAYTHROUGH: extraction refusals, encounter traversal, mission routing and graphical verification remain separate acceptance requirements.");
    }
    private static void require(boolean valid,String message){if(!valid)throw new IllegalStateException(message);}
}
