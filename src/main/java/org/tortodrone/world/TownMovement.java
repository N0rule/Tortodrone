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
    // Current stock Dungeoneer activities; never use the archive's 2011 numbering.
    public static final int HURT=274, SHOCKED=278, DYING=280, DEAD=281, RESURRECT=282;
    // Native character/pc/hurt_soft.dat duration (11 frames at 25 Hz, including transition).
    public static final int HURT_MILLIS=425, DYING_MILLIS=1290, RESURRECT_MILLIS=400;
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
    private int interactionChange=-1;
    private int interactionMillis=670;
    public void interactionDuration(int millis){if(millis<0||millis>10000)throw new IllegalArgumentException("Invalid interaction duration");interactionMillis=millis;}
    private KnightCombat combat;
    private SceneStatus.Modifiers status=SceneStatus.Modifiers.NORMAL;
    public void status(SceneStatus.Modifiers modifiers){status=modifiers;if(combat!=null)combat.status(modifiers);
        if(modifiers.shocked()&&!dead()&&activity!=RESURRECT&&activity!=SHOCKED){if(combat!=null)combat.reset(timestamp);setActivity(SHOCKED,timestamp);}
        else if(!modifiers.shocked()&&activity==SHOCKED)setActivity(0,timestamp);
        updateFlags();}
    private java.util.function.IntPredicate revival=stamp->false;
    public void revival(java.util.function.IntPredicate handler){revival=java.util.Objects.requireNonNull(handler);}
    public boolean dead(){return activity==DYING||activity==DEAD;}
    public void hurt(int stamp){
        if(dead()||activity==RESURRECT||activity==SHOCKED)return;
        resetInput(stamp);setActivity(HURT,stamp);updateFlags();
    }
    public void die(int stamp){if(dead())return;resetInput(stamp);setActivity(DYING,stamp);}
    public void resurrect(int stamp){resetInput(stamp);setActivity(RESURRECT,stamp);updateFlags();}
    private org.tortodrone.character.StarterCatalog.Model activityData;
    private java.util.function.IntPredicate throwing=stamp->false;
    public void throwing(java.util.function.IntPredicate handler){throwing=java.util.Objects.requireNonNull(handler);}
    public boolean lift(int stamp){
        if(activity!=0&&activity!=INVENTORY&&activity!=INVENTORY_END||combat!=null&&!combat.canOverride())return false;
        if(combat!=null)combat.reset(stamp);setActivity(268,stamp);updateFlags();return true;
    }
    public void thrown(boolean drop,int stamp){if(combat!=null)combat.reset(stamp);setActivity(drop?271:270,stamp);updateFlags();}
    public org.tortodrone.character.StarterCatalog.Model activityData(){return activityData;}
    public boolean useItem(org.tortodrone.character.StarterCatalog.Model item,int stamp){
        if(activity!=0&&activity!=INVENTORY&&activity!=INVENTORY_END||combat!=null&&!combat.canOverride())return false;
        if(combat!=null)combat.reset(stamp);setActivity(5,stamp);activityData=item;updateFlags();return true;
    }
    private java.util.function.IntPredicate interaction=stamp->false;
    private java.util.function.BiPredicate<Float,Float> dynamicCollision=(x,y)->true;
    private SceneCollision staticCollision=SceneCollision.empty();
    private int collisionMask=43;
    private static final Object PAWN_SHAPE=new FireCatalog.DataValue("com.threerings.tudey.shape.config.ShapeConfig$Circle",
        java.util.List.of(new FireCatalog.FieldValue("radius","float",ScenePortals.PAWN_RADIUS)));
    public void staticCollision(SceneCollision geometry,int mask){staticCollision=java.util.Objects.requireNonNull(geometry);collisionMask=mask;}
    private boolean walkable(float toX,float toY){
        if(staticCollision.isEmpty())return terrain.walkable(toX,toY);
        if(!staticCollision.floorSupported(toX,toY)||!staticCollision.clearSegment(collisionMask,PAWN_SHAPE,x,y,toX,toY,0))return false;
        int steps=Math.max(1,(int)Math.ceil(Math.hypot(toX-x,toY-y)/.1));
        for(int i=1;i<steps;i++)if(!staticCollision.floorSupported(x+(toX-x)*i/steps,y+(toY-y)*i/steps))return false;
        return true;
    }
    public void interaction(java.util.function.IntPredicate handler){interaction=java.util.Objects.requireNonNull(handler);}
    public void dynamicCollision(java.util.function.BiPredicate<Float,Float> predicate){dynamicCollision=java.util.Objects.requireNonNull(predicate);}

    public void combat(KnightCombat controller){combat=java.util.Objects.requireNonNull(controller);}
    public KnightCombat.State combatState(){return combat==null?null:combat.state();}

    public TownMovement() {
        this(SceneRegistry.defaultEntry().map().collision(),SceneRegistry.defaultEntry().map().spawnX(),
            SceneRegistry.defaultEntry().map().spawnY(),SceneRegistry.defaultEntry().map().spawnRotation());
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
        if(!dead()&&activity!=RESURRECT){activity = 0;activityStarted = sceneTime;activityData=null;}
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
        if(portal!=null||dead()||activity==RESURRECT)return false;
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
        if(portal!=null||!dead()&&detectPortal(sceneTime))return;
        while (!pending.isEmpty() && sceneTime - pending.peekFirst().timestamp() >= 0) {
            var frame = pending.removeFirst();
            if (frame.timestamp() - timestamp > 0) moveTo(frame.timestamp());
            if(portal!=null)return;
            if (status.canRotate()&&(activity == 0 || activityAllowsMovement()) && (combat==null||combat.allowsRotation(timestamp))) rotation = frame.rotation();
            direction = frame.direction();
            inputFlags = frame.flags();
            if (activity != 0 && !activityAllowsMovement() && (inputFlags & 1) != 0) {
                var emote = PlayerEmotes.byActivity(activity);
                if(emote!=null)setActivity(emote.endActivity(),timestamp);
            }
            if ((inputFlags & 2) != 0 && activity != INVENTORY && (activity==0||PlayerEmotes.byActivity(activity)!=null) && (combat==null||combat.canOverride())) {
                if(combat!=null)combat.reset(timestamp);setActivity(INVENTORY, timestamp);
            }
            else if ((inputFlags & 2) == 0 && activity == INVENTORY) setActivity(INVENTORY_END, timestamp);
            if(activity==269&&(inputFlags&4)!=0&&frame.actionChanged()>=activityStarted&&throwing.test(timestamp))inputFlags&=~4;
            boolean freshAction=(inputFlags&4)!=0&&frame.actionChanged()>interactionChange;
            if(freshAction)interactionChange=frame.actionChanged();
            if(dead()){
                if(activity==DEAD&&freshAction&&frame.actionChanged()>=activityStarted&&revival.test(timestamp)){
                    setActivity(RESURRECT,timestamp);pending.clear();
                }
                inputFlags=0;updateFlags();continue;
            }
            if(status.canAttack()&&activity==0&&(combat==null||combat.canOverride())&&freshAction&&interaction.test(timestamp)){
                inputFlags&=~4;if(combat!=null)combat.reset(timestamp);
                if(activity==0)setActivity(2,timestamp);
            }
            if(combat!=null)combat.input(inputFlags,frame.actionChanged(),timestamp,rotation,direction,activity==0&&status.canAttack());
            updateFlags();
        }
        if (now - lastInputAt > 500) {
            inputFlags = 0;
            if (activity == INVENTORY) setActivity(INVENTORY_END, timestamp);
            if(combat!=null)combat.input(0,timestamp,timestamp,rotation,direction,activity==0&&status.canAttack());
            updateFlags();
        }
        moveTo(sceneTime);
    }

    private void moveTo(int target) {
        if(activity==DYING&&target-activityStarted>=DYING_MILLIS)setActivity(DEAD,activityStarted+DYING_MILLIS);
        if(activity==268&&target-activityStarted>=277)setActivity(269,activityStarted+277);
        int duration = activity==HURT?HURT_MILLIS:activity==RESURRECT?RESURRECT_MILLIS:activity==270||activity==271?767:activity == 5 ? 1433 : activity == 2 ? interactionMillis : activity == INVENTORY_END ? INVENTORY_END_MILLIS
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
            float distance = (flags&2)==0?0:SPEED * step / 1000f*status.speed()*(combat==null?1:combat.movementScale());
            float dx=(float)Math.cos(direction)*distance,dy=(float)Math.sin(direction)*distance;
            var impulse=combat==null?null:combat.state().impulse();
            if(impulse!=null&&status.canMove()){float portion=impulse.fraction(timestamp)-impulse.fraction(previous);dx+=impulse.x()*portion;dy+=impulse.y()*portion;}
            float nx = x + dx;
            float ny = y + dy;
            if (walkable(nx, ny)&&dynamicCollision.test(nx,ny)) { x = nx; y = ny; }
            else if (walkable(nx, y)&&dynamicCollision.test(nx,y)) x = nx;
            else if (walkable(x, ny)&&dynamicCollision.test(x,ny)) y = ny;
            if(combat!=null)combat.retireImpulse(timestamp);
            if(!dead()&&detectPortal(target))return;
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
    public int inputFlags(){return inputFlags;}
    public int activity() { return combat!=null&&combat.activity()!=0?combat.activity():activity; }
    public int activityStarted() { return combat!=null&&(combat.activity()!=0||activity==0&&combat.started()-activityStarted>0)?combat.started():activityStarted; }
    private void setActivity(int value, int started) {
        activityData=null;
        activity = value;
        activityStarted = started;
    }
    private boolean activityAllowsMovement() {
        if(activity==269)return true;
        var emote=PlayerEmotes.byActivity(activity);
        return emote!=null&&emote.allowsMovement();
    }
    private void updateFlags() {
        flags = status.canMove()&&(activity == 0 || activityAllowsMovement()) && (combat==null||combat.allowsMovement()) && (inputFlags & 1) != 0 ? 2 : 0;
    }
    private static float normalize(float angle) {
        return (float) Math.IEEEremainder(angle, Math.PI * 2);
    }
}
