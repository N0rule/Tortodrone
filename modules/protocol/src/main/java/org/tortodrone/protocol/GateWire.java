package org.tortodrone.protocol;

import java.io.DataOutputStream;
import java.io.IOException;
import org.tortodrone.core.WireTypes;
import org.tortodrone.core.character.ItemCatalog;

/** Bootstrap gate-summary records. The fixed tutorial and opening gates are hand-authored
 *  capture data, and shared mission gates follow the prepared catalogue; only the bootstrap
 *  frame emits them, so they stay out of the shared {@link WireWriter} base. */
final class GateWire extends WireWriter {
    GateWire(WireContext context, ItemCatalog catalog) { super(context, catalog); }

    void tutorialGate(DataOutputStream out) throws IOException {
        type(out, "com.threerings.projectx.dungeon.data.GateSummary"); out.writeBoolean(true); out.writeInt(org.tortodrone.core.mission.TutorialRoute.GATE);
        string(out, "m.tutorial_b"); string(out, "queen");
        type(out, "[Lcom.threerings.opengl.renderer.config.ColorizationConfig;"); out.writeInt(2);
        for (int color : new int[]{544, 799}) { type(out, "com.threerings.opengl.renderer.config.ColorizationConfig$Normal"); out.writeInt(color); }
        string(out, "~"); out.writeInt(0); out.writeInt(0); out.writeInt(0); out.writeInt(9); out.writeInt(0); out.writeInt(3);
        type(out, "[Lcom.threerings.projectx.dungeon.data.GateSummary$Wheel;"); out.writeInt(1);
        type(out, "com.threerings.projectx.dungeon.data.GateSummary$Constant"); out.writeByte(0);
        type(out, "[Lcom.threerings.projectx.dungeon.data.GateSummary$Wedge;"); out.writeInt(1);
        type(out, "com.threerings.projectx.dungeon.data.GateSummary$Level"); string(out, "m.tutorial_b"); string(out, "clockwork");
        emptyObjectArray(out, WireTypes.COLORIZATION, false); string(out, "~");
        type(out, "com.threerings.projectx.dungeon.data.DungeonCodes$LevelType"); out.writeByte(0); out.writeBoolean(false);
        out.writeFloat(0); out.writeBoolean(true); out.writeInt(1); out.writeFloat((float) (2 * Math.PI)); out.writeLong(0);
    }

    void openingGate(DataOutputStream out, int gateId) throws IOException {
        type(out, "com.threerings.projectx.dungeon.data.MissionGateSummary"); out.writeBoolean(true); out.writeInt(gateId);
        string(out, "%mission:m.ancient_generator"); string(out, "pawn");
        type(out, "[Lcom.threerings.opengl.renderer.config.ColorizationConfig;"); out.writeInt(2);
        for (int color : new int[]{532, 787}) { type(out, "com.threerings.opengl.renderer.config.ColorizationConfig$Normal"); out.writeInt(color); }
        string(out, "m.training_1_desc"); for (int i = 0; i < 5; i++) out.writeInt(0); out.writeInt(8); // source/dest/priority/tokens/owner/flags
        type(out, "[Lcom.threerings.projectx.dungeon.data.GateSummary$Wheel;"); out.writeInt(2);
        for (int floor = 0; floor < 2; floor++) {
            type(out, "com.threerings.projectx.dungeon.data.GateSummary$Constant"); out.writeByte(0);
            type(out, "[Lcom.threerings.projectx.dungeon.data.GateSummary$Wedge;"); out.writeInt(1);
            type(out, "com.threerings.projectx.dungeon.data.GateSummary$Level"); string(out, "m.mission|%mission:m.ancient_generator|" + (floor == 0 ? "m.mission_lobby" : "m.training_grounds_generator")); string(out, floor == 0 ? "lobby" : "clockwork");
            emptyObjectArray(out, WireTypes.COLORIZATION, false); string(out, floor == 0 ? "m.lobby_desc" : "~");
            type(out, "com.threerings.projectx.dungeon.data.DungeonCodes$LevelType"); out.writeByte(floor == 0 ? 2 : 0); out.writeBoolean(floor != 0);
            out.writeFloat(0); out.writeBoolean(true); out.writeInt(1); out.writeFloat((float) (2 * Math.PI)); out.writeLong(0);
        }
        string(out, null); string(out, null); // no expansion/prestige access requirement
    }

    void missionGate(DataOutputStream out, org.tortodrone.core.mission.MissionGates.Gate gate) throws IOException {
        type(out, "com.threerings.projectx.dungeon.data.MissionGateSummary"); out.writeBoolean(true); out.writeInt(gate.gateId());
        string(out, gate.name()); string(out, gate.icon());
        type(out, "[Lcom.threerings.opengl.renderer.config.ColorizationConfig;"); out.writeInt(gate.colors().size());
        for (int color : gate.colors()) { type(out, "com.threerings.opengl.renderer.config.ColorizationConfig$Normal"); out.writeInt(color); }
        string(out, gate.description());
        out.writeInt(gate.sourceId()); out.writeInt(gate.destinationId()); out.writeInt(gate.priority()); out.writeInt(gate.tokens());
        out.writeInt(0); out.writeInt(gate.flags()); // owner is unset for a shared mission gate
        type(out, "[Lcom.threerings.projectx.dungeon.data.GateSummary$Wheel;"); out.writeInt(gate.wedges().size());
        for (var wedge : gate.wedges()) {
            type(out, "com.threerings.projectx.dungeon.data.GateSummary$Constant"); out.writeByte(wedge.skip());
            type(out, "[Lcom.threerings.projectx.dungeon.data.GateSummary$Wedge;"); out.writeInt(1);
            type(out, "com.threerings.projectx.dungeon.data.GateSummary$Level"); string(out, wedge.name()); string(out, wedge.icon());
            emptyObjectArray(out, WireTypes.COLORIZATION, false); string(out, wedge.description());
            type(out, "com.threerings.projectx.dungeon.data.DungeonCodes$LevelType"); out.writeByte(levelType(wedge.type()));
            out.writeBoolean(wedge.restricted());
            out.writeFloat(wedge.velocity()); out.writeBoolean(true); out.writeInt(1); out.writeFloat(wedge.length()); out.writeLong(wedge.start());
        }
        string(out, null); string(out, null); // no expansion/prestige access requirement
    }

    private static int levelType(String type) {
        return switch (type) { case "LEVEL" -> 0; case "TERMINAL" -> 1; case "LOBBY" -> 2; case "SUBTOWN" -> 3; case "CORE" -> 4; default -> 5; };
    }
}
