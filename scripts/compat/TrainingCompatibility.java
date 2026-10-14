import java.nio.file.*;
import java.util.*;
import java.io.*;
import org.tortodrone.world.*;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;

/** Focused native-reader acceptance for prepared scene entities and their deltas. */
public final class TrainingCompatibility {
    public static void main(String[] args)throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        Path pack=Path.of(args[0]);
        var content=SceneContent.load(pack,"bbf967c39b3883e438fd8165fba74c3be45cfcbecdec9c585f1edaa21d1cc144");
        var entities=new SceneEntities(content,CollisionMap.read(Path.of("content/packs/vanilla/maps/advanced_training/walkability.gz")));
        var actors=entities.actors();
        var added=FireCompatibility.read(new SessionCodec().sceneDelta(1001,140,0,(short)0,100,100,(short)50,actors,List.of(),new int[0],Map.of(),true));
        if(added.aej().length!=actors.size())throw new AssertionError("Missing native scene actors");
        var baseline=new HashMap<Integer,ActorState>();actors.forEach(a->baseline.put(a.id(),a));

        for(int i=0;i<actors.size();i++){
            var state=actors.get(i);var nativeActor=added.aej()[i];
            if(!nativeActor.getClass().getName().equals(state.entity().nativeClass()))throw new AssertionError("Wrong native entity type");
            if(!FireCompatibility.field(nativeActor,"_config").equals(FireCompatibility.reference(state.config())))throw new AssertionError("Changed native actor arguments");
            com.threerings.tudey.dobj.SceneDeltaEvent updated;
            try{updated=FireCompatibility.read(new SessionCodec().sceneDelta(1001,140,100,(short)0,100,150,(short)50,List.of(),List.of(state),new int[0],Map.of(state.id(),state),true));}
            catch(Exception failure){throw new IOException("Native entity delta failed for "+state.entity().kind(),failure);}
            var applied=updated.aek()[0].apply(nativeActor);
            if((int)FireCompatibility.field(applied,"_id")!=state.id())throw new AssertionError("Entity delta changed identity");
            if(state.entity().kind().equals("Monster")&&Math.abs((float)FireCompatibility.field(applied,"_healthPct")-1)>.0001)throw new AssertionError("Wrong target health delta");
        }
        var npc=actors.stream().filter(a->a.entity().kind().equals("Stranger")).findFirst().orElseThrow();
        var capturedPrompt=entities.interaction(5.5f,.7496119f,1.3592349f);
        if(capturedPrompt==null||capturedPrompt.actorId()!=npc.id()||!capturedPrompt.action().equals("Talk"))
            throw new AssertionError("Captured counter-front Konway Talk prompt is unreachable");
        var pc=new ActorState(1,1001,"Fixture",256,100,0,0,0,0,0,0,100,
            new StarterCatalog.Model("Character/PC/Default",Map.of()),false,null,null,null,capturedPrompt);
        var nativePc=FireCompatibility.read(new SessionCodec().sceneDelta(1001,140,0,(short)0,100,100,(short)50,List.of(pc),List.of(),new int[0],Map.of(),true)).aej()[0];
        if(FireCompatibility.field(nativePc,"_actionDesc")==null||FireCompatibility.field(nativePc,"_actionEntity")==null)throw new AssertionError("Native interaction prompt missing");
        var pcDelta=FireCompatibility.read(new SessionCodec().sceneDelta(1001,140,100,(short)0,100,150,(short)50,List.of(),List.of(pc),new int[0],Map.of(1,pc),true));
        pcDelta.aek()[0].apply(nativePc);
        // Exercise non-empty native status/lift fields, not just null/default masks.
        var condition=content.statuses().entrySet().stream().filter(e->e.getValue().text("class","").equals("Freeze")).findFirst().orElseThrow();
        var status=new SceneStatus.State(List.of(new StarterCatalog.Model(condition.getKey(),Map.of())),List.of(5000),
            new SceneStatus.Modifiers(1,1,1,1,1,false,false,true,false,false));
        var carrying=new ActorState(pc.id(),pc.clientOid(),pc.name(),pc.color(),pc.created(),pc.x(),pc.y(),pc.rotation(),pc.direction(),pc.flags(),268,150,
            pc.config(),false,null,null,null,pc.interaction(),new SceneKnightState(new StarterCatalog.Model("Pickup/Capsule/Health Capsule",Map.of()),actors.getFirst().id(),status));
        var carriedDelta=FireCompatibility.read(new SessionCodec().sceneDelta(1001,140,100,(short)0,100,150,(short)50,List.of(),List.of(carrying),new int[0],Map.of(pc.id(),pc),true));
        var carried=carriedDelta.aek()[0].apply(nativePc);
        if((int)FireCompatibility.field(carried,"_lift")!=actors.getFirst().id()||FireCompatibility.field(carried,"_activityData")==null
                ||java.lang.reflect.Array.getLength(FireCompatibility.field(carried,"_conditions"))!=1)throw new AssertionError("Native knight lift/status delta mismatch");
        gameplay(content);
        quickbar();
        var conversation=content.conversations().get(npc.config().name());
        var bytes=new ByteArrayOutputStream();var codec=new SessionCodec();
        for(var frame:List.of(HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"),
                HandshakeCodec.authenticationResponse((short)2,true,"synthetic","Fixture"),
                codec.conversationDialog(1001,140,conversation,conversation.initial(),npc,1)))bytes.write(frame,4,frame.length-4);
        var input=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));input.qe();input.qe();
        var event=(com.threerings.presents.dobj.CompoundEvent)((com.threerings.presents.net.EventNotification)input.qe()).yj();
        var dialog=(com.threerings.projectx.data.ConversationDialogInfo)((com.threerings.presents.dobj.AttributeChangedEvent)event.xE().get(1)).getValue();
        if(!Arrays.equals(dialog.responses,conversation.nodes().get(conversation.initial()).responses().stream().map(SceneConversation.Response::text).toArray(String[]::new))||dialog.conversationService==null)
            throw new AssertionError("Native conversation responses or service missing");
        if(input.available()!=0)throw new AssertionError("Conversation framing mismatch");
        System.out.println("PASS: native reader accepts "+actors.size()+" scene actors, all subtype deltas, target health and Konway conversation/prompt framing");
    }
    private static StarterCatalog.Model hit(int flags,float amount){
        var damage=new FireCatalog.DataValue("com.threerings.projectx.dungeon.config.DamageConfig$Normal",List.of(
            new FireCatalog.FieldValue("flags","int",flags),new FireCatalog.FieldValue("amount","float",amount)));
        return new StarterCatalog.Model("DamageRegion",Map.of("damage",damage,"shape",new FireCatalog.DataValue(
            "com.threerings.tudey.shape.config.ShapeConfig$Circle",List.of(new FireCatalog.FieldValue("radius","float",.1f)))));
    }
    private static ActorState player(float x,float y,float rotation){return new ActorState(219,1001,"Fixture",256,100,x,y,rotation,rotation,0,new StarterCatalog.Model("Character/PC/Default",Map.of()));}
    private static void gameplay(SceneContent content)throws Exception {
        var collision=CollisionMap.read(Path.of("content/packs/vanilla/maps/advanced_training/walkability.gz"));
        var world=new SceneEntities(content,collision);int stamp=SceneClock.now();
        long initialTargets=world.actors().stream().filter(a->a.entity().kind().equals("Monster")).count();
        var cube=world.actors().stream().filter(a->a.entity().kind().equals("Monster")).findFirst().orElseThrow();
        world.damage(hit(2,1000),cube.x(),cube.y(),0,stamp,219);
        if(world.actor(cube.id())!=null)throw new AssertionError("Cube did not take weapon damage");
        var dying=world.actors().stream().filter(a->a.id()==cube.id()).findFirst().orElseThrow();
        var nativeDying=FireCompatibility.read(new SessionCodec().sceneDelta(1001,140,0,(short)0,100,100,(short)50,List.of(dying),List.of(),new int[0],Map.of(),true)).aej()[0];
        if((int)FireCompatibility.field(nativeDying,"_affectedTime")!=stamp||(int)FireCompatibility.field(nativeDying,"_activity")!=2)throw new AssertionError("Monster hit/death feedback missing");
        int deathEnds=(int)FireCompatibility.field(nativeDying,"_destroyed");
        world.advance(deathEnds+150,List.of());
        if(world.actors().stream().anyMatch(a->a.id()==cube.id()))throw new AssertionError("Monster death animation duration was counted twice");
        for(int at=deathEnds+200;at<=stamp+10100;at+=50)world.advance(at,List.of());
        if(world.actors().stream().filter(a->a.entity().kind().equals("Monster")).count()!=initialTargets)throw new AssertionError("Training target pad did not regenerate: initial="+initialTargets+" current="+world.actors().stream().filter(a->a.entity().kind().equals("Monster")).count());
        var lever=world.actors().stream().filter(a->a.entity().kind().equals("Switch")&&a.config().name().equals("Dynamic/Switch/Lever/Damage Toggle")).findFirst().orElseThrow();
        var doorFlags=new HashMap<Integer,Integer>();world.actors().stream().filter(a->a.entity().kind().equals("Door")).forEach(a->doorFlags.put(a.id(),a.flags()));
        world.damage(hit(2,100),lever.x(),lever.y(),0,stamp+10200,219);
        if(world.actors().stream().filter(a->a.entity().kind().equals("Door")).noneMatch(a->(a.flags()&2)!=(doorFlags.get(a.id())&2)))throw new AssertionError("Scene-tagged lever did not change a door");
        var pickup=world.actors().stream().filter(a->a.entity().kind().equals("Pickup")).findFirst().orElseThrow();
        world.advance(stamp+10300,List.of(player(pickup.x(),pickup.y(),0)));
        var claims=world.takePickups(219);if(claims.isEmpty())throw new AssertionError("Pickup was not claimed");
        for(var claim:claims)world.completePickup(219,claim.entityId(),true);
        if(world.actor(pickup.id())!=null)throw new AssertionError("Accepted pickup was not removed");
        var lift=world.actors().stream().filter(a->a.entity().kind().equals("DungeonLift")&&a.config().name().equals("Dynamic/Lift Objects/Clay Pot")).findFirst().orElseThrow();
        var owner=player(lift.x(),lift.y(),0);if(!world.carry(lift.id(),owner,stamp+10400))throw new AssertionError("Lift was not carried");
        world.throwLift(lift.id(),owner,stamp+10500);var thrown=world.actor(lift.id());
        var nativeLift=FireCompatibility.read(new SessionCodec().sceneDelta(1001,140,0,(short)0,100,100,(short)50,List.of(lift),List.of(),new int[0],Map.of(),true)).aej()[0];
        var delta=FireCompatibility.read(new SessionCodec().sceneDelta(1001,140,100,(short)0,100,150,(short)50,List.of(),List.of(thrown),new int[0],Map.of(lift.id(),lift),true));
        var applied=delta.aek()[0].apply(nativeLift);
        if((float)FireCompatibility.field(applied,"_z")!=1||(int)FireCompatibility.field(applied,"_faction")!=219
                ||((int[])FireCompatibility.field(applied,"_actorIds")).length!=0)throw new AssertionError("Native thrown-lift delta mismatch");
        var health=new SceneHealth(content);if(!health.damage(hit(8,30).arguments().get("damage"),stamp)||!health.heal(120)||health.state().current()!=health.state().maximum())throw new AssertionError("Party damage/heal mismatch");
        var map=SceneCatalog.load(Path.of("content/packs/vanilla/maps")).scene(294);
        var movement=new TownMovement(collision,map.spawnX(),map.spawnY(),map.spawnRotation());movement.combat(new KnightCombat(WeaponCatalog.empty()));
        var interactions=new java.util.concurrent.atomic.AtomicInteger();movement.interaction(at->{interactions.incrementAndGet();return true;});
        movement.accept(100,0,0,4,99,100,100);movement.advance(100,100);
        if(interactions.get()!=1)throw new AssertionError("Delayed native interaction edge was ignored");
        if(world.actors().stream().anyMatch(a->a.entity().kind().startsWith("Block")&&a.entity().state()!=0))throw new AssertionError("A pad activated a block without weapon damage");
        var plate=world.actors().stream().filter(a->a.entity().kind().equals("Switch")&&a.config().name().equals("Dynamic/Switch/Pressure Plate/Base")).findFirst().orElseThrow();
        var weight=world.actors().stream().filter(a->a.entity().kind().equals("DungeonLift")&&a.config().name().equals("Dynamic/Lift Objects/Heavy Statue")).findFirst().orElseThrow();
        var dropper=player(plate.x()-1.35f,plate.y(),0);if(!world.carry(weight.id(),dropper,stamp+10600))throw new AssertionError("Heavy weight could not be lifted");world.dropLift(weight.id(),dropper,stamp+10600);
        world.advance(stamp+10650,List.of(dropper));
        if(Math.hypot(world.actor(weight.id()).x()-plate.x(),world.actor(weight.id()).y()-plate.y())>.001||world.actor(plate.id()).entity().state()!=2)throw new AssertionError("Heavy weight did not press native plate: plate="+plate+" weight="+world.actor(weight.id()));
        var dropped=world.actor(weight.id());
        var nativeWeight=FireCompatibility.read(new SessionCodec().sceneDelta(1001,140,0,(short)0,100,100,(short)50,List.of(dropped),List.of(),new int[0],Map.of(),true)).aej()[0];
        if(!new com.threerings.math.Vector2f(plate.x(),plate.y()).equals(FireCompatibility.field(nativeWeight,"_dropPoint")))throw new AssertionError("Native statue drop point missing");
        var spikes=world.actors().stream().filter(a->a.entity().kind().equals("Trap")&&a.config().name().endsWith("/Spikes")).findFirst().orElseThrow();
        var hazards=new SceneWeapons(collision);hazards.targets(world);world.attacks((actor,action)->hazards.fire(action,actor,stamp+10700));
        var trapHealth=new SceneHealth(content);world.playerDamage((id,damage)->trapHealth.damage(damage,stamp+10700));
        for(int at=stamp+10700;at<stamp+20700&&trapHealth.state().current()==trapHealth.state().maximum();at+=50)world.advance(at,List.of(player(spikes.x(),spikes.y(),0)));
        if(trapHealth.state().current()==trapHealth.state().maximum())throw new AssertionError("Raised spikes did not apply native trap damage");
        System.out.println("PASS: target damage/regeneration, scene-tagged door switch, pickup claim, thrown lift wire fields, delayed interaction edge, inactive block pads, snapped heavy pressure plate, trap damage and party healing");
    }

    private static Object nativeMessage(byte[] frame)throws Exception {
        var bytes=new ByteArrayOutputStream();for(var part:List.of(HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"),HandshakeCodec.authenticationResponse((short)2,true,"synthetic","Fixture"),frame))bytes.write(part,4,part.length-4);
        var input=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));input.qe();input.qe();Object message=input.qe();
        if(input.available()!=0)throw new AssertionError("Native message framing mismatch");return message;
    }
    private static void quickbar()throws Exception {
        var map=SceneCatalog.load(Path.of("content/packs/vanilla/maps")).scene(294);var scene=new SceneRegistry.Instance(140,0,map);
        var knight=new Knight(1,"Fixture","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0);
        var profile=new WorldProfile(219,1,1,0,0,0,List.of());
        var party=(com.threerings.projectx.dungeon.data.LevelPartyObject)((com.threerings.presents.net.ObjectResponse<?>)nativeMessage(new SessionCodec().interiorParty((short)4,640,1001,knight,profile,scene))).yk();
        if(!party.hasPickups)throw new AssertionError("Native quickbar HUD is hidden");
        if(!party.members.e(1001))throw new AssertionError("Native party does not contain its local player oid");
        var event=(com.threerings.presents.dobj.CompoundEvent)((com.threerings.presents.net.EventNotification)nativeMessage(new SessionCodec().quickbar(1001,new long[]{-1,-2,0,0}))).yj();
        if(event.xE().size()!=4||!Long.valueOf(-1).equals(((com.threerings.presents.dobj.ElementUpdatedEvent)event.xE().getFirst()).getValue()))throw new AssertionError("Native quickbar slot event mismatch");
        System.out.println("PASS: native party enables pickups and four-slot quickbar updates preserve transient item IDs");
    }

}
