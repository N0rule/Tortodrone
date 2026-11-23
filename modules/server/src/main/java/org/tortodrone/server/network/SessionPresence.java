package org.tortodrone.server.network;

/** Volatile cross-session presence used by the native friend list and auction refunds.
 *
 *  Only these immutable values are read while the static ACTIVE map is held; no peer
 *  session monitor or scene is touched, so presence lookups cannot deadlock a session. */
final class SessionPresence {
    record FriendPresence(String location,int zone,int instance) { }

    private volatile boolean online;
    private volatile FriendPresence location=new FriendPresence("~",0,0);

    boolean online(){return online;}
    void online(boolean value){online=value;}
    FriendPresence location(){return location;}
    void location(FriendPresence value){location=value;}
    /** Leaving a scene clears the join affordance without changing the online flag. */
    void reset(){location=new FriendPresence("~",0,0);}
}
