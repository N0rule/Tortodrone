package org.tortodrone.world;

import java.util.HashMap;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.world.GearStats;
import org.tortodrone.world.DiskgunPolicy.PiercePolicy;

/** Resolved origin of a fire action or projectile: world pose, owning knight and the
 *  client prediction oid the effect is attributed to. */
final class Pose {
    final float x,y,rotation;
    final int ownerId,ownerOid,actorId;
    final Object shape,sceneSource;
    final GearStats.Damage damage;
    final PiercePolicy pierce;

    Pose(float x,float y,float rotation,int ownerId,int ownerOid,Object shape,int actorId,GearStats.Damage damage,Object sceneSource,PiercePolicy pierce){
        this.x=x;this.y=y;this.rotation=rotation;this.ownerId=ownerId;this.ownerOid=ownerOid;this.shape=shape;this.actorId=actorId;this.damage=damage;this.sceneSource=sceneSource;this.pierce=pierce;
    }
    Pose(float x,float y,float rotation,int ownerId,int ownerOid,Object shape,int actorId,GearStats.Damage damage,Object sceneSource){this(x,y,rotation,ownerId,ownerOid,shape,actorId,damage,sceneSource,PiercePolicy.NONE);}
    Pose(float x,float y,float rotation,int ownerId,int ownerOid,Object shape,int actorId,GearStats.Damage damage){this(x,y,rotation,ownerId,ownerOid,shape,actorId,damage,null);}
    Pose(float x,float y,float rotation,int ownerId,int ownerOid,Object shape,int actorId){this(x,y,rotation,ownerId,ownerOid,shape,actorId,GearStats.Damage.NONE);}
    Pose(float x,float y,float rotation,int ownerId,int ownerOid,Object shape){this(x,y,rotation,ownerId,ownerOid,shape,ownerId);}
    Pose(float x,float y,float rotation,int ownerId,int ownerOid){this(x,y,rotation,ownerId,ownerOid,null);}

    org.tortodrone.core.character.StarterCatalog.Model region(org.tortodrone.core.character.StarterCatalog.Model model){
        if(damage==GearStats.Damage.NONE)return model;
        var args=new HashMap<>(model.arguments());args.put("equipment-damage",damage);return new org.tortodrone.core.character.StarterCatalog.Model(model.name(),args);
    }
    org.tortodrone.core.character.StarterCatalog.Model region(FireCatalog.Action action){
        var model=region(action.config());
        // Older prepared ShieldBash sweeps stored the sensor's damage mask here.
        // The native DamageTargetFromPoint uses the ordinary 1|128 occlusion mask.
        int mask=model.name().equals("ShieldBash")?129:action.collisionMask();
        if(mask==0)return model;
        var args=new HashMap<>(model.arguments());args.put("collision-mask",mask);
        return new org.tortodrone.core.character.StarterCatalog.Model(model.name(),args);
    }
}
