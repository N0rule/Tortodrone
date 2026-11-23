package org.tortodrone.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.SceneCombatEvent;
import org.tortodrone.core.world.SceneEffect;
import org.tortodrone.core.world.SceneFrame;

/** Builds the reusable scene-delta fan-out template. The body is encoded once with class and
 *  interned-string symbols left as placeholders; every recipient then materialises it with its
 *  own per-connection codes. Offsets are recorded after the four-byte frame-length prefix. */
final class SceneDeltaTemplate {
    private SceneDeltaTemplate() { }

    static SceneFrame encode(ItemCatalog catalog, int sceneOid, int timestamp,
            List<ActorState> added, List<ActorState> updated, int[] removed, Map<Integer,ActorState> baseline,
            boolean interior, List<SceneEffect> effects, List<SceneCombatEvent> combat) throws IOException {
        var codec = new SessionCodec(catalog);
        codec.context.beginTemplate();
        try {
            var body = new ByteArrayOutputStream(4096);
            var out = new DataOutputStream(body);
            codec.type(out, WireWriter.PREFIX + "EventNotification"); out.writeShort(-1);
            codec.type(out, "com.threerings.tudey.dobj.SceneDeltaEvent");
            int oidOffset = body.size(); out.writeInt(0);
            out.writeInt(sceneOid);
            int acknowledgeOffset = body.size(); out.writeInt(0);
            int pingOffset = body.size(); out.writeShort(0);
            int referenceOffset = body.size(); out.writeInt(0);
            out.writeInt(timestamp);
            int elapsedOffset = body.size(); out.writeShort(0);
            codec.sceneCodec().sceneDeltaActors(out, 0, added, updated, removed, baseline, interior, effects, combat);
            out.flush();
            if (body.size() + 4 > WireWriter.MAX_DOWNSTREAM_FRAME) throw new IOException("Session frame too large");
            int total = body.size() + 4;
            byte[] template = new byte[total];
            template[0] = (byte) (total >>> 24); template[1] = (byte) (total >>> 16);
            template[2] = (byte) (total >>> 8); template[3] = (byte) total;
            System.arraycopy(body.toByteArray(), 0, template, 4, body.size());
            return new SceneFrame(template, 4 + oidOffset, 4 + acknowledgeOffset, 4 + pingOffset,
                4 + referenceOffset, 4 + elapsedOffset, shift(codec.context.templateTypes()), shift(codec.context.templateInterns()));
        } finally {
            codec.context.endTemplate();
        }
    }

    /** Frame body offsets become frame offsets once the four-byte length prefix is prepended. */
    private static List<SceneFrame.Symbol> shift(List<SceneFrame.Symbol> symbols) {
        var shifted = new ArrayList<SceneFrame.Symbol>(symbols.size());
        for (var symbol : symbols) shifted.add(new SceneFrame.Symbol(symbol.offset() + 4, symbol.name()));
        return List.copyOf(shifted);
    }
}
