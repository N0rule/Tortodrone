package org.tortodrone.world;

import org.tortodrone.character.StarterCatalog;

/** A native cosmetic scene effect emitted by verified stock actions. */
public record SceneEffect(long sequence,StarterCatalog.Model config,int timestamp,float x,float y,float rotation,int ownerOid) { }
