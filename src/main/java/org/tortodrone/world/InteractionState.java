package org.tortodrone.world;

import org.tortodrone.character.StarterCatalog;

/** Native knight interaction prompt identifying one authoritative scene actor. */
public record InteractionState(int actorId,StarterCatalog.Model description,String action) {
    public InteractionState(int actorId,StarterCatalog.Model description){this(actorId,description,"Interact");}
}
