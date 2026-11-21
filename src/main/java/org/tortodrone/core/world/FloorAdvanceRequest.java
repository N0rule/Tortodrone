package org.tortodrone.core.world;

/** A native floor-completion request as the simulation reports it to route owners. */
public record FloorAdvanceRequest(long sequence,ActorState source,int timestamp) { }
