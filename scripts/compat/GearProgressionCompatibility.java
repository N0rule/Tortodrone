import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.presents.client.A;
import com.threerings.presents.dobj.InvocationRequestEvent;
import com.threerings.projectx.item.data.LevelItem;
import com.threerings.projectx.item.config.ItemConfig;
import org.tortodrone.protocol.*;
import org.tortodrone.town.TownCatalog;

/** Current installed marshallers are the oracle; no hardcoded upstream packets or graphical client. */
public final class GearProgressionCompatibility {
    private static InvocationRequestEvent captured;
    private static final class Admin extends com.threerings.projectx.admin.data.ProjectXAdminMarshaller {
        @Override protected void c(int id,Object[] args){captured=new InvocationRequestEvent(SessionCodec.INVOCATION_OID,SessionCodec.ADMIN_SERVICE,id,args);}
    }
    private static final class Item extends com.threerings.projectx.item.data.ItemMarshaller {
        @Override protected void c(int id,Object[] args){captured=new InvocationRequestEvent(SessionCodec.INVOCATION_OID,9,id,args);}
    }
    private static final class Dispatch extends com.threerings.presents.client.x {
        @Override public void a(int oid,int code,int method,Object[] args,com.threerings.presents.net.Transport transport){captured=new InvocationRequestEvent(oid,code,method,args);}
    }
    public static void main(String[] args)throws Exception{
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");var admin=new Admin();
        admin.d(98,(A.a)null);check(45,List.of(98),"ConfirmMarshaller");
        admin.b(1,2,3,(A.a)null);check(46,List.of(1,2,3),"ConfirmMarshaller");
        admin.d(1,2,(A.a)null);check(47,List.of(1,2),"ConfirmMarshaller");
        admin.a(6,.5f,(A.a)null);check(41,List.of(6,.5f),"ConfirmMarshaller");
        admin.a(6,2,(A.a)null);check(42,List.of(6,2),"ConfirmMarshaller");
        admin.a(6,4,(A.c)null);check(23,List.of(6,4),"ResultMarshaller");
        new Item().a(22L,6,0,(A.c)null);check(8,List.of(22L,6,0),"ResultMarshaller");
        var variant=new com.threerings.projectx.craft.data.VariantMarshaller();variant.a(10,101,new Dispatch());
        variant.a(2,22L,0L,1,(A.c)null);check(2,List.of(2,22L,0L,1),"ResultMarshaller");
        variant.a(22L,24L,1,(A.c)null);check(1,List.of(22L,24L,1),"ResultMarshaller");
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");resource.getClass().getMethod("ga",String.class).invoke(resource,Path.of(args[0]).resolve("rsrc").toString());
        var manager=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");manager.init();
        var rules=TownCatalog.load(Path.of("content/packs/vanilla/town/catalog.json"));
        for(String config:List.of("Weapon/Sword/Proto Sword","Weapon/Sword/Calibur","Gear/Armor/Padded Armor")){
            var nativeItem=new LevelItem(0,new ConfigReference<ItemConfig>(config));var gear=rules.gear().gear(config);
            for(int level:new int[]{0,5,8}){nativeItem.a(manager,level,.2f);float expected=nativeItem.a(manager,17.25f),actual=gear.addHeat(level,.2f,17.25f);if(Math.abs(expected-actual)>.000001f)throw new AssertionError("Native heat gain differs: "+config+" level "+level+" "+expected+" != "+actual);}
        }
        for(int count=1;count<=3;count++)if(rules.roll("Shop/Variant "+count,"SWORD").size()!=count)throw new AssertionError("Resolved UV selection count differs");
        var original=manager.d(ItemConfig.class,"Weapon/Sword/Calibur").w(manager);
        for(boolean gm:new boolean[]{false,true}){
            var codec=new SessionCodec();var bytes=new ByteArrayOutputStream();
            for(byte[] frame:List.of(HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"),HandshakeCodec.authenticationResponse((short)2,true,"synthetic","Alice"),codec.player((short)3,1001,1,"Alice",Map.of(),List.of(),gm)))bytes.write(frame,4,frame.length-4);
            var in=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));in.qe();in.qe();var player=(com.threerings.projectx.data.PlayerObject)((com.threerings.presents.net.ObjectResponse<?>)in.qe()).yk();
            if(player.b(com.threerings.projectx.admin.data.a.avR,original)!=gm)throw new AssertionError("Staff Trash permission leaked to an ordinary player");
        }
        System.out.println("PASS: installed sprite/gear/forge/UV invocation layouts and native heat formula");
        System.out.println("PASS: stock broad Trash permission is GM-only; ordinary accounts retain native item rules");
    }
    private static void check(int method,List<?> args,String listener)throws Exception{
        var buffer=new ByteArrayOutputStream();var out=new com.threerings.io.w(buffer);out.bf(new com.threerings.presents.net.ForwardEventRequest(captured));out.flush();
        var parsed=(SessionDecoder.Invocation)new HandshakeCodec().sessionRequest(buffer.toByteArray()).event();
        if(parsed.method()!=method||!parsed.arguments().subList(0,parsed.arguments().size()-1).equals(args)||!(parsed.arguments().getLast() instanceof SessionDecoder.Listener l)||!l.type().endsWith("$"+listener))throw new AssertionError("Installed invocation differs: "+parsed);
    }
}
