import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.tortodrone.world.*;
import org.tortodrone.character.StarterCatalog;

/** Original exported scene/actors, with a pawn fixture beside a placed Ember; not a full walk. */
public final class TutorialHeatCompatibility {
    public static void main(String[] args)throws Exception {
        var folder=Path.of(args[1]);var p=new Properties();try(var in=Files.newInputStream(folder.resolve("map.properties"))){p.load(in);}
        byte[] layout=Files.readAllBytes(folder.resolve("scene.dat"));
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(layout));
        check(hash.equals("2770fdbe0905a6dc0b565d756a851b768caab8c776ab090a181f72a66e424500")&&hash.equals(p.getProperty("scene-sha256")),"pinned original scene layout");
        var map=new SceneCatalog.MapScene(Integer.parseInt(p.getProperty("scene-id")),Integer.parseInt(p.getProperty("zone-id")),p.getProperty("zone-name"),p.getProperty("name"),p.getProperty("image"),p.getProperty("kind"),Integer.parseInt(p.getProperty("version")),Float.parseFloat(p.getProperty("spawn-x")),Float.parseFloat(p.getProperty("spawn-y")),Float.parseFloat(p.getProperty("spawn-rotation")),1,CollisionMap.read(folder.resolve("walkability.gz")),layout,ScenePortals.EMPTY,SceneContent.load(folder,hash),SceneCollision.load(folder,hash));
        check(map.version()==2008859103&&map.spawnX()==-21&&map.spawnY()==-69.5f,"pinned original tutorial revision/entrance");
        int count=0;SceneContent.Entry sample=null;
        for(var entry:map.content().entries())if(entry.spec().config().name().startsWith("Item/Consumable/Heat/Ember ")){
            int index=Integer.parseInt(entry.spec().config().name().substring("Item/Consumable/Heat/Ember ".length()));
            check(index>=1&&index<=5&&entry.spec().integer("pickup-heat",0)==new int[]{1,5,10,25,50}[index-1],"sourced denomination on original entry "+entry.key());
            check(entry.spec().number("vacuum-speed",0)==8&&entry.spec().number("radius",0)==.4f,"original pickup body and vacuum retained");
            count++;if(sample==null)sample=entry;
        }
        check(count>0,"original scene contains mapped Ember actors");
        var simulation=new SceneRegistry.Instance(700,0,map).simulation();
        // Explicit fixture: start at an original Ember's authored position. No later
        // pawn/actor position, claim, heat or completion fields are assigned by the test.
        int now=SceneClock.now();var fixture=sample;float sx=0,sy=0;double best=Double.POSITIVE_INFINITY;
        for(float x=fixture.x()-2;x<=fixture.x()+2;x+=.25f)for(float y=fixture.y()-2;y<=fixture.y()+2;y+=.25f){
            double distance=Math.hypot(x-fixture.x(),y-fixture.y());
            if(distance<best&&map.collision().walkable(x,y)&&!map.staticCollision().penetration(Math.round(map.content().stats().get("player.collision-mask")),x,y,ScenePortals.PAWN_RADIUS).present()){best=distance;sx=x;sy=y;}
        }
        check(best<2.4,"authored Ember has a nearby native walkable pawn fixture");
        try(var member=simulation.join(new ActorState(219,1001,"Heat-Fixture",0,now,sx,sy,0,0,0,new StarterCatalog.Model("Character/PC/Level",Map.of())),s->{})){
            member.enter();List<SceneEntities.Pickup> claims=List.of();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(claims.isEmpty()&&System.nanoTime()<until){Thread.sleep(50);claims=member.takePickups();}
            var claim=claims.stream().filter(pickup->pickup.heat()>0).findFirst().orElseThrow(()->new AssertionError("Original Ember never produced a native heat claim"));
            var before=simulation.snapshot().actors().stream().filter(a->a.id()==claim.entityId()).findFirst().orElseThrow();
            check(member.collectHeat(claim.entityId())&&member.floorHeat()==claim.heat(),"original scene actor -> production claim -> floor heat");
            check(!member.collectHeat(claim.entityId()),"same original Ember cannot credit twice");
            ActorState activated=null;until=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
            while(activated==null&&System.nanoTime()<until){Thread.sleep(5);activated=simulation.snapshot().actors().stream().filter(a->a.id()==claim.entityId()&&a.entity().pickup()!=null).findFirst().orElse(null);}
            check(activated!=null,"native activation is published before the pickup's vacuum lifetime ends");
            var wire=new org.tortodrone.protocol.SessionCodec().sceneDelta(1001,700,0,(short)0,100,150,(short)50,List.of(activated),List.of(),new int[0],Map.of(),true);
            var nativePickup=(com.threerings.projectx.dungeon.data.actor.Pickup)FireCompatibility.read(wire).aej()[0];
            check(nativePickup.playerOids==null&&Arrays.equals(nativePickup.activations,new int[]{1001,activated.entity().pickup().activated()}),"2026 shared activation uses the player OID, not the pawn ID");
            var original=(com.threerings.projectx.dungeon.data.actor.Pickup)FireCompatibility.read(new org.tortodrone.protocol.SessionCodec().sceneDelta(1001,700,0,(short)0,100,100,(short)50,List.of(before),List.of(),new int[0],Map.of(),true)).aej()[0];
            var delta=FireCompatibility.read(new org.tortodrone.protocol.SessionCodec().sceneDelta(1001,700,0,(short)0,100,150,(short)50,List.of(),List.of(activated),new int[0],Map.of(before.id(),before),true));
            var changed=(com.threerings.projectx.dungeon.data.actor.Pickup)delta.aek()[0].apply(original);
            check(Arrays.equals(changed.activations,nativePickup.activations)&&changed.tz().equals(original.tz()),"native mutable activation delta preserves the authored pickup position");
        }
        check(simulation.playerCount()==0,"original scene fixture releases its membership");
        System.out.println("PASS: "+count+" original scene-398 Ember bindings, sourced denominations/body/vacuum, real pickup ticks, floor credit, native activation add/delta and replay refusal (pawn fixture beside original Ember; not a complete playthrough)");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
