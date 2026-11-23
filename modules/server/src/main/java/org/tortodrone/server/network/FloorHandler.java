package org.tortodrone.server.network;

import java.io.IOException;
import java.util.Set;
import org.tortodrone.core.world.FloorAdvanceRequest;
import org.tortodrone.core.world.SceneElevator;
import org.tortodrone.core.world.ScenePortals;
import org.tortodrone.storage.OpeningMissionStore;
import org.tortodrone.world.FloorReport;
import org.tortodrone.world.SceneRegistry;

/** Tutorial and opening-mission floor reports for one connection.
 *
 * <p>The scene selects a floor advance; the report collects every member's native
 * NextFloor/reportComplete (or the authored timeout). Storage settles before progress flags,
 * forced travel or party publication, and a refused settlement keeps the owned ride for retry.
 */
final class FloorHandler {
    private final WorldSession session;
    private final SessionResponder responder;
    private FloorReport report;
    private FloorAdvanceRequest openingAdvance;
    private boolean openingDropStarted,openingPayoutWarning;
    private int openingPayoutRetry;

    FloorHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
    }

    FloorReport report(){return report;}
    void clearReport(){report=null;}
    void reset(){report=null;openingAdvance=null;openingDropStarted=openingPayoutWarning=false;openingPayoutRetry=0;}

    /** Open and finish the tutorial floor report; false keeps the normal scene publication. */
    boolean tutorial(int stamp)throws IOException { synchronized (session) {
        var place=session.currentScene();
        if(place.tutorial()==null||session.forcedReceiverValue()==null
                ||session.subscribedPartyOid()!=session.partyOid())return false;
        if(report!=null&&!report.valid())report=null;
        if(report==null){
            var request=session.membership().takeFloorAdvance();
            if(request!=null){
                if(!place.tutorial().exit(request)||!session.membership().ownsFloorAdvance(request))
                    session.membership().cancelFloorAdvance(request);
                else {
                    session.reloadProfile();
                    if(session.sceneRegistry().parties().view(session.partyMember()).members().size()!=1)
                        throw new IOException("Tutorial party changed");
                    report=new FloorReport(session.membership(),request,request.timestamp());
                    int party=session.partyOid();responder.send(()->responder.codec().finalFloorReport(party));
                }
            }
        }
        return finishTutorial(stamp);
    }}

    /** Called only after the scene's original NextFloor and native reportComplete (or its
     *  authored timeout). Storage commits before flags, forced movement or party publication. */
    boolean finishTutorial(int stamp)throws IOException {
        var place=session.currentScene();
        var route=place.tutorial();
        if(report==null||!report.ready(stamp)||session.forcedReceiverValue()==null)return false;
        if(route==null||!session.sceneSubscribed()||!session.sceneEntered()||session.sceneTravelPending())return false;
        var next=session.destination(route.destination().id());
        if(next.map()!=route.destination()||!report.valid())return false;
        session.reloadProfile();
        var before=session.currentProfile();
        var settled=session.charactersStore().completeTutorial(session.selectedKnight().id(),session.authRevision(),before,
            session.membership().health().current()>0?session.membership().floorHeat():0,session.sceneRegistry().town());
        session.applyProfile(settled);
        report.finish();report=null;
        if(!before.items().equals(settled.items()))
            responder.send(()->responder.codec().itemChanges(responder.oid(),session.selectedKnight(),before,settled));
        // The settlement's 2-1 arrival rank and orientation letter are already durable.
        if(!before.progress().equals(settled.progress()))
            responder.send(()->responder.codec().knightProgress(responder.oid(),settled));
        if(!before.town().equals(settled.town()))
            responder.send(()->responder.codec().townState(responder.oid(),settled));
        session.closeConversation();session.closeTownDialog();
        session.beginForcedTravel(next,route.entrance());
        short receiver=session.forcedReceiverValue();
        boolean gameMaster=session.gameMasterFlag(),hidden=session.inspectionHiddenValue();
        responder.send(()->responder.codec().playerFlags(responder.oid(),gameMaster,
            hidden,settled.progress().tutorialComplete()));
        responder.send(()->responder.codec().forceScene(responder.oid(),receiver,next));
        return true;
    }

    /** Opening-mission floors: earlier floors ride onward and only the last one settles. */
    boolean opening(int stamp)throws IOException { synchronized (session) {
        if(session.currentScene().run()==null||session.forcedReceiverValue()==null
                ||session.subscribedPartyOid()!=session.partyOid())return false;
        return processOpening(stamp);
    }}

    /** The native 1050 ms NextFloor call pays before it opens the floor report. */
    private boolean processOpening(int stamp)throws IOException {
        var place=session.currentScene();
        var scenes=session.sceneRegistry();
        var run=place.run();
        if(!scenes.authorized(place,session.currentProfile().worldId(),session.partyMember())){
            session.forcePartyExit();return true;
        }
        if(report!=null&&!report.valid()){report=null;openingDropStarted=false;}
        if(openingAdvance!=null&&!session.membership().ownsFloorAdvance(openingAdvance))openingAdvance=null;
        if(report==null){
            if(openingAdvance==null)openingAdvance=session.membership().takeFloorAdvance();
            if(openingAdvance!=null){
                var request=openingAdvance;
                if(!run.exit(request)||!new SceneElevator(1,true).equals(session.membership().elevatorConfiguration(request))){
                    session.membership().cancelFloorAdvance(request);openingAdvance=null;return false;
                }
                if(openingPayoutRetry!=0&&stamp-openingPayoutRetry<0)return false;
                if(run.lastFloor()){
                    session.reloadProfile();var before=session.currentProfile();boolean firstEver=session.firstOpeningCompletion();
                    OpeningMissionStore.Settlement settled;
                    try{
                        settled=scenes.parties().soloGate(session.partyMember(),run.partyOid(),()->session.membership().settleSoloFloor(request,
                            ()->session.charactersStore().services().openingMissions(scenes.town(),scenes.missions()).complete(
                                session.charactersStore().accountId(),session.selectedKnight().id(),session.authRevision(),
                                before,run.missionId(),run.difficulty(),session.membership().floorHeat())));
                    }catch(IOException failure){
                        openingPayoutRetry=stamp+1000;
                        if(!openingPayoutWarning){
                            openingPayoutWarning=true;
                            session.sessionLog().warn("Opening mission settlement failed; keeping the owned ride for retry");
                        }
                        return false;
                    }
                    session.applyProfile(settled.profile());openingAdvance=null;openingPayoutRetry=0;openingPayoutWarning=false;
                    session.applySettlementStory(settled.memories(),settled.objectives());
                    var gateMission=run.missionId();
                    session.recordOpeningCompletion(gateMission,settled.completion());
                    if(settled.first()){
                        session.closeConversation();session.closeTownDialog();
                        session.membership().equipment(session.visibleProfileNow());
                        long dialog=session.noteMissionDialog();
                        var settledProfile=session.currentProfile();
                        responder.send(()->responder.codec().missionSettlement(responder.oid(),before,settledProfile,
                            settled.completion(),scenes.missions().grants(gateMission),scenes.missions().prestige(gateMission),false,dialog));
                        responder.send(()->responder.codec().itemChanges(responder.oid(),session.selectedKnight(),before,settledProfile));
                        if(firstEver)responder.send(()->responder.codec().controllerAction(responder.oid(),"new_mission",null));
                        session.publishPartyState();
                    }
                }else{
                    // Earlier floors only ride onward; the mission settles once on its last floor.
                    scenes.parties().soloGate(session.partyMember(),run.partyOid(),()->session.membership().settleSoloFloor(request,()->Boolean.TRUE));
                    openingAdvance=null;
                }
                report=new FloorReport(session.membership(),request,stamp);openingDropStarted=false;
                int party=session.partyOid();responder.send(()->responder.codec().openingFloorReport(party));
            }else session.membership().authorizeElevator(run.exitKey(),Set.of(session.currentProfile().worldId()));
        }
        return finishOpening(stamp);
    }

    boolean finishOpening(int stamp)throws IOException {
        var place=session.currentScene();
        var scenes=session.sceneRegistry();
        if(place.run()==null||report==null||!report.votedOrTimedOut(stamp)||session.forcedReceiverValue()==null
                ||!session.sceneSubscribed()||!session.sceneEntered()||session.sceneTravelPending())return false;
        var run=place.run();var request=report.request();
        if(!scenes.authorized(place,session.currentProfile().worldId(),session.partyMember()))return false;
        if(!openingDropStarted){
            if(!session.membership().dropElevator(request,report.returning()))return false;
            openingDropStarted=true;
        }
        if(!session.membership().elevatorReady(request))return false;
        SceneRegistry.Instance next;ScenePortals.Entrance arrival;
        if(run.lastFloor()){
            next=session.destination(run.destination().id());
            if(next.map()!=run.destination())return false;
            arrival=run.arrival();
        }else{
            next=scenes.advanceRun(run,session.currentProfile().worldId(),session.partyMember());
            if(next.run()==null||next.map()!=next.run().source())return false;
            arrival=next.run().entrance();
        }
        scenes.parties().soloGate(session.partyMember(),run.partyOid(),()->session.membership().settleSoloElevator(request,()->Boolean.TRUE));
        report.finish();report=null;
        session.beginForcedTravel(next,arrival);
        short receiver=session.forcedReceiverValue();
        responder.send(()->responder.codec().forceScene(responder.oid(),receiver,next));
        return true;
    }
}
