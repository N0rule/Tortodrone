package org.tortodrone.world;

import java.security.MessageDigest;
import java.util.HexFormat;
import static org.tortodrone.support.TestWire.check;

public final class TownMovementTest {
    private static CollisionMap corridor;
    private static TownMovement movement(){return new TownMovement(corridor,0,-8,0);}
    public static void main(String[] args)throws Exception {
        var fixture=java.nio.file.Files.createTempFile("tortodrone-movement-corridor-",".gz");
        try {
            // Timing/pose assertions use a controlled corridor; map geometry is verified separately.
            int width=145,height=9;var clear=new java.util.BitSet(width*height);
            for(int y=1;y<height-1;y++)for(int x=1;x<width-1;x++)clear.set(y*width+x);
            try(var out=new java.io.DataOutputStream(new java.util.zip.GZIPOutputStream(java.nio.file.Files.newOutputStream(fixture)))) {
                out.writeInt(0x54445731);out.writeInt(width);out.writeInt(height);
                out.writeFloat(-1);out.writeFloat(-9);out.writeFloat(.25f);
                out.write(java.util.Arrays.copyOf(clear.toByteArray(),(width*height+7)/8));
            }
            corridor=CollisionMap.read(fixture);verify();
        } finally {java.nio.file.Files.deleteIfExists(fixture);}
    }
    private static void verify() throws Exception {
        byte[] scene = TownSquare.model();
        check(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(scene))
            .equals("83115fa639bb1e55c93827772d2c46b929282c3641854c1ea5422e8cb0949ac2"), "captured modern Town Square");
        scene[0] ^= 1;
        check(scene[0] != TownSquare.model()[0], "callers cannot modify the cached scene");
        check(TownSquare.clock() > 0, "scene clock starts positive");
        var entry=SceneRegistry.defaultEntry().map();
        var arrival=new TownMovement();
        check(arrival.x()==entry.spawnX()&&arrival.y()==entry.spawnY()
            &&Math.abs(arrival.rotation()-entry.spawnRotation())<.00001f,
            "default movement starts at the authored modern entrance and facing");
        check(entry.collision().walkable(0,-8), "clear movement test corridor");
        check(!entry.collision().walkable(Float.NaN,0) && !entry.collision().walkable(10000,0)
            && !entry.collision().walkable(0,Float.POSITIVE_INFINITY), "invalid positions blocked");
        var m = movement();
        m.resetInput(100);
        check(m.accept(100,1.5f,0,1,100,1000), "first input accepted");
        m.advance(100,1000);
        m.advance(150,1050);
        check(Math.abs(m.x()-.25f)<.00001 && m.y()==-8 && m.rotation()==1.5f, "movement independent of facing");
        check(!m.accept(100,0,0,1,100,1050) && !m.accept(99,0,0,1,100,1050), "replays rejected");
        check(!m.accept(101,Float.NaN,0,1,100,1050)
            && !m.accept(101,0,Float.POSITIVE_INFINITY,1,100,1050)
            && !m.accept(20000,0,0,1,100,1050), "invalid angles and clocks rejected");
        m.advance(151,1501);
        check(Math.abs(m.x()-.25f)<.00001 && m.flags()==0, "stale input stops movement");
        check(m.accept(101,7,0,2,101,1600), "facing update accepted");
        m.advance(152,1600);
        check(Math.abs(m.x()-.25f)<.00001 && m.flags()==0 && Math.abs(m.rotation())<=Math.PI, "inventory input does not cause movement and angle normalized");
        int stamp=153;
        for(int i=0;i<1000;i++) {
            check(m.accept(stamp,0,0,1,stamp,2000+i*50), "fresh frame");
            m.advance(stamp,2000+i*50); stamp+=50;
            check(corridor.walkable(m.x(),m.y()), "movement stays on walkable terrain");
        }
        check(m.x()<35, "corridor wall blocks an unlimited straight walk");
        var future = movement();
        future.resetInput(100);
        check(future.accept(200,1.5f,0,1,100,1000), "future input enqueued");
        future.advance(150,1050);
        check(future.x()==0 && future.flags()==0 && future.rotation()==0, "future input not applied early");
        future.advance(200,1100);
        check(future.x()==0 && future.flags()==2 && future.rotation()==1.5f, "input applies when due");
        future.advance(225,1125);
        check(Math.abs(future.x()-.125f)<.00001, "partial tick uses elapsed scene time");
        check(future.accept(250,0,0,0,225,1125), "future stop enqueued");
        future.advance(300,1200);
        check(Math.abs(future.x()-.25f)<.00001 && future.flags()==0, "batched stop splits simulation at timestamp");
        future.resetInput(300);
        check(future.accept(300,0,0,1,300,1200), "re-entry starts a fresh input stream");
        future.advance(300,1200);
        float before = future.x();
        future.advance(317,1217);
        future.advance(341,1241);
        future.advance(350,1250);
        check(Math.abs(future.x()-before-.25f)<.00001, "scheduler jitter does not alter movement speed");
        var bounded = movement();
        bounded.resetInput(100);
        for(int i=0;i<256;i++) check(bounded.accept(1000+i,0,0,1,100,1000), "bounded future input");
        check(!bounded.accept(1256,0,0,1,100,1000), "future queue cannot grow without limit");
        var inventory = movement();
        inventory.resetInput(100);
        check(inventory.accept(100,1,0,3,100,1000), "inventory and move input accepted");
        inventory.advance(150,1050);
        check(inventory.activity()==3 && inventory.activityStarted()==100
            && inventory.flags()==0 && inventory.x()==0, "inventory blocks walking");
        check(inventory.accept(200,2,0,3,150,1050), "continued inventory input accepted");
        inventory.advance(200,1100);
        check(inventory.activityStarted()==100 && inventory.rotation()==1, "inventory start and facing remain stable");
        check(inventory.accept(250,2,0,1,200,1100), "inventory close accepted");
        inventory.advance(250,1150);
        check(inventory.activity()==4 && inventory.activityStarted()==250, "closing activity starts once");
        for(int time=300;time<=1100;time+=50) {
            check(inventory.accept(time,2,0,1,time,900+time), "walking during inventory close accepted");
            inventory.advance(time,900+time);
            check(inventory.x()==0 && inventory.flags()==0 && inventory.activityStarted()==250,
                "closing animation is not restarted and prevents early movement");
        }
        inventory.advance(1189,2089);
        check(inventory.activity()==0 && inventory.activityStarted()==1139
            && inventory.flags()==2 && Math.abs(inventory.x()-.25f)<.00001,
            "walking resumes at the exact closing deadline within a tick");
        inventory.resetInput(1200);
        check(inventory.activity()==0 && inventory.activityStarted()==1200 && inventory.flags()==0,
            "re-entry clears inventory activity");
        var interacting=movement();interacting.resetInput(100);int[] interactions={0};
        interacting.interaction(time->{interactions[0]++;return true;});
        check(interacting.accept(100,0,0,4,100,1000),"peaceful interaction input accepted");interacting.advance(150,1050);
        check(interactions[0]==1&&interacting.activity()==2,"Town interaction button reaches the handler without a combat controller");
        check(interacting.accept(151,0,0,4,100,151,1051),"held interaction accepted");interacting.advance(152,1052);
        check(interactions[0]==1,"held interaction does not repeat an operation");
        var peaceful=movement();peaceful.resetInput(100);
        check(peaceful.accept(100,1,0,4|8|16|32,100,1000),"combat input remains bounded input");
        peaceful.advance(150,1050);
        check(peaceful.activity()==0&&peaceful.flags()==0&&peaceful.x()==0,"combat input cannot start town actions");
        check(peaceful.accept(150,1,0,1|4|8|16|32,150,1050),"move with combat bits accepted");
        peaceful.advance(200,1100);
        check(peaceful.activity()==0&&peaceful.flags()==2&&Math.abs(peaceful.x()-.25f)<.00001,
            "combat bits neither block walking nor become actor flags");
        var seated=movement();seated.resetInput(100);
        check(seated.sit(100,1000),"idle knight can sit");
        seated.advance(10000,10000);
        check(seated.activity()==TownMovement.SIT&&seated.activityStarted()==100&&seated.x()==0,
            "sitting persists without input, including past movement timeout");
        check(seated.sit(10000,10000)&&seated.activityStarted()==100,"repeated sit does not restart the pose");
        check(seated.accept(10050,2,0,1,10000,10000),"movement while seated queued");
        seated.advance(10050,10050);
        check(seated.activity()==TownMovement.SIT_END&&seated.activityStarted()==10050
            &&seated.x()==0&&seated.rotation()==0,"walk starts standing without snapping facing or position");
        for(int time=10100;time<11383;time+=50){
            check(seated.accept(time,2,0,1,time,time),"fresh movement while standing");seated.advance(time,time);
            check(seated.activity()==TownMovement.SIT_END&&seated.x()==0,"standing blocks early walking");
        }
        seated.advance(11433,11433);
        check(seated.activity()==0&&seated.activityStarted()==11383&&Math.abs(seated.x()-.25f)<.00001,
            "walking resumes at the exact stock standing deadline");
        seated.resetInput(12000);check(seated.sit(12000,12000),"sit after reset");
        check(seated.accept(12050,2,0,2,12000,12000),"inventory while sitting");seated.advance(12050,12050);
        check(seated.activity()==TownMovement.INVENTORY&&!seated.sit(12050,12050),
            "inventory interrupts sitting and cannot be replaced by another sit");
        seated.resetInput(12100);
        check(seated.activity()==0&&seated.flags()==0,"scene transition clears the sitting activity and queued input");
        var animated=movement();animated.resetInput(1000);
        check(animated.emote(PlayerEmotes.find("Attack"),1000,1000),"catalogued emote starts");
        check(animated.activity()==9&&animated.activityStarted()==1000,"stock activity id and start time");
        check(animated.accept(1000,0,0,1,1000,1000),"movement begins during movement-enabled emote");
        animated.advance(1000,1000);
        for(int time=1100;time<=1600;time+=100){
            check(animated.accept(time,0,0,1,time,time),"movement frame during emote");
            animated.advance(time,time);
            check(animated.activity()==9&&animated.flags()==2,"movement and emote coexist before deadline");
        }
        animated.advance(1699,1699);
        check(animated.activity()==9,"timed activity persists until its exact deadline");
        animated.advance(1700,1700);
        check(animated.activity()==0&&animated.activityStarted()==1700&&animated.flags()==2
            &&Math.abs(animated.x()-3.5f)<.0001,"timed activity ends exactly and movement integrates across deadline");
        var sleep=movement();sleep.resetInput(2000);
        check(sleep.emote(PlayerEmotes.find("Sleep"),2000,2000)&&sleep.activity()==27
            &&sleep.activityStarted()==2000,"zero-duration blocked emote remains active");
        check(sleep.accept(2000,0,0,1,2000,2000),"movement can cancel Sleep");sleep.advance(2050,2050);
        check(sleep.activity()==0&&sleep.activityStarted()==2000&&sleep.flags()==2
            &&Math.abs(sleep.x()-.25f)<.0001,"Sleep without end animation clears to idle on movement");
        var textEmote=movement();textEmote.resetInput(3000);
        check(textEmote.emote(PlayerEmotes.find("Sit"),3000,3000),"animated pose starts");
        check(textEmote.emote(PlayerEmotes.find("Gaze"),3001,3001)
            &&textEmote.activity()==26&&textEmote.activityStarted()==3000,
            "chat-only emote leaves ongoing actor activity untouched");
        var interrupted=movement();interrupted.resetInput(4000);
        check(interrupted.emote(PlayerEmotes.find("Hello"),4000,4000),"emote can start before inventory");
        check(interrupted.accept(4050,0,0,2,4000,4000),"inventory input during emote accepted");
        interrupted.advance(4050,4050);
        check(interrupted.activity()==TownMovement.INVENTORY&&interrupted.flags()==0,
            "inventory interrupts an active emote");
        check(!interrupted.emote(PlayerEmotes.find("Hello"),4051,4051),
            "emote cannot replace active inventory pose");
        var forged=new PlayerEmotes.Descriptor("forged",264,0,0,0,true,false,true,false,0);
        check(!movement().emote(forged,5000,5000),"uncatalogued activity descriptors rejected");
        System.out.println("PASS: archived scene, spawn, movement, facing, replay limits, timeout, timestamped batches, scheduler jitter and town collision");
    }
}
