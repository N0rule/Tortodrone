package org.tortodrone.server.network;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Cross-session lookup for one deployment: online knights, friend presence and the auth
 *  revision a direct auction refund must re-check. Only this class owns the live-session map;
 *  every read acquires its monitor and never acquires a peer session monitor. */
final class SessionDirectory {
    private static final Map<Integer,WorldSession> ACTIVE=new HashMap<>();

    /** Install a newly selected knight while the directory monitor is held. */
    @FunctionalInterface interface Registration { void install()throws java.io.IOException; }

    private SessionDirectory() { }

    static void register(int world,WorldSession session,Registration install)throws java.io.IOException {
        synchronized(ACTIVE){
            if(ACTIVE.containsKey(world))throw new java.io.IOException("This knight is already online");
            install.install();
            ACTIVE.put(world,session);
        }
    }

    static void leave(int world,WorldSession session){synchronized(ACTIVE){ACTIVE.remove(world,session);}}

    static WorldSession activeSession(int world){synchronized(ACTIVE){return ACTIVE.get(world);}}

    static List<WorldSession> activeSessions(){synchronized(ACTIVE){return List.copyOf(ACTIVE.values());}}

    static boolean active(int accountId,int knightId){
        synchronized (ACTIVE) {
            return ACTIVE.values().stream().anyMatch(session ->
                session.charactersStore().accountId() == accountId && session.currentProfile().knightId() == knightId);
        }
    }

    static Map<Integer,SessionPresence.FriendPresence> friendPresence(Path storage) {
        var result=new HashMap<Integer,SessionPresence.FriendPresence>();
        synchronized(ACTIVE){for(var entry:ACTIVE.entrySet()){var session=entry.getValue();
            if(session.presenceState.online()&&session.responder().open()&&session.charactersStore().hasWorldStorage()
                    &&session.charactersStore().worldStorageFile().equals(storage))result.put(entry.getKey(),session.presenceState.location());}}
        return Map.copyOf(result);
    }

    static Map<Integer,Long> auctionPresence(Path storage) {
        var result=new HashMap<Integer,Long>();
        synchronized(ACTIVE){for(var entry:ACTIVE.entrySet()){var session=entry.getValue();
            if(session.presenceState.online()&&session.responder().open()&&session.charactersStore().hasWorldStorage()
                    &&session.charactersStore().worldStorageFile().equals(storage))result.put(entry.getKey(),session.authRevision());}}
        return Map.copyOf(result);
    }
}
