package org.tortodrone.world;

import org.tortodrone.character.StarterCatalog;

/** Native ephemeral actor state; ownership never grants inventory or player mutations. */
public record ProjectileState(String nativeType,StarterCatalog.Model config,int ownerOid,
        int created,int destroyed,float originX,float originY,float z,float speed,float maxStep,
        int faction,boolean fizzle,Orbit orbit) {
    /** Native Bullet/Bomb sentinel: the client resolves floor Z, then adds config height. */
    public static final float UNSPECIFIED_Z = Float.NEGATIVE_INFINITY;
    public record Orbit(float radius,int followId,float relativeX,float relativeY,int collided,float originalRotation,int homeTargetId) { }
    public ProjectileState(String nativeType,StarterCatalog.Model config,int ownerOid,
            int created,int destroyed,float originX,float originY,float z,float speed,float maxStep,int faction,boolean fizzle) {
        this(nativeType,config,ownerOid,created,destroyed,originX,originY,z,speed,maxStep,faction,fizzle,null);
    }
    public String nativeClass() {
        return switch(nativeType) {
            case "BULLET" -> "Bullet";
            case "BOMB" -> "Bomb";
            case "LASER" -> "Laser";
            case "ORBITAL" -> "Orbital";
            case "ACTOR" -> "Actor";
            case "ORBITAL_BULLET" -> "OrbitalBullet";
            case "COMPOUND_ORBITAL_BULLET" -> "CompoundOrbitalBullet";
            default -> throw new IllegalArgumentException("Unsupported projectile actor: "+nativeType);
        };
    }
}
