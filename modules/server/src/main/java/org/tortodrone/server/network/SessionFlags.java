package org.tortodrone.server.network;

import org.tortodrone.core.world.SceneClock;

/** Per-connection role flags and the native scene/ping sequence counters. Every field is read
 *  and written under the session monitor so a delayed acknowledgement or client report cannot
 *  rewind a newer scene stamp. */
final class SessionFlags {
    private boolean gameMaster;
    private boolean inspectionHidden;
    private int acknowledge;
    private int created;
    private int reference;
    private int lastStamp;
    private short ping;

    boolean gameMaster() {
        return gameMaster;
    }

    void gameMaster(boolean value) {
        gameMaster = value;
    }

    boolean inspectionHidden() {
        return inspectionHidden;
    }

    void inspectionHidden(boolean value) {
        inspectionHidden = value;
    }

    void apply(boolean gm, boolean hidden) {
        gameMaster = gm;
        inspectionHidden = hidden;
    }

    int acknowledge() {
        return acknowledge;
    }

    void acknowledge(int value) {
        acknowledge = value;
    }

    int created() {
        return created;
    }

    void markCreated() {
        created = SceneClock.now();
    }

    int reference() {
        return reference;
    }

    int lastStamp() {
        return lastStamp;
    }

    /** The client has entered the freshly forced scene. */
    void markEntry() {
        lastStamp = SceneClock.now();
    }

    void recordPublished(int stamp) {
        lastStamp = stamp;
        reference = stamp;
    }

    void resetReferences() {
        reference = 0;
        acknowledge = 0;
    }

    short ping() {
        return ping;
    }

    void ping(short value) {
        ping = value;
    }
}
