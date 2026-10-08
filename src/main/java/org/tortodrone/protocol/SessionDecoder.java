package org.tortodrone.protocol;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A bounded, explicit Presents subset. Never loads classes named by the peer. */
public final class SessionDecoder {
    /** Fixed parser diagnostics, containing no peer strings or argument values. */
    public static final class Rejected extends IOException {
        public Rejected(String reason) { super(reason); }
    }
    private static final String NET = "com.threerings.presents.net.";
    private static final String DOB = "com.threerings.presents.dobj.";
    private static final String INV = "com.threerings.presents.data.InvocationMarshaller$";
    private final Types types;
    private int nodes;

    public sealed interface Event permits Compound, Mutation, Invocation, Input { int oid(); }
    public record Compound(int oid, List<Event> events) implements Event { }
    public record Mutation(int oid, String operation, String field, Object value) implements Event { }
    public record Invocation(int oid, int service, int method, List<Object> arguments) implements Event { }
    public record Input(int oid,int acknowledge,int smoothedTime,List<InputFrame> frames) implements Event { }
    public record InputFrame(int timestamp,float rotation,float direction,int flags) { }
    public record Registration(String code, short id) { }
    public record Listener(String type, short requestId) { }
    public record EnumValue(String type, int ordinal) { }

    @FunctionalInterface interface Types { String read(DataInputStream in) throws IOException; }
    SessionDecoder(Types types) { this.types = types; }

