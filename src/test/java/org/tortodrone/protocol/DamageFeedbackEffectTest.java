package org.tortodrone.protocol;

import static org.tortodrone.support.TestWire.check;

import java.util.*;
import org.tortodrone.core.world.SceneCombatEvent;
import org.tortodrone.support.TestWire;

/** Feedback/Damage must stream the current client's combat feedback layout verbatim: the party
 *  object's DamageEvent of the earlier clients is gone from the live client build (it crashes
 *  on the class), its Feedback/Damage entry now defines a tudey scene effect carrying the same
 *  seven fields — a knight's packed type breakdown with a literal zero source, a monster's
 *  dealt amount attributed to the attacking pawn — behind the base Effect fields. */
public final class DamageFeedbackEffectTest {
    public static void main(String[] args) throws Exception {
        // A knight's lost hit: packed amount, literal zero source.
        var wire = new TestWire();
        var in = wire.frame(new SessionCodec()
                .sceneDelta(
                        2114,
                        3002,
                        0,
                        (short) 30,
                        88,
                        41300,
                        (short) 50,
                        List.of(),
                        List.of(),
                        new int[0],
                        Map.of(),
                        false,
                        List.of(),
                        List.of(new SceneCombatEvent(
                                1, 41300, 211, 28856833, 0, 12.5f, -40.25f, 1.5707964f, "NORMAL"))));
        check(
                wire.type(in).equals(TestWire.NET + "EventNotification") && in.readShort() == -1,
                "unsolicited network envelope");
        check(
                wire.type(in).equals("com.threerings.tudey.dobj.SceneDeltaEvent") && in.readInt() == 2114,
                "scene delta target");
        in.readInt();
        in.readInt();
        in.readShort();
        in.readInt();
        in.readInt();
        in.readShort(); // scene, acknowledge, ping, reference, timestamp, elapsed
        check(in.readShort() == 0 && in.readShort() == 0, "no actor additions or updates");
        check(in.readBoolean() && in.readInt() == 0, "no actor removals");
        check(
                wire.type(in).equals("[Lcom.threerings.tudey.data.effect.Effect;") && in.readInt() == 1,
                "effect array carries one feedback effect");
        check(wire.type(in).equals("com.threerings.projectx.dungeon.data.effect.DamageEffect"), "damage effect class");
        check(
                wire.type(in).equals("com.threerings.config.ConfigReference")
                        && in.readShort() == -1
                        && in.readUTF().equals("Feedback/Damage"),
                "Feedback/Damage entry reference");
        check(wire.type(in).equals("com.threerings.config.ArgumentMap") && in.readInt() == 0, "no reference arguments");
        check(in.readInt() == 41300, "hit timestamp");
        check(
                wire.type(in).equals("com.threerings.tudey.data.EntityKey$Actor") && in.readInt() == 211,
                "hit target actor key");
        check(
                wire.type(in).equals("com.threerings.math.Vector2f")
                        && in.readFloat() == 12.5f
                        && in.readFloat() == -40.25f,
                "hit translation");
        check(in.readFloat() == 1.5707964f, "hit rotation");
        check(in.readInt() == 0 && in.readBoolean() == false, "shared client oid and damage kind");
        check(in.readInt() == 28856833 && in.readInt() == 0, "packed type breakdown and literal zero source");
        check(
                wire.type(in).equals("com.threerings.projectx.dungeon.util.DamageSummary$Performance")
                        && in.readUnsignedByte() == 1,
                "NORMAL performance ordinal");
        TestWire.end(in);
        // A knight's sword on a monster: plain dealt amount, attributed source.
        var wire2 = new TestWire();
        var in2 = wire2.frame(new SessionCodec()
                .sceneDelta(
                        2114,
                        3002,
                        0,
                        (short) 30,
                        89,
                        41400,
                        (short) 50,
                        List.of(),
                        List.of(),
                        new int[0],
                        Map.of(),
                        false,
                        List.of(),
                        List.of(new SceneCombatEvent(1, 41400, 402, 13, 211, -1.25f, -53.5f, 0f, "NORMAL_LARGE"))));
        check(wire2.type(in2).equals(TestWire.NET + "EventNotification") && in2.readShort() == -1, "monster envelope");
        check(
                wire2.type(in2).equals("com.threerings.tudey.dobj.SceneDeltaEvent") && in2.readInt() == 2114,
                "monster scene delta target");
        in2.readInt();
        in2.readInt();
        in2.readShort();
        in2.readInt();
        in2.readInt();
        in2.readShort(); // scene, acknowledge, ping, reference, timestamp, elapsed
        in2.readShort();
        in2.readShort();
        in2.readBoolean();
        in2.readInt(); // empty actor sets
        check(
                wire2.type(in2).equals("[Lcom.threerings.tudey.data.effect.Effect;") && in2.readInt() == 1,
                "monster hit rides one effect");
        check(
                wire2.type(in2).equals("com.threerings.projectx.dungeon.data.effect.DamageEffect"),
                "same damage effect class");
        check(
                wire2.type(in2).equals("com.threerings.config.ConfigReference")
                        && in2.readShort() == -1
                        && in2.readUTF().equals("Feedback/Damage"),
                "Feedback/Damage entry reference");
        check(
                wire2.type(in2).equals("com.threerings.config.ArgumentMap") && in2.readInt() == 0,
                "no reference arguments");
        check(in2.readInt() == 41400, "monster hit timestamp");
        check(
                wire2.type(in2).equals("com.threerings.tudey.data.EntityKey$Actor") && in2.readInt() == 402,
                "damaged monster target");
        check(
                wire2.type(in2).equals("com.threerings.math.Vector2f")
                        && in2.readFloat() == -1.25f
                        && in2.readFloat() == -53.5f
                        && in2.readFloat() == 0f,
                "monster hit translation and rotation");
        check(in2.readInt() == 0 && in2.readBoolean() == false, "shared client oid and damage kind");
        check(in2.readInt() == 13 && in2.readInt() == 211, "dealt amount and the attacking pawn as source");
        check(
                wire2.type(in2).equals("com.threerings.projectx.dungeon.util.DamageSummary$Performance")
                        && in2.readUnsignedByte() == 8,
                "NORMAL_LARGE performance ordinal");
        TestWire.end(in2);
        // Ordinary effects and combat feedback share one array, effects first.
        var wire3 = new TestWire();
        var outage = List.of(new org.tortodrone.core.world.SceneEffect(
                3,
                new org.tortodrone.core.character.StarterCatalog.Model("Effect/Outro", Map.of()),
                41500,
                0f,
                0f,
                0f,
                0,
                402,
                null));
        var in3 = wire3.frame(new SessionCodec()
                .sceneDelta(
                        2114,
                        3002,
                        0,
                        (short) 30,
                        90,
                        41500,
                        (short) 50,
                        List.of(),
                        List.of(),
                        new int[0],
                        Map.of(),
                        false,
                        outage,
                        List.of(new SceneCombatEvent(2, 41500, 402, 7, 211, 0, 0, 0, "NORMAL"))));
        check(
                wire3.type(in3).equals(TestWire.NET + "EventNotification") && in3.readShort() == -1,
                "shared array envelope");
        check(
                wire3.type(in3).equals("com.threerings.tudey.dobj.SceneDeltaEvent") && in3.readInt() == 2114,
                "shared scene delta target");
        in3.readInt();
        in3.readInt();
        in3.readShort();
        in3.readInt();
        in3.readInt();
        in3.readShort(); // scene, acknowledge, ping, reference, timestamp, elapsed
        in3.readShort();
        in3.readShort();
        in3.readBoolean();
        in3.readInt();
        check(
                wire3.type(in3).equals("[Lcom.threerings.tudey.data.effect.Effect;") && in3.readInt() == 2,
                "one visual effect and one feedback effect");
        check(wire3.type(in3).equals("com.threerings.tudey.data.effect.Effect"), "visual effect first");
        check(
                wire3.type(in3).equals("com.threerings.config.ConfigReference")
                        && in3.readShort() == -1
                        && in3.readUTF().equals("Effect/Outro"),
                "visual effect's config reference");
        check(
                wire3.type(in3).equals("com.threerings.config.ArgumentMap") && in3.readInt() == 0,
                "no visual effect arguments");
        check(in3.readInt() == 41500, "visual effect timestamp");
        check(
                wire3.type(in3).equals("com.threerings.tudey.data.EntityKey$Actor") && in3.readInt() == 402,
                "visual effect target key");
        check(
                wire3.type(in3).equals("com.threerings.math.Vector2f")
                        && in3.readFloat() == 0f
                        && in3.readFloat() == 0f,
                "visual effect translation");
        check(in3.readFloat() == 0f && in3.readInt() == 0, "visual effect rotation and owner");
        check(
                wire3.type(in3).equals("com.threerings.projectx.dungeon.data.effect.DamageEffect"),
                "feedback effect after the visual");
        check(
                wire3.type(in3).equals("com.threerings.config.ConfigReference")
                        && in3.readShort() == -2
                        && in3.readUTF().equals("Feedback/Damage"),
                "Feedback/Damage entry intern after a class back-reference");
        check(
                wire3.type(in3).equals("com.threerings.config.ArgumentMap") && in3.readInt() == 0,
                "no reference arguments");
        check(in3.readInt() == 41500, "feedback timestamp");
        check(
                wire3.type(in3).equals("com.threerings.tudey.data.EntityKey$Actor") && in3.readInt() == 402,
                "feedback target key");
        check(
                wire3.type(in3).equals("com.threerings.math.Vector2f")
                        && in3.readFloat() == 0f
                        && in3.readFloat() == 0f
                        && in3.readFloat() == 0f,
                "feedback translation and rotation");
        check(in3.readInt() == 0 && in3.readBoolean() == false, "shared client oid and damage kind");
        check(in3.readInt() == 7 && in3.readInt() == 211, "dealt amount and the attacking pawn as source");
        check(
                wire3.type(in3).equals("com.threerings.projectx.dungeon.util.DamageSummary$Performance")
                        && in3.readUnsignedByte() == 1,
                "NORMAL performance ordinal");
        TestWire.end(in3);
        // Only native performance names stream; anything else must refuse, not send garbage.
        try {
            new SessionCodec()
                    .sceneDelta(
                            2114,
                            3002,
                            0,
                            (short) 30,
                            91,
                            0,
                            (short) 50,
                            List.of(),
                            List.of(),
                            new int[0],
                            Map.of(),
                            false,
                            List.of(),
                            List.of(new SceneCombatEvent(1, 0, 0, 0, 0, 0, 0, 0, "SPARKLY")));
            check(false, "unsupported performance refused");
        } catch (java.io.IOException expected) {
        }
        System.out.println(
                "PASS: Feedback/Damage effect element, its base Effect fields, the native damage fields and performance ordinal, refusing unknown performances");
    }
}
