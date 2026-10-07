package org.tortodrone.support;

import java.io.*;
import java.net.Socket;
import java.util.*;

/** Creation, saved roster, and failure callback checks reusable across server restarts. */
public final class CharacterScenario {
    public static void session(Socket peer, TestWire wire, int oid, boolean create) throws Exception {
        TestWire.Writer writer = new TestWire.Writer(TestWire.NET + "SecureRequest", TestWire.NET + "PublicKeyCredentials",
            "[Ljava.lang.String;", TestWire.NET + "AESAuthRequest");
        if (create) {
            creation(peer, writer, "Saved-Knight", null, (short) 41);
            DataInputStream update = event(peer, wire, oid, "AttributeChangedEvent");
            TestWire.check(TestWire.string(update).equals("availableKnights"), "roster update field");
            roster(wire.object(update)); TestWire.end(update);
            confirmation(peer, wire, oid, (short) 41,
                "Knight saved. Reconnect to view it; world entry is not available yet.");
        }
        writer.send(peer.getOutputStream(), out -> { writer.message(out, "SubscribeRequest", 42); out.writeInt(oid); });
        var player = wire.snapshot(wire.frame(peer.getInputStream()), (short) 42, oid);
        roster(player.get("availableKnights"));
        creation(peer, writer, "saved-knight", null, (short) 43);
        confirmation(peer, wire, oid, (short) 43, "That knight name is already in use");
        creation(peer, writer, "Bad-Request", "old-knight", (short) 44);
        confirmation(peer, wire, oid, (short) 44, "No such knight");
        writer.send(peer.getOutputStream(), out -> {
            invocation(writer, out, 3, 2); writer.type(out, "com.threerings.util.Name"); string(out, "saved-knight");
            writer.type(out, TestWire.INV + "ConfirmMarshaller"); out.writeShort(45);
        });
        confirmation(peer, wire, oid, (short) 45, "That knight name is already in use");
        // World selection remains an honest failure, rather than a false entry acknowledgement.
        writer.send(peer.getOutputStream(), out -> {
            invocation(writer, out, 4, 3); writer.type(out, "com.threerings.util.Name"); string(out, "Saved-Knight");
            out.writeShort(0); writer.type(out, TestWire.INV + "ConfirmMarshaller"); out.writeShort(46);
        });
        confirmation(peer, wire, oid, (short) 46, "World entry is not available yet");
        writer.send(peer.getOutputStream(), out -> writer.message(out, "LogoffRequest", 47));
    }

    /** Full slots, edits, deletion, slot reuse and restoration through real wire messages. */
    public static void management(Socket peer, TestWire wire, int oid, boolean create) throws Exception {
        TestWire.Writer writer = new TestWire.Writer(TestWire.NET + "SecureRequest", TestWire.NET + "PublicKeyCredentials",
            "[Ljava.lang.String;", TestWire.NET + "AESAuthRequest");
        if (create) {
            String[] names = {"Saved-Knight", "Other-Knight", "Third-Knight"};
            for (int i = 0; i < names.length; i++) {
                short callback = (short) (60 + i);
                creation(peer, writer, names[i], null, callback);
                changedRoster(peer, wire, oid, Arrays.asList(names).subList(0, i + 1));
                confirmation(peer, wire, oid, callback, "Knight saved. Reconnect to view it; world entry is not available yet.");
            }
            creation(peer, writer, "Fourth-Knight", null, (short) 63);
            confirmation(peer, wire, oid, (short) 63, "All three knight slots are occupied");
            creation(peer, writer, "Renamed-Knight", "saved-knight", (short) 64);
            changedRoster(peer, wire, oid, List.of("Renamed-Knight", "Other-Knight", "Third-Knight"));
            confirmation(peer, wire, oid, (short) 64, "Knight updated. Reconnect to view it; world entry is not available yet.");
            creation(peer, writer, "other-knight", "Renamed-Knight", (short) 65);
            confirmation(peer, wire, oid, (short) 65, "That knight name is already in use");
            creation(peer, writer, "Changed-Knight", "Missing-Knight", (short) 66);
            confirmation(peer, wire, oid, (short) 66, "No such knight");
            creation(peer, writer, "x", "Renamed-Knight", (short) 67);
            confirmation(peer, wire, oid, (short) 67, "Knight names need at least three letters");
            deletion(peer, writer, "Missing-Knight", (short) 68);
            confirmation(peer, wire, oid, (short) 68, "No such knight");
            writer.send(peer.getOutputStream(), out -> {
                invocation(writer, out, 6, 2); writer.type(out, "java.lang.Integer"); out.writeInt(1);
                writer.type(out, TestWire.INV + "ConfirmMarshaller"); out.writeShort(69);
            });
            confirmation(peer, wire, oid, (short) 69, "Invalid character deletion request");
            deletion(peer, writer, "other-knight", (short) 70);
            changedRoster(peer, wire, oid, List.of("Renamed-Knight", "Third-Knight"));
            confirmation(peer, wire, oid, (short) 70, null);
            deletion(peer, writer, "Other-Knight", (short) 71);
            confirmation(peer, wire, oid, (short) 71, "No such knight");
            creation(peer, writer, "Next-Knight", null, (short) 72);
            changedRoster(peer, wire, oid, List.of("Renamed-Knight", "Third-Knight", "Next-Knight"));
            confirmation(peer, wire, oid, (short) 72, "Knight saved. Reconnect to view it; world entry is not available yet.");
        }
        writer.send(peer.getOutputStream(), out -> { writer.message(out, "SubscribeRequest", 73); out.writeInt(oid); });
        var player = wire.snapshot(wire.frame(peer.getInputStream()), (short) 73, oid);
        List<?> roster = checkRoster(player.get("availableKnights"), List.of("Renamed-Knight", "Third-Knight", "Next-Knight"));
        for (int i = 0; i < roster.size(); i++) {
            TestWire.check(((Map<?, ?>) roster.get(i)).get("id").equals(new int[] {1, 3, 4}[i]), "edits preserve IDs and deleted IDs are not reused");
        }
        if (!create) {
            String[] names = {"Renamed-Knight", "Third-Knight", "Next-Knight"};
            for (int i = 0; i < names.length; i++) {
                short callback = (short) (74 + i);
                deletion(peer, writer, names[i], callback);
                changedRoster(peer, wire, oid, Arrays.asList(names).subList(i + 1, names.length));
                confirmation(peer, wire, oid, callback, null);
            }
        }
        writer.send(peer.getOutputStream(), out -> writer.message(out, "LogoffRequest", 77));
    }

