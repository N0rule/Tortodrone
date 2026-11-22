package org.tortodrone.server.network;

import java.io.IOException;
import java.util.List;
import org.tortodrone.core.character.OpeningMission;
import org.tortodrone.core.mission.MissionRun;
import org.tortodrone.core.wire.EnumValue;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.core.world.ScenePortals;

/** Native mission service for one connection: comlinks, exchange settlement, route preview and
 *  gate start. Validation and routing live here; the session owns travel, attachment and
 *  portal state, and storage commits before the forced move publishes. */
final class OpeningHandler {
    private final WorldSession session;
    private final SessionResponder responder;

    OpeningHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
    }

    boolean invocation(SessionDecoder.Invocation request)throws IOException { synchronized (session) {
        if(request.oid()!=SessionCodec.INVOCATION_OID||request.service()!=SessionCodec.MISSION_SERVICE)return false;
        var args=request.arguments();int method=request.method();
        if(method==8){
            if(args.size()!=1||!(args.getFirst() instanceof List<?> values)||values.size()>128
                    ||values.stream().anyMatch(v->!(v instanceof org.tortodrone.core.wire.ConfigReference)))
                throw new SessionDecoder.Rejected("Invalid mission crafting intent");
            return true; // Native no-listener operation; no unsupported item mutation.
        }
        boolean result=method==7;
        if(args.isEmpty()||!(args.getLast() instanceof SessionDecoder.Listener listener)
                ||!listener.type().equals(org.tortodrone.core.WireTypes.LISTENER_PREFIX+(result?"ResultMarshaller":"ConfirmMarshaller")))
            throw new SessionDecoder.Rejected("Invalid mission listener");
        boolean valid=switch(method){
            case 1->args.size()==3&&args.getFirst() instanceof String&&args.get(1) instanceof long[];
            case 2->args.size()==6&&args.getFirst() instanceof String&&args.get(1) instanceof EnumValue band
                &&band.type().equals(org.tortodrone.core.WireTypes.DIFFICULTY)&&args.get(2) instanceof Boolean
                &&args.get(3) instanceof Boolean&&args.get(4) instanceof Boolean;
            // Current build dropped the third boolean from the preview request.
            case 7->args.size()==5&&args.getFirst() instanceof String&&args.get(1) instanceof EnumValue band
                &&band.type().equals(org.tortodrone.core.WireTypes.DIFFICULTY)&&args.get(2) instanceof Boolean
                &&args.get(3) instanceof Boolean;
            case 3,9->args.size()==2&&args.getFirst() instanceof String;
            default->false;
        };
        if(!valid)throw new SessionDecoder.Rejected("Invalid mission arguments");
        if(method==3){
            try{
                var missionId=(String)args.getFirst();
                var mission=session.sceneRegistry().missions().mission(missionId);
                if(mission==null||!mission.requirement().type().equals("conversation")&&!mission.requirement().type().equals("gate_conversation"))
                    throw new IOException("m.internal_error");
                session.conversations().openComlink(missionId);session.clearArrivalComlink();
                responder.confirmed(listener.requestId());
            }catch(IOException failure){responder.failure(listener.requestId(),failure.getMessage());}
            return true;
        }
        if(method==1){
            try{
                if(session.isClosed()||!session.selected())throw new IOException("m.internal_error");
                var missionId=(String)args.getFirst();var itemIds=(long[])args.get(1);
                if(session.tradeHandler().active())throw new IOException("Finish the trade first");
                session.settleMissionExchange(missionId,itemIds);
                responder.confirmed(listener.requestId());
            }catch(IOException failure){responder.failure(listener.requestId(),failure.getMessage());}
            return true;
        }
        if(method==2||method==7){
            try{
                String missionId=(String)args.getFirst();int band=((EnumValue)args.get(1)).ordinal();
                if(band<0||band>2||!Boolean.TRUE.equals(args.get(2))
                        ||method==2&&(!Boolean.FALSE.equals(args.get(3))||!Boolean.FALSE.equals(args.get(4))))
                    throw new IOException("m.internal_error");
                String difficulty=List.of("EASY","MEDIUM","HARD").get(band);
                if(method==2&&session.sameGateStart(missionId,difficulty,listener.requestId())){
                    session.reloadProfile();
                    responder.confirmed(listener.requestId());return true;
                }
                var opening=OpeningMission.byId(missionId);MissionRun run;ScenePortals.Entrance start;
                if(opening!=null&&session.sceneRegistry().openingGate(opening)!=null){
                    var gate=session.preflightOpeningGate(opening);
                    run=MissionRun.opening(gate,session.currentProfile().worldId(),session.partyOid(),difficulty);
                    start=gate.entrance();
                }else{
                    run=session.preflightMissionRoute(missionId,difficulty);start=run.entrance();
                }
                if(method==7){responder.result(listener.requestId(),Boolean.TRUE,session.currentProfile());return true;}
                session.beginMissionRun(run,start,listener.requestId());
            }catch(IOException failure){responder.failure(listener.requestId(),failure.getMessage());}
            return true;
        }
        // A served object/service must fail unsupported cards through the native listener.
        // Gate start is enabled only when its actual floor gameplay/route is implemented.
        responder.failure(listener.requestId(),"m.internal_error");return true;
    }}
}
