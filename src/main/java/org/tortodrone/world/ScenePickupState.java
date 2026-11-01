package org.tortodrone.world;

/** 2026 shared Pickup.activations = {player object OID, activation timestamp}. */
public record ScenePickupState(int playerOid,int activated) {
    public ScenePickupState {if(playerOid<=0||activated==0)throw new IllegalArgumentException("Invalid native pickup activation");}
}
