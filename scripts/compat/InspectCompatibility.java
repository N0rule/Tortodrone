import com.threerings.io.Streamer;
import com.threerings.io.v;
import com.threerings.presents.dobj.CompoundEvent;
import com.threerings.presents.dobj.DObject;
import com.threerings.presents.dobj.InvocationResponseEvent;
import com.threerings.presents.net.EventNotification;
import com.threerings.presents.net.ObjectResponse;
import com.threerings.projectx.data.PlayerEquipment;
import com.threerings.projectx.data.PlayerObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.*;
import org.tortodrone.character.Knight;
import org.tortodrone.character.WorldProfile;
import org.tortodrone.protocol.HandshakeCodec;
import org.tortodrone.protocol.SessionCodec;

/** Stock decoder comparison for the server's method-2 inspection result. */
public final class InspectCompatibility {
    private InspectCompatibility() {}

    public static void main(String[] args) throws Exception {
        System.setProperty("com.threerings.io.enumPolicy", "ORDINAL");
        fields();
        compare(false);
        compare(true);
    }

    private static void compare(boolean hidden) throws Exception {
        Knight knight = new Knight(1, "Inspect-Knight", "Gear/Helm/Spiral Sallet (Blue)",
            "Gear/Armor/Spiral Culet (Blue)", null, null, "Blue", 1, 2, 0, 1);
        var items = List.of(
            new WorldProfile.Item(101, WorldProfile.Slot.HELM, knight.helm(), null, 0, 0, true, true, true),
            new WorldProfile.Item(102, WorldProfile.Slot.ARMOR, knight.armor(), null, 0, 1, true, true, true),
            new WorldProfile.Item(103, WorldProfile.Slot.SWORD, "Weapon/Sword/Calibur", null, 0, 6, true, true, true),
            new WorldProfile.Item(104, WorldProfile.Slot.GUN, "Weapon/Gun/Blaster", null, 0, 7, true, true, true),
            new WorldProfile.Item(105, WorldProfile.Slot.SHIELD, "Gear/Shield/Spiral Shield (Blue)", null, 0, 2, true, true, true),
            new WorldProfile.Item(106, WorldProfile.Slot.ARMOR_COSTUME, "Costume/Armor/Bug/Hexoskeleton, Cool",
                "Helm/Back/Plume/Plume", 0, 4, true, true, true));
        var profile = new WorldProfile(50, 1, 1, 73, 29, 1, items);
        var codec = new SessionCodec();
        var bytes = new ByteArrayOutputStream();
        append(bytes, HandshakeCodec.response((short) 1, "0123456789abcdef0123456789abcdef"));
        append(bytes, HandshakeCodec.authenticationResponse((short) 2, true, "fixture", "InspectUser"));
        append(bytes, codec.player((short) 3, 1002, 1, "InspectUser"));
        append(bytes, codec.selected(1002, knight, profile));
        append(bytes, codec.inspected(1002, (short) 9, knight, profile, hidden));

        var input = new v(new ByteArrayInputStream(bytes.toByteArray()));
        input.qe(); input.qe();
        PlayerObject target = (PlayerObject) ((ObjectResponse) input.qe()).yk();
        apply(input, target);
        target.flags = hidden ? 1024 : 0;
        PlayerEquipment stock = target.a(PlayerObject.EquipView.INSPECT);
        var resultEvent = (InvocationResponseEvent) ((EventNotification) input.qe()).yj();
        if (resultEvent.yf() != 9 || resultEvent.yc() != 1 || resultEvent.yd().length != 1 ||
            !(resultEvent.yd()[0] instanceof PlayerEquipment actual))
            throw new AssertionError("Malformed native inspect response: " + resultEvent);
        compareFields(stock, actual, hidden);
        if (input.available() != 0) throw new AssertionError("Unexpected trailing inspect payload");
        System.out.println("PASS stock PlayerObject.EquipView.INSPECT matches server result: hidden=" + hidden +
            ", visibleSlots=" + (hidden ? actual.visibleEquipment.keySet() : actual.items.keySet()) +
            ", privateItems=" + (actual.items == null));
    }

