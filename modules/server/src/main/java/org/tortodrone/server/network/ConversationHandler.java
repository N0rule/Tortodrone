package org.tortodrone.server.network;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.tortodrone.core.character.KnightMemory;
import org.tortodrone.core.character.KnightObjective;
import org.tortodrone.core.character.OpeningMission;
import org.tortodrone.core.world.SceneActions;
import org.tortodrone.core.world.SceneConversation;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.world.OpeningComlinks;

/** Native conversation dialogs for one connection: owned nodes, responses and terminal settlement.
 *
 * <p>The dialog stamp is this connection's monotonic id; a stale client close cannot dismiss a
 * replacement. Terminal settlement commits through the session before any callback publishes,
 * so a refused write leaves the dialog open and never emits a settlement.
 */
final class ConversationHandler {
    private final WorldSession session;
    private final SessionResponder responder;
    private SceneConversation conversation;
    private String mission;
    private String node;
    private ActorState source;
    private long created;
    private float x;
    private float y;
    private final Set<Short> responses=new LinkedHashSet<>();

    ConversationHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
    }

    boolean active() { return conversation!=null; }

    SceneConversation current() { return conversation; }

    String mission() { return mission; }

    String node() { return node; }

    ActorState source() { return source; }

    long created() { return created; }

    /** Advance the dialog stamp so a stale close cannot dismiss the replacement. */
    long touch() {
        created=Math.max(System.currentTimeMillis(),created+1);
        return created;
    }

    boolean expired(ActorState own) {
        return conversation!=null&&(own==null||mission==null&&Math.hypot(own.x()-x,own.y()-y)>1);
    }

    /** Open a conversation where the knight stands; the caller has already resolved eligibility. */
    void open(SceneConversation definition,String missionId,ActorState own)throws IOException {
        open(definition,missionId,own,definition.initial());
    }

    void open(SceneConversation definition,String missionId,ActorState own,String initialNode)throws IOException {
        session.closeTownDialog();
        close();
        this.mission=missionId;this.conversation=definition;this.source=own;this.node=initialNode;
        touch();x=own.x();y=own.y();
        responder.send(()->responder.codec().conversationDialog(responder.oid(),session.currentScene().oid(),
            conversation,node,source,created));
    }

    void close()throws IOException {
        if(conversation==null)return;
        conversation=null;source=null;node=null;mission=null;
        responder.send(()->responder.codec().conversationDialog(responder.oid(),session.currentScene().oid(),
            null,null,null,created));
    }

    boolean invocation(SessionDecoder.Invocation request)throws IOException {
        if(conversation!=null&&session.ownsScene(request.oid())&&request.service()==6&&request.method()==1){
            if(request.arguments().size()!=1||!(request.arguments().getFirst() instanceof Long stamp))
                throw new SessionDecoder.Rejected("Invalid conversation close");
            if(stamp!=created)return true;close();return true;
        }
        if(!session.ownsScene(request.oid())||request.service()!=SceneConversation.SERVICE)return false;
        var args=request.arguments();
        if(!session.ownsScene(request.oid())||!session.sceneEntered()||request.method()!=1||args.size()!=2
                ||!(args.get(0) instanceof Integer choice)||!(args.get(1) instanceof SessionDecoder.Listener listener)
                ||!listener.type().endsWith("$ConfirmMarshaller"))throw new SessionDecoder.Rejected("Invalid conversation response");
        if(responses.contains(listener.requestId())){responder.confirmed(listener.requestId());return true;}
        // A response can cross the native 1.0-unit auto-close: the player keeps walking
        // until the panel appears and the queued confirm lands after the server dropped
        // the conversation. ProjectX answers that listener with m.internal_error rather
        // than treating it as a protocol violation, so never disconnect for it.
        if(conversation==null){responder.failure(listener.requestId(),"m.internal_error");return true;}
        var replies=conversation.nodes().get(node).responses();
        if(choice<0||choice>=replies.size()){responder.failure(listener.requestId(),"Invalid conversation choice");return true;}
        var response=replies.get(choice);String destination=response.destination();
        var openingTerminal=mission==null?null:OpeningMission.byId(mission);
        var map=session.currentScene().map();
        boolean securing=map.id()==217&&conversation.actor().equals(WorldSession.RHENDON_CAMP)&&node.equals("part 3a")&&choice==0;
        String terminalId=securing?OpeningMission.SECURING_CAMP.id():
            openingTerminal!=null?OpeningComlinks.terminal(openingTerminal,conversation,node,choice)?mission:null:
            mission!=null&&response.terminal()?mission:null;
        if(terminalId!=null){
            var own=session.selfActor();
            var membership=session.membership();
            int world=session.currentProfile().worldId();
            boolean validSource=securing?membership.conversationValid(conversation,node,choice,source):
                openingTerminal!=null?session.sceneEntered()&&session.sceneSubscribed()&&map.id()==OpeningComlinks.scene(openingTerminal)
                    &&membership.health().current()>0&&source!=null&&source.id()==world
                    &&session.currentScene().simulation().snapshot().portal(world)==null:
                session.sceneEntered()&&session.sceneSubscribed()&&membership.health().current()>0&&source!=null&&source.id()==world
                    &&session.currentScene().simulation().snapshot().portal(world)==null;
            if(own==null||securing&&Math.hypot(own.x()-x,own.y()-y)>1||!validSource){
                responder.failure(listener.requestId(),"Conversation source is unavailable");return true;
            }
            try{
                var expectedTerminal=new SceneActions.Sequence(List.of(new SceneActions.Memory(new KnightMemory("talkto_rhendon_icon",false)),new SceneActions.Objective(new KnightObjective("findRhendon",null))),false);
                if(securing?!expectedTerminal.equals(response.action()):response.action()!=null)throw new IOException("Unsupported mission terminal action graph");
                var prepared=session.prepareConversationSettlement(terminalId);
                close();
                long dialog=prepared.settled().first()?touch():created;
                session.applyConversationSettlement(prepared,terminalId,own,dialog);
                remember(listener.requestId());responder.confirmed(listener.requestId());
            }catch(IOException failure){responder.failure(listener.requestId(),failure.getMessage());}
            return true;
        }
        if(response.action()!=null&&!session.membership().conversationResponse(conversation,node,choice,source)){
            responder.failure(listener.requestId(),"Conversation source is unavailable");return true;
        }
        if(!response.station().isEmpty()){
            var station=session.sceneRegistry().town().station(response.station());
            if(station==null){responder.failure(listener.requestId(),"Town service is unavailable");return true;}
            try{session.openTownDialog(station,source);}
            catch(IOException failure){responder.failure(listener.requestId(),failure.getMessage());return true;}
        }else if(!response.command().isEmpty()){
            var origin=source;close();
            responder.send(()->responder.codec().controllerAction(responder.oid(),response.command(),origin));
        }else if(destination.isEmpty())close();else{
            node=destination;
            responder.send(()->responder.codec().conversationDialog(responder.oid(),session.currentScene().oid(),
                conversation,node,source,created));
        }
        remember(listener.requestId());
        responder.confirmed(listener.requestId());return true;
    }

    private void remember(short id) {
        responses.add(id);
        if(responses.size()>256){var oldest=responses.iterator();oldest.next();oldest.remove();}
    }
}
