package org.tortodrone.server.network;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.tortodrone.core.WireTypes;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.character.CharacterStore;

/** Social service requests (friends, party suggestions, joins, party finder and unfriend), the
 *  empty feed answer and event-log telemetry for one connection. Join travel is here; the
 *  session only lends narrow state accessors and its forced-travel tail. */
final class FriendHandler {
    private final WorldSession session;
    private final SessionResponder responder;

    FriendHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
    }

    boolean invocation(SessionDecoder.Invocation request) throws IOException {
        int method = request.method();
        // The friend service shares service 12 with scene chat. Scene chat addresses the scene
        // object (not the global invocation object), so anything without the global oid is not a
        // friend request and must fall through to the chat dispatcher instead of being rejected.
        // Current SocialMarshaller: 1 request, 2 party suggestions, 3 join friend, 4 join secret,
        // 6 looking-for-group, 8 unfriend. Methods 5 and 7 are not served yet.
        if (request.oid() != SessionCodec.INVOCATION_OID || request.service() != SessionCodec.SOCIAL_SERVICE
                || !Set.of(1, 2, 3, 4, 6, 8).contains(method)) return false;
        var args = request.arguments();
        boolean result = method == 2;
        if (args.isEmpty() || !(args.getLast() instanceof SessionDecoder.Listener listener)
                || !listener.type().equals(WireTypes.LISTENER_PREFIX + (result ? "ResultMarshaller" : "ConfirmMarshaller")))
            throw new SessionDecoder.Rejected("Invalid friend-list listener");
        boolean valid = switch (method) {
            case 1 -> args.size() == 4 && args.get(0) instanceof String
                && args.get(1) instanceof String && args.get(2) instanceof String;
            case 2 -> args.size() == 3 && args.get(0) instanceof Integer
                && args.get(1) instanceof List<?> names && names.size() <= 256;
            case 3, 8 -> args.size() == 2 && args.get(0) instanceof String;
            // joinOrSpectate: the secret is PlayerObject.Nq(), spectate is carried but unobserved.
            case 4 -> args.size() == 3 && args.get(0) instanceof String && args.get(1) instanceof Boolean;
            // setLookingForGroup: current build sends only the inverted toggle and difficulty.
            case 6 -> args.size() == 3 && args.get(0) instanceof Boolean
                && (args.get(1) == null || args.get(1) instanceof org.tortodrone.core.wire.EnumValue e
                    && e.type().equals(WireTypes.DIFFICULTY));
            default -> false;
        };
        if (!valid) throw new SessionDecoder.Rejected("Invalid friend-list arguments");
        try {
            if (result) {
                responder.send(() -> responder.codec().invocationResult(responder.oid(), listener.requestId(), List.of()));
                return true;
            }
            switch (method) {
                case 3 -> joinFriend((String) args.get(0));
                case 4 -> joinBySecret((String) args.get(0), (Boolean) args.get(1));
                case 6 -> session.setLookingForGroup((Boolean) args.get(0), (org.tortodrone.core.wire.EnumValue) args.get(1));
                case 8 -> removeFriend((String) args.get(0));
                default -> requestFriend((String) args.get(0), (String) args.get(1), (String) args.get(2));
            }
            responder.confirmed(listener.requestId());
        } catch (org.tortodrone.character.CharacterStore.Refused | IOException refused) {
            responder.failure(listener.requestId(), refused.getMessage());
        }
        return true;
    }

    private static String friendUnjoinable(String name){return "%social:m.friend_unjoinable|~"+name;}
    /** Native method 3 in the current build: relationship-authorized travel, rechecked after the peer is captured. */
    private void joinFriend(String name)throws IOException {
        int target;
        synchronized(session){requireFriendReady();target=friendNamed(name);}
        var peer=WorldSession.activeSession(target);
        if(peer==null||peer==session||!session.sameWorld(peer))throw new IOException(friendUnjoinable(name));
        // Like archive joinPlayerInstance: capture the ready target, then transfer on the
        // caller. Never acquire both session monitors; recheck presence before departure.
        var location=peer.adminHandler().location();var presence=peer.friendPresenceValue();
        var peerMember=peer.partyMemberSnapshot();
        synchronized(session){
            requireFriendReady();
            if(friendNamed(name)!=target||!peer.friendOnlineValue()||peer.socket().isClosed()
                    ||presence!=peer.friendPresenceValue()||location.scene().oid()!=presence.instance())
                throw new IOException(friendUnjoinable(name));
            if(location.scene().map().town()){
                if(session.currentScene()!=location.scene())session.adminHandler().travel(location);
                return;
            }
            followIntoInterior(location,peerMember,true);
        }
    }
    /** Native method 3: the per-session join secret authorizes travel without a friendship. */
    private void joinBySecret(String secret,boolean spectate)throws IOException {
        synchronized(session){requireFriendReady();}
        int comma=secret.lastIndexOf(',');
        if(comma<1||secret.length()-comma-1!=16)throw new IOException("%social:m.internal_error");
        long claimed;
        try{claimed=Long.parseUnsignedLong(secret.substring(comma+1),16);}catch(NumberFormatException invalid){throw new IOException("%social:m.internal_error");}
        String name=secret.substring(0,comma);
        if(name.equalsIgnoreCase(session.selectedKnight().name()))throw new IOException("%social:m.friend_self");
        var candidates=WorldSession.activeSessions().stream()
            .filter(s->s.friendOnlineValue()&&!s.socket().isClosed()&&s.hasWorldStorage()&&session.sameWorld(s))
            .toList();
        WorldSession peer=null;int target=0;
        for(var candidate:candidates){
            int world=candidate.identityWorld(name);
            if(world!=0){peer=candidate;target=world;break;}
        }
        // A forged secret must not learn whether the knight it names is joinable.
        if(peer==null||peer.externalSecret()!=claimed)throw new IOException(friendUnjoinable(name));
        // Neither shipped 2026 sender sets spectate; the archive refuses a spectator through
        // joinability anyway, so the flag is carried and has no separate destination here.
        if(spectate&&session.sessionLog().debugEnabled())session.sessionLog().debug("joinOrSpectate arrived with spectate=true; joining as usual");
        var location=peer.adminHandler().location();var presence=peer.friendPresenceValue();
        var peerMember=peer.partyMemberSnapshot();
        synchronized(session){
            requireFriendReady();
            if(peer!=WorldSession.activeSession(target)||!peer.friendOnlineValue()||peer.socket().isClosed()
                    ||presence!=peer.friendPresenceValue()||location.scene().oid()!=presence.instance())
                throw new IOException(friendUnjoinable(name));
            if(location.scene().map().town()){
                if(session.currentScene()!=location.scene())session.adminHandler().travel(location);
                return;
            }
            followIntoInterior(location,peerMember,friendsWithParty(peerMember));
        }
    }
    /** A secret proves consent to travel, not friendship; private parties still admit friends of a member. */
    private boolean friendsWithParty(org.tortodrone.world.PartyRegistry.Member member)throws IOException {
        var view=session.sceneRegistry().parties().view(member);
        var friends=session.charactersStore().services().friends().list(
            session.charactersStore().accountId(),session.selectedKnight().id(),session.authRevision());
        return view.members().stream().anyMatch(p->p.worldId()!=session.currentProfile().worldId()
            &&friends.stream().anyMatch(f->f.world()==p.worldId()));
    }
    /** Follows a friend into a shared Training party room, rolling the party seat back if travel fails. */
    private void followIntoInterior(AdminHandler.AdminLocation location,org.tortodrone.world.PartyRegistry.Member peerMember,boolean friend)throws IOException {
        boolean joined=false;
        try{
            joined=session.sceneRegistry().parties().joinParty(session.partyMember(),peerMember,friend);
            session.refreshPartyForTravel();
            if(session.currentScene()!=location.scene())session.adminHandler().travel(location);
        }catch(IOException|RuntimeException failed){
            if(joined)try{session.sceneRegistry().parties().abandon(session.partyMember());}catch(IOException ignored){}
            if(failed instanceof IOException io)throw io;
            throw new IOException("m.internal_error",failed);
        }
    }
    private void requireFriendReady()throws IOException {
        if(session.isClosed()||!session.selected()||!session.sceneEntered()||!session.sceneSubscribed()
                ||session.sceneTravelPending()||session.deferredMovePending())throw new IOException("Enter a scene first");
        try{session.charactersStore().worldProfile(session.selectedKnight().name(),session.authRevision(),session.currentProfile());}
        catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
    }
    private int friendNamed(String name)throws IOException {
        return session.charactersStore().services().friends().list(
                session.charactersStore().accountId(),session.selectedKnight().id(),session.authRevision()).stream()
            .filter(p->p.name().equalsIgnoreCase(name))
            .mapToInt(org.tortodrone.storage.FriendStore.Peer::world).findFirst()
            .orElseThrow(()->new IOException("m.internal_error"));
    }

    /** Stock social edits (request/unfriend) need only a selected knight; joins still travel. */
    private void requireCurrent()throws IOException {
        if(session.isClosed()||!session.selected())throw new IOException("m.internal_error");
        try{session.charactersStore().worldProfile(session.selectedKnight().name(),session.authRevision());}catch(CharacterStore.Refused refused){throw new IOException("Knight changed; reconnect",refused);}
    }

    void removeFriend(String name)throws IOException,CharacterStore.Refused { synchronized (session) {
        requireCurrent();var friends=session.charactersStore().services().friends();
        friends.remove(session.charactersStore().accountId(),session.selectedKnight().id(),session.authRevision(),name);
        session.applyProfile(session.charactersStore().worldProfile(session.selectedKnight().name(),session.authRevision(),session.currentProfile()));
        session.refreshFriends(null,friends.snapshot(),WorldSession.friendPresence(session.charactersStore().worldStorageFile()));
    }}

    void requestFriend(String name,String message,String response)throws IOException,CharacterStore.Refused { synchronized (session) {
        requireCurrent();var friends=session.charactersStore().services().friends();
        if(!session.chatRate().allow(System.nanoTime()))throw new IOException("Please slow down before sending another friend request");
        friends.request(session.charactersStore().accountId(),session.selectedKnight().id(),session.authRevision(),name,message,response);
    }}

    /** Current FeedMarshaller method one; no feed is stored yet, so a bounded empty list is honest. */
    boolean feedInvocation(SessionDecoder.Invocation request) throws IOException {
        if (request.oid() != SessionCodec.INVOCATION_OID || request.service() != SessionCodec.FEED_SERVICE
                || request.method() != 1) return false;
        var args = request.arguments();
        if (args.size() != 3 || !(args.get(0) instanceof Boolean) || !(args.get(1) instanceof Long)
                || !(args.getLast() instanceof SessionDecoder.Listener listener)
                || !listener.type().equals(WireTypes.RESULT_LISTENER))
            throw new SessionDecoder.Rejected("Invalid feed request");
        responder.send(() -> responder.codec().invocationResult(responder.oid(), listener.requestId(), List.of()));
        return true;
    }

    /** Current EventLogMarshaller methods; view telemetry has no gameplay effect. */
    boolean eventLogInvocation(SessionDecoder.Invocation request) throws IOException {
        if (request.oid() != SessionCodec.INVOCATION_OID || request.service() != SessionCodec.EVENT_LOG_SERVICE)
            return false;
        int method = request.method();
        var args = request.arguments();
        if (method == 1) {
            if (args.size() != 3 || !(args.get(0) instanceof org.tortodrone.core.wire.EnumValue event)
                    || !event.type().equals("com.threerings.projectx.client.EventLogService$EventHubEntry")
                    || !(args.get(1) instanceof Integer) || !(args.get(2) instanceof String))
                throw new SessionDecoder.Rejected("Invalid event log entry");
            return true;
        }
        if (method == 2) {
            if (!args.isEmpty()) throw new SessionDecoder.Rejected("Invalid event log request");
            return true;
        }
        return false;
    }
}
