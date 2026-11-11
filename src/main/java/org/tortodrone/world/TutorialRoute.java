package org.tortodrone.world;

import java.io.IOException;
import java.security.*;
import java.util.*;

/** Verified Crash Site exit with direct arrival at Haven's authored default spawn.
 * Native NextFloor remains a generic scene action; this supplies its tutorial party's destination. */
public record TutorialRoute(SceneCatalog.MapScene source,SceneCatalog.MapScene destination) {
    public static final int HAVEN=1;
    public static final int GATE=397,EXIT=1943,CAMP=217,CAMP_ZONE=16777221,CAMP_ENTRANCE=2017;
    public static final int ENTRANCE=2659;
    public static final String SOURCE_HASH="c43f6fe662c56c7f6d56efe855387e5b55e6723144a2fd2b8e49f18fcc600ecc";
    public static final String CAMP_HASH="2b33dcb4c8d98eb8a049e59c6ef86ef6fe153f42a00cd3ece167d22a645a803f";
    private static final Map<String,Integer> SOURCE_REVISIONS=Map.of(SOURCE_HASH,2008845103,"2770fdbe0905a6dc0b565d756a851b768caab8c776ab090a181f72a66e424500",2008859103);
    private static final Map<String,Integer> CAMP_REVISIONS=Map.of(CAMP_HASH,359,"280e87c4cf2ba341381f8f1a59b2ecab54ea8af158fc41c3082cd1f5ec73a3fe",373);
    public static int sourceVersion(byte[] layout){return version(SOURCE_REVISIONS,layout);}
    public static int campVersion(byte[] layout){return version(CAMP_REVISIONS,layout);}
    private static int version(Map<String,Integer> revisions,byte[] layout){var version=revisions.get(hash(layout));if(version==null)throw new IllegalArgumentException("Unverified tutorial scene revision");return version;}
    public TutorialRoute {
        Objects.requireNonNull(source);Objects.requireNonNull(destination);
        if(source.id()!=398||!source.kind().equals("level")||source.version()!=sourceVersion(source.layout())||source.zone()>>>24!=3
                ||source.spawnX()!=-21||source.spawnY()!=-69.5f||source.staticCollision().isEmpty()
                ||!source.collision().walkable(source.spawnX(),source.spawnY())||!source.staticCollision().floorSupported(source.spawnX(),source.spawnY())||!source.portals().triggers().isEmpty()
                ||destination.id()!=HAVEN||!destination.town()
                ||!destination.collision().walkable(destination.spawnX(),destination.spawnY())
                ||destination.portals().triggers().stream().anyMatch(t->t.intersects(destination.spawnX(),destination.spawnY())))throw new IllegalArgumentException("Unverified tutorial route content");
        var entrance=source.portals().entrances().get("level_start");
        if(entrance==null||entrance.id()!=ENTRANCE||entrance.x()!=source.spawnX()||entrance.y()!=source.spawnY()||Math.abs(entrance.rotation()-source.spawnRotation())>1e-6f)throw new IllegalArgumentException("Unverified tutorial entrance");
        var exits=source.content().entries().stream().filter(e->e.key().equals(Integer.toString(EXIT))&&e.spec().flag("scene-sensor",false)).toList();
        try{if(exits.size()!=1||!SceneActions.rule(exits.getFirst()).action().equals(new SceneActions.NextFloor()))throw new IllegalArgumentException("Tutorial native exit is unavailable");}
        catch(IOException invalid){throw new IllegalArgumentException("Invalid tutorial native exit",invalid);}
    }
    public static TutorialRoute available(SceneCatalog catalog){
        try{return new TutorialRoute(catalog.scene(398),catalog.scene(HAVEN));}
        catch(IOException|IllegalArgumentException unavailable){return null;}
    }
    // Default town spawns have no native portal entry; use the same ID as normal town travel.
    public ScenePortals.Entrance entrance(){return new ScenePortals.Entrance(1,destination.spawnX(),destination.spawnY(),destination.spawnRotation());}
    public boolean exit(SceneSimulation.FloorAdvanceRequest request){return request!=null&&request.source().entity()!=null&&request.source().entity().entrySource()&&request.source().entity().key().equals(Integer.toString(EXIT));}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
