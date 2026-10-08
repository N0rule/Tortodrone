import java.io.*;import java.nio.file.*;import java.util.*;import java.util.zip.*;import com.threerings.config.*;import com.threerings.tudey.data.*;import com.threerings.math.*;
public class TownCollisionExport {
 public static void main(String[]args)throws Exception {
  var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");resource.getClass().getMethod("ga",String.class).invoke(resource,args[0]+"/rsrc");var mgr=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");mgr.init();
  var scene=(TudeySceneModel)com.threerings.export.util.b.e(Files.readAllBytes(Path.of(args[1])));scene.init(mgr);
  var actor=new com.threerings.projectx.data.actor.Knight(new ConfigReference<>("Character/PC/Default"),1,0,new Vector2f(0,-8),0);actor.init(mgr);
  var cfg=mgr.d(com.threerings.tudey.config.ActorConfig.class,"Character/PC/Default").ai(mgr);System.out.println("shape="+cfg.shape+" mask="+cfg.collisionMask);
  var shape=cfg.shape.acP();var map=new BitSet(280*304);int n=0;
  for(int yy=0;yy<304;yy++)for(int xx=0;xx<280;xx++) {float x=-35+xx*.25f,y=-32+yy*.25f;var moved=shape.d(new Transform2D(new Vector2f(x,y),0));boolean allowed=scene.aQ((int)Math.floor(x),(int)Math.floor(y))!=null&&!scene.a(actor,moved);if(allowed){map.set(yy*280+xx);n++;}}
  try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(Path.of(args[2]))))){out.writeInt(0x54445731);out.writeInt(280);out.writeInt(304);out.writeFloat(-35);out.writeFloat(-32);out.writeFloat(.25f);byte[] b=Arrays.copyOf(map.toByteArray(),(280*304+7)/8);out.write(b);}
  System.out.println("PASS: walkable samples="+n+" spawn="+map.get((int)((-8+32)*4)*280+35*4));
 }
}
