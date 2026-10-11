package org.tortodrone.world;

import org.tortodrone.character.StarterCatalog;

/** Native ephemeral actor state; ownership never grants inventory or player mutations. */
public record ProjectileState(String nativeType,StarterCatalog.Model config,int ownerOid,
        int created,int destroyed,float originX,float originY,float z,float speed,float maxStep,
        int faction,boolean fizzle) { }
