package org.tortodrone.protocol;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.tortodrone.core.WireTypes;
import org.tortodrone.core.world.SceneFrame;

/**
 * Per-connection symbol tables shared by every feature codec.
 *
 * <p>The stock protocol keeps one class table and one string table per connection. Feature
 * codecs therefore cannot own private tables without changing the bytes; they all encode through
 * this context. A scene template records symbol positions instead of registering codes, and
 * {@link #materialize} later copies the template with this connection's codes.
 */
public final class WireContext {
    private static final String PREFIX = WireTypes.NET_PREFIX;
    private final Map<String, Integer> classes = new HashMap<>();
    private final Map<String, Integer> interns = new HashMap<>();
    private boolean templating;
    private ArrayList<SceneFrame.Symbol> templateTypes, templateInterns;
    /** Resolved codes for the template being published this tick; one entry is enough because a
     * recipient materializes the same frame repeatedly before the next tick replaces it. */
    private SceneFrame resolvedFrame;

    private int[] resolvedCodes;

    public WireContext() {
        // HandshakeCodec has already introduced these five downstream mappings.
        classes.put(PREFIX + "SecureResponse", 1);
        classes.put(PREFIX + "AuthResponseData", 2);
        classes.put(PREFIX + "AuthResponse", 3);
        classes.put("com.threerings.projectx.data.ProjectXAuthResponseData", 4);
        classes.put(WireTypes.NAME, 5);
    }

    /** Register or reference a downstream class code. */
    public void type(DataOutputStream out, String name) throws IOException {
        if (templating) {
            templateTypes.add(new SceneFrame.Symbol(out.size(), name));
            out.writeShort(0);
            return;
        }
        Integer code = classes.get(name);
        if (code != null) {
            out.writeShort(code);
        } else {
            int next = classes.size() + 1;
            out.writeShort(-next);
            out.writeUTF(name);
            classes.put(name, next);
        }
    }

    /** Register or reference a connection-local interned string. */
    public void intern(DataOutputStream out, String value) throws IOException {
        if (templating) {
            templateInterns.add(new SceneFrame.Symbol(out.size(), value));
            out.writeShort(0);
            return;
        }
        Integer code = interns.get(value);
        if (code == null) {
            code = interns.size() + 1;
            interns.put(value, code);
            out.writeShort(-code);
            out.writeUTF(value);
        } else out.writeShort(code);
    }

    public boolean templating() {
        return templating;
    }

    public ArrayList<SceneFrame.Symbol> templateTypes() {
        return templateTypes;
    }

    public ArrayList<SceneFrame.Symbol> templateInterns() {
        return templateInterns;
    }

    void beginTemplate() {
        templateTypes = new ArrayList<>();
        templateInterns = new ArrayList<>();
        templating = true;
    }

    void endTemplate() {
        templating = false;
        templateTypes = null;
        templateInterns = null;
    }

    /**
     * Copy a shared template with this connection's codes and header fields. Returns null while
     * the connection has not seen every symbol yet; the caller then encodes the tick directly,
     * which also registers them for later ticks.
     */
    public byte[] materialize(SceneFrame frame, int oid, int acknowledge, short ping, int reference, short elapsed) {
        if (frame == null) return null;
        int[] codes;
        if (resolvedFrame == frame) codes = resolvedCodes;
        else {
            codes = new int[frame.types().size() + frame.interns().size()];
            int index = 0;
            for (var symbol : frame.types()) {
                var code = classes.get(symbol.name());
                if (code == null) return null;
                codes[index++] = code;
            }
            for (var symbol : frame.interns()) {
                var code = interns.get(symbol.name());
                if (code == null) return null;
                codes[index++] = code;
            }
            resolvedFrame = frame;
            resolvedCodes = codes;
        }
        byte[] data = frame.template().clone();
        integer(data, frame.oidOffset(), oid);
        integer(data, frame.acknowledgeOffset(), acknowledge);
        data[frame.pingOffset()] = (byte) (ping >>> 8);
        data[frame.pingOffset() + 1] = (byte) ping;
        integer(data, frame.referenceOffset(), reference);
        data[frame.elapsedOffset()] = (byte) (elapsed >>> 8);
        data[frame.elapsedOffset() + 1] = (byte) elapsed;
        int index = 0;
        for (int i = 0; i < frame.types().size(); i++)
            shortValue(data, frame.types().get(i).offset(), codes[index++]);
        for (int i = 0; i < frame.interns().size(); i++)
            shortValue(data, frame.interns().get(i).offset(), codes[index++]);
        return data;
    }

    private static void integer(byte[] data, int at, int value) {
        data[at] = (byte) (value >>> 24);
        data[at + 1] = (byte) (value >>> 16);
        data[at + 2] = (byte) (value >>> 8);
        data[at + 3] = (byte) value;
    }

    private static void shortValue(byte[] data, int at, int value) {
        data[at] = (byte) (value >>> 8);
        data[at + 1] = (byte) value;
    }
}
