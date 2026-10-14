package org.tortodrone.world;

/** An instance-owned native party notification; player amounts use the knight HUD encoding. */
public record SceneCombatEvent(long sequence,int timestamp,int actor,int amount,int source,String performance) { }