    private static void deletion(Socket peer, TestWire.Writer writer, String name, short callback) throws IOException {
        writer.send(peer.getOutputStream(), out -> {
            invocation(writer, out, 6, 2); writer.type(out, "com.threerings.util.Name"); string(out, name);
            writer.type(out, TestWire.INV + "ConfirmMarshaller"); out.writeShort(callback);
        });
    }

    private static void changedRoster(Socket peer, TestWire wire, int oid, List<String> names) throws IOException {
        DataInputStream update = event(peer, wire, oid, "AttributeChangedEvent");
        TestWire.check(TestWire.string(update).equals("availableKnights"), "management updates the roster before its callback");
        checkRoster(wire.object(update), names);
        TestWire.end(update);
    }

    private static List<?> checkRoster(Object value, List<String> names) {
        TestWire.check(value instanceof List<?> knights && knights.size() == names.size(), "roster size");
        List<?> knights = (List<?>) value;
        for (int i = 0; i < knights.size(); i++) {
            TestWire.check(((Map<?, ?>) knights.get(i)).get("name").equals(names.get(i)), "roster names");
        }
        return knights;
    }

    private static void creation(Socket peer, TestWire.Writer writer, String name, String oldName, short callback) throws IOException {
        writer.send(peer.getOutputStream(), out -> {
            invocation(writer, out, 5, 14);
            if (oldName == null) out.writeShort(0); else { writer.type(out, "com.threerings.util.Name"); string(out, oldName); }
            writer.type(out, "com.threerings.util.Name"); string(out, name);
            for (String asset : List.of("helm", "armor")) { writer.type(out, "java.lang.String"); out.writeUTF(asset); }
            out.writeShort(0); out.writeShort(0); writer.type(out, "java.lang.String"); out.writeUTF("costume");
            writer.type(out, "java.lang.Integer"); out.writeInt(1);
            writer.type(out, "com.threerings.projectx.data.ProjectXCodes$KnightHeight"); out.writeByte(2);
            writer.type(out, "com.threerings.projectx.data.ProjectXCodes$EyeType"); out.writeByte(0);
            out.writeShort(0); writer.type(out, "java.util.List"); out.writeInt(0); out.writeShort(0);
            writer.type(out, TestWire.INV + "ConfirmMarshaller"); out.writeShort(callback);
        });
    }
    private static void invocation(TestWire.Writer writer, DataOutputStream out, int method, int count) throws IOException {
        writer.message(out, "ForwardEventRequest", 40);
        writer.type(out, TestWire.DOB + "InvocationRequestEvent"); out.writeInt(3); out.writeInt(1); out.writeByte(method);
        out.writeBoolean(true); out.writeInt(count);
    }
    private static DataInputStream event(Socket peer, TestWire wire, int oid, String type) throws IOException {
        DataInputStream in = wire.frame(peer.getInputStream());
        TestWire.check(wire.type(in).equals(TestWire.NET + "EventNotification") && in.readShort() == -1, "unsolicited event");
        TestWire.check(wire.type(in).equals(TestWire.DOB + type) && in.readInt() == oid, "event identity and type"); return in;
    }
    private static void confirmation(Socket peer, TestWire wire, int oid, short callback, String failure) throws IOException {
        DataInputStream in = event(peer, wire, oid, "InvocationResponseEvent");
        TestWire.check(in.readShort() == callback && in.readByte() == (failure == null ? 1 : 0), "callback correlation and method");
        if (failure == null) TestWire.check(!in.readBoolean(), "confirmation has null arguments");
        else TestWire.check(in.readBoolean() && in.readInt() == 1 && wire.object(in).equals("~" + failure), "literal display reason");
        TestWire.end(in);
    }
    private static void roster(Object value) {
        TestWire.check(value instanceof List<?> knights && knights.size() == 1, "one saved knight");
        var knight = (Map<?, ?>) ((List<?>) value).get(0);
        TestWire.check(knight.get("id").equals(1) && knight.get("name").equals("Saved-Knight"), "saved identity");
        var model = (Map<?, ?>) knight.get("model");
        TestWire.check(model.get("_name").equals("character/pc/model_level.dat") && model.get("_arguments") instanceof Map<?, ?>,
            "nonnull preview configuration");
    }
    private static void string(DataOutputStream out, String value) throws IOException { out.writeBoolean(true); out.writeUTF(value); }
}
