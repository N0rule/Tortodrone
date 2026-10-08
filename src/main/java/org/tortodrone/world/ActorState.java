package org.tortodrone.world;
import org.tortodrone.character.StarterCatalog;
/** Immutable downstream actor snapshot; client positions are never accepted. */
public record ActorState(int id,int clientOid,String name,int color,int created,float x,float y,float rotation,float direction,int flags,StarterCatalog.Model config) { }
