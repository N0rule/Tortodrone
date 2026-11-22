package org.tortodrone.world;

import java.util.*;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.world.SceneContent;
import org.tortodrone.core.world.SceneDamage;

/** Served pot behavior: the smash region and each variant's bounded ground hazard. The
 *  entity model owns insertion and damage; this system only decides what a landed pot
 *  does and where its hazard patches fit. */
final class PotSystem {
    private final SceneEntities scene;

    PotSystem(SceneEntities scene) {
        this.scene = scene;
    }

    /** Served {@code Dynamic/Lift Objects} pot arguments, condensed to what the land handler
     *  executes: {@code Pot (Base)} {@code land} runs DamageRegionFromPoint with damage
     *  [Status(Ailment), DepthScale PC/Damage/Base/Default (flags 2)] and region expansion
     *  = Blast Radius, then the per-variant Extra Action spawns ground hazards (Fire Pot one
     *  ScorcherTorch, Damage {flags 2, Fire 1.0/100, ULTRA/MODERATE}, toggled by its Lifetime
     *  argument's served choice to a 6 Second cleanup fuse while the streamed Lifetime argument
     *  renders the flame for its authored 4 seconds; Oil Pot nine Oil Slick patches).
     *  Damage stops at 4 seconds conservatively: stripped handler conditions do not establish
     *  the native damage cutoff. Cleanup must never extend the active damage region.
     *  Pots without an entry below stay inert. */
    record Pot(float blast,StarterCatalog.Model ailment,Object hazardDamage,String hazardConfig,float hazardRadius,int hazardLifetime) { }
    /** The capture separates the pot smash ailment from the torch hazard. The direct
     *  torch carries Fire with Power 100; the smash keeps the previously authored 80. */
    private static StarterCatalog.Model fireStatus(int power){
        return new StarterCatalog.Model("Fire",Map.of(
            "Chance",1f,"Power",power,
            "Status Chance",new FireCatalog.EnumValue(org.tortodrone.core.WireTypes.STATUS_CHANCE,"ULTRA",5),
            "Status Power",new FireCatalog.EnumValue(org.tortodrone.core.WireTypes.STATUS_POWER,"MODERATE",2)));
    }
    private static final Map<String,Pot> POTS=Map.of(
        "Dynamic/Lift Objects/Clay Pot",new Pot(.5f,null,null,null,0,0),
        "Dynamic/Lift Objects/Fire Pot",new Pot(1f,fireStatus(80),
            new FireCatalog.DataValue(org.tortodrone.core.WireTypes.DAMAGE_STATUS,Map.of(
                "flags",2,"statusCondition",fireStatus(100))),
            "Bomb/Monster/ScorcherTorch",1f,6000),
        "Dynamic/Lift Objects/Oil Pot",new Pot(1f,null,null,"Bomb/Monster/Oil Slick",.45f,15000));

