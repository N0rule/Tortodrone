import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import com.threerings.config.*;
import com.threerings.projectx.data.*;
import com.threerings.projectx.item.config.*;
import com.threerings.presents.net.*;
public class PreviewCompatibility {
 static void append(ByteArrayOutputStream s,byte[] f){s.write(f,4,f.length-4);}
 public static void main(String[] args)throws Exception {
  System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
  var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
  resource.getClass().getMethod("ga",String.class).invoke(resource,args[0]+"/rsrc");
  var manager=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");manager.init();
  var builder=Arrays.stream(Class.forName("com.threerings.projectx.item.b.b").getMethods()).filter(m->m.getName().equals("a")&&Arrays.equals(m.getParameterTypes(),new Class[]{int.class,boolean.class,boolean.class,ProjectXCodes.EyeType.class,ProjectXCodes.KnightHeight.class,Collection.class,Collection.class})).findFirst().orElseThrow();
  var bytes=new ByteArrayOutputStream();append(bytes,HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"));append(bytes,HandshakeCodec.authenticationResponse((short)2,true,"synthetic-session","Alice"));
  var codec=new SessionCodec();var knights=new ArrayList<Knight>();
  for(String line:Files.readAllLines(Path.of(args[1]))) {
   if(line.startsWith("#")||line.isBlank())continue;
   var fields=line.split("\\|");var type=fields[0];String costume;
   if(type.equals("HELM")||type.equals("ARMOR"))costume=fields[1].substring(fields[1].lastIndexOf('(')+1,fields[1].length()-1);
   else costume=fields[1].substring(fields[1].lastIndexOf(", ")+2);
   var knight=new Knight(1,"Preview-Knight",type.equals("HELM")?fields[1]:"Gear/Helm/Spiral Sallet ("+costume+")",type.equals("ARMOR")?fields[1]:"Gear/Armor/Spiral Culet ("+costume+")",type.equals("HELM_ACCESSORY")?fields[1].substring(0,fields[1].lastIndexOf(", ")):null,type.equals("BACK_ACCESSORY")?fields[1].substring(0,fields[1].lastIndexOf(", ")):null,costume,7,4,29,1);
   knights.add(knight);
  }
  for(int eye=0;eye<47;eye++)knights.add(new Knight(1,"Eye-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,eye,1));
  for(var knight:knights)append(bytes,codec.player((short)3,1001,1,"Alice",Map.of(),List.of(knight)));
  var input=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));input.qe();input.qe();
  for(var knight:knights){
   var player=(PlayerObject)((ObjectResponse)input.qe()).yk();
   var items=List.of(manager.d(ItemConfig.class,knight.helm()).w(manager),manager.d(ItemConfig.class,knight.armor()).w(manager));
   var accessories=new ArrayList<AccessoryConfig>();
   if(knight.helmAccessory()!=null)accessories.add(manager.d(AccessoryConfig.class,knight.helmAccessory()+", "+knight.costume()));
   if(knight.armorAccessory()!=null)accessories.add(manager.d(AccessoryConfig.class,knight.armorAccessory()+", "+knight.costume()));
   var expected=builder.invoke(null,256|knight.color(),false,true,ProjectXCodes.EyeType.values()[knight.eye()],ProjectXCodes.KnightHeight.values()[knight.height()],items,accessories);
   if(!player.availableKnights[0].model.equals(expected))throw new AssertionError("Preview differs: "+knight+"\n actual "+player.availableKnights[0].model+"\n expected "+expected);
  }
  if(input.available()!=0)throw new AssertionError("Trailing serialized data");
  System.out.println("PASS: installed client deserializes "+knights.size()+" previews; all 126 starter references and 47 eye choices match its own model builder exactly");
 }
}
