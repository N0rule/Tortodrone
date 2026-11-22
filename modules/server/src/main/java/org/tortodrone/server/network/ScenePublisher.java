package org.tortodrone.server.network;

import java.io.IOException;
import java.util.*;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.core.character.OpeningMission;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.core.world.ScenePortals;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.world.SceneSimulation;

/** Owns the per-scene publication pipeline for one connection: snapshot publication, deferred
 *  arrival comlinks and interactions, capsule pickups and portal entry. The session still owns
 *  the scene, its subscription generation and the delivery worker; this handler reaches that
 *  state through narrow package-private accessors and writes only through the session output. */
final class ScenePublisher {
    private final WorldSession session;

    ScenePublisher(WorldSession session) {
        this.session = session;
    }

    private void collectPickups()throws IOException {
        var scene=session.currentScene();
        for(var pickup:session.membership().takePickups()){
            boolean success=false;
            try{
                if(pickup.heat()>0){success=session.membership().collectHeat(pickup.entityId());continue;}
                var before=session.visibleProfileNow();
                if(pickup.temporary()){
                    if(pickup.equippedSlot()!=null){
                        var existing=before.items().stream().filter(item->pickup.equippedSlot().equals(item.equippedSlot())).findFirst().orElse(null);
                        var stats=scene.map().content().stats();
                        if(existing!=null&&stats.getOrDefault("gear."+existing.config()+".health-bonus",0f)>=stats.getOrDefault("gear."+pickup.item()+".health-bonus",0f))throw new IOException("A better vitapod is equipped");
                        if(existing!=null&&existing.id()>0)throw new IOException("That equipment slot is occupied");
                        if(existing!=null)session.temporaryItems().remove(existing.id());
                        long id=session.nextTemporaryItemId();session.temporaryItems().put(id,new WorldProfile.Item(id,WorldProfile.Slot.OTHER,pickup.item(),null,0,pickup.equippedSlot(),false,false,false,1,false));
                        session.updateAppearance();
                    }else {
                    var existing=session.temporaryItems().values().stream().filter(item->item.config().equals(pickup.item())).findFirst().orElse(null);
                    // One carried stack per consumable, three uses per stack, four carried types.
                    // Refusal leaves the shared pickup available to another knight.
                    if(existing!=null&&existing.count()>=3||existing==null&&session.temporaryItems().values().stream().filter(item->item.equippedSlot()==null).count()>=session.quickbarLength())
                        throw new IOException("Consumable capacity reached");
                    long id=existing==null?session.nextTemporaryItemId():existing.id();
                    int count=existing==null?1:existing.count()+1;
                    session.temporaryItems().put(id,new WorldProfile.Item(id,WorldProfile.Slot.OTHER,pickup.item(),null,0,null,false,false,false,count,false));
                    if(!session.quickbarContains(id))session.addToQuickbar(id);
                    }
                }else{
                    var reward=session.charactersStore().services().town(session.sceneRegistry().town()).pickup(session.charactersStore().accountId(),session.selectedKnight().id(),session.authRevision(),session.membership().pickupReceipt(pickup.entityId()),pickup.item());
                    // A notification failure must not reopen a durably awarded actor.
                    success=true;
                    session.townChanged(reward,false);
                    continue;
                }
                var after=session.visibleProfileNow();session.output().send(()->session.codec().itemChanges(session.connectionOid(),session.selectedKnight(),before,after));session.publishQuickbar();success=true;
            }catch(IOException failure){session.sessionLog().debug("Scene pickup refused: "+failure.getMessage());}
            finally{session.membership().completePickup(pickup.entityId(),success);}
        }
    }


