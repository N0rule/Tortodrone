import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.projectx.item.config.ItemConfig;
import com.threerings.tudey.config.ActionConfig;
import com.threerings.presents.dobj.CompoundEvent;
import com.threerings.presents.net.EventNotification;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;

/** Read-only oracle for every player firework/firefly reference and timed upgrade attribute. */
public final class AnimatedItemsCompatibility {
    public static void main(String[] args)throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,Path.of(args[0]).resolve("rsrc").toString());
        var manager=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");manager.init();
        var catalog=ItemCatalog.load(Path.of("content/items"));int count=0;
        for(var item:catalog.entries())if(AnimatedItems.supports(item.config())){
            var action=(ItemConfig.AnimatedAction)manager.d(ItemConfig.class,item.config()).w(manager);
            check(action.land==AnimatedItems.land(item.config()),"native land time: "+item.config());
            check(action.land+action.fn(12345).clear==AnimatedItems.duration(item.config()),"native clear time: "+item.config());
            var refs=new ArrayList<ConfigReference<?>>();
            if(action.action instanceof ActionConfig.FireEffect fire)refs.add(fire.effect);
            else if(action.action instanceof ActionConfig.Random random)for(var weighted:random.actions){
                check(weighted.weight==1,"equal native random weights");refs.add(((ActionConfig.FireEffect)weighted.action).effect);
            }else throw new AssertionError("Unsupported native action: "+item.config());
            var definitions=AnimatedItems.definitions(item.config());check(refs.size()==definitions.size(),"all native effect alternatives included");
            for(int i=0;i<refs.size();i++){
                var def=definitions.get(i);var ref=refs.get(i);check(ref.getName().equals(def.effect()),"native effect name");
                if(def.effect().equals("Basic")){
                    check(Boolean.FALSE.equals(ref.kW().get("Attach to Target"))&&ref.kW().get("Lifespan").equals(6000),"native detached effect lifetime");
                    var model=(ConfigReference<?>)ref.kW().get("Model");
                    check(model.getName().equals("world/dynamic/fireworks/fx_fire.dat")&&model.kW().get("Model").equals(def.size())
                        &&model.kW().get("Colorization").equals(def.color()),"native firework model/size/color");
                }else check(ref.kW().get("Colorization").equals(def.color()),"native firefly color");
            }
            count++;
        }
        check(count==82,"all playable animated variants");
        var knight=new Knight(1,"Fixture","","",null,null,"Blue",0,2,0,0);
        long until=System.currentTimeMillis()+60000;
        var heat=new WorldProfile.Item(81,WorldProfile.Slot.OTHER,"Upgrade/Heat Bonus",null,0,null,true,false,false,1,false).withExpiration(until);
        var coin=new WorldProfile.Item(82,WorldProfile.Slot.OTHER,"Upgrade/Coin Bonus",null,0,null,true,false,false,1,false).withExpiration(until);
        var active=new WorldProfile(1,1,1,0,0,1,List.of(heat,coin));var empty=new WorldProfile(1,1,1,0,0,2,List.of());
        var codec=new SessionCodec(catalog);var bytes=new ByteArrayOutputStream();
        append(bytes,HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"));
        append(bytes,HandshakeCodec.authenticationResponse((short)2,true,"synthetic","Alice"));
        append(bytes,codec.selected(1001,knight,active));append(bytes,codec.itemChanges(1001,knight,active,empty));
        var in=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));in.qe();in.qe();
        var player=new com.threerings.projectx.data.PlayerObject();
        for(var child:((CompoundEvent)((EventNotification)in.qe()).yj()).xE())child.b(player);
        check(player.heatBonus&&player.pvpTokenBonus==1,"native timed bonus selection attributes");
        for(var child:((CompoundEvent)((EventNotification)in.qe()).yj()).xE())child.b(player);
        check(!player.heatBonus&&player.pvpTokenBonus==0&&in.available()==0,"native expiry clears bonuses without trailing bytes");
        System.out.println("PASS: 82 native animated variants, effects, colors, sizes, delays and timed bonus selection/expiry attributes");
    }
    private static void append(ByteArrayOutputStream out,byte[] frame){out.write(frame,4,frame.length-4);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