    /** Native {@code Pot (Base)} land handler: smash region, then per-variant Extra Action. */
    void land(SceneEntity e,int stamp){
        var pot=POTS.get(e.spec.config().name());if(pot==null)return;
        var damages=new ArrayList<Object>();
        if(pot.ailment()!=null)damages.add(new FireCatalog.DataValue(org.tortodrone.core.WireTypes.DAMAGE_STATUS,
            Map.of("flags",2,"statusCondition",pot.ailment())));
        damages.add(new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$DepthScale",Map.of(
            "depthScale",new StarterCatalog.Model("PC/Damage/Base/Default",Map.of()),
            "type",new FireCatalog.EnumValue("com.threerings.projectx.dungeon.util.DamageSummary$DamageType","NORMAL",0),"flags",2)));
        var args=new LinkedHashMap<String,Object>(Map.of("damage",new FireCatalog.DataValue(
                "com.threerings.projectx.dungeon.config.DamageConfig$Compound",Map.of("damages",
                    new FireCatalog.ListValue("com.threerings.projectx.dungeon.config.DamageConfig",damages))),
            "region",new FireCatalog.DataValue(org.tortodrone.core.WireTypes.REGION_DEFAULT,Map.of(
                "location",new FireCatalog.DataValue("com.threerings.tudey.config.TargetConfig$Source",List.of()),"expansion",pot.blast()))));
        var prepared=e.spec.model("source-shape");
        if(prepared!=null)args.put("shape",prepared.arguments().get("value"));
        scene.damageRegion(new StarterCatalog.Model("DamageRegionFromPoint",args),e.x,e.y,e.rotation,stamp,e.id);
        if(pot.hazardConfig()!=null)ignite(pot,e.x,e.y,stamp,e.owner,e.id);
    }
    /** Extra Action ground hazard: a native Bomb patch whose hazard applies native status
     *  damage on the Floor trap cadence (hazard-refractory 500), expiring at its Lifetime. */
    private void ignite(Pot pot,float x,float y,int stamp,int owner,int source){
        if(pot.hazardDamage()==null){
            for(int i=0;i<9;i++){
                float[] position=oilSpawn(x,y,pot.hazardRadius(),source);
                if(position!=null)scene.hazard(pot.hazardConfig(),null,pot.hazardRadius(),pot.hazardLifetime(),Map.of(),position[0],position[1],stamp).owner=owner;
            }
            return;
        }
        // The Fire Pot spawns its torch with served arguments: the Lifetime argument selects
        // the "4 Seconds" choice, whose Creation Life renders the client flame for 4 seconds.
        scene.hazard(pot.hazardConfig(),pot.hazardDamage(),pot.hazardRadius(),pot.hazardLifetime(),
            Map.of("config.argument.Lifetime","string|4 Seconds","hazard-active-lifetime","4000"),x,y,stamp).owner=owner;
    }
    /** Clyde's bounded perimeter search, using the slick diameter plus its .01 clearance.
     * Unlike an unadjusted SpawnActor, each patch must occupy a separate supported pose. */
    private float[] oilSpawn(float x,float y,float radius,int source){
        if(oilSpawnClear(x,y,radius,source))return new float[]{x,y};
        float step=radius*2+.01f;
        for(int distance=1;distance<=4;distance++){
            for(int dx=-distance;dx<=distance;dx++)if(oilSpawnClear(x+dx*step,y-distance*step,radius,source))return new float[]{x+dx*step,y-distance*step};
            for(int dy=1-distance;dy<distance;dy++)if(oilSpawnClear(x+distance*step,y+dy*step,radius,source))return new float[]{x+distance*step,y+dy*step};
            for(int dx=distance;dx>=-distance;dx--)if(oilSpawnClear(x+dx*step,y+distance*step,radius,source))return new float[]{x+dx*step,y+distance*step};
            for(int dy=distance-1;dy>-distance;dy--)if(oilSpawnClear(x-distance*step,y+dy*step,radius,source))return new float[]{x-distance*step,y+dy*step};
        }
        return null;
    }
    private boolean oilSpawnClear(float x,float y,float radius,int source){
        var circle=new FireCatalog.DataValue(org.tortodrone.core.WireTypes.SHAPE_CIRCLE,Map.of("radius",radius));
        if(scene.staticCollision().isEmpty()?!scene.terrain().walkable(x,y):!scene.staticCollision().floorSupported(x,y)||!scene.staticCollision().clear(3,circle,x,y,0,scene.disabledCollisionSources()))return false;
        for(var e:scene.entityValues())if(e.id!=source&&e.destroyed==Integer.MAX_VALUE&&e.carrier==0
                &&(e.config.name().equals("Bomb/Monster/Oil Slick")||(e.collisionFlags&3)!=0)
                &&e.spec.contains(e.x,e.y,e.rotation,x,y,radius))return false;
        return true;
    }
    void lightOil(SceneEntity oil,int stamp){
        if(oil.ignited)return;
        oil.ignited=true;
        // Capture: oil-ignited torches use Fire (Scaled), flags 1, Chance 0.0 / Power 0.
        var fire=new StarterCatalog.Model("Fire (Scaled)",Map.of("Chance",0f,"Power",0));
        var damage=new FireCatalog.DataValue(org.tortodrone.core.WireTypes.DAMAGE_STATUS,Map.of("statusCondition",fire,"flags",1));
        scene.hazard("Bomb/Monster/ScorcherTorch",damage,1f,4000,
            Map.of("config.argument.Lifetime","string|2 Seconds","config.argument.Nature","string|Neutral","hazard-active-lifetime","2000"),oil.x,oil.y,stamp).owner=oil.owner;
    }
    boolean lightsOil(Object damage){
        if(SceneDamage.field(damage,"statusCondition") instanceof StarterCatalog.Model status){
            var spec=scene.content().statuses().get(status.name());
            if(status.name().equals("Fire")||status.name().equals("Fire (Scaled)")||spec!=null&&spec.text("class","").equals("Fire"))return true;
        }
        if(SceneDamage.field(damage,"damages") instanceof FireCatalog.ListValue parts)for(var part:parts.values())if(lightsOil(part))return true;
        return false;
    }

}
