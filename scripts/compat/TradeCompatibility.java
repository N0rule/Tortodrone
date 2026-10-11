import com.threerings.io.Streamer;
import com.threerings.io.w;
import com.threerings.io.v;
import com.threerings.presents.dobj.DSet;
import com.threerings.projectx.trade.data.TradeObject;
import com.threerings.projectx.trade.data.TradeDialogInfo;
import com.threerings.projectx.trade.data.TradeMarshaller;
import com.threerings.projectx.social.data.Notification;
import com.threerings.util.Name;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Installed-client trade wire oracle. Run with the stock game jars on the classpath. */
public final class TradeCompatibility {
    private TradeCompatibility() {}

    public static void main(String[] args) throws Exception {
        System.setProperty("com.threerings.io.enumPolicy", "ORDINAL");
        schemas(TradeObject.class, TradeObject.ItemOrder.class, TradeDialogInfo.class,
            Notification.TradeRequest.class);
        marshaller();
        notification();
        roundTrips();
        serverFrames();
    }

    /** Independent stock decoder applies the emulator's actual events to native objects. */
    private static void serverFrames()throws Exception {
        var knight=new org.tortodrone.character.Knight(1,"First-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,1);
        var other=new org.tortodrone.character.Knight(1,"Second-Knight",knight.helm(),knight.armor(),null,null,"Blue",1,2,0,1);
        var item=new org.tortodrone.character.WorldProfile.Item(17,org.tortodrone.character.WorldProfile.Slot.SWORD,"Weapon/Sword/Calibur",null,0,null,false,true,false);
        var first=new org.tortodrone.character.WorldProfile(10,1,1,100,40,1,List.of(item));
        var second=new org.tortodrone.character.WorldProfile(11,2,1,50,30,1,List.of());
        var empty=new org.tortodrone.protocol.SessionCodec.TradeView(1001,knight,first,List.of(),other,second,List.of(),0,0,0,0,1,0,0,false);
        var offered=new org.tortodrone.protocol.SessionCodec.TradeView(1001,knight,first,List.of(item),other,second,List.of(),12,0,0,0,2,2,0,false);
        var removed=new org.tortodrone.protocol.SessionCodec.TradeView(1001,knight,first,List.of(),other,second,List.of(),0,0,0,0,3,0,0,false);
        var codec=new org.tortodrone.protocol.SessionCodec();var bytes=new ByteArrayOutputStream();
        append(bytes,org.tortodrone.protocol.HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"));
        append(bytes,org.tortodrone.protocol.HandshakeCodec.authenticationResponse((short)2,true,"fixture","Alice"));
        append(bytes,codec.player((short)3,1002,1,"Alice"));append(bytes,codec.selected(1002,knight,first));
        for(boolean gm:new boolean[]{true,false})for(boolean hidden:new boolean[]{true,false})
            append(bytes,codec.playerFlags(1002,gm,hidden));
        append(bytes,codec.tradeNotification(1002,other.name(),true));append(bytes,codec.tradeDialog(1002,empty,987654L));
        append(bytes,codec.tradeObject((short)4,empty));append(bytes,codec.tradeChanges(empty,offered));append(bytes,codec.tradeChanges(offered,removed));
        append(bytes,codec.tradeNotification(1002,other.name(),false));append(bytes,codec.tradeDialog(1002,null,987654L));
        append(bytes,codec.itemChanges(1002,knight,first,new org.tortodrone.character.WorldProfile(10,1,1,100,40,2,List.of())));
        var input=new v(new ByteArrayInputStream(bytes.toByteArray()));input.qe();input.qe();
        var player=(com.threerings.projectx.data.PlayerObject)((com.threerings.presents.net.ObjectResponse)input.qe()).yk();
        apply(input,player);
        for(boolean gm:new boolean[]{true,false})for(boolean hidden:new boolean[]{true,false}) {
            apply(input,player);
            if(!player.bR(32)||player.bR(256)!=gm||player.bR(1024)!=hidden)
                throw new AssertionError("Native notification HUD/role/privacy flags mismatch");
        }
        apply(input,player);
        if(player.notifications.size()!=1)throw new AssertionError("Native notification entry missing");
        apply(input,player);
        var dialog=(TradeDialogInfo)player.sceneDialog;
        if(dialog.tradeOid!=1001||dialog.names.length!=2||!dialog.names[1].toString().equals(other.name())||player.sceneDialogCreated!=987654L)throw new AssertionError("Native trade dialog identity/names mismatch");
        var trade=(TradeObject)((com.threerings.presents.net.ObjectResponse)input.qe()).yk();
        apply(input,trade);
        if(trade.firstItems.size()!=1||trade.orderings.size()!=1||trade.crowns[0]!=12||trade.revision!=2||trade.accepted[0]!=2)throw new AssertionError("Native offer events missing");
        apply(input,trade);
        if(!trade.firstItems.isEmpty()||!trade.orderings.isEmpty()||trade.crowns[0]!=0||trade.revision!=3||trade.accepted[0]!=0)throw new AssertionError("Native offer removal/invalidation missing");
        apply(input,player);apply(input,player);apply(input,player);
        if(!player.notifications.isEmpty()||player.sceneDialog!=null||!player.items.isEmpty()||input.available()!=0)throw new AssertionError("Native cancel/inventory removal mismatch");
        System.out.println("PASS: stock decoder applies emulator trade snapshots, dialogs, notification/offer removals, revisions and inventory removals");
    }
    private static void append(ByteArrayOutputStream bytes,byte[] frame){bytes.write(frame,4,frame.length-4);}
    private static void apply(v input,com.threerings.presents.dobj.DObject object)throws Exception {
        var event=((com.threerings.presents.net.EventNotification)input.qe()).yj();
        if(event instanceof com.threerings.presents.dobj.CompoundEvent compound)for(var child:compound.xE())child.b(object);
        else event.b(object);
    }

    private static void schemas(Class<?>... classes) throws Exception {
        for (Class<?> type : classes) {
            Streamer streamer = Streamer.bR(type);
            Field fields = streamer.getClass().getDeclaredField("_fields");
            fields.setAccessible(true);
            System.out.println("FIELDS " + type.getName());
            for (Field field : (Field[]) fields.get(streamer)) {
                System.out.println("  " + field.getDeclaringClass().getSimpleName() + "." +
                    field.getName() + ":" + field.getType().getName());
            }
        }
    }

    private static void marshaller() throws Exception {
        Map<String, Class<?>[]> expected = Map.of(
            "p", new Class<?>[]{int.class, com.threerings.presents.client.A.a.class},
            "c", new Class<?>[]{long.class, int.class, com.threerings.presents.client.A.a.class},
            "d", new Class<?>[]{long.class, int.class, com.threerings.presents.client.A.a.class},
            "q", new Class<?>[]{int.class, com.threerings.presents.client.A.a.class},
            "r", new Class<?>[]{int.class, com.threerings.presents.client.A.a.class});
        Method[] methods = TradeMarshaller.class.getDeclaredMethods();
        Arrays.sort(methods, Comparator.comparing(Method::getName));
        for (Method method : methods) {
            if (!expected.containsKey(method.getName())) continue;
            if (!Arrays.equals(method.getParameterTypes(), expected.get(method.getName())))
                throw new AssertionError("Unexpected TradeMarshaller signature: " + method);
            System.out.println("RPC " + method.getName() + Arrays.toString(method.getParameterTypes()));
        }
        for (String name : expected.keySet())
            if (Arrays.stream(methods).noneMatch(m -> m.getName().equals(name)))
                throw new AssertionError("Missing TradeMarshaller method " + name);
    }

    private static void notification() {
        Notification.TradeRequest request = new Notification.TradeRequest(new Name("TradeOracle"));
        String[] keys = request.YG();
        if (keys.length != 2 || !keys[0].equals("m.trade_reject") || !keys[1].equals("m.trade_accept") ||
            !request.getKey().equals("TRADE_REQ:TradeOracle"))
            throw new AssertionError("Unexpected trade request notification mapping");
        System.out.println("NOTIFICATION key=" + request.getKey() + " buttons=" + Arrays.toString(keys));
    }

    private static void roundTrips() throws Exception {
        TradeObject trade = new TradeObject();
        trade.cH(123);
        trade.firstItems = new DSet<>();
        trade.secondItems = new DSet<>();
        trade.orderings = new DSet<>(List.of(new TradeObject.ItemOrder(0x0102030405060708L, 0x11223344)));
        trade.crowns = new int[]{7, 8};
        trade.energy = new int[]{9, 10};
        trade.revision = 11;
        trade.accepted = new int[]{12, 13};
        trade.completed = true;

        TradeDialogInfo dialog = new TradeDialogInfo();
        dialog.sourceRotation = 12.5f;
        dialog.names = new Name[]{new Name("One"), new Name("Two")};
        dialog.tradeOid = 456;
        Notification.TradeRequest request = new Notification.TradeRequest(new Name("Trader"));

        TradeObject decodedTrade = (TradeObject) read(write(trade));
        TradeDialogInfo decodedDialog = (TradeDialogInfo) read(write(dialog));
        Notification.TradeRequest decodedRequest = (Notification.TradeRequest) read(write(request));
        TradeObject.ItemOrder decodedOrder = (TradeObject.ItemOrder) read(write(
            new TradeObject.ItemOrder(0x0102030405060708L, 0x11223344)));
        if (!Arrays.equals(decodedTrade.crowns, new int[]{7, 8}) ||
            !Arrays.equals(decodedTrade.energy, new int[]{9, 10}) || decodedTrade.revision != 11 ||
            !Arrays.equals(decodedTrade.accepted, new int[]{12, 13}) || !decodedTrade.completed ||
            decodedTrade.orderings.size() != 1 || decodedOrder.Mw() != 0x0102030405060708L ||
            !Arrays.equals(decodedDialog.names, dialog.names) || decodedDialog.tradeOid != 456 ||
            decodedDialog.sourceRotation != 12.5f || !decodedRequest.getKey().equals("TRADE_REQ:Trader"))
            throw new AssertionError("Native trade object round trip did not preserve representative values");
        for (Object value : List.of(trade, trade.orderings.iterator().next(), dialog, request)) {
            byte[] bytes = write(value);
            System.out.println("ROUNDTRIP " + value.getClass().getName() + " bytes=" + bytes.length +
                " hex=" + HexFormat.of().formatHex(bytes));
        }
        System.out.println("PASS stock reader/writer trade round trips; ItemOrder key=" + decodedOrder.Mw() +
            " compare order=0x11223344; TradeDialogInfo names=" + Arrays.toString(decodedDialog.names) +
            " tradeOid=" + decodedDialog.tradeOid);
    }

    private static byte[] write(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        w out = new w(bytes);
        out.bf(value);
        out.flush();
        return bytes.toByteArray();
    }

    private static Object read(byte[] bytes) throws IOException {
        return new v(new ByteArrayInputStream(bytes)).qe();
    }
}