    void publish(SessionPublications.Publication publication)throws IOException { synchronized(session){
        var scene=session.currentScene();
        // Optional scene work is skipped before encoding when this reader is behind its byte
        // budget; the next publication re-encodes from the last snapshot actually sent.
        if(session.output().optionalOverBudget())return;
        var current=publication.snapshot();int stamp=current.timestamp();
        if(session.isClosed()||!session.sceneSubscribed()||!session.sceneEntered()||scene!=publication.scene()||session.sceneGenerationValue()!=publication.generation()||stamp-session.lastStampValue()<=0)return;
        boolean interior=!scene.map().town();
        var encoded=current.encoded(session.lastSnapshotValue(),interior,
            (frameDelta,frameEffects,frameCombat,frameInterior)->SessionCodec.sceneDeltaTemplate(session.codec().catalog(),
                scene.oid(),stamp,frameDelta.added(),frameDelta.updated(),frameDelta.removed(),
                frameDelta.baseline(),frameInterior,frameEffects,frameCombat));
        var delta=encoded.delta();
        // Subscription already announces occupants; the first reliable delta has no baseline.
        var changedRoles=session.lastSnapshotValue()==null?current.actors().stream().filter(a->session.occupantRolesValue().containsKey(a.id())&&session.occupantRolesValue().get(a.id())!=a.gameMaster()).toList():delta.changedRoles();
        if(!changedRoles.isEmpty())session.output().send(()->session.codec().occupantRoles(scene.oid(),changedRoles));
        var clients=current.clientOids();
        if(!clients.equals(session.occupantClientsValue())){
            session.output().send(()->session.codec().occupantChanges(scene.oid(),session.occupantClientsValue(),current.players()));
            session.setOccupantClients(clients);
        }
        session.setOccupantRoles(current.roles());
        session.publishVisited(scene.visited());
        short elapsed=(short)Math.max(1,Math.min(Short.MAX_VALUE,stamp-session.lastStampValue()));
        var frame=encoded.frame();
        // The first frame for a connection registers the symbols; later ones reuse the shared body.
        // This is an optional update: when the session is already over budget it is skipped before
        // encoding, so no unseen symbol mapping can be introduced.
        if(!session.output().sendOptional(()->{
            var shared=session.codec().materialize(frame,session.connectionOid(),session.acknowledgeValue(),session.pingValue(),session.referenceValue(),elapsed);
            return shared!=null?shared:session.codec().sceneDelta(session.connectionOid(),scene.oid(),session.acknowledgeValue(),session.pingValue(),session.referenceValue(),stamp,elapsed,
                delta.added(),delta.updated(),delta.removed(),delta.baseline(),interior,encoded.effects(),encoded.combat());
        }))return;
        var currentOwn=current.player(session.connectionOid());
        var health=currentOwn==null?null:current.health(currentOwn.id());
        var previousOwn=session.lastSnapshotValue()==null?null:session.lastSnapshotValue().actor(session.currentProfile().worldId());
        Set<Long> previousCurse=previousOwn==null||previousOwn.sceneKnight()==null?Set.of():previousOwn.sceneKnight().status().cursedItems();
        Set<Long> currentCurse=currentOwn==null||currentOwn.sceneKnight()==null?Set.of():currentOwn.sceneKnight().status().cursedItems();
        if(!previousCurse.equals(currentCurse))session.output().send(()->session.codec().itemChanges(session.connectionOid(),session.selectedKnight(),session.visibleProfile(previousCurse),session.visibleProfile(currentCurse)));
        if(health!=null&&currentOwn.sceneKnight()!=null){
            var status=currentOwn.sceneKnight().status();
            if(!health.equals(session.publishedHealthValue())||!status.equals(session.publishedStatusValue())){
                session.publishedHealthStatus(health,status);
            }
            session.publishPartyState();
        }
        session.recordPublished(stamp,current);
        var story=session.membership().takeStory();
        if(!story.memories().isEmpty()||!story.objectives().isEmpty()){
            session.reloadProfile();
            var result=session.charactersStore().knightStory(session.selectedKnight().id(),session.authRevision(),session.currentProfile(),story.memories().stream().map(SceneSimulation.MemoryRequest::operation).toList(),story.objectives().stream().map(SceneSimulation.ObjectiveRequest::operation).toList());
            session.applyProfile(result.profile());
            if(!session.memoriesValue().equals(result.memories())){var before=session.memoriesValue();session.setMemories(result.memories());session.output().send(()->session.codec().knightMemoryChanges(session.connectionOid(),before,session.memoriesValue()));}
            if(!session.objectivesValue().equals(result.objectives())){session.setObjectives(result.objectives());session.publishObjectives();}
        }
        collectPickups();
        var capsuleCounters=new TreeMap<String,Integer>();
        for(long id:session.membership().takeUsedItems()){
            var item=session.temporaryItems().get(id);if(item==null)continue;
            var used=scene.map().content().items().get(item.config());
            if(used!=null&&!used.text("stat","").isBlank())
                capsuleCounters.merge(used.text("stat",""),Math.max(1,used.integer("stat-delta",1)),Integer::sum);
            var before=session.visibleProfileNow();
            if(item.count()==1)session.temporaryItems().remove(id);else session.temporaryItems().put(id,new WorldProfile.Item(id,item.slot(),item.config(),item.accessory(),item.heat(),item.equippedSlot(),item.bound(),item.bindOnEquip(),item.starter(),item.count()-1,item.permanent()));
            var after=session.visibleProfileNow();session.output().send(()->session.codec().itemChanges(session.connectionOid(),session.selectedKnight(),before,after));
            session.publishQuickbar();
        }
        if(!capsuleCounters.isEmpty()){
            var before=session.visibleProfileNow();
            try{
                var result=session.charactersStore().services().town(session.sceneRegistry().town()).stats(session.charactersStore().accountId(),session.selectedKnight().id(),session.authRevision(),capsuleCounters);
                session.applyProfile(result.profile());
                var changes=session.codec().achievementChanges(session.connectionOid(),before,session.visibleProfileNow());
                if(changes!=null)session.output().send(()->changes);
            }catch(IOException failure){
                // The scene capsule has already been consumed; a refused receipt is recorded
                // without turning a healthy scene stream into a disconnect.
                session.sessionLog().warn("Lifetime capsule counter not recorded; "+WorldSession.deliveryFailure(failure));
            }
        }
        var own=current.actor(session.currentProfile().worldId());
        boolean arsenal=session.membership().arsenalZone();
        if(session.publishedArsenalValue()==null||session.publishedArsenalValue()!=arsenal){session.setPublishedArsenal(arsenal);session.output().send(()->session.codec().equipPolicy(session.connectionOid(),arsenal));}
        session.townHandler().expire(own);
        if(session.conversations().expired(own))session.closeConversation();
        var scripted=session.membership().takeConversation();
        if(scripted!=null&&scripted.definition()==null){session.closeConversation();scripted=null;}
        if(scripted!=null&&own!=null){
            if(scripted.definition().initialAction()!=null&&!session.membership().conversationStart(scripted.definition(),scripted.source()))return;
            session.conversations().open(scripted.definition(),null,scripted.source());
        }
        if(session.arrivalComlinkValue()!=null&&own!=null&&!session.conversations().active()&&session.townHandler().dialogOpen()==false&&session.missionDialogCreatedValue()==0){
            var pending=session.arrivalComlinkValue();session.clearArrivalComlink();session.conversations().openComlink(pending.id());session.setDeliveredComlink(pending);
        }
        int target=session.membership().takeInteraction();
        if(target>0&&own!=null){
            var interaction=scene.simulation().interactionSource(target);
            var source=interaction.actor();
            var binding=source==null?null:scene.map().content().conversations().get(source.config().name());
            var sourceSpec=interaction.specification();
            var station=source==null?null:session.sceneRegistry().town().station(sourceSpec==null?source.config().name():sourceSpec.text("town-station",source.config().name()));
            if(sourceSpec!=null)binding=scene.map().content().conversations().get(sourceSpec.text("dialogue",source.config().name()));
            if(station!=null&&(!session.tradeHandler().active())&&own.interaction()!=null&&own.interaction().actorId()==target){session.openTownDialog(station,source);}
            else if(binding!=null&&(!session.tradeHandler().active())&&own.interaction()!=null&&own.interaction().actorId()==target){
                if(binding.actor().equals(WorldSession.RHENDON_CAMP)&&!session.completedOpeningKeys().contains(OpeningMission.SECURING_CAMP.id())&&!session.sceneRegistry().missions().eligible(OpeningMission.SECURING_CAMP.id(),session.currentProfile().progress(),session.completedOpeningKeys()))return;
                if(binding.initialAction()!=null&&!session.membership().conversationStart(binding,source))return;
                // Repeat node is the archive's documented interim HasCompletedMission branch;
                // it cannot pay or set the mission completed merely by opening.
                String initial=binding.actor().equals(WorldSession.RHENDON_CAMP)&&session.completedOpeningKeys().contains(OpeningMission.SECURING_CAMP.id())?"part 4a":binding.initial();
                session.conversations().open(binding,null,source,initial);
            }
        }
        if(session.tutorialFloorDue(stamp))return;
        if(session.openingFloorDue(stamp))return;
        var trigger=current.portal(session.currentProfile().worldId());
        if(trigger!=null&&session.forcedReceiverValue()!=null)beginPortal(trigger);
    }}


    /** Simulation selects the destination; the normal stock move callback loads its scene. */
    void beginPortal(ScenePortals.Trigger trigger)throws IOException {
        session.closeConversation();
        try{session.charactersStore().worldProfile(session.selectedKnight().name(),session.authRevision(),session.currentProfile());}
        catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
        var destination=session.destination(trigger.destination());
        var entrance=destination.map().portals().entrances().get(trigger.entrance());
        if(entrance==null)throw new IOException("Portal destination is unavailable");
        session.beginForcedTravel(destination,entrance);
        short receiver=session.forcedReceiverValue();
        session.output().send(()->session.codec().forceScene(session.connectionOid(),receiver,destination));
    }
}
