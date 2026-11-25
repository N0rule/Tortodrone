package org.tortodrone.core.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Numeric combat metadata exported from the unchanged stock client. */
public final class WeaponCatalog {
    static final int MAX_ATTACKS = 32;

    public record Attack(
            int land,
            int rearm,
            int clear,
            boolean blended,
            int impulseDelay,
            int impulseDuration,
            float impulseX,
            float impulseY) {
        public Attack {
            boundedTime(land, "attack land");
            boundedTime(rearm, "attack rearm");
            boundedTime(clear, "attack clear");
            if (impulseDelay < -1
                    || impulseDelay > 600_000
                    || impulseDuration < -1
                    || impulseDuration > 600_000
                    || !Float.isFinite(impulseX)
                    || !Float.isFinite(impulseY)
                    || Math.abs(impulseX) > 10_000
                    || Math.abs(impulseY) > 10_000
                    || (impulseDelay == -1) != (impulseDuration == -1))
                throw new IllegalArgumentException("Invalid attack impulse metadata");
        }
    }

    public record Weapon(
            String config,
            int chargeTime,
            int maxChain,
            int chainCooldown,
            boolean cycle,
            boolean blended,
            float attackingSpeed,
            float chargingSpeed,
            List<Attack> attacks,
            Attack incomplete,
            Attack charged,
            Attack cooldown) {
        public Weapon {
            config = reference(config);
            boundedTime(chargeTime, "charge time");
            if (maxChain < 0 || maxChain > MAX_ATTACKS)
                throw new IllegalArgumentException("Invalid weapon chain length");
            boundedTime(chainCooldown, "chain cooldown");
            speed(attackingSpeed);
            speed(chargingSpeed);
            attacks = List.copyOf(attacks);
            if (attacks.size() > MAX_ATTACKS) throw new IllegalArgumentException("Too many weapon attacks");
        }
    }

    public record Shield(
            String config,
            int health,
            int regenTime,
            int regenDelay,
            float defendingSpeed,
            float bashHealthPct,
            Attack bash,
            int breakDelay) {
        public Shield(
                String config,
                int health,
                int regenTime,
                int regenDelay,
                float defendingSpeed,
                float bashHealthPct,
                Attack bash) {
            // Legacy seven-column exports omitted the stock Shield default breakTime.
            this(config, health, regenTime, regenDelay, defendingSpeed, bashHealthPct, bash, 7000);
        }
        /** Compatibility constructor for small fixtures that do not exercise shield speed or bash cost. */
        public Shield(String config, int health, int regenTime, int regenDelay, Attack bash) {
            this(config, health, regenTime, regenDelay, 0f, 0f, bash);
        }

        public Shield {
            config = reference(config);
            if (health < 0 || health > 100_000) throw new IllegalArgumentException("Invalid shield health");
            if (regenTime < 1 || regenTime > 600_000) throw new IllegalArgumentException("Invalid shield regen time");
            boundedTime(regenDelay, "shield regen delay");
            boundedTime(breakDelay, "shield break delay");
            // Stock Dungeoneer speed is multiplied by 1 + defendingSpeed; installed shields use [-1, 0].
            if (!Float.isFinite(defendingSpeed) || defendingSpeed < -1 || defendingSpeed > 0)
                throw new IllegalArgumentException("Invalid shield defending speed");
            if (!Float.isFinite(bashHealthPct) || bashHealthPct < 0 || bashHealthPct > 1)
                throw new IllegalArgumentException("Invalid shield bash cost");
        }
    }

    /** Verified stock knight dodge cooldown; deployments no longer carry a combat override file. */
    public static final int STOCK_DODGE_COOLDOWN_MS = 8000;

    private final Map<String, Weapon> weapons;
    private final Map<String, Shield> shields;
    private final int dodgeCooldown;

    public WeaponCatalog(Collection<Weapon> weapons, Collection<Shield> shields) {
        this(weapons, shields, 0);
    }

    public WeaponCatalog(Collection<Weapon> weapons, Collection<Shield> shields, int dodgeCooldown) {
        var wm = new TreeMap<String, Weapon>();
        for (Weapon weapon : weapons)
            if (wm.put(key(weapon.config()), weapon) != null)
                throw new IllegalArgumentException("Duplicate weapon reference");
        var sm = new TreeMap<String, Shield>();
        for (Shield shield : shields)
            if (sm.put(key(shield.config()), shield) != null)
                throw new IllegalArgumentException("Duplicate shield reference");
        boundedTime(dodgeCooldown, "dodge cooldown");
        this.weapons = Map.copyOf(wm);
        this.shields = Map.copyOf(sm);
        this.dodgeCooldown = dodgeCooldown;
    }

    public static WeaponCatalog empty() {
        return new WeaponCatalog(List.of(), List.of());
    }

    /** Read the exported catalogue; the codec owns format, ordering and row validation. */
    public static WeaponCatalog load(Path file) throws IOException {
        return WeaponCatalogCodec.load(file);
    }

    public static void write(Path file, Collection<Weapon> weapons, Collection<Shield> shields) throws IOException {
        WeaponCatalogCodec.write(file, weapons, shields);
    }

    public static void write(Path file, Collection<Weapon> weapons, Collection<Shield> shields, int dodgeCooldown)
            throws IOException {
        WeaponCatalogCodec.write(file, weapons, shields, dodgeCooldown);
    }

    public List<Weapon> weapons() {
        return weapons.values().stream()
                .sorted(Comparator.comparing(Weapon::config))
                .toList();
    }

    public List<Shield> shields() {
        return shields.values().stream()
                .sorted(Comparator.comparing(Shield::config))
                .toList();
    }

    public Weapon find(String config) {
        return weapons.get(key(config));
    }

    public Weapon require(String config) throws IOException {
        Weapon weapon = find(config);
        if (weapon == null) throw new IOException("Unknown weapon config: " + config);
        return weapon;
    }

    public Shield findShield(String config) {
        return shields.get(key(config));
    }

    public int dodgeCooldown() {
        return dodgeCooldown;
    }

    static String reference(String value) {
        if (value == null
                || value.isBlank()
                || value.length() > 256
                || value.indexOf('\t') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0) throw new IllegalArgumentException("Invalid config reference");
        return value;
    }

    static String key(String value) {
        return reference(value).toLowerCase(Locale.ROOT);
    }

    static void boundedTime(int value, String label) {
        if (value < 0 || value > 600_000) throw new IllegalArgumentException("Invalid " + label);
    }

    private static void speed(float value) {
        if (!Float.isFinite(value) || value < 0 || value > 100)
            throw new IllegalArgumentException("Invalid weapon speed");
    }
}
