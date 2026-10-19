import java.io.*;
import java.util.*;
import com.threerings.presents.client.A;
import com.threerings.presents.dobj.*;
import com.threerings.presents.net.*;
import com.threerings.util.Name;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.town.TownState;

/** Read-only stock marshallers/readers for native slash commands and named loadouts. */
public final class CommandCompatibility {
    private static InvocationRequestEvent captured;
    private static final class Admin extends com.threerings.projectx.admin.data.ProjectXAdminMarshaller {
        @Override protected void c(int method,Object[] args){captured=new InvocationRequestEvent(3,SessionCodec.ADMIN_SERVICE,method,args);}
    }
    private static final class Item extends com.threerings.projectx.item.data.ItemMarshaller {
        @Override protected void c(int method,Object[] args){captured=new InvocationRequestEvent(3,9,method,args);}
    }
    private static final class Chat extends com.threerings.crowd.chat.data.ChatMarshaller {
        @Override protected void c(int method,Object[] args){captured=new InvocationRequestEvent(3,3,method,args);}
    }
    public static void main(String[] args)throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");var admin=new Admin();var item=new Item();var chat=new Chat();
        admin.e(new Name("Jane"),(A.a)null);checkInvocation(13,List.of("Jane"),true);
        admin.b(new Name("Jane"),(A.a)null);checkInvocation(21,List.of("Jane"),true);
        admin.a(new Name("Jane"),(A.a)null);checkInvocation(27,List.of("Jane"),true);
        admin.c("30893",(A.a)null);checkInvocation(28,List.of("30893"),true);
        admin.b("haven",(A.a)null);checkInvocation(30,List.of("haven"),true);
        admin.c(new Name("Jane"),(A.a)null);checkInvocation(50,List.of("Jane"),true);
        admin.c(10,5,(A.a)null);checkInvocation(24,List.of(10,5),true);
        admin.b(1,"Accessory/Test",(A.a)null);checkInvocation(1,List.of(1,"Accessory/Test"),true);
        admin.c(1,(A.a)null);checkInvocation(5,List.of(1),true);
        item.m("Battle",(A.a)null);checkInvocation(10,List.of("Battle"),true);
        item.l("Battle",(A.a)null);checkInvocation(4,List.of("Battle"),true);
        chat.by("Away");checkInvocation(1,List.of("Away"),false);
        chat.by(null);checkInvocation(1,Collections.singletonList(null),false);
        var codec=new SessionCodec();var bytes=new ByteArrayOutputStream();
        append(bytes,HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"));
        append(bytes,HandshakeCodec.authenticationResponse((short)2,true,"synthetic","Alice"));
        append(bytes,codec.player((short)3,1001,1,"Alice"));
        append(bytes,codec.chatChannel((short)4,1500000000,6));append(bytes,codec.chatChannel((short)5,1500000001,7));
        var equipment=new ArrayList<Long>(Collections.nCopies(16,0L));equipment.set(6,123L);
        var saved=new TownState.Loadout("Battle",equipment,0,0,null);
        var state=new TownState(List.of(),List.of(),0,List.of(),List.of(),List.of(saved,new TownState.Loadout("alpha",equipment,0,0,null)));
        append(bytes,codec.loadouts(1001,new WorldProfile(1,1,1,0,0,1,List.of(),KnightProgress.RECRUIT,state)));
        append(bytes,codec.tellSucceeded(1001,(short)6,"Away"));
        var in=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));in.qe();in.qe();
        var player=(com.threerings.projectx.data.PlayerObject)((ObjectResponse<?>)in.qe()).yk();
        for(int i=0;i<2;i++) {
            var channel=(com.threerings.projectx.data.ChatObject)((ObjectResponse<?>)in.qe()).yk();
            require(channel.xL()==1500000000+i&&channel.speakService!=null&&channel.occupants.size()==0,"native ChatObject field layout");
            require(channel.speakType.ordinal()==6+i,"native zone-general/trade channel type");
        }
        var event=(AttributeChangedEvent)((EventNotification)in.qe()).yj();event.b(player);
        var nativeSaved=player.loadouts.f("battle");
        require(player.loadouts.size()==3&&player.loadouts.f("alpha")!=null&&nativeSaved!=null&&nativeSaved.equipment.length==12&&nativeSaved.equipment[6]==123L&&nativeSaved.spriteId==0&&nativeSaved.harnessId==0,"native named loadout identity and compressed equipment-slot layout");
        var reply=(InvocationResponseEvent)((EventNotification)in.qe()).yj();
        require(reply.yc()==1&&reply.yd().length==2&&reply.yd()[0].equals(0L)&&reply.yd()[1].equals("Away"),"native TellMarshaller away response");
        require(in.available()==0,"no trailing bytes");
        System.out.println("PASS: native GM who/kick/go/summon/move/accessory, save/equip loadout, away/back RPCs, ChatObject channel layouts, named loadout encoding and tell-away callback");
    }
    private static void append(ByteArrayOutputStream stream,byte[] frame){stream.write(frame,4,frame.length-4);}
    private static void checkInvocation(int method,List<?> args,boolean confirm)throws Exception {
        var buffer=new ByteArrayOutputStream();var out=new com.threerings.io.w(buffer);out.bf(new ForwardEventRequest(captured));out.flush();
        var parsed=(SessionDecoder.Invocation)new HandshakeCodec().sessionRequest(buffer.toByteArray()).event();
        require(parsed.method()==method&&parsed.arguments().subList(0,args.size()).equals(args),"native RPC argument order");
        require(parsed.arguments().size()==args.size()+(confirm?1:0),"native RPC argument count");
        if(confirm)require(parsed.arguments().getLast() instanceof SessionDecoder.Listener l&&l.type().equals("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller"),"native confirm callback");
    }
    private static void require(boolean value,String label){if(!value)throw new AssertionError(label);}
}
