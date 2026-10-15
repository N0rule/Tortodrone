package org.tortodrone.world;
import org.tortodrone.character.StarterCatalog;
/** Immutable downstream actor snapshot; client positions are never accepted. */
public record ActorState(int id,int clientOid,String name,int color,int created,float x,float y,float rotation,float direction,int flags,int activity,int activityStarted,StarterCatalog.Model config,boolean gameMaster,KnightCombat.State combat,ProjectileState projectile,SceneEntityState entity,InteractionState interaction,SceneKnightState sceneKnight,ScenePetState pet) {
    public ActorState(int id,int clientOid,String name,int color,int created,float x,float y,float rotation,float direction,int flags,int activity,int activityStarted,StarterCatalog.Model config,boolean gameMaster,KnightCombat.State combat,ProjectileState projectile,SceneEntityState entity,InteractionState interaction,SceneKnightState sceneKnight) {
        this(id,clientOid,name,color,created,x,y,rotation,direction,flags,activity,activityStarted,config,gameMaster,combat,projectile,entity,interaction,sceneKnight,null);
    }
    public ActorState(int id,int clientOid,String name,int color,int created,float x,float y,float rotation,float direction,int flags,int activity,int activityStarted,StarterCatalog.Model config,boolean gameMaster,KnightCombat.State combat,ProjectileState projectile,SceneEntityState entity,InteractionState interaction) {
        this(id,clientOid,name,color,created,x,y,rotation,direction,flags,activity,activityStarted,config,gameMaster,combat,projectile,entity,interaction,null);
    }
    public ActorState(int id,int clientOid,String name,int color,int created,float x,float y,float rotation,float direction,int flags,int activity,int activityStarted,StarterCatalog.Model config,boolean gameMaster,KnightCombat.State combat,ProjectileState projectile,SceneEntityState entity) {
        this(id,clientOid,name,color,created,x,y,rotation,direction,flags,activity,activityStarted,config,gameMaster,combat,projectile,entity,null);
    }
    public ActorState(int id,int clientOid,String name,int color,int created,float x,float y,float rotation,float direction,int flags,int activity,int activityStarted,StarterCatalog.Model config,boolean gameMaster,KnightCombat.State combat,ProjectileState projectile) {
        this(id,clientOid,name,color,created,x,y,rotation,direction,flags,activity,activityStarted,config,gameMaster,combat,projectile,null);
    }
    public boolean player(){return projectile==null&&entity==null&&pet==null;}
    public ActorState(int id,int clientOid,String name,int color,int created,float x,float y,float rotation,float direction,int flags,int activity,int activityStarted,StarterCatalog.Model config,boolean gameMaster,KnightCombat.State combat) {
        this(id,clientOid,name,color,created,x,y,rotation,direction,flags,activity,activityStarted,config,gameMaster,combat,null);
    }
    public ActorState(int id,int clientOid,String name,int color,int created,float x,float y,float rotation,float direction,int flags,int activity,int activityStarted,StarterCatalog.Model config,boolean gameMaster) {
        this(id,clientOid,name,color,created,x,y,rotation,direction,flags,activity,activityStarted,config,gameMaster,null);
    }
    public ActorState(int id,int clientOid,String name,int color,int created,float x,float y,
            float rotation,float direction,int flags,int activity,int activityStarted,StarterCatalog.Model config) {
        this(id,clientOid,name,color,created,x,y,rotation,direction,flags,activity,activityStarted,config,false);
    }
    public ActorState(int id,int clientOid,String name,int color,int created,float x,float y,
            float rotation,float direction,int flags,StarterCatalog.Model config) {
        this(id,clientOid,name,color,created,x,y,rotation,direction,flags,0,created,config);
    }
}