    private static void compareFields(PlayerEquipment expected, PlayerEquipment actual, boolean hidden) {
        if (!Objects.equals(expected.name, actual.name) || expected.color != actual.color ||
            expected.eye != actual.eye || expected.height != actual.height || expected.rank != actual.rank ||
            expected.prestige != actual.prestige || !Objects.equals(expected.sprite, actual.sprite) ||
            (expected.achievements == null) != (actual.achievements == null) ||
            expected.achievements != null && expected.achievements.size() != actual.achievements.size())
            throw new AssertionError("PlayerEquipment identity/rank/achievement fields diverged: expected=" +
                expected.name + "/" + expected.color + "/" + expected.eye + "/" + expected.height + "/" +
                expected.rank + "/" + expected.prestige + "/" + expected.sprite + "/" + expected.achievements +
                " actual=" + actual.name + "/" + actual.color + "/" + actual.eye + "/" + actual.height + "/" +
                actual.rank + "/" + actual.prestige + "/" + actual.sprite + "/" + actual.achievements);
        if (hidden) {
            if (actual.items != null || actual.visibleEquipment == null || actual.accessories == null)
                throw new AssertionError("Private inspection must include visible equipment/accessories only");
            if (!actual.visibleEquipment.keySet().equals(Set.of(0, 1, 2)) ||
                !actual.visibleEquipment.get(1).getName().equals("Costume/Armor/Bug/Hexoskeleton, Cool") ||
                !expected.visibleEquipment.equals(actual.visibleEquipment))
                throw new AssertionError("Private appearance did not preserve armor costume override: " + actual.visibleEquipment);
            if (actual.accessories.size() != 1 || !expected.accessories.equals(actual.accessories) ||
                !actual.accessories.iterator().next().getName().equals("Helm/Back/Plume/Plume, Blue"))
                throw new AssertionError("Private appearance accessory mismatch: " + actual.accessories);
        } else {
            if (actual.items == null || actual.visibleEquipment != null || actual.accessories != null || actual.prestige != expected.prestige)
                throw new AssertionError("Public inspection must contain equipped items, not appearance-only maps");
            if (!actual.items.keySet().equals(Set.of(0, 1, 2, 4, 6, 7)) || actual.items.size() != expected.items.size())
                throw new AssertionError("Public inspection item slot map mismatch: " + actual.items.keySet());
            if (!Objects.equals(actual.visibleEquipment, expected.visibleEquipment))
                throw new AssertionError("Public inspection unexpectedly included appearance-only equipment");
            for (var entry : expected.items.entrySet()) {
                var received = actual.items.get(entry.getKey());
                if (received == null || received.Vx() == null || !received.Vx().getName().equals(entry.getValue().Vx().getName()))
                    throw new AssertionError("Public inspected item mismatch at slot " + entry.getKey());
            }
        }
    }

    private static void apply(v input, DObject object) throws Exception {
        var event = ((EventNotification) input.qe()).yj();
        if (event instanceof CompoundEvent compound) for (var child : compound.xE()) child.b(object);
        else event.b(object);
    }

    private static void fields() throws Exception {
        Field declared = Streamer.bR(PlayerEquipment.class).getClass().getDeclaredField("_fields");
        declared.setAccessible(true);
        String[] expected = {"name", "color", "eye", "height", "rank", "prestige", "visibleEquipment",
            "items", "accessories", "sprite", "achievements"};
        String[] actual = Arrays.stream((Field[]) declared.get(Streamer.bR(PlayerEquipment.class)))
            .map(Field::getName).toArray(String[]::new);
        if (!Arrays.equals(expected, actual)) throw new AssertionError("Stock PlayerEquipment _fields order changed: " + Arrays.toString(actual));
        System.out.println("FIELDS PlayerEquipment=" + Arrays.toString(actual));
    }

    private static void append(ByteArrayOutputStream bytes, byte[] frame) {
        bytes.write(frame, 4, frame.length - 4);
    }
}
