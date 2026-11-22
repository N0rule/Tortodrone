package org.tortodrone.world;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import org.tortodrone.core.character.KnightMemory;
import org.tortodrone.core.character.KnightObjective;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.world.KnightCombat;
import org.tortodrone.core.world.SceneActions;
import org.tortodrone.core.world.SceneCombatEvent;
import org.tortodrone.core.world.SceneContent;
import org.tortodrone.core.world.SceneConversation;

/** Explicit contract between the entity model and the simulation that owns it.
 *
 *  Every callback defaults to the same no-op or refusal the entity model used before the
 *  split, so isolated tests can override one seam. None of these callbacks may perform
 *  network or storage work: they run under the scene monitor on the simulation thread. */
public final class SceneCallbacks {
    @FunctionalInterface public interface PawnStatusSink { boolean apply(int actor,SceneActions.PawnStatus operation,int stamp); }
    @FunctionalInterface public interface ConversationSink { boolean start(SceneConversation conversation,ActorState source,int stamp,int target); }
    @FunctionalInterface public interface DialogClearSink {boolean clear(int target,int stamp);}
    @FunctionalInterface public interface EffectSink { void fire(StarterCatalog.Model config,ActorState source,int stamp); }
    /** Simulation-only request sink. Never perform storage or network work in this callback. */
    @FunctionalInterface public interface FloorAdvanceSink { boolean request(ActorState player,ActorState source,int stamp); }
    @FunctionalInterface public interface MemorySink {boolean apply(ActorState player,KnightMemory operation,ActorState source,int stamp);}
    @FunctionalInterface public interface ObjectiveSink {boolean apply(ActorState player,KnightObjective operation,ActorState source,int stamp);}

    private Consumer<SceneEntities.PlayerHit> playerDamage=hit->{};
    private Consumer<SceneCombatEvent> feedback=event->{};
    private BiPredicate<Integer,Integer> playerHeal=(actor,amount)->false;
    private IntPredicate playerActive=id->true;
    private BiPredicate<Integer,SceneContent.Spec> pickupAllowed=(id,spec)->true;
    private IntPredicate playerTargetable=id->true;
    private BiConsumer<Integer,KnightCombat.Impulse> playerImpulse=(id,impulse)->{};
    private BiConsumer<ActorState,FireCatalog.Action> attack=(actor,action)->{};
    private PawnStatusSink pawnStatus=(actor,operation,stamp)->false;
    private ConversationSink conversationSink=(conversation,source,stamp,target)->false;
    private DialogClearSink dialogClearSink=(target,stamp)->false;
    private EffectSink effectSink=(config,source,stamp)->{};
    private FloorAdvanceSink floorAdvanceSink=(player,source,stamp)->false;
    private MemorySink memorySink=(player,operation,source,stamp)->false;
    private ObjectiveSink objectiveSink=(player,operation,source,stamp)->false;

    public void playerDamage(BiConsumer<Integer,Object> damage){Objects.requireNonNull(damage);playerDamage=hit->damage.accept(hit.actor(),hit.damage());}
    public void playerHits(Consumer<SceneEntities.PlayerHit> damage){playerDamage=Objects.requireNonNull(damage);}
    public void feedback(Consumer<SceneCombatEvent> value){feedback=Objects.requireNonNull(value);}
    public void playerHeal(BiPredicate<Integer,Integer> heal){playerHeal=Objects.requireNonNull(heal);}
    public void playerActive(IntPredicate predicate){playerActive=Objects.requireNonNull(predicate);}
    public void pickupEligible(BiPredicate<Integer,SceneContent.Spec> predicate){pickupAllowed=Objects.requireNonNull(predicate);}
    public void playerTargetable(IntPredicate predicate){playerTargetable=Objects.requireNonNull(predicate);}
    public void playerImpulse(BiConsumer<Integer,KnightCombat.Impulse> callback){playerImpulse=Objects.requireNonNull(callback);}
    public void attacks(BiConsumer<ActorState,FireCatalog.Action> fire){attack=Objects.requireNonNull(fire);}
    public void pawnStatuses(PawnStatusSink sink){pawnStatus=Objects.requireNonNull(sink);}
    public void conversations(ConversationSink sink){conversationSink=Objects.requireNonNull(sink);}
    public void dialogClears(DialogClearSink sink){dialogClearSink=Objects.requireNonNull(sink);}
    public void effects(EffectSink sink){effectSink=Objects.requireNonNull(sink);}
    public void floorAdvances(FloorAdvanceSink sink){floorAdvanceSink=Objects.requireNonNull(sink);}
    public void memories(MemorySink sink){memorySink=Objects.requireNonNull(sink);}
    public void objectives(ObjectiveSink sink){objectiveSink=Objects.requireNonNull(sink);}

    void damagePlayer(SceneEntities.PlayerHit hit){playerDamage.accept(hit);}
    void combatEvent(SceneCombatEvent event){feedback.accept(event);}
    boolean healPlayer(int id,int amount){return playerHeal.test(id,amount);}
    boolean activePlayer(int id){return playerActive.test(id);}
    boolean pickupEligible(int id,SceneContent.Spec spec){return pickupAllowed.test(id,spec);}
    boolean targetablePlayer(int id){return playerActive.test(id)&&playerTargetable.test(id);}
    void impulsePlayer(int actor,KnightCombat.Impulse impulse){playerImpulse.accept(actor,impulse);}
    void fireAttack(ActorState actor,FireCatalog.Action action){attack.accept(actor,action);}
    boolean applyPawnStatus(int actor,SceneActions.PawnStatus operation,int stamp){return pawnStatus.apply(actor,operation,stamp);}
    boolean startConversation(SceneConversation conversation,ActorState source,int stamp,int target){return conversationSink.start(conversation,source,stamp,target);}
    boolean clearDialog(int target,int stamp){return dialogClearSink.clear(target,stamp);}
    void fireEffect(StarterCatalog.Model config,ActorState source,int stamp){effectSink.fire(config,source,stamp);}
    boolean requestFloorAdvance(ActorState player,ActorState source,int stamp){return floorAdvanceSink.request(player,source,stamp);}
    boolean applyMemory(ActorState player,KnightMemory operation,ActorState source,int stamp){return memorySink.apply(player,operation,source,stamp);}
    boolean applyObjective(ActorState player,KnightObjective operation,ActorState source,int stamp){return objectiveSink.apply(player,operation,source,stamp);}
}
