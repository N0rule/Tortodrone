import com.threerings.io.v;
import com.threerings.presents.client.A;
import com.threerings.presents.dobj.*;
import com.threerings.presents.net.ForwardEventRequest;
import com.threerings.projectx.craft.data.CraftMarshaller;
import com.threerings.projectx.data.ProjectXSceneMarshaller;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.core.character.KnightProgress;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.protocol.*;

/** Current installed marshallers are the oracle: craft invocation, dialog close and reply wire layouts. */
public final class CraftCompatibility {
    private static final class Dispatch extends com.threerings.presents.client.x {
        InvocationRequestEvent event;

        @Override
        public void a(int oid, int code, int method, Object[] args, com.threerings.presents.net.Transport transport) {
            event = new InvocationRequestEvent(oid, code, method, args);
        }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("com.threerings.io.enumPolicy", "ORDINAL");
        // The pet alchemy machine is a catalog crafter: method one carries the machine catalog index.
        var dispatch = new Dispatch();
        var craft = new CraftMarshaller();
        craft.a(10, 101, dispatch);
        craft.a(3, new long[] {22L, 44L}, true, (A.c) null);
        var invocation = decode(dispatch.event);
        Harness.eq("method", invocation.method(), 1);
        Harness.eq("service", invocation.service(), 101);
        Harness.eq("oid", invocation.oid(), 10);
        Harness.eq("index", invocation.arguments().get(0), Integer.valueOf(3));
        Harness.eq(
                "items type",
                invocation.arguments().get(1) instanceof long[] ids
                        && ids.length == 2
                        && ids[0] == 22L
                        && ids[1] == 44L,
                true);
        Harness.eq("upgrade", invocation.arguments().get(2), Boolean.TRUE);
        listener(invocation.arguments().get(3), true);
        var itemService = new com.threerings.projectx.item.data.ItemMarshaller();
        itemService.a(3, 9, dispatch);
        itemService.a(22L, (Object) null, (A.a) null);
        invocation = decode(dispatch.event);
        Harness.eq("inventory use method", invocation.method(), 14);
        Harness.eq("inventory use arguments", invocation.arguments().size(), 3);
        listener(invocation.arguments().get(2), false);
        var gifts = com.google.common.collect.HashMultiset.<Long>create();
        gifts.add(44L, 2);
        itemService.a(22L, gifts, "A gift", (A.a) null);
        invocation = decode(dispatch.event);
        Harness.eq("wrap method", invocation.method(), 15);
        Harness.eq("wrap arguments", invocation.arguments().size(), 4);
        Harness.eq(
                "wrap contents", invocation.arguments().get(1), new org.tortodrone.core.wire.Multiset(Map.of(44L, 2)));
        listener(invocation.arguments().get(3), false);
        itemService.e(55L, (A.c) null);
        invocation = decode(dispatch.event);
        Harness.eq("unwrap method", invocation.method(), 13);
        Harness.eq("unwrap arguments", invocation.arguments().size(), 2);
        listener(invocation.arguments().get(1), true);
        // Plain crafters send the learned recipe identifier instead.
        craft.a(77L, new long[] {88L}, false, (A.c) null);
        invocation = decode(dispatch.event);
        Harness.eq("method", invocation.method(), 2);
        Harness.eq("learned id", invocation.arguments().get(0), Long.valueOf(77L));
        listener(invocation.arguments().get(3), true);
        // An ingredient-less craft request is still well formed.
        craft.a(3, new long[0], false, (A.c) null);
        invocation = decode(dispatch.event);
        Harness.eq("empty items", ((long[]) invocation.arguments().get(1)).length == 0, true);
        // Dialog closes ride the scene service as [created].
        var scene = new ProjectXSceneMarshaller();
        scene.a(10, 6, dispatch);
        scene.Q(123456L);
        invocation = decode(dispatch.event);
        Harness.eq("service", invocation.service(), 6);
        Harness.eq("method", invocation.method(), 1);
        Harness.eq("close created", invocation.arguments().getFirst(), Long.valueOf(123456L));
        Harness.eq("close listener count", invocation.arguments().size(), 1);
        // Our winner/loser replies must satisfy the stock craft result listener contract,
        // read through a normal session stream (the handshake primes the stock class table).
        var bytes = new ByteArrayOutputStream();
        var codec = new SessionCodec();
        Harness.append(bytes, HandshakeCodec.response((short) 1, "0123456789abcdef0123456789abcdef"));
        Harness.append(bytes, HandshakeCodec.authenticationResponse((short) 2, true, "synthetic", "Alice"));
        var item = new WorldProfile.Item(
                20,
                WorldProfile.Slot.SWORD,
                "Weapon/Sword/Calibur",
                null,
                0,
                null,
                false,
                true,
                false,
                1,
                false,
                2,
                .4f,
                List.of());
        var profile = new WorldProfile(42, 1, 1, 1000, 100, 1, List.of(item), KnightProgress.RECRUIT);
        Harness.append(bytes, codec.townResult(10, (short) 9, 527L, profile));
        Harness.append(bytes, codec.invocationFailure(10, (short) 10, "Missing recipe ingredients"));
        Harness.append(bytes, codec.invocationConfirmed(10, (short) 11));
        var in = new v(new ByteArrayInputStream(bytes.toByteArray()));
        in.ql();
        in.ql(); // handshake and authentication responses prime the stock reader
        var winner = (InvocationResponseEvent) ((com.threerings.presents.net.EventNotification) in.ql()).yn();
        Harness.eq("success method", success(winner), true);
        Harness.eq("success request", winner.yj(), 9);
        Harness.eq("success payload", winner.yh()[0], Long.valueOf(527L));
        var loser = (InvocationResponseEvent) ((com.threerings.presents.net.EventNotification) in.ql()).yn();
        Harness.eq("failure method", success(loser), false);
        Harness.eq("failure request", loser.yj(), 10);
        Harness.eq("failure reason", loser.yh()[0] instanceof String text && text.length() > 1, true);
        var confirmed = (InvocationResponseEvent) ((com.threerings.presents.net.EventNotification) in.ql()).yn();
        Harness.eq("confirm method", success(confirmed), true);
        Harness.eq("confirm args", confirmed.yh(), null);
        if (in.available() != 0) throw new AssertionError("Trailing craft reply wire bytes");
        // The alchemy machines' catalogs must resolve server-side with matchable native ingredients.
        var rules = org.tortodrone.resources.ContentManager.load(Path.of(".")).town();
        var items = org.tortodrone.resources.ContentManager.load(Path.of(".")).items();
        for (String actor : List.of(
                "Shop/Haven/Alchemy, Battle Sprites", "scene.1.6837.39", "scene.30893.207.4", "scene.2.placed.5184")) {
            var station = rules.station(actor);
            if (station == null) throw new AssertionError("Missing station: " + actor);
            for (String text : station.recipes()) {
                var recipe = rules.recipe(text);
                if (recipe == null) throw new AssertionError("Station catalog references unavailable recipe: " + text);
                Harness.eq("recipe output item", recipe.item().isEmpty() || items.find(recipe.item()) != null, true);
                for (var ingredient : recipe.ingredients()) {
                    if (ingredient.kind().equals("SpriteFoodMaterial")
                            || ingredient.kind().equals("ItemGroup")) continue;
                    if (ingredient.item() == null
                            || ingredient.item().isEmpty()
                            || items.find(ingredient.item()) == null)
                        throw new AssertionError(
                                "Recipe " + text + " ingredient does not resolve: " + ingredient.item());
                }
            }
        }
        System.out.println("PASS: native craft invocations, dialog close and callback replies match the stock layouts");
    }

    private static SessionDecoder.Invocation decode(InvocationRequestEvent event) throws Exception {
        var bytes = new ByteArrayOutputStream();
        var nativeOut = new com.threerings.io.w(bytes);
        nativeOut.bf(new ForwardEventRequest(event));
        nativeOut.flush();
        return (SessionDecoder.Invocation)
                new HandshakeCodec().sessionRequest(bytes.toByteArray()).event();
    }

    private static void listener(Object value, boolean result) throws Exception {
        if (!(value instanceof org.tortodrone.protocol.SessionDecoder.Listener listener))
            throw new AssertionError("Missing result listener");
        String expected = "com.threerings.presents.data.InvocationMarshaller$"
                + (result ? "ResultMarshaller" : "ConfirmMarshaller");
        if (!listener.type().equals(expected))
            throw new AssertionError("Listener type differs: " + listener.type() + " != " + expected);
    }

    private static boolean success(com.threerings.presents.dobj.InvocationResponseEvent event) {
        var args = event.yh();
        return args == null || args.length == 0 || !(args[0] instanceof String);
    }
}
