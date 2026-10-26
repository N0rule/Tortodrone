package org.tortodrone.world;

import org.tortodrone.character.StarterCatalog;

/** A native cosmetic scene effect emitted by verified stock actions. */
public record SceneEffect(long sequence,StarterCatalog.Model config,int timestamp,float x,float y,float rotation,int ownerOid,int targetActor,Integer targetEntry) {
    public SceneEffect(long sequence,StarterCatalog.Model config,int timestamp,float x,float y,float rotation,int ownerOid,int targetActor){this(sequence,config,timestamp,x,y,rotation,ownerOid,targetActor,null);}
    public SceneEffect(long sequence,StarterCatalog.Model config,int timestamp,float x,float y,float rotation,int ownerOid){this(sequence,config,timestamp,x,y,rotation,ownerOid,0);}
    public SceneEffect { if(targetEntry!=null&&(targetEntry<1||targetActor!=0))throw new IllegalArgumentException("Invalid effect entry identity"); }
}
