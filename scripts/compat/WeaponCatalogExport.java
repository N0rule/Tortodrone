import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.projectx.dungeon.config.*;
import com.threerings.projectx.item.config.*;
import com.threerings.tudey.config.ActorConfig;
import org.tortodrone.world.WeaponCatalog;
import org.tortodrone.world.WeaponCatalog.Attack;
import org.tortodrone.world.WeaponCatalog.Shield;
import org.tortodrone.world.WeaponCatalog.Weapon;

/** Read-only exporter of stock combat timing/configuration references; emits no assets. */
public final class WeaponCatalogExport {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: WeaponCatalogExport game-directory fresh-output-directory");
        Path game = Path.of(args[0]).toRealPath(), out = Path.of(args[1]).toAbsolutePath().normalize();
        Path ancestor = out; while (!Files.exists(ancestor)) ancestor = ancestor.getParent();
        if (ancestor.toRealPath().startsWith(game) || Files.exists(out))
            throw new IOException("Choose a fresh output directory outside the installation");

        var resource = Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga", String.class).invoke(resource, game.resolve("rsrc").toString());
        var manager = (ConfigManager)ConfigManager.class
            .getConstructor(Class.forName("com.threerings.b.f"), com.threerings.util.R.class, String.class)
            .newInstance(resource, null, "config/");
        manager.init();

        var weapons = new ArrayList<Weapon>(); var shields = new ArrayList<Shield>();
        for (var config : manager.bd(ItemConfig.class).kK()) {
            ItemConfig.Original item = config.w(manager);
            if (!playerDefinition(config.getConfigId(), item)) continue;
            if (item instanceof ItemConfig.Weapon weapon) weapons.add(readWeapon(manager, config.getName(), weapon));
            else if (item instanceof ItemConfig.Shield shield) shields.add(readShield(manager, config.getName(), shield));
        }
        var pcConfig = manager.d(ActorConfig.class, "Character/PC/Level").ai(manager);
        if (!(pcConfig instanceof DungeonActorConfig.Dungeoneer dungeoneerConfig))
            throw new IOException("Character/PC/Level did not resolve to the stock Dungeoneer config");

        Files.createDirectories(out);
        WeaponCatalog.write(out.resolve("weapons.tsv"), weapons, shields, dungeoneerConfig.dodgeCooldown);
        String facts = "# Read-only stock metadata; no assets.\n";
        for (String file : List.of("code/projectx-pcode.jar", "rsrc/config/item.dat", "rsrc/config/attack.dat", "rsrc/config/shield.dat")) {
            Path path = game.resolve(file); if (!Files.isRegularFile(path)) continue;
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(path)) { byte[] buf = new byte[65536]; for (int n; (n = in.read(buf)) != -1;) digest.update(buf, 0, n); }
            facts += file + "\t" + HexFormat.of().formatHex(digest.digest()) + "\n";
        }
        Files.writeString(out.resolve("provenance.tsv"), facts, StandardOpenOption.CREATE_NEW);
        var loaded = WeaponCatalog.load(out.resolve("weapons.tsv"));
        System.out.println("PASS: exported " + loaded.weapons().size() + " player weapon definitions and "
            + loaded.shields().size() + " shield definitions");
    }

    private static Weapon readWeapon(ConfigManager manager, String name, ItemConfig.Weapon weapon) {
        var attacks = new ArrayList<Attack>();
        for (AttackConfig.AttackReference reference : weapon.attacks) {
            AttackConfig.Original attack = resolve(manager, reference == null ? null : reference.ref);
            if (attack != null) attacks.add(readAttack(weapon.Vb(), attack));
        }
        Attack incomplete = readAttackRef(manager, weapon.Vb(), weapon.incompleteChargedAttack);
        Attack charged = readAttackRef(manager, weapon.Vb(), weapon.chargedAttack);
        Attack cooldown = readAttackRef(manager, weapon.Vb(), weapon.cooldownAttack);
        return new Weapon(name, weapon.chargeTime, weapon.maxChain, weapon.chainCooldown, weapon.Vc(), weapon.Vb(),
            weapon.attackingSpeed, weapon.chargingSpeed, attacks, incomplete, charged, cooldown);
    }

    private static Shield readShield(ConfigManager manager, String name, ItemConfig.Shield shield) {
        int health = shield.d(manager, 0);
        AttackConfig.Original bashOriginal = resolve(manager, shield.bash);
        if (bashOriginal != null && !(bashOriginal instanceof AttackConfig.ShieldBash))
            throw new IllegalStateException("Shield bash reference is not a ShieldBash: " + name);
        float bashHealthPct = bashOriginal == null ? 0f : ((AttackConfig.ShieldBash)bashOriginal).bashHealthPct;
        Attack bash = readAttack(false, bashOriginal);
        return new Shield(name, health, shield.regenTime, shield.hitTime, shield.defendingSpeed, bashHealthPct, bash);
    }

    private static Attack readAttackRef(ConfigManager manager, boolean blended,
            ConfigReference<AttackConfig> reference) {
        return readAttack(blended, resolve(manager, reference));
    }

    private static AttackConfig.Original resolve(ConfigManager manager, ConfigReference<AttackConfig> reference) {
        return reference == null ? null : AttackConfig.b(manager, reference);
    }

    private static Attack readAttack(boolean blended, AttackConfig.Original attack) {
        if (attack == null) return null;
        var impulse = attack.impulseReceived;
        return new Attack(attack.Qh(), attack.Qg(), attack.Qf(), blended && attack.blended,
            impulse == null ? -1 : impulse.delay, impulse == null ? -1 : impulse.duration,
            impulse == null || impulse.translation == null ? 0f : impulse.translation.x,
            impulse == null || impulse.translation == null ? 0f : impulse.translation.y);
    }

    private static boolean playerDefinition(int id, ItemConfig.Original item) {
        boolean eligibleSlot = item instanceof ItemConfig.Weapon weapon
            && weapon.h(6, false)
            && (item instanceof ItemConfig.Sword || item instanceof ItemConfig.Handgun || item instanceof ItemConfig.Bomb)
            || item instanceof ItemConfig.Shield shield && shield.h(2, false);
        return id != 0 && item.name != null && !item.name.isBlank() && item.icon != null
            && item.expireDays == 0 && (item.expireEvent == null || item.expireEvent.isBlank()) && eligibleSlot;
    }
}
