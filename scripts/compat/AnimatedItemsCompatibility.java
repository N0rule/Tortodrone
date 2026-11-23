import com.threerings.config.*;
import com.threerings.presents.dobj.CompoundEvent;
import com.threerings.presents.net.EventNotification;
import com.threerings.projectx.item.config.ItemConfig;
import com.threerings.tudey.config.ActionConfig;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.core.character.AnimatedItems;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.protocol.*;

/** Read-only oracle for every player firework/firefly reference and timed upgrade attribute. */
public final class AnimatedItemsCompatibility {
    public static void main(String[] args) throws Exception {
        NativeConfig.ordinal();
        var manager = NativeConfig.load(Path.of(args[0]));
        var catalog = ItemCatalog.load(Path.of("content/items"));
        int count = 0;
        for (var item : catalog.entries())
            if (AnimatedItems.supports(item.config())) {
                var action = (ItemConfig.AnimatedAction)
                        manager.d(ItemConfig.class, item.config()).w(manager);
                Harness.check(action.land == AnimatedItems.land(item.config()), "native land time: " + item.config());
                Harness.check(
                        action.land + action.fv(12345).clear == AnimatedItems.duration(item.config()),
                        "native clear time: " + item.config());
                var refs = new ArrayList<ConfigReference<?>>();
                if (action.action instanceof ActionConfig.FireEffect fire) refs.add(fire.effect);
                else if (action.action instanceof ActionConfig.Random random)
                    for (var weighted : random.actions) {
                        Harness.check(weighted.weight == 1, "equal native random weights");
                        refs.add(((ActionConfig.FireEffect) weighted.action).effect);
                    }
                else throw new AssertionError("Unsupported native action: " + item.config());
                var definitions = AnimatedItems.definitions(item.config());
                Harness.check(refs.size() == definitions.size(), "all native effect alternatives included");
                for (int i = 0; i < refs.size(); i++) {
                    var def = definitions.get(i);
                    var ref = refs.get(i);
                    Harness.check(ref.getName().equals(def.effect()), "native effect name");
                    if (def.effect().equals("Basic")) {
                        Harness.check(
                                Boolean.FALSE.equals(ref.ld().get("Attach to Target"))
                                        && ref.ld().get("Lifespan").equals(6000),
                                "native detached effect lifetime");
                        var model = (ConfigReference<?>) ref.ld().get("Model");
                        Harness.check(
                                model.getName().equals("world/dynamic/fireworks/fx_fire.dat")
                                        && model.ld().get("Model").equals(def.size())
                                        && model.ld().get("Colorization").equals(def.color()),
                                "native firework model/size/color");
                    } else Harness.check(ref.ld().get("Colorization").equals(def.color()), "native firefly color");
                }
                count++;
            }
        Harness.check(count == 82, "all playable animated variants");
        var knight = new Knight(1, "Fixture", "", "", null, null, "Blue", 0, 2, 0, 0);
        long until = System.currentTimeMillis() + 60000;
        var heat = new WorldProfile.Item(
                        81, WorldProfile.Slot.OTHER, "Upgrade/Heat Bonus", null, 0, null, true, false, false, 1, false)
                .withExpiration(until);
        var coin = new WorldProfile.Item(
                        82, WorldProfile.Slot.OTHER, "Upgrade/Coin Bonus", null, 0, null, true, false, false, 1, false)
                .withExpiration(until);
        var active = new WorldProfile(1, 1, 1, 0, 0, 1, List.of(heat, coin));
        var empty = new WorldProfile(1, 1, 1, 0, 0, 2, List.of());
        var codec = new SessionCodec(catalog);
        var bytes = new ByteArrayOutputStream();
        Harness.append(bytes, HandshakeCodec.response((short) 1, "0123456789abcdef0123456789abcdef"));
        Harness.append(bytes, HandshakeCodec.authenticationResponse((short) 2, true, "synthetic", "Alice"));
        Harness.append(bytes, codec.selected(1001, knight, active));
        Harness.append(bytes, codec.itemChanges(1001, knight, active, empty));
        var in = new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));
        in.ql();
        in.ql();
        var player = new com.threerings.projectx.data.PlayerObject();
        for (var child : ((CompoundEvent) ((EventNotification) in.ql()).yn()).xI()) child.b(player);
        Harness.check(player.heatBonus && player.pvpTokenBonus == 1, "native timed bonus selection attributes");
        for (var child : ((CompoundEvent) ((EventNotification) in.ql()).yn()).xI()) child.b(player);
        Harness.check(
                !player.heatBonus && player.pvpTokenBonus == 0 && in.available() == 0,
                "native expiry clears bonuses without trailing bytes");
        System.out.println(
                "PASS: 82 native animated variants, effects, colors, sizes, delays and timed bonus selection/expiry attributes");
    }
}
