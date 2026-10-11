package org.tortodrone.world;

import java.util.ArrayDeque;

/** Server-owned movement on the scene clock. Client frames never provide positions. */
public final class TownMovement {
    public static final float SPEED = 5f;
    // Half the shortest edge of the verified Default PC collision shape, as in MobileLogic.
    public static final float MAX_STEP = .333333f;
    // Verified stock KnightAdvancer inventory activities and closing duration.
    public static final int INVENTORY = 3, INVENTORY_END = 4, INVENTORY_END_MILLIS = 889;
    // Stock EmoteConfig sorted animation index 17, plus 9; end index adds 20 emotes.
    public static final int SIT = 26, SIT_END = 46, SIT_END_MILLIS = 1333;
    private static final int MAX_PENDING = 256;
    private record Frame(int timestamp, float rotation, float direction, int flags,int actionChanged) { }
    private final ArrayDeque<Frame> pending = new ArrayDeque<>();
    private final CollisionMap terrain;
    private final ScenePortals portals;
    private ScenePortals.Trigger portal;
    private float x, y, rotation, direction;
    private int flags, inputFlags, activity, activityStarted, lastTimestamp, timestamp;
    private long lastInputAt;
    private boolean inputSeen;
    private KnightCombat combat;

    public void combat(KnightCombat controller){combat=java.util.Objects.requireNonNull(controller);}
    public KnightCombat.State combatState(){return combat==null?null:combat.state();}

    public TownMovement() {
        this(SceneRegistry.defaultEntry().map().collision(),SceneRegistry.defaultEntry().map().spawnX(),
            SceneRegistry.defaultEntry().map().spawnY());
    }
    public TownMovement(CollisionMap terrain,float spawnX,float spawnY) {
        this(terrain,spawnX,spawnY,0);
    }
    public TownMovement(CollisionMap terrain,float spawnX,float spawnY,float facing) {
        this(terrain,spawnX,spawnY,facing,ScenePortals.EMPTY);
    }
    public TownMovement(CollisionMap terrain,float spawnX,float spawnY,float facing,ScenePortals portals) {
        if(!terrain.walkable(spawnX,spawnY))throw new IllegalArgumentException("Invalid scene spawn");
        if(!Float.isFinite(facing))throw new IllegalArgumentException("Invalid scene facing");
        this.terrain=terrain;this.portals=java.util.Objects.requireNonNull(portals);x=spawnX;y=spawnY;
        rotation=normalize(facing);
    }

    /** Clear input on scene entry/re-entry without moving the character. */
    public void resetInput(int sceneTime) {
        pending.clear();
        timestamp = sceneTime;
        flags = 0;
        inputFlags = 0;
        activity = 0;
        activityStarted = sceneTime;
        inputSeen = false;
        if(combat!=null)combat.reset(sceneTime);
    }

    public boolean accept(int stamp, float facing, float heading, int inputFlags,
            int sceneTime, long now) {
        return accept(stamp,facing,heading,inputFlags,stamp,sceneTime,now);
    }
    public boolean accept(int stamp,float facing,float heading,int inputFlags,int actionChanged,int sceneTime,long now) {
        if (portal!=null || !Float.isFinite(facing) || !Float.isFinite(heading)
                || Math.abs((long) (stamp - sceneTime)) > 10000
                || (inputSeen && stamp - lastTimestamp <= 0)
                || pending.size() >= MAX_PENDING) return false;
        if(combat!=null&&Math.abs((long)actionChanged-stamp)>600000)return false;
        pending.addLast(new Frame(stamp, normalize(facing), normalize(heading), inputFlags & (combat==null?3:4095),actionChanged));
        lastTimestamp = stamp;
        lastInputAt = now;
        inputSeen = true;
        return true;
    }

    /** Compatibility adapter for the original sit RPC. */
    public boolean sit(int sceneTime, long now) {
        return emote(PlayerEmotes.find("Sit"),sceneTime,now);
    }

    /** Start a catalogued stock emote without accepting client-supplied activity data. */
    public boolean emote(PlayerEmotes.Descriptor emote, int sceneTime, long now) {
        if(!PlayerEmotes.isCatalogued(emote))return false;
        advance(sceneTime, now);
        if(portal!=null)return false;
        // Text-only emotes use the normal chat bubble path and must not clear a pose.
        if (!emote.animated()) return true;
        if (activity == INVENTORY || activity == INVENTORY_END || combat!=null&&!combat.canOverride()) return false;
        if(combat!=null)combat.reset(timestamp);
        if (activity != emote.activity() || emote.activity()!=SIT)
            setActivity(emote.activity(), timestamp);
        updateFlags();
        return true;
    }

