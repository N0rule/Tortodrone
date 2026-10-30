package org.tortodrone.world;

import java.io.IOException;
import java.security.*;
import java.util.*;

/** Sourced opening route, enabled only for the exact locally verified original scene pair.
 * Native NextFloor remains a generic scene action; this supplies its tutorial party's destination. */
public record TutorialRoute(SceneCatalog.MapScene source,SceneCatalog.MapScene camp) {
    public static final int GATE=397,EXIT=1943,CAMP=217,CAMP_ZONE=16777221,CAMP_ENTRANCE=2017;
    public static final int ENTRANCE=2659;
    public static final String SOURCE_HASH="c43f6fe662c56c7f6d56efe855387e5b55e6723144a2fd2b8e49f18fcc600ecc";
    public static final String CAMP_HASH="2b33dcb4c8d98eb8a049e59c6ef86ef6fe153f42a00cd3ece167d22a645a803f";
    public TutorialRoute {
        Objects.requireNonNull(source);Objects.requireNonNull(camp);
        if(source.id()!=398||!source.kind().equals("level")||source.version()!=2008845103||source.zone()>>>24!=3
                ||source.spawnX()!=-21||source.spawnY()!=-69.5f||!hash(source.layout()).equals(SOURCE_HASH)||source.staticCollision().isEmpty()
                ||!source.collision().walkable(source.spawnX(),source.spawnY())||!source.staticCollision().floorSupported(source.spawnX(),source.spawnY())||!source.portals().triggers().isEmpty()
                ||camp.id()!=CAMP||!camp.town()||camp.zone()!=CAMP_ZONE||camp.version()!=359||camp.spawnX()!=.5f||camp.spawnY()!=-.5f
                ||!hash(camp.layout()).equals(CAMP_HASH)||camp.staticCollision().isEmpty()
                ||!camp.collision().walkable(.5f,-.5f)||!camp.staticCollision().floorSupported(.5f,-.5f)
                ||camp.portals().triggers().stream().anyMatch(t->t.intersects(camp.spawnX(),camp.spawnY())))throw new IllegalArgumentException("Unverified tutorial route content");
        var entrance=source.portals().entrances().get("level_start");
        if(entrance==null||entrance.id()!=ENTRANCE||entrance.x()!=source.spawnX()||entrance.y()!=source.spawnY()||Math.abs(entrance.rotation()-source.spawnRotation())>1e-6f)throw new IllegalArgumentException("Unverified tutorial entrance");
        var exits=source.content().entries().stream().filter(e->e.key().equals(Integer.toString(EXIT))&&e.spec().flag("scene-sensor",false)).toList();
        try{if(exits.size()!=1||!SceneActions.rule(exits.getFirst()).action().equals(new SceneActions.NextFloor()))throw new IllegalArgumentException("Tutorial native exit is unavailable");}
        catch(IOException invalid){throw new IllegalArgumentException("Invalid tutorial native exit",invalid);}
    }
    public static TutorialRoute available(SceneCatalog catalog){
        try{return new TutorialRoute(catalog.scene(398),catalog.scene(CAMP));}
        catch(IOException|IllegalArgumentException unavailable){return null;}
    }
    public ScenePortals.Entrance entrance(){return new ScenePortals.Entrance(CAMP_ENTRANCE,camp.spawnX(),camp.spawnY(),camp.spawnRotation());}
    public boolean exit(SceneSimulation.FloorAdvanceRequest request){return request!=null&&request.source().entity()!=null&&request.source().entity().entrySource()&&request.source().entity().key().equals(Integer.toString(EXIT));}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
