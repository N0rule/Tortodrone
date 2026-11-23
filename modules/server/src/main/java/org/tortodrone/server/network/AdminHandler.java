package org.tortodrone.server.network;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.world.ScenePortals;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.world.SceneRegistry;

/** Stock admin service for one connection: item, gear, progress and sprite tools, plus the
 *  cross-session player tools. Every call is re-authorized here and every travel readiness
 *  check stays in the session, which owns the scene and queue transitions. */
final class AdminHandler {
    private final WorldSession session;
    private final SessionResponder responder;
    private final CharacterStore store;

    AdminHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
        this.store = session.charactersStore();
    }

    /** One live pawn the admin tools may inspect or move to. */
    record AdminLocation(SceneRegistry.Instance scene,int world,String name,float x,float y,float rotation) { }

    /** The caller's live pawn, re-checked under the session monitor. */
    AdminLocation location()throws IOException { synchronized (session) {
        if(session.isClosed()||!session.responder().open()||!session.selected()
                ||!session.sceneSubscribed()||!session.sceneEntered()||session.sceneTravelPending())
            throw new IOException("That knight is changing scenes");
        try{store.worldProfile(session.selectedKnight().name(),session.authRevision());}
        catch(CharacterStore.Refused refused){throw new IOException("That knight is no longer online",refused);}
        var profile=session.currentProfile();
        var actor=session.currentScene().simulation().snapshot().players().stream()
            .filter(a->a.id()==profile.worldId()).findFirst()
            .orElseThrow(()->new IOException("That knight is not in a ready scene"));
        return new AdminLocation(session.currentScene(),profile.worldId(),session.selectedKnight().name(),
            actor.x(),actor.y(),actor.rotation());
    }}

    /** Move this session to a captured location; never acquires another session monitor. */
    void travel(AdminLocation location)throws IOException { synchronized (session) {
        if(session.isClosed()||!session.responder().open()||!session.selected()
                ||(!session.inReadyRoom()&&(!session.sceneSubscribed()||!session.sceneEntered()))
                ||session.forcedReceiverValue()==null||session.sceneTravelPending()||session.deferredMovePending())
            throw new IOException("Finish entering this scene first");
        var knight=session.selectedKnight();
        try{store.worldProfile(knight.name(),session.authRevision());}
        catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
        if(session.tradeHandler().active())throw new IOException("Finish the trade first");
        var next=location.scene();
        if(!next.map().town()) {
            var owned=session.destination(next.map().id());
            if(owned!=next){
                if(owned!=session.currentScene())session.sceneRegistry().release(owned,session.currentProfile().worldId());
                throw new IOException("That private instance belongs to another knight or party");
            }
            if(!session.sceneRegistry().authorized(next,session.currentProfile().worldId(),session.partyMember()))
                throw new IOException("Party membership changed");
        }
        if(!next.simulation().placement(location.x(),location.y()))throw new IOException("Destination is blocked");
        if(next==session.currentScene()&&!session.inReadyRoom()){
            if(!session.membership().teleport(location.x(),location.y(),location.rotation()))
                throw new IOException("Destination is blocked or the knight is busy");
            session.closeConversation();session.closeTownDialog();return;
        }
        session.closeConversation();session.closeTownDialog();
        session.beginAdminTravel(next,location);
    }}

    /** Cross-session tools deliberately acquire only one session monitor at a time. */
    boolean people(SessionDecoder.Invocation request)throws IOException {
        // Current build inserted a new tool at 48, moving the old bring-to-me request from 50 to 51.
        int method=request.method();if(!Set.of(13,21,24,27,28,30,51).contains(method))return false;
        var args=request.arguments();
        if(request.oid()!=SessionCodec.INVOCATION_OID||request.service()!=SessionCodec.ADMIN_SERVICE||args.size()!=(method==24?3:2)
                ||!(args.getLast() instanceof SessionDecoder.Listener listener)||!listener.type().equals(org.tortodrone.core.WireTypes.CONFIRM_LISTENER)
                ||(method==24?!(args.get(0) instanceof Integer)||!(args.get(1) instanceof Integer):!(args.getFirst() instanceof String)))throw new SessionDecoder.Rejected("Invalid player admin request");
        String name=method==24?null:(String)args.getFirst();
        try {
            session.requireAdminReady(method==28||method==30);
            if(!session.chatRate().allow(System.nanoTime()))throw new IOException("Please slow down before sending another command");
            if(method==24) {
                var own=location();
                travel(new AdminLocation(own.scene(),own.world(),own.name(),(Integer)args.get(0),(Integer)args.get(1),own.rotation()));
            }else if(method==28||method==30) {
                org.tortodrone.core.world.MapScene map=null;
                String key=name.strip();
                for(var candidate:session.sceneRegistry().catalog().scenes()) {
                    boolean match=method==30?candidate.town()&&(candidate.zoneName().equalsIgnoreCase(key)||candidate.zoneName().equalsIgnoreCase("%town:m."+key)||candidate.zoneName().equalsIgnoreCase("%zone:m."+key)||Integer.toString(candidate.zone()).equals(key)):
                        candidate.name().equalsIgnoreCase(key)||Integer.toString(candidate.id()).equals(key)||candidate.name().equalsIgnoreCase("%town:m."+key);
                    if(match){map=candidate;break;}
                }
                if(map==null)throw new IOException("No deployed scene or zone matches that name or ID");
                AdminLocation location;
                synchronized(session){
                    session.requireAdminReady(true);
                    var next=session.destination(map.id());
                    if(next==session.currentScene()&&!session.inReadyRoom())throw new IOException("You are already in that scene");
                    location=new AdminLocation(next,session.currentProfile().worldId(),session.selectedKnight().name(),map.spawnX(),map.spawnY(),map.spawnRotation());
                }
                travel(location);
            }else {
                var target=session.adminTarget(name);var location=target.adminHandler().location();
                if(method==13) {
                    session.adminFeedback(location.name()+"; knight="+location.world()+"; scene="+location.scene().map().name()+" ("+location.scene().map().id()+"); instance="+location.scene().oid());
                }else if(method==21) {
                    if(target==session)throw new IOException("You cannot kick yourself");
                    // Recheck identity/readiness immediately before ending only this live connection.
                    synchronized(target){target.adminHandler().location();target.close();}
                    target.responder().disconnect();
                }else if(method==27){
                    session.requireAdminReady();
                    if(location.scene()==session.currentScene())throw new IOException("That knight is already in this scene");
                    travel(location);
                }
                else {
                    if(target==session)throw new IOException("You are already here");
                    var own=location();session.requireAdminReady();
                    if(location.scene()==own.scene())throw new IOException("That knight is already in this scene");
                    target.adminHandler().travel(own);
                }
            }
            responder.confirmed(listener.requestId());
        }catch(IOException refused){responder.failure(listener.requestId(),refused.getMessage());}
        return true;
    }

    /** Stock admin service, resolved by the client from the bootstrap; every call is authorized here. */
    void dispatch(SessionDecoder.Invocation request) throws IOException { synchronized (session) {
        if(request.service()!=SessionCodec.ADMIN_SERVICE||request.oid()!=SessionCodec.INVOCATION_OID)return;
        var args=request.arguments();
        SessionDecoder.Listener listener=args.isEmpty()||!(args.getLast() instanceof SessionDecoder.Listener last)?null:last;
        String failure=null;
        int method=request.method();
        if(method!=1&&method!=5&&method!=17&&method!=44&&method!=7&&method!=8&&method!=41&&method!=42&&method!=23&&method!=45&&method!=46&&method!=47&&method!=49) {
            // Unknown tools must not leave the stock dashboard waiting for a callback.
            String reason=session.gameMasterRole()?"Placeholder: this admin tool is not implemented yet":"Game Master permission required";
            if(listener!=null){short id=listener.requestId();responder.failure(id,reason);}
            else session.unavailable(reason);
            return;
        }
        if(listener==null||!listener.type().equals(org.tortodrone.core.WireTypes.LISTENER_PREFIX+(method==23?"ResultMarshaller":"ConfirmMarshaller")))throw new SessionDecoder.Rejected("Invalid admin listener");
        if(method==1||method==5) {
            if(args.size()!=(method==1?3:2)||!(args.getFirst() instanceof Integer slot)||method==1&&!(args.get(1) instanceof String))throw new SessionDecoder.Rejected("Invalid accessory admin request");
            try {
                session.requireAdminReady();
                if(session.tradeHandler().active())throw new IOException("Finish the trade first");
                var knight=session.selectedKnight();
                var result=store.services().town(session.sceneRegistry().town()).adminAccessory(store.accountId(),knight.id(),session.authRevision(),slot,method==1?(String)args.get(1):null,knight.costume());
                session.townChanged(result);responder.confirmed(listener.requestId());
            }catch(IOException refused){responder.failure(listener.requestId(),refused.getMessage());}
            return;
        }
        if(method==17||method==44) {
            if(args.size()!=(method==17?2:3)||!(args.getFirst() instanceof Integer)||method==44&&!(args.get(1) instanceof Integer))throw new SessionDecoder.Rejected("Invalid progress admin request");
            try {
                if(session.isClosed()||!session.selected())throw new IOException("Select a knight first");
                if(!session.gameMasterRole())throw new IOException("Game Master permission required");
                if(session.tradeHandler().active())throw new IOException("Finish the trade first");
                var knight=session.selectedKnight();
                var result=store.services().town(session.sceneRegistry().town()).adminProgress(store.accountId(),knight.id(),session.authRevision(),method,args);
                session.townChanged(result);
                if(method==44)responder.send(()->responder.codec().knightProgress(responder.oid(),session.currentProfile()));
                responder.confirmed(listener.requestId());
            }catch(IOException refused){responder.failure(listener.requestId(),refused.getMessage());}
            return;
        }
        if(method==41||method==42||method==23){
            if(args.size()!=3||!(args.getFirst() instanceof Integer)||!(method==41?args.get(1) instanceof Float:args.get(1) instanceof Integer))throw new SessionDecoder.Rejected("Invalid gear admin request");
            try{
                if(session.isClosed()||!session.selected())throw new IOException("Select a knight first");
                if(!session.gameMasterRole())throw new IOException("Game Master permission required");
                if(session.tradeHandler().active())throw new IOException("Finish the trade first");
                var knight=session.selectedKnight();
                var result=store.services().town(session.sceneRegistry().town()).adminGear(store.accountId(),knight.id(),session.authRevision(),method,args);
                session.townChanged(result);
                if(method==23)responder.result(listener.requestId(),result.result(),session.currentProfile());
                else responder.confirmed(listener.requestId());
            }catch(IOException refused){responder.failure(listener.requestId(),refused.getMessage());}return;
        }
        if(method==49){
            // Stock ActorSpawner and /spawn both call ProjectXAdminMarshaller.b(ConfigReference,
            // callback), id 49 in the installed build. The argument is a config reference only.
            if(args.size()!=2||!(args.getFirst() instanceof org.tortodrone.core.wire.ConfigReference reference))throw new SessionDecoder.Rejected("Invalid monster spawn request");
            try{
                session.requireAdminReady();
                store.services().town(session.sceneRegistry().town()).requireGameMaster(store.accountId(),session.authRevision());
                var location=location();
                if(location.scene().map().town())throw new IOException("Monsters can only be spawned in a level scene");
                int count=1;float radius=0;
                for(var entry:reference.arguments().entrySet()){
                    if(!Set.of("count","radius").contains(entry.getKey()))
                        throw new IOException("Monster spawn accepts only count and radius");
                    if(entry.getKey().equals("count"))count=adminInteger(entry.getValue(),"count");
                    else radius=adminNumber(entry.getValue(),"radius");
                }
                location.scene().simulation().spawnMonsters(reference.name(),location.x(),location.y(),location.rotation(),count,radius);
                session.sessionLog().info("Spawned monster; knightId="+session.currentProfile().worldId()+"; type="+reference.name()+"; count="+count);
                responder.confirmed(listener.requestId());
            }catch(IOException refused){responder.failure(listener.requestId(),refused.getMessage());}
            return;
        }
        failure=method==7?createItem(args):adminSprite(method,args);
        if(failure==null)responder.confirmed(listener.requestId());
        else responder.failure(listener.requestId(),failure);
    }}

    private static int adminInteger(Object value,String name)throws IOException{
        if(!(value instanceof Number number))throw new IOException(name+" must be an integer");
        double parsed=number.doubleValue();
        if(!Double.isFinite(parsed)||parsed!=Math.rint(parsed)||parsed<Integer.MIN_VALUE||parsed>Integer.MAX_VALUE)
            throw new IOException(name+" must be an integer");
        return (int)parsed;
    }

    private static float adminNumber(Object value,String name)throws IOException{
        if(!(value instanceof Number number))throw new IOException(name+" must be a number");
        float parsed=number.floatValue();
        if(!Float.isFinite(parsed))throw new IOException(name+" must be a number");
        return parsed;
    }

    private String adminSprite(int method,List<Object> args)throws IOException{
        boolean valid=switch(method){
            case 8->args.size()==2&&args.getFirst() instanceof org.tortodrone.core.wire.ConfigReference;
            case 45->args.size()==2&&args.getFirst() instanceof Integer;
            case 46->args.size()==4&&args.subList(0,3).stream().allMatch(v->v instanceof Integer);
            case 47->args.size()==3&&args.subList(0,2).stream().allMatch(v->v instanceof Integer);
            default->false;
        };
        if(!valid)return "Invalid sprite admin request";
        try{
            if(session.isClosed()||!session.selected())return "Select a knight first";
            if(!session.gameMasterRole())return "Game Master permission required";
            var knight=session.selectedKnight();
            var result=store.services().town(session.sceneRegistry().town()).adminSprite(store.accountId(),knight.id(),session.authRevision(),method,args);
            session.townChanged(result);
            session.sessionLog().info("Updated battle sprite; knightId="+session.currentProfile().worldId()+"; adminMethod="+method);
            return null;
        }catch(IOException refused){return refused.getMessage();}
    }

    /** ProjectXAdminMarshaller.createItem(config, quantity, locked, expireDays), stock method seven. */
    private String createItem(List<Object> args)throws IOException {
        if(args.size()!=5||!(args.get(0) instanceof org.tortodrone.core.wire.ConfigReference reference)
                ||reference.name()==null||!(args.get(1) instanceof Integer quantity)
                ||!(args.get(2) instanceof Boolean locked)||!(args.get(3) instanceof Integer expireDays))
            return "Invalid item creation request";
        try {
            if(!session.selected())return "Select a knight first";
            // The stored grant re-checks this account's privileges in the same transaction.
            if(!session.gameMasterRole())return "Game Master permission required";
            if(quantity<1||quantity>ItemCatalog.GRANT_LIMIT)
                return "Quantity must be between 1 and "+ItemCatalog.GRANT_LIMIT;
            if(expireDays<0||expireDays>3650)return "Expiry must be between 0 and 3650 days";
            var knight=session.selectedKnight();
            var entry=store.catalog().require(reference.name());
            try{store.worldProfile(knight.name(),session.authRevision());}
            catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
            var before=session.visibleProfileNow();
            session.applyProfile(store.createItem(knight.name(),entry.config(),quantity,session.authRevision(),locked,expireDays));
            var after=session.visibleProfileNow();responder.send(()->responder.codec().itemChanges(responder.oid(),knight,before,after));
            session.sessionLog().info("Created "+quantity+" x "+entry.displayName()+"; knightId="+session.currentProfile().worldId()+"; config="+entry.config());
            return null;
        }catch(IOException refused){return refused.getMessage();}
    }
}
