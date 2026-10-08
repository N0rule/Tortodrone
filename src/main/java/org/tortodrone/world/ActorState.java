package org.tortodrone.world;
import org.tortodrone.character.StarterCatalog;
/** Immutable downstream actor snapshot; client positions are never accepted. */
public record ActorState(int id,int clientOid,String name,int color,int created,float x,float y,float rotation,float direction,int flags,int activity,int activityStarted,StarterCatalog.Model config,boolean gameMaster) {
    public ActorState(int id,int clientOid,String name,int color,int created,float x,float y,
            float rotation,float direction,int flags,int activity,int activityStarted,StarterCatalog.Model config) {
        this(id,clientOid,name,color,created,x,y,rotation,direction,flags,activity,activityStarted,config,false);
    }
    public ActorState(int id,int clientOid,String name,int color,int created,float x,float y,
            float rotation,float direction,int flags,StarterCatalog.Model config) {
        this(id,clientOid,name,color,created,x,y,rotation,direction,flags,0,created,config);
    }
}