    List<HandshakeCodec.SessionRequest> decode(byte[] body) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
        List<HandshakeCodec.SessionRequest> requests = new ArrayList<>();
        request(in, requests, 0);
        check(in.available() == 0, "Unexpected session fields");
        return List.copyOf(requests);
    }

    private void request(DataInputStream in, List<HandshakeCodec.SessionRequest> requests, int depth) throws IOException {
        limit(depth);
        String type = types.read(in);
        check(type != null && type.startsWith(NET), "Expected upstream message");
        String name = type.substring(NET.length());
        short id = in.readShort();
        int oid = 0;
        Event event = null;
        switch (name) {
            case "CompoundUpstreamMessage" -> {
                check(present(in), "Missing upstream message list");
                int count = count(in);
                check(count > 0, "Empty upstream message list");
                for (int i = 0; i < count; i++) request(in, requests, depth + 1);
                return;
            }
            case "SubscribeRequest", "UnsubscribeRequest" -> {
                oid = in.readInt(); check(oid > 0, "Invalid object ID");
            }
            case "ForwardEventRequest" -> event = event(in, depth + 1);
            case "PingRequest", "LogoffRequest" -> { }
            default -> throw new Rejected("Unsupported upstream message");
        }
        requests.add(new HandshakeCodec.SessionRequest(name, id, oid, event));
    }

    private Event event(DataInputStream in, int depth) throws IOException {
        limit(depth);
        String type = types.read(in);
        if("com.threerings.tudey.data.InputEnqueueEvent".equals(type)) {
            int oid=in.readInt(),ack=in.readInt(),time=in.readInt();
            check(oid>0,"Invalid input target");
            check("[Lcom.threerings.tudey.data.InputFrame;".equals(types.read(in)),"Expected input frames");
            int length=count(in);check(length<=32,"Invalid input frame count");
            List<InputFrame> frames=new ArrayList<>();
            for(int i=0;i<length;i++) {
                String frameType=types.read(in);
                boolean extended="com.threerings.projectx.data.ProjectXInputFrame".equals(frameType);
                check(extended||"com.threerings.tudey.data.InputFrame".equals(frameType),"Expected input frame");
                int stamp=in.readInt();float rotation=in.readFloat(),direction=in.readFloat();int flags=in.readInt();
                if(extended)in.readInt(); // actionChanged: combat/action execution is not available in town.
                check(Float.isFinite(rotation)&&Float.isFinite(direction),"Invalid input angles");
                frames.add(new InputFrame(stamp,rotation,direction,flags));
            }
            return new Input(oid,ack,time,List.copyOf(frames));
        }
        check(type != null && type.startsWith(DOB), "Expected distributed event");
        String name = type.substring(DOB.length());
        int oid = in.readInt(); check(oid > 0, "Invalid event target");
        if (name.equals("CompoundEvent")) {
            check("com.threerings.util.StreamableArrayList".equals(types.read(in)), "Expected event list");
            int size = count(in);
            List<Event> events = new ArrayList<>();
            for (int i = 0; i < size; i++) events.add(event(in, depth + 1));
            return new Compound(oid, List.copyOf(events));
        }
        if (name.equals("InvocationRequestEvent")) {
            int service = in.readInt(); int method = in.readUnsignedByte();
            check(service > 0, "Invalid service code");
            return new Invocation(oid, service, method, arguments(in, depth + 1));
        }
        check(name.equals("AttributeChangedEvent") || name.equals("EntryAddedEvent")
            || name.equals("EntryUpdatedEvent") || name.equals("EntryRemovedEvent"), "Unsupported distributed event");
        String field = string(in);
        return new Mutation(oid, name, field, value(in, depth + 1));
    }

    private List<Object> arguments(DataInputStream in, int depth) throws IOException {
        if (!present(in)) return List.of();
        return values(in, depth);
    }

    private List<Object> values(DataInputStream in, int depth) throws IOException {
        int size = count(in);
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < size; i++) values.add(value(in, depth + 1));
        return Collections.unmodifiableList(values); // null is a valid invocation argument.
    }

    private Object value(DataInputStream in, int depth) throws IOException {
        limit(depth);
        String type = types.read(in);
        if (type == null) return null;
        return switch (type) {
            case "java.lang.String" -> in.readUTF();
            case "java.lang.Boolean" -> present(in);
            case "java.lang.Integer" -> in.readInt();
            case "java.lang.Byte" -> in.readByte();
            case "java.lang.Long" -> in.readLong();
            case "java.lang.Float" -> in.readFloat();
            case "com.threerings.util.Name" -> string(in);
            case "com.threerings.presents.dobj.DSet", "com.threerings.util.StreamableArrayList", "java.util.List" -> values(in, depth);
            case "com.threerings.projectx.data.ProjectXCodes$KnightHeight" -> enumeration(in, type, 5);
            case "com.threerings.projectx.data.ProjectXCodes$EyeType" -> enumeration(in, type, 47);
            case "com.threerings.projectx.item.data.ItemCodes$Location" -> enumeration(in,type,4);
            case "com.threerings.projectx.data.Pref" -> enumeration(in, type, 3);
            case "com.threerings.presents.client.InvocationReceiver$Registration" ->
                new Registration(string(in), in.readShort());
            case INV + "ListenerMarshaller", INV + "ConfirmMarshaller", INV + "ResultMarshaller",
                 "com.threerings.crowd.chat.data.ChatMarshaller$TellMarshaller",
                 "com.threerings.crowd.data.LocationMarshaller$MoveMarshaller",
                 "com.threerings.whirled.data.SceneMarshaller$SceneMoveMarshaller",
                 "com.threerings.whirled.zone.data.ZoneMarshaller$ZoneMoveMarshaller" ->
                new Listener(type, in.readShort());
            default -> throw new Rejected("Unsupported event value");
        };
    }

    private void limit(int depth) throws IOException {
        check(depth <= 8 && ++nodes <= 512, "Session nesting or element limit exceeded");
    }
    private static EnumValue enumeration(DataInputStream in, String type, int count) throws IOException {
        int ordinal = in.readUnsignedByte(); check(ordinal < count, "Invalid enum ordinal");
        return new EnumValue(type, ordinal);
    }
    private static int count(DataInputStream in) throws IOException {
        int size = in.readInt(); check(size >= 0 && size <= 128, "Invalid collection length"); return size;
    }
    private static String string(DataInputStream in) throws IOException {
        return present(in) ? in.readUTF() : null;
    }
    private static boolean present(DataInputStream in) throws IOException {
        int flag = in.readUnsignedByte(); check(flag <= 1, "Invalid presence flag"); return flag == 1;
    }
    private static void check(boolean condition, String reason) throws IOException {
        if (!condition) throw new Rejected(reason);
    }
}
