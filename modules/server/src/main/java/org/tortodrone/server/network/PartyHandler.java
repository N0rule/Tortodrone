package org.tortodrone.server.network;

import java.io.IOException;
import java.util.Set;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.core.party.PartyView;
import org.tortodrone.core.world.SceneChat;
import org.tortodrone.core.world.SceneClock;
import org.tortodrone.core.world.SceneInstanceView;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.core.world.ScenePortals;
import org.tortodrone.world.PartyRegistry;

/** Native party services and the party object publication for one connection. The party
 *  seat stays authoritative in the session; this handler owns only the announced/subscribed
 *  party view and turns registry events into stock callbacks. Every body that reads session
 *  state keeps the session monitor, preserving the original lock order. */
final class PartyHandler {
    private final WorldSession session;
    private final SessionResponder responder;
    private int subscribedPartyOid;
    private int announcedPartyOid;
    private PartyView.View publishedParty;
    /** The scene whose party attributes the subscribed party object currently reflects. */
    private SceneInstanceView publishedPartyScene;
    /** The explored-tile keys already sent to the subscribed party. */
    private Set<Integer> publishedPartyVisited;

    PartyHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
    }

    int oid()throws IOException{return session.sceneRegistry().parties().view(session.partyMember()).oid();}

    synchronized int subscribedOid(){return subscribedPartyOid;}
    synchronized int announcedOid(){return announcedPartyOid;}
    synchronized PartyView.View published(){return publishedParty;}
    /** Publish newly explored tiles only to the currently subscribed party object. */
    synchronized void publishVisited(Set<Integer> explored)throws IOException {
        if(subscribedPartyOid==0||publishedPartyVisited==null)return;
        if(!explored.equals(publishedPartyVisited)){
            var previous=publishedPartyVisited;publishedPartyVisited=explored;
            var added=new java.util.ArrayList<Integer>();
            for(int coord:explored)if(!previous.contains(coord))added.add(coord);
            if(!added.isEmpty())responder.send(()->responder.codec().partyVisited(subscribedPartyOid,added));
        }
    }

    void state()throws IOException {
        synchronized(session){
            var member=session.partyMember();var membership=session.membership();
            if(member!=null&&membership!=null)session.sceneRegistry().parties().state(member,session.selectedKnight(),
                session.currentProfile(),membership.health(),membership.status(),
                session.currentScene().map().town()&&!session.sceneTravelPending(),membership.floorHeat());
        }
    }

    /** Leave to a safe non-party destination; the stock client answers with its zone move. */
    void forceExit()throws IOException { synchronized(session) {
        var runtime=session.runtime();
        if(runtime.readyRoom())return;
        var scene=runtime.scene();
        var move=runtime.portalMove();
        if(scene.map().town()&&move==null)return;
        if(move!=null&&move.destination().map().town())return;
        if(move!=null&&move.destination()!=scene)
            session.sceneRegistry().release(move.destination(),session.currentProfile().worldId());
        var run=scene.run()!=null?scene.run():move==null?null:move.destination().run();
        if(run!=null)session.applyProfile(session.loadProfile(session.currentProfile()));
        var profile=session.currentProfile();
        int safeTown=run==null?session.sceneRegistry().catalog().entry().id()
            :profile.progress().openingStage()>=2
                ||!org.tortodrone.core.character.OpeningMission.ANCIENT_GENERATOR.id().equals(run.missionId())
                ?run.destination().id():217;
        var next=session.destination(safeTown);
        runtime.beginPortal(next,new ScenePortals.Entrance(1,next.map().spawnX(),next.map().spawnY(),next.map().spawnRotation()));
        session.clearDeferredMoves();runtime.retireScene(scene.oid());
        var receiver=session.forcedReceiverValue();
        if(receiver!=null)responder.send(()->responder.codec().forceScene(responder.oid(),receiver,next));
    }}

    void offer(PartyView.Event event){session.offerPartyEvent(event);}

    void deliver(PartyView.Event event)throws IOException {
        synchronized(session){
            var member=session.partyMember();
            if(session.isClosed()||member==null)return;
            if(event instanceof PartyView.Invitation invite){responder.send(()->responder.codec().partyInvitation(responder.oid(),invite));return;}
            if(event instanceof PartyView.Chat chat){
                if(session.sceneRegistry().parties().owns(member,chat.partyOid()))responder.send(()->responder.codec().chat(chat.partyOid(),chat.message()));
                return;
            }
            refreshLocked();
        }
    }

    void refresh()throws IOException{refresh(false);}

    void refresh(boolean traveling)throws IOException {
        synchronized(session){refreshLocked(traveling);}
    }

    private void refreshLocked()throws IOException{refreshLocked(false);}

    private void refreshLocked(boolean traveling)throws IOException {
        var member=session.partyMember();
        var view=session.sceneRegistry().parties().view(member);
        if(session.sceneEntered())session.partyPresence(view);
        // A private solo bookkeeping party is not an active dungeon party in Haven.
        // Publishing it there makes the stock command handler ask to abandon a mission.
        var scene=session.currentScene();
        int visibleParty=session.inReadyRoom()?0:scene.map().town()&&view.members().size()==1
            &&session.sceneRegistry().parties().owner(member,session.currentProfile().worldId())==0?0:view.oid();
        if(visibleParty!=announcedPartyOid){
            boolean changed=announcedPartyOid!=0;announcedPartyOid=visibleParty;subscribedPartyOid=0;
            publishedParty=null;publishedPartyScene=null;publishedPartyVisited=null;
            responder.send(()->responder.codec().partyOid(responder.oid(),visibleParty));
            if(changed&&!traveling&&!session.inReadyRoom()&&(!scene.map().town()||session.sceneTravelPending()))
                session.forcePartyExit();
        }else if(subscribedPartyOid==view.oid()&&publishedParty!=null&&!publishedParty.equals(view)){
            var before=publishedParty;responder.send(()->responder.codec().partyChanges(before,view));publishedParty=view;
        }
        var now=session.sceneNowForParty();
        if(subscribedPartyOid==view.oid()&&publishedPartyScene!=null&&!samePartyScene(publishedPartyScene,now)){
            var before=publishedPartyScene;publishedPartyScene=now;publishedPartyVisited=now.visited();
            responder.send(()->responder.codec().partySceneChanges(view.oid(),before,now));
        }
        if(subscribedPartyOid==view.oid()&&publishedPartyVisited!=null){
            var current=now.visited();
            if(!current.equals(publishedPartyVisited)){
                var previous=publishedPartyVisited;publishedPartyVisited=current;
                var added=new java.util.ArrayList<Integer>();
                for(int coord:current)if(!previous.contains(coord))added.add(coord);
                if(!added.isEmpty())responder.send(()->responder.codec().partyVisited(view.oid(),added));
            }
        }
    }

    void subscribeInterior(short message)throws IOException {
        synchronized(session){
            if(session.inReadyRoom())throw new IOException("The Ready Room has no scene party");
            session.reloadProfile();
            var member=session.partyMember();
            var membership=session.membership();
            if(member!=null&&membership!=null)session.sceneRegistry().parties().state(member,session.selectedKnight(),
                session.currentProfile(),membership.health(),membership.status(),
                session.currentScene().map().town()&&!session.sceneTravelPending(),membership.floorHeat());
            var view=session.sceneRegistry().parties().view(member);
            // A solo party is announced while the knight is already travelling, so the stock
            // client subscribes before the destination scene is current. The party object
            // belongs to the destination (its gate and mission class), never to the old town.
            var partyScene=session.sceneNowForParty();
            var health=membership.health();var status=membership.status();
            var report=session.floorReport();boolean validReport=report!=null&&report.valid();
            responder.send(()->responder.codec().party(message,view,partyScene,validReport));
            subscribedPartyOid=view.oid();publishedParty=view;publishedPartyScene=partyScene;publishedPartyVisited=partyScene.visited();
            if(validReport){boolean opening=session.sceneRun()!=null;
                responder.send(()->opening?responder.codec().openingFloorReport(view.oid()):responder.codec().finalFloorReport(view.oid()));}
            session.publishedHealthStatus(health,status);
            session.completeDeferredPartyMove();
        }
    }

    void unsubscribeInterior(int party){
        synchronized(session){
            if(subscribedPartyOid==party){
                subscribedPartyOid=0;publishedParty=null;publishedPartyScene=null;publishedPartyVisited=null;
            }
        }
    }

    private static boolean samePartyScene(SceneInstanceView a,SceneInstanceView b){
        return a.map().zone()==b.map().zone()&&a.oid()==b.oid()&&a.map().town()==b.map().town()
            &&a.hasMission()==b.hasMission()&&a.missionGateId()==b.missionGateId()&&a.missionFloor()==b.missionFloor()
            &&java.util.Objects.equals(a.missionId(),b.missionId())
            &&a.map().kind().equals(b.map().kind());
    }

    boolean invocation(SessionDecoder.Invocation request)throws IOException {
        var args=request.arguments();
        boolean response=request.oid()==SessionCodec.INVOCATION_OID&&request.service()==SessionCodec.SOCIAL_SERVICE&&request.method()==5
            &&!args.isEmpty()&&args.getFirst() instanceof String key&&key.startsWith("PARTY_INV:");
        boolean operation=request.service()==20&&request.method()>=1&&request.method()<=3;
        if(!response&&!operation)return false;
        if(args.isEmpty()||!(args.getLast() instanceof SessionDecoder.Listener listener)
                ||!listener.type().equals(org.tortodrone.core.WireTypes.CONFIRM_LISTENER))
            throw new SessionDecoder.Rejected("Invalid party listener");
        boolean valid=response?args.size()==3&&args.get(1) instanceof Integer
            :request.method()==3?args.size()==1:args.size()==2&&args.getFirst() instanceof String;
        if(!valid)throw new SessionDecoder.Rejected("Invalid party arguments");
        try{
            PartyRegistry.Member member;String self;
            synchronized(session){
                if(session.isClosed()||!session.selected())throw new IOException("Select a knight first");
                if(session.currentScene().tutorial()!=null)throw new IOException("Finish the tutorial before changing parties");
                if(operation&&!session.sceneRegistry().parties().owns(session.partyMember(),request.oid()))
                    throw new IOException("Party membership changed");
                session.charactersStore().worldProfile(session.selectedKnight().name(),session.authRevision(),session.currentProfile());
                member=session.partyMember();self=session.selectedKnight().name();state();
            }
            if(response)session.sceneRegistry().parties().respond(member,(String)args.get(0),(Integer)args.get(1));
            else if(request.method()==2){
                if(!session.chatRate().allow(System.nanoTime()))throw new IOException("Please slow down before inviting again");
                session.sceneRegistry().parties().invite(member,(String)args.get(0));
            }else session.sceneRegistry().parties().boot(member,request.method()==3?self:(String)args.get(0));
            synchronized(session){
                refreshLocked();
                if(!response&&request.method()==3)session.forcePartyExit();
                responder.confirmed(listener.requestId());
            }
        }catch(CharacterStore.Refused|IOException refused){responder.failure(listener.requestId(),refused.getMessage());}
        return true;
    }

    boolean ownsInterior(int target){
        synchronized(session){
            return session.selected()&&!session.inReadyRoom()&&session.sceneRegistry().parties().owns(session.partyMember(),target);
        }
    }

    boolean interiorInvocation(SessionDecoder.Invocation request)throws IOException {
        synchronized(session){
            if(!session.selected()||session.inReadyRoom()||!session.sceneRegistry().parties().owns(session.partyMember(),request.oid()))
                return false;
            if(request.service()==SceneChat.PARTY_SERVICE)return false;
            var args=request.arguments();
            if(request.service()==SessionCodec.LEVEL_PARTY_SERVICE&&request.method()==3){
                if(args.size()!=1||!(args.getFirst() instanceof Boolean descend))throw new SessionDecoder.Rejected("Invalid floor report");
                var report=session.floorReport();
                // Native reportComplete has no callback. A late or premature vote does nothing.
                if(!session.isClosed()&&session.sceneSubscribed()&&session.sceneEntered()&&!session.sceneTravelPending()
                        &&report!=null&&report.report(descend,SceneClock.now())){
                    if(session.sceneTutorial())session.finishTutorialFloor(SceneClock.now());
                    else if(session.sceneRun()!=null)session.finishOpeningFloor(SceneClock.now());
                }
                return true;
            }
            var member=session.partyMember();
            if(request.service()==20&&request.method()==4){
                if(args.size()!=3||!(args.get(0) instanceof String target)||!(args.get(1) instanceof Integer cost)
                        ||!(args.get(2) instanceof SessionDecoder.Listener listener)
                        ||!listener.type().equals(org.tortodrone.core.WireTypes.CONFIRM_LISTENER))
                    throw new SessionDecoder.Rejected("Invalid revive request");
                try{
                    if(session.isClosed()||!session.sceneSubscribed()||!session.sceneEntered()||session.currentScene().map().town())
                        throw new IOException("m.no_revive");
                    if(session.tradeHandler().active())throw new IOException("Finish the trade first");
                    var result=session.sceneRegistry().parties().revive(member,request.oid(),target,world->
                        session.currentScene().simulation().revive(session.membership(),world,()->
                            session.charactersStore().services().town(session.sceneRegistry().town()).revive(
                                session.charactersStore().accountId(),session.selectedKnight().id(),session.authRevision(),cost)));
                    session.townChanged(result);
                    responder.confirmed(listener.requestId());
                }catch(IOException refused){responder.send(()->responder.codec().reviveFailure(responder.oid(),listener.requestId(),refused.getMessage()));}
                return true;
            }
            if(request.service()==SessionCodec.LEVEL_PARTY_SERVICE&&request.method()>=4&&request.method()<=6){
                if(args.size()!=2||!(args.get(0) instanceof Boolean flag)||!(args.get(1) instanceof SessionDecoder.Listener listener)
                        ||!listener.type().equals(org.tortodrone.core.WireTypes.CONFIRM_LISTENER))
                    throw new SessionDecoder.Rejected("Invalid party settings request");
                try{
                    if(session.isClosed()||!session.sceneSubscribed()||!session.sceneEntered()||session.sceneTutorial())
                        throw new IOException("m.internal_error");
                    switch(request.method()){
                        case 4->session.sceneRegistry().parties().setAllowFriends(member,flag);
                        case 5->session.sceneRegistry().parties().setAllowGuild(member,flag);
                        default->session.sceneRegistry().parties().setInviteOnly(member,flag);
                    }
                    refreshLocked();
                    responder.confirmed(listener.requestId());
                }catch(IOException refused){responder.failure(listener.requestId(),refused.getMessage());}
                return true;
            }
            throw new SessionDecoder.Rejected("Unsupported interior party operation");
        }
    }
}
