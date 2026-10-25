package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;
import static org.tortodrone.support.TestWire.check;

/** Cached pawn geometry follows actual entity movement, rotation, ownership and destruction. */
public final class CollisionCacheTest {
    public static void main(String[] args)throws Exception {
        compoundOverlap();
        var rectangle=new FireCatalog.DataValue("Shape$Rectangle",Map.of("width",2f,"height",.5f));
        var fields=new HashMap<String,String>();
        fields.put("native-type","Monster");fields.put("config","Fixture");fields.put("health","100");fields.put("damage-mask","2");fields.put("collision-flags","8");
        fields.put("width","2");fields.put("height",".5");fields.put("speed","1");fields.put("acquire-distance","10");
        fields.put("source-shape",FireCatalog.modelJson(new StarterCatalog.Model("shape",Map.of("value",rectangle))));
        var cached=new SceneContent.Spec(fields);var uncached=new SceneContent.Spec(fields);
        var reference=cached.model("source-shape");
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(4)){
            var reads=new ArrayList<java.util.concurrent.Callable<StarterCatalog.Model>>();
            for(int i=0;i<16;i++)reads.add(()->cached.model("source-shape"));
            for(var result:workers.invokeAll(reads))check(result.get()==reference,"shared immutable references decode once across instance threads");
        }
        check(cached.equals(uncached)&&cached.hashCode()==uncached.hashCode(),"definition value equality excludes derived caches");
        check(cached.model("absent")==null,"missing optional references remain absent");
        fields.put("health","101");check(cached.number("health",0)==100,"cached definitions retain their immutable source fields");fields.put("health","100");
        var content=new SceneContent(List.of(new SceneContent.Entry("mob",0,0,0,new SceneContent.Spec(fields))),Map.of());
        var terrain=SceneWeaponsTest.openTerrain();var moving=new SceneEntities(content,terrain);var isolated=new SceneEntities(content,terrain);
        var initial=moving.actors().getFirst();var before=moving.penetration(1.2f,0,219);
        check(before.x()>0&&before.y()==0,"initial horizontal rectangle has an edge contact");
        int stamp=SceneClock.now()+10;moving.advance(stamp,List.of(player(stamp,3,0)));moving.advance(stamp+100,List.of(player(stamp,3,0)));
        check(moving.actor(initial.id()).x()>.09,"fixture monster moves toward its target");
        check(moving.penetration(1.2f,0,219).x()>before.x()+.09,"translation refreshes an already cached world shape");
        moving.advance(stamp+200,List.of(player(stamp, .1f,3)));
        var turned=moving.actor(initial.id());
        check(Math.abs(turned.rotation()-(float)Math.PI/2)<.001,"fixture monster turns toward its new target");
        check(!moving.penetration(turned.x()+.6f,turned.y(),219).present(),"rotation removes an old horizontal contact");
        check(moving.penetration(turned.x(),turned.y()+1.2f,219).y()>0,"rotation creates the matching vertical contact");
        check(isolated.penetration(1.2f,0,219).equals(before),"shared definitions do not share mutable pose caches between instances");
        check(!moving.penetration(turned.x(),turned.y()+1.2f,initial.id()).present(),"cached shapes still respect ignored actor ownership");
        var lethal=new FireCatalog.DataValue("Damage$Normal",Map.of("flags",2,"amount",1000f));
        moving.damage(new StarterCatalog.Model("DamageRegion",Map.of("damage",lethal)),turned.x(),turned.y(),0,stamp+201,219);
        check(moving.actor(initial.id())==null&&!moving.penetration(turned.x(),turned.y()+1.2f,219).present(),"destroyed actors stop colliding despite a populated cache");
        System.out.println("PASS: concurrent immutable reference reuse, definition equality, collision translation/rotation refresh, instance isolation, ownership and destruction");
    }
    private static void compoundOverlap() {
        var parts=List.of(
            new FireCatalog.DataValue("Shape$Rectangle",Map.of("width",2f,"height",.5f)),
            new FireCatalog.DataValue("Shape$Capsule",Map.of("radius",.3f,"length",2f)),
            new FireCatalog.DataValue("Shape$Circle",Map.of("radius",.7f)));
        var compound=new FireCatalog.DataValue("Shape$Compound",Map.of("shapes",new FireCatalog.ListValue("shape",new ArrayList<>(parts))));
        var empty=new FireCatalog.DataValue("Shape$Compound",Map.of("shapes",new FireCatalog.ListValue("shape",List.of())));
        check(!SceneCollision.overlaps(empty,0,0,0,compound,0,0,0,1),"empty first compound has no contact");
        check(!SceneCollision.overlaps(compound,0,0,0,empty,0,0,0,1),"empty second compound has no contact");
        int hits=0,misses=0;
        for(float rotation: new float[]{0,.7f,1.57f})for(float expansion:new float[]{0,.2f})
            for(float x=-3;x<=3;x+=.3f)for(float y=-2;y<=2;y+=.4f){
                boolean expected=false;
                for(var a:parts)for(var b:parts)expected|=SceneCollision.overlaps(a,x,y,rotation,b,.2f,-.3f,-rotation,expansion);
                check(SceneCollision.overlaps(compound,x,y,rotation,compound,.2f,-.3f,-rotation,expansion)==expected,
                    "compound poses preserve individual shape contact and rounded expansion");
                if(expected)hits++;else misses++;
            }
        check(hits>0&&misses>0,"compound regression includes contacts and exclusions");
    }
    private static ActorState player(int stamp,float x,float y){return new ActorState(219,1001,"Fixture",0,stamp,x,y,0,0,0,new StarterCatalog.Model("Fixture",Map.of()));}
}
