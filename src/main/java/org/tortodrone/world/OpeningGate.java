package org.tortodrone.world;

import java.io.IOException;
import java.security.*;
import java.util.*;
import org.tortodrone.character.OpeningMission;

/** Allowlisted opening gate evidence. Runtime ownership is separate from scene template IDs. */
public record OpeningGate(OpeningMission mission,int id,SceneCatalog.MapScene source,SceneCatalog.MapScene destination) {
    public static final int GENERATOR_GATE=398,GENERATOR_SCENE=337,GENERATOR_EXIT=2930;
    public OpeningGate {
        Objects.requireNonNull(mission);Objects.requireNonNull(source);Objects.requireNonNull(destination);
        if(mission!=OpeningMission.ANCIENT_GENERATOR||id!=GENERATOR_GATE
            ||source.id()!=GENERATOR_SCENE||!source.kind().equals("level")||source.version()!=-1986376641
            ||!hash(source.layout()).equals("48fba600280b77d97edc991619340a576aaeeea347476f003f3c6d93c3b91630")
            ||source.spawnX()!=.5f||source.spawnY()!=.5f||source.staticCollision().isEmpty()
            ||destination.id()!=218||!destination.town()||destination.version()!=374
            ||!hash(destination.layout()).equals("a982b827fc34b5607f55e6316c2ec21c7b9ea45896c144ed78a50c20dee8c841")
            ||destination.spawnX()!=9.5f||destination.spawnY()!=2.5f||destination.staticCollision().isEmpty())throw new IllegalArgumentException("Unverified opening gate templates");
        entrance(source,82);entrance(destination,5158);
        for(var map:List.of(source,destination))if(!map.collision().walkable(map.spawnX(),map.spawnY())||!map.staticCollision().floorSupported(map.spawnX(),map.spawnY()))throw new IllegalArgumentException("Unsupported opening gate entrance");
        var exits=source.content().entries().stream().filter(e->e.key().equals(Integer.toString(GENERATOR_EXIT))&&e.spec().fields().containsKey("elevator-mode")).toList();
        if(exits.size()!=1||!new SceneElevator(1,true).equals(SceneElevator.read(exits.getFirst().spec())))throw new IllegalArgumentException("Missing native Generator exit");
        var gold=entry(source,"604","Door");var key=entry(source,"3427","DungeonLift");var counter=entry(source,"3563","Door");
        if(!gold.spec().text("door-lock","").equals("gold")||!key.spec().text("actionable-signal","").equals("gold_unlock")||key.spec().flag("can-throw",true)||counter.spec().integer("door-increments",0)!=2)throw new IllegalArgumentException("Generator key/door controls require current preparation");
    }
    public static OpeningGate available(SceneCatalog catalog,OpeningMission mission){
        if(mission!=OpeningMission.ANCIENT_GENERATOR)return null;
        try{return new OpeningGate(mission,GENERATOR_GATE,catalog.scene(GENERATOR_SCENE),catalog.scene(218));}
        catch(IOException|IllegalArgumentException unavailable){return null;}
    }
    public ScenePortals.Entrance entrance(){return entrance(source,82);}
    public ScenePortals.Entrance arrival(){return entrance(destination,5158);}
    public boolean exit(SceneSimulation.FloorAdvanceRequest request){return request!=null&&request.source().entity()!=null&&request.source().entity().entrySource()&&request.source().entity().key().equals(Integer.toString(GENERATOR_EXIT));}
    private static ScenePortals.Entrance entrance(SceneCatalog.MapScene map,int id){
        return map.portals().entrances().values().stream().filter(e->e.id()==id&&e.x()==map.spawnX()&&e.y()==map.spawnY()&&Math.abs(e.rotation()-map.spawnRotation())<.00001f).findFirst().orElseThrow(()->new IllegalArgumentException("Missing authored gate entrance"));
    }
    private static SceneContent.Entry entry(SceneCatalog.MapScene map,String key,String kind){
        var matches=map.content().entries().stream().filter(e->e.key().equals(key)&&e.spec().text("kind","").equals(kind)).toList();
        if(matches.size()!=1)throw new IllegalArgumentException("Missing original Generator control "+key);return matches.getFirst();
    }
    private static String hash(byte[] layout){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(layout));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    /** Server-issued solo attempt; never deserialized from the client. */
    public record Attempt(OpeningGate gate,int worldId,int partyOid,String difficulty){
        public Attempt {Objects.requireNonNull(gate);if(worldId<1||partyOid<1||!List.of("EASY","MEDIUM","HARD").contains(difficulty))throw new IllegalArgumentException("Invalid opening gate attempt");}
        public int difficultyOrdinal(){return List.of("EASY","MEDIUM","HARD").indexOf(difficulty);}
    }
}
