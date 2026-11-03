import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import com.threerings.config.*;
import com.threerings.tudey.config.*;
import org.tortodrone.world.*;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.resources.Json;

/** Native platform configuration and actual spawn/state publication on explicit synthetic geometry. */
public final class PartyPlatformCompatibility {
 public static void main(String[] args)throws Exception {
  System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
  var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");resource.getClass().getMethod("ga",String.class).invoke(resource,args[0]+"/rsrc");
  var cm=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");cm.init();
  var signal=new ActionConfig.Signal();var target=new TargetConfig.Tagged();target.tag="outro";signal.target=target;signal.name="open";
  var spawn=new ActionConfig.SpawnActor();spawn.actor=new ConfigReference<>("Dynamic/Switch/Party Platform/Base","Action",signal);
  var original=(com.threerings.projectx.dungeon.config.DungeonActorConfig.Switch)spawn.actor.a(cm,ActorConfig.class).ai(cm);
  System.out.println("NATIVE handlers="+Arrays.toString(Arrays.stream(original.handlers).map(h->h.getClass().getSimpleName()+(h instanceof HandlerConfig.BaseIntersectionCount count?" mask="+count.mask+" condition="+count.condition.getClass().getSimpleName():"")).toArray())+" states="+Arrays.toString(Arrays.stream(original.stateActions).map(a->a.getClass().getSimpleName()+(a instanceof ActionConfig.Compound compound?" children="+Arrays.toString(Arrays.stream(compound.actions).map(c->c.getClass().getSimpleName()).toArray()):"")).toArray()));
  System.out.println("ACTION "+((HandlerConfig.ActionHandlerConfig)original.handlers[2]).action);
  var ga=((ActionConfig.Conditional)((ActionConfig.Compound)((HandlerConfig.ActionHandlerConfig)original.handlers[2]).action).actions[0]).action;System.out.println("GUARD "+ga.getClass()+" "+(ga instanceof com.threerings.projectx.dungeon.config.DungeonActionConfig.FireAction fa?fa.fireAction.getName()+" / "+fa.fireAction.kW().entrySet():""));
  var graph=SceneContentExport.sceneAction(spawn,0,cm);var prepared=(SceneActions.Spawn)SceneActions.parse(Json.stringify(graph));
  if(ScenePartyPlatform.read(new SceneContent.Spec(prepared.fields()))==null)throw new AssertionError("Native platform was not prepared");
  Path temp=Files.createTempFile("party-platform-terrain-",".gz");
  try {
   try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(temp)))){out.writeInt(0x54445731);out.writeInt(65);out.writeInt(65);out.writeFloat(-16);out.writeFloat(-16);out.writeFloat(.5f);byte[] bits=new byte[(65*65+7)/8];Arrays.fill(bits,(byte)255);out.write(bits);}
   var trigger=new SceneContent.Entry("900",0,0,0,new SceneContent.Spec(Map.of("config","Fixture/Spawn","kind","SceneTrigger","event","signal-start","watch-tag","spawn","signal","intersection","scene-sensor","true","sensor-mask","4","radius",".1","action",Json.stringify(Map.of("kind","limited","limit",1,"action",graph,"otherwise",Map.of("kind","sequence","actions",List.of(),"stopOnFailure",false))))));
   var door=new SceneContent.Entry("901",8,0,0,new SceneContent.Spec(Map.of("config","Fixture/Door","kind","Door","native-type","com.threerings.projectx.dungeon.data.actor.Door","tags","outro","collision-flags","1","width","1","height","1","door-speed","0")));
   var scene=new SceneEntities(new SceneContent(List.of(trigger,door),Map.of()),CollisionMap.read(temp));
   scene.advance(1000,List.of(player(21,0),player(22,5)));scene.advance(1050,List.of(player(21,0),player(22,5)));
   var platform=scene.actors().stream().filter(a->a.entity().kind().equals("Switch")).findFirst().orElseThrow();
   var nativeActor=FireCompatibility.read(new SessionCodec().sceneDelta(1001,140,0,(short)0,1000,1050,(short)50,List.of(platform),List.of(),new int[0],Map.of(),true)).aej()[0];
   if(FireCompatibility.field(nativeActor,"_key")!=null||platform.entity().state()!=0)throw new AssertionError("Spawned platform must have no entry binding and wait for the party");
   scene.advance(1100,List.of(player(21,0),player(22,1)));scene.advance(1150,List.of(player(21,0),player(22,1)));
   var pressed=scene.actor(platform.id());var delta=FireCompatibility.read(new SessionCodec().sceneDelta(1001,140,1050,(short)0,1000,1150,(short)50,List.of(),List.of(pressed),new int[0],Map.of(platform.id(),platform),true));var changed=delta.aek()[0].apply(nativeActor);
   if((int)FireCompatibility.field(changed,"_state")!=1||!scene.walkable(8,0,0)||scene.defaultEntrance()==null)throw new AssertionError("Native platform did not press, execute its supplied action and set the checkpoint");
   System.out.println("PASS: original native party platform -> real source spawn -> living-party activation -> supplied action/checkpoint -> stock Switch creation/delta with null entry key (synthetic geometry; no full mission claim)");
  } finally {Files.deleteIfExists(temp);}
 }
 private static ActorState player(int id,float x){return new ActorState(id,id+1000,"Fixture",0,1000,x,0,0,0,0,new StarterCatalog.Model("Character/PC/Default",Map.of()));}
}
