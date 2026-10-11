import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.projectx.item.config.*;
import com.threerings.projectx.item.data.*;
import org.tortodrone.character.*;
import org.tortodrone.world.EquipmentCatalog;
import org.tortodrone.world.EquipmentCatalog.Entry;
import org.tortodrone.world.EquipmentCatalog.Modifiers;
import org.tortodrone.world.EquipmentCatalog.WeaponClass;

/** Exports stock numeric equipment-control modifiers, with no client assets. */
public final class EquipmentCatalogExport {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException(
            "Usage: EquipmentCatalogExport game-directory grant-catalog.json fresh-output-directory");
        Path game = Path.of(args[0]).toRealPath();
        Path grants = Path.of(args[1]).toRealPath();
        Path out = Path.of(args[2]).toAbsolutePath().normalize();
        Path ancestor = out; while (!Files.exists(ancestor)) ancestor = ancestor.getParent();
        if (ancestor.toRealPath().startsWith(game) || Files.exists(out))
            throw new IOException("Choose a fresh output directory outside the installation");

        ItemCatalog grantCatalog = ItemCatalog.load(grants);
        var resource = Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga", String.class).invoke(resource, game.resolve("rsrc").toString());
        var manager = (ConfigManager)ConfigManager.class
            .getConstructor(Class.forName("com.threerings.b.f"), com.threerings.util.R.class, String.class)
            .newInstance(resource, null, "config/");
        manager.init();

        var definitions = new HashMap<String, ItemConfig.Original>();
        var representatives = new EnumMap<WeaponClass, ItemConfig.Weapon>(WeaponClass.class);
        for (var config : manager.bd(ItemConfig.class).kK()) {
            ItemConfig.Original original = config.w(manager);
            definitions.put(config.getName().toLowerCase(Locale.ROOT), original);
            if (original instanceof ItemConfig.Sword sword) representatives.putIfAbsent(WeaponClass.SWORD, sword);
            else if (original instanceof ItemConfig.Handgun handgun) representatives.putIfAbsent(WeaponClass.HANDGUN, handgun);
            else if (original instanceof ItemConfig.Bomb bomb) representatives.putIfAbsent(WeaponClass.BOMB, bomb);
        }
        for (WeaponClass weaponClass : List.of(WeaponClass.SWORD, WeaponClass.HANDGUN, WeaponClass.BOMB))
            if (!representatives.containsKey(weaponClass)) throw new IOException("Stock weapon class unavailable: " + weaponClass);

        var rows = new ArrayList<Entry>();
        for (ItemCatalog.Entry grantable : grantCatalog.entries()) {
            if (!grantable.grantable()) continue;
            ItemConfig.Original original = definitions.get(grantable.config().toLowerCase(Locale.ROOT));
            if (original == null) throw new IOException("Grant catalogue config missing from stock: " + grantable.config());
            var byClass = new EnumMap<WeaponClass, Modifiers>(WeaponClass.class);
            for (WeaponClass weaponClass : WeaponClass.values()) {
                ItemConfig.Weapon weapon = weaponClass == WeaponClass.NONE ? null : representatives.get(weaponClass);
                byClass.put(weaponClass, readModifiers(manager, grantable.config(), original, weapon));
            }
            rows.add(new Entry(grantable.config(), grantable.slot(), byClass));
        }

        Files.createDirectories(out);
        EquipmentCatalog.write(out.resolve("equipment.tsv"), rows);
        String facts = "# Read-only stock metadata; numeric control modifiers only.\n";
        for (Path path : List.of(game.resolve("code/projectx-pcode.jar"), game.resolve("rsrc/config/item.dat"), grants)) {
            if (!Files.isRegularFile(path)) continue;
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536]; for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer, 0, n);
            }
            facts += path.toString() + "\t" + HexFormat.of().formatHex(digest.digest()) + "\n";
        }
        Files.writeString(out.resolve("provenance.tsv"), facts, StandardOpenOption.CREATE_NEW);
        var loaded = EquipmentCatalog.load(out.resolve("equipment.tsv"));
        System.out.println("PASS: exported level-zero control modifiers for " + loaded.entries().size()
            + " grantable item definitions");
    }

    private static Modifiers readModifiers(ConfigManager manager, String name,
            ItemConfig.Original original, ItemConfig.Weapon weapon) throws IOException {
        var modifiers = new EquipmentModifiers();
        if (original instanceof ItemConfig.Level levelItem) {
            levelItem.a(modifiers);
            LevelConfig level = levelItem.c(manager, 0);
            if (level == null) throw new IOException("Unresolved level-zero modifier config: " + name);
            level.a(modifiers, weapon);
            // The persisted WorldProfile currently carries no variant list, so the stock streamed
            // item state is the unvarianted LevelItem for these grantable references.
            new LevelItem(0, new ConfigReference<ItemConfig>(name)).a(manager, modifiers);
        }
        try {
            return new Modifiers(modifiers.chargeTime, modifiers.speed,
                modifiers.attackSpeed, modifiers.defendingSpeed);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Unsupported control modifier for " + name, invalid);
        }
    }
}
