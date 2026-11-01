package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.resources.Json;
import static org.tortodrone.support.TestWire.check;

/** Synthetic controls for the explicitly provisional Giant targeting policy. */
public final class GiantTargetingTest {
    public static void main(String[] args)throws Exception {
        var fields=new HashMap<String,String>();
        fields.putAll(Map.of("native-type","com.threerings.projectx.dungeon.data.actor.Monster","config","Fixture/Giant","health","100","radius","1.25","damage-mask","2","turn-rate","100"));
        fields.putAll(Map.of("targeting.kind","weighted-path-attacks","targeting.expansion","6.7366667","targeting.evaluation","2000","targeting.interval","1000","targeting.monster-mask","4","targeting.include-players","true"));
        fields.put("targeting.modes",Json.stringify(List.of(Map.of("attack",0,"minimum",0,"maximum",Float.MAX_VALUE,"range",6.0166667f,"weight",1,"faces",true,"turn-snap",0))));
        fields.putAll(Map.of("attack.0.clear","200","attack.0.land","0","attack.0.fires","0","attack.0.fire.0.delay","0"));
        fields.put("attack.0.fire.0.action",FireCatalog.actionJson(new FireCatalog.Action(FireCatalog.Kind.FIRE_EFFECT,new StarterCatalog.Model("Fixture/Warning",Map.of()),null,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(),"")));
        var giant=new SceneContent.Entry("giant",0,0,0,new SceneContent.Spec(fields));
        var ally=body("ally",3,0,5);var hostile=body("hostile",1,0,2);
        var terrain=SceneWeaponsTest.openTerrain();
        var content=new SceneContent(List.of(giant,ally,hostile),Map.of());
        var world=new SceneEntities(content,terrain);var fired=new ArrayList<ActorState>();world.attacks((actor,fire)->fired.add(actor));
        var pawn=new ActorState(219,1001,"Fixture",0,1,0,4,0,0,0,new StarterCatalog.Model("Player",Map.of()));
        world.advance(1000,List.of(pawn));world.advance(1050,List.of(pawn));
        check(fired.size()==1&&fired.getFirst().rotation()==0,"Giant can target a nearer friendly knight NPC, excluding its hostile peer");
        world.damage(damage(1),0,0,0,1100,219);
        for(int stamp=1100;stamp<=3300;stamp+=50)world.advance(stamp,List.of(pawn));
        check(fired.stream().anyMatch(a->Math.abs(a.rotation()-Math.PI/2)<.01),"actual player damage admits player threat and can switch the Giant from an NPC");
        var isolated=new SceneEntities(new SceneContent(List.of(giant,hostile),Map.of()),terrain);
        isolated.advance(1000,List.of());isolated.damage(damage(1),0,0,0,1050,219);isolated.advance(3100,List.of());
        check((isolated.actors().getFirst().flags()&16)==0,"absent/cross-instance player ID and hostile peer cannot become a target");
        var playersOnly=new SceneEntities(new SceneContent(List.of(giant,hostile),Map.of()),terrain);
        playersOnly.advance(1000,List.of(pawn));playersOnly.advance(1050,List.of(pawn));
        check((playersOnly.actors().getFirst().flags()&16)!=0,"mixed selector includes the authenticated scene player");
        playersOnly.playerActive(id->false);playersOnly.advance(1100,List.of(pawn));
        check((playersOnly.actors().getFirst().flags()&16)==0,"departed player is released immediately");
        var weapons=new SceneWeapons(terrain);weapons.targets(world);
        var marker=new FireCatalog.ActorSpec("ACTOR",new StarterCatalog.Model("Fixture/InertWarning",Map.of()),0,0,-1,0,0,0,0,null,null,0,null);
        var spawn=new FireCatalog.Action(FireCatalog.Kind.EMIT_PROJECTILE,null,marker,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(),"");
        var boss=world.actors().getFirst();check(weapons.fire(spawn,boss,3350),"native inert helper is actually created");
        weapons.advance(3400,List.of(pawn));check(weapons.actors().size()==1&&weapons.actors().getFirst().projectile().destroyed()==Integer.MAX_VALUE,"zero-fuse inert helper retains source-owned lifetime");
        world.damage(damage(200),0,0,0,3450,219);world.advance(3500,List.of(pawn));weapons.advance(3500,List.of(pawn));
        check(weapons.actors().getFirst().projectile().destroyed()==3500,"real source death cancels its inert helper");
        weapons.advance(3600,List.of(pawn));check(weapons.actors().isEmpty(),"helper removal is published then reclaimed");
        System.out.println("PASS: provisional Giant player/NPC selection, damage threat, hostile/cross-scene exclusion, departure and native helper ownership/death cleanup (synthetic terrain)");
    }
    private static SceneContent.Entry body(String key,float x,float y,int mask){return new SceneContent.Entry(key,x,y,0,new SceneContent.Spec(Map.of("native-type","com.threerings.projectx.dungeon.data.actor.Monster","config","Fixture/"+key,"health","100","radius",".4","damage-mask",Integer.toString(mask))));}
    private static StarterCatalog.Model damage(float amount){return new StarterCatalog.Model("DamageRegion",Map.of("shape",new FireCatalog.DataValue("Shape$Circle",Map.of("radius",1f)),"damage",new FireCatalog.DataValue("Damage$Normal",Map.of("flags",2,"amount",amount))));}
}