    /** Integrate each input segment only when its timestamp is due. */
    public void advance(int sceneTime, long now) {
        if (sceneTime - timestamp < 0) return;
        if(portal!=null||detectPortal(sceneTime))return;
        while (!pending.isEmpty() && sceneTime - pending.peekFirst().timestamp() >= 0) {
            var frame = pending.removeFirst();
            if (frame.timestamp() - timestamp > 0) moveTo(frame.timestamp());
            if(portal!=null)return;
            if ((activity == 0 || activityAllowsMovement()) && (combat==null||combat.allowsRotation(timestamp))) rotation = frame.rotation();
            direction = frame.direction();
            inputFlags = frame.flags();
            if (activity != 0 && !activityAllowsMovement() && (inputFlags & 1) != 0) {
                var emote = PlayerEmotes.byActivity(activity);
                if(emote!=null)setActivity(emote.endActivity(),timestamp);
            }
            if ((inputFlags & 2) != 0 && activity != INVENTORY && (combat==null||combat.canOverride())) {
                if(combat!=null)combat.reset(timestamp);setActivity(INVENTORY, timestamp);
            }
            else if ((inputFlags & 2) == 0 && activity == INVENTORY) setActivity(INVENTORY_END, timestamp);
            if(combat!=null)combat.input(inputFlags,frame.actionChanged(),timestamp,rotation,direction,activity==0);
            updateFlags();
        }
        if (now - lastInputAt > 500) {
            inputFlags = 0;
            if (activity == INVENTORY) setActivity(INVENTORY_END, timestamp);
            if(combat!=null)combat.input(0,timestamp,timestamp,rotation,direction,activity==0);
            updateFlags();
        }
        moveTo(sceneTime);
    }

    private void moveTo(int target) {
        int duration = activity == INVENTORY_END ? INVENTORY_END_MILLIS
            : activity == SIT_END ? SIT_END_MILLIS : 0;
        var emote = PlayerEmotes.byActivity(activity);
        boolean movingEmote=emote!=null&&emote.allowsMovement();
        if(movingEmote)duration=emote.duration();
        if (duration > 0 && target - activityStarted >= duration) {
            int ended = activityStarted + duration;
            // A movement-enabled animation permits the same movement before and after
            // its exact deadline, so integrate the whole interval in one pass.
            if (!movingEmote && ended - timestamp > 0) timestamp = ended;
            setActivity(0, ended);
            updateFlags();
        }
        int elapsed = target - timestamp;
        // Match the client's small collision substeps instead of one large 50 ms jump.
        while (elapsed > 0) {
            int step = Math.min(elapsed, 16);
            elapsed -= step;
            int previous=timestamp;timestamp+=step;
            if(combat!=null){combat.advance(timestamp,rotation);updateFlags();}
            float distance = (flags&2)==0?0:SPEED * step / 1000f*(combat==null?1:combat.movementScale());
            float dx=(float)Math.cos(direction)*distance,dy=(float)Math.sin(direction)*distance;
            var impulse=combat==null?null:combat.state().impulse();
            if(impulse!=null){float portion=impulse.fraction(timestamp)-impulse.fraction(previous);dx+=impulse.x()*portion;dy+=impulse.y()*portion;}
            float nx = x + dx;
            float ny = y + dy;
            if (terrain.walkable(nx, ny)) { x = nx; y = ny; }
            else if (terrain.walkable(nx, y)) x = nx;
            else if (terrain.walkable(x, ny)) y = ny;
            if(combat!=null)combat.retireImpulse(timestamp);
            if(detectPortal(target))return;
        }
    }
    private boolean detectPortal(int sceneTime) {
        for(var trigger:portals.triggers())if(trigger.intersects(x,y)) {
            portal=trigger;resetInput(sceneTime);return true;
        }
        return false;
    }
    public ScenePortals.Trigger portal(){return portal;}

    public float x() { return x; }
    public float y() { return y; }
    public float rotation() { return rotation; }
    public float direction() { return direction; }
    public int flags() { return flags; }
    public int activity() { return combat!=null&&combat.activity()!=0?combat.activity():activity; }
    public int activityStarted() { return combat!=null&&(combat.activity()!=0||activity==0&&combat.started()-activityStarted>0)?combat.started():activityStarted; }
    private void setActivity(int value, int started) {
        activity = value;
        activityStarted = started;
    }
    private boolean activityAllowsMovement() {
        var emote=PlayerEmotes.byActivity(activity);
        return emote!=null&&emote.allowsMovement();
    }
    private void updateFlags() {
        flags = (activity == 0 || activityAllowsMovement()) && (combat==null||combat.allowsMovement()) && (inputFlags & 1) != 0 ? 2 : 0;
    }
    private static float normalize(float angle) {
        return (float) Math.IEEEremainder(angle, Math.PI * 2);
    }
}
