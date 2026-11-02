import java.io.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.tudey.config.*;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.protocol.*;
import org.tortodrone.world.*;

/** Current native Retrode emergence graph through production scheduling and stock actor/effect readers.
 * Synthetic trigger geometry; does not establish Retrode targeting or a playable Generator floor. */
public final class GameplayEffectCompatibility {
    public static void main(String[] args)throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");resource.getClass().getMethod("ga",String.class).invoke(resource,args[0]+"/rsrc");
        var cm=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");cm.init();
        var action=new ActionConfig.FireEffect();var table=new ConfigReference<com.threerings.projectx.dungeon.config.SpawnTableConfig>("Monster/Tutorial/Training Grounds/Retrode","Tag","verified-wave");
        action.effect=new ConfigReference<>("Monster/Construct/Retrode/Emerge from Ground","Spawn Table",table);var target=new TargetConfig.Tagged();target.tag="emerge";action.location=target;
        var prepared=SceneActions.parse(org.tortodrone.resources.Json.stringify(SceneContentExport.sceneAction(action,0,cm)));
        if(!(prepared instanceof SceneActions.GameplayEffect gameplay)||!gameplay.locationTag().equals("emerge"))throw new AssertionError("Native gameplay effect was reduced to a cosmetic effect");
        var nativeEffect=action.effect.a(cm,com.threerings.tudey.config.EffectConfig.class);
        var delay=(ActionConfig.Delayed)((ActionConfig.Compound)nativeEffect.action).actions[0];
        if(!(gameplay.action() instanceof SceneActions.Sequence compound)||!(compound.actions().getFirst() instanceof SceneActions.Delay delayed)||delayed.millis()!=delay.delay||delayed.variance()!=delay.variance)throw new AssertionError("Native delay/variance changed");
        // Same ordinary open-grid fixture as standalone geometry tests, no original scene claim.
        var terrain=terrain();var area=new SceneContent.Entry("10",0,0,0,new SceneContent.Spec(Map.of("kind","SceneTrigger","config","Fixture/Area","handler-index","0","event","signal-start","watch-tag","area","signal","intersection","scene-sensor","true","sensor-mask","4","radius",".4","action",org.tortodrone.resources.Json.stringify(SceneContentExport.sceneAction(action,0,cm)))));
        var marker=new SceneContent.Entry("20",2,3,.4f,new SceneContent.Spec(Map.of("kind","SceneAnchor","config","Fixture/Marker","source-tags","emerge")));
        var world=new SceneEntities(new SceneContent(List.of(area,marker),Map.of()),terrain);var weapons=new SceneWeapons(terrain);world.effects(weapons::effect);
        var pawn=new ActorState(219,1001,"Fixture",0,1000,0,0,0,0,0,new StarterCatalog.Model("Character/PC/Default",Map.of()));
        world.advance(1000,List.of(pawn));if(delay.delay>0)world.advance(1000+delay.delay,List.of(pawn));
        var monster=world.actors().stream().filter(a->a.config().name().equals("Character/NPC/Monster/Construct/Retrode/Retrode (TG)")).findFirst().orElseThrow(()->new AssertionError("Native effect did not spawn the actual server-owned Retrode"));
        var spec=world.specification(monster.id());
        if(monster.x()!=2||monster.y()!=3||monster.rotation()!=.4f||!Arrays.asList(spec.text("tags","").split(",")).contains("verified-wave"))throw new AssertionError("Native parameter binding, spawn transform or wave tag lost: "+spec.text("tags",""));
        var effects=weapons.effects();if(effects.size()!=1||!Integer.valueOf(20).equals(effects.getFirst().targetEntry()))throw new AssertionError("Native emergence effect lost entry identity");
        var bytes=new ByteArrayOutputStream();var codec=new SessionCodec();append(bytes,HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"));append(bytes,HandshakeCodec.authenticationResponse((short)2,true,"synthetic","Fixture"));
        append(bytes,codec.sceneDelta(1001,140,0,(short)0,1000+delay.delay,1000+delay.delay,(short)50,List.of(monster),List.of(),new int[0],Map.of(),true,effects));
        var reader=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));reader.qe();reader.qe();var delta=(com.threerings.tudey.dobj.SceneDeltaEvent)((com.threerings.presents.net.EventNotification)reader.qe()).yj();
        if(delta.aej().length!=1||!(delta.aej()[0] instanceof com.threerings.projectx.dungeon.data.actor.Monster)||delta.aem().length!=1||reader.available()!=0)throw new AssertionError("Native Monster/effect framing differs");
        System.out.println("PASS: native Retrode emergence compound/delay/point impulse/table spawn -> real scene-owned Monster, exact wave/transform, original entry-targeted effect and stock reader (synthetic trigger; AI/floor/graphics not verified)");
    }
    private static CollisionMap terrain()throws Exception {
        var bits=new BitSet(81*81);bits.set(0,81*81);var bytes=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(new java.util.zip.GZIPOutputStream(bytes))){out.writeInt(0x54445731);out.writeInt(81);out.writeInt(81);out.writeFloat(-10);out.writeFloat(-10);out.writeFloat(.25f);out.write(Arrays.copyOf(bits.toByteArray(),(81*81+7)/8));}
        var file=java.nio.file.Files.createTempFile("tortodrone-effect-grid-",".gz");
        try{java.nio.file.Files.write(file,bytes.toByteArray());return CollisionMap.read(file);}finally{java.nio.file.Files.delete(file);}
    }
    private static void append(ByteArrayOutputStream bytes,byte[] frame){bytes.write(frame,4,frame.length-4);}
}
