package org.tortodrone.world;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Numeric combat metadata exported from the unchanged stock client. */
public final class WeaponCatalog {
    private static final String HEADER = "TORTODRONE-COMBAT\t1";
    private static final long MAX_BYTES = 16L * 1024 * 1024;
    private static final int MAX_ROWS = 100_000, MAX_WEAPONS = 10_000, MAX_ATTACKS = 32;

    public record Attack(int land, int rearm, int clear, boolean blended, int impulseDelay,
            int impulseDuration, float impulseX, float impulseY) {
        public Attack {
            boundedTime(land, "attack land"); boundedTime(rearm, "attack rearm");
            boundedTime(clear, "attack clear");
            if (impulseDelay < -1 || impulseDelay > 600_000 || impulseDuration < -1 || impulseDuration > 600_000
                    || !Float.isFinite(impulseX) || !Float.isFinite(impulseY)
                    || Math.abs(impulseX) > 10_000 || Math.abs(impulseY) > 10_000
                    || (impulseDelay == -1) != (impulseDuration == -1))
                throw new IllegalArgumentException("Invalid attack impulse metadata");
        }
    }

    public record Weapon(String config, int chargeTime, int maxChain, int chainCooldown, boolean cycle,
            boolean blended, float attackingSpeed, float chargingSpeed, List<Attack> attacks,
            Attack incomplete, Attack charged, Attack cooldown) {
        public Weapon {
            config = reference(config);
            boundedTime(chargeTime, "charge time");
            if (maxChain < 0 || maxChain > MAX_ATTACKS) throw new IllegalArgumentException("Invalid weapon chain length");
            boundedTime(chainCooldown, "chain cooldown");
            speed(attackingSpeed); speed(chargingSpeed);
            attacks = List.copyOf(attacks);
            if (attacks.size() > MAX_ATTACKS) throw new IllegalArgumentException("Too many weapon attacks");
        }
    }

    public record Shield(String config, int health, int regenTime, int regenDelay,
            float defendingSpeed, float bashHealthPct, Attack bash,int breakDelay) {
        public Shield(String config,int health,int regenTime,int regenDelay,float defendingSpeed,float bashHealthPct,Attack bash){
            // Legacy seven-column exports omitted the stock Shield default breakTime.
            this(config,health,regenTime,regenDelay,defendingSpeed,bashHealthPct,bash,7000);
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

    private record AttackRow(String kind, int index, Attack attack) { }
    private static final class Building {
        final Weapon base;
        final ArrayList<Attack> normal = new ArrayList<>();
        final Set<String> special = new HashSet<>();
        Attack incomplete, charged, cooldown;
        Building(Weapon base) { this.base = base; }
    }

    private final Map<String, Weapon> weapons;
    private final Map<String, Shield> shields;
    private final int dodgeCooldown;

    public WeaponCatalog(Collection<Weapon> weapons, Collection<Shield> shields) {
        this(weapons, shields, 0);
    }

    public WeaponCatalog(Collection<Weapon> weapons, Collection<Shield> shields, int dodgeCooldown) {
        var wm = new TreeMap<String, Weapon>();
        for (Weapon weapon : weapons)
            if (wm.put(key(weapon.config()), weapon) != null) throw new IllegalArgumentException("Duplicate weapon reference");
        var sm = new TreeMap<String, Shield>();
        for (Shield shield : shields)
            if (sm.put(key(shield.config()), shield) != null) throw new IllegalArgumentException("Duplicate shield reference");
        boundedTime(dodgeCooldown, "dodge cooldown");
        this.weapons = Map.copyOf(wm); this.shields = Map.copyOf(sm); this.dodgeCooldown = dodgeCooldown;
    }

    public static WeaponCatalog empty() { return new WeaponCatalog(List.of(), List.of()); }

    public static WeaponCatalog load(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_BYTES) throw new IOException("Weapon catalogue exceeds size limit");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !HEADER.equals(lines.getFirst())) throw new IOException("Unsupported weapon catalogue format");
        if (lines.size() > MAX_ROWS) throw new IOException("Weapon catalogue exceeds row limit");
        var bases = new TreeMap<String, Weapon>();
        var attacks = new HashMap<String, List<AttackRow>>();
        var shields = new ArrayList<Shield>();
        var shieldRows = new TreeMap<String, Shield>();
        var shieldBashes = new HashMap<String, Attack>();
        Integer dodgeCooldown = null;
        for (int lineNo = 1; lineNo < lines.size(); lineNo++) {
            String line = lines.get(lineNo);
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\t", -1);
            try {
                switch (f[0]) {
                    case "W" -> {
                        requireFields(f, 9);
                        Weapon w = new Weapon(f[1], integer(f[2]), integer(f[3]), integer(f[4]), bool(f[5]), bool(f[6]),
                            decimal(f[7]), decimal(f[8]), List.of(), null, null, null);
                        if (bases.put(key(w.config()), w) != null || bases.size() > MAX_WEAPONS) throw new IllegalArgumentException("Duplicate/too many weapons");
                    }
                    case "A" -> {
                        requireFields(f, 12);
                        String weaponKey = key(reference(f[1]));
                        String kind = f[2]; int index = integer(f[3]);
                        if (!Set.of("NORMAL", "INCOMPLETE", "CHARGED", "COOLDOWN", "BASH").contains(kind)
                                || index < 0 || index >= MAX_ATTACKS || (!kind.equals("NORMAL") && index != 0))
                            throw new IllegalArgumentException("Invalid attack key");
                        Attack attack = new Attack(integer(f[4]), integer(f[5]), integer(f[6]), bool(f[7]), integer(f[8]),
                            integer(f[9]), decimal(f[10]), decimal(f[11]));
                        if (kind.equals("BASH")) {
                            if (shieldBashes.put(weaponKey, attack) != null) throw new IllegalArgumentException("Duplicate shield bash");
                        } else attacks.computeIfAbsent(weaponKey, ignored -> new ArrayList<>()).add(new AttackRow(kind, index, attack));
                    }
                    case "S" -> {
                        if(f.length!=7&&f.length!=8)throw new IllegalArgumentException("Invalid shield row");
                        Shield s = new Shield(f[1], integer(f[2]), integer(f[3]), integer(f[4]), decimal(f[5]), decimal(f[6]), null,f.length==8?integer(f[7]):7000);
                        if (shieldRows.put(key(s.config()), s) != null) throw new IllegalArgumentException("Duplicate shield reference");
                    }
                    case "C" -> {
                        requireFields(f, 2);
                        if (dodgeCooldown != null) throw new IllegalArgumentException("Duplicate cooldown row");
                        dodgeCooldown = integer(f[1]); boundedTime(dodgeCooldown, "dodge cooldown");
                    }
                    default -> throw new IllegalArgumentException("Unknown row kind");
                }
            } catch (RuntimeException invalid) {
                throw new IOException("Invalid weapon catalogue row " + (lineNo + 1) + ": " + invalid.getMessage(), invalid);
            }
        }
        var result = new ArrayList<Weapon>();
        for (var entry : bases.entrySet()) {
            String k = entry.getKey(); Weapon base = entry.getValue();
            var normal = new TreeMap<Integer, Attack>(); Attack incomplete = null, charged = null, cooldown = null;
            var seen = new HashSet<String>();
            for (AttackRow row : attacks.getOrDefault(k, List.of())) {
                String id = row.kind() + ":" + row.index();
                if (!seen.add(id)) throw new IOException("Duplicate attack metadata for " + base.config());
                switch (row.kind()) {
                    case "NORMAL" -> normal.put(row.index(), row.attack());
                    case "INCOMPLETE" -> incomplete = row.attack();
                    case "CHARGED" -> charged = row.attack();
                    case "COOLDOWN" -> cooldown = row.attack();
                    default -> throw new AssertionError();
                }
            }
            for (int i = 0; i < normal.size(); i++) if (!normal.containsKey(i)) throw new IOException("Non-contiguous attack chain for " + base.config());
            result.add(new Weapon(base.config(), base.chargeTime(), base.maxChain(), base.chainCooldown(), base.cycle(),
                base.blended(), base.attackingSpeed(), base.chargingSpeed(), List.copyOf(normal.values()), incomplete, charged, cooldown));
        }
        for (String k : attacks.keySet()) if (!bases.containsKey(k)) throw new IOException("Attack metadata has no weapon row");
        for (var entry : shieldRows.entrySet()) {
            Attack bash = shieldBashes.remove(entry.getKey()); Shield s = entry.getValue();
            shields.add(new Shield(s.config(), s.health(), s.regenTime(), s.regenDelay(), s.defendingSpeed(),
                bash == null ? 0f : s.bashHealthPct(), bash,s.breakDelay()));
        }
        if (!shieldBashes.isEmpty()) throw new IOException("Shield bash metadata has no shield row");
        if (dodgeCooldown == null) throw new IOException("Missing knight dodge cooldown");
        try { return new WeaponCatalog(result, shields, dodgeCooldown); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid weapon catalogue", invalid); }
    }

    public static void write(Path file, Collection<Weapon> weapons, Collection<Shield> shields) throws IOException {
        write(file, weapons, shields, 0);
    }

    public static void write(Path file, Collection<Weapon> weapons, Collection<Shield> shields, int dodgeCooldown) throws IOException {
        boundedTime(dodgeCooldown, "dodge cooldown");
        var lines = new ArrayList<String>(); lines.add(HEADER); lines.add("C\t" + dodgeCooldown);
        var sortedWeapons = weapons.stream().sorted(Comparator.comparing(Weapon::config)).toList();
        for (Weapon w : sortedWeapons) {
            lines.add(String.join("\t", "W", w.config(), Integer.toString(w.chargeTime()), Integer.toString(w.maxChain()),
                Integer.toString(w.chainCooldown()), Boolean.toString(w.cycle()), Boolean.toString(w.blended()),
                Float.toString(w.attackingSpeed()), Float.toString(w.chargingSpeed())));
            for (int i = 0; i < w.attacks().size(); i++) lines.add(attackLine(w.config(), "NORMAL", i, w.attacks().get(i)));
            if (w.incomplete() != null) lines.add(attackLine(w.config(), "INCOMPLETE", 0, w.incomplete()));
            if (w.charged() != null) lines.add(attackLine(w.config(), "CHARGED", 0, w.charged()));
            if (w.cooldown() != null) lines.add(attackLine(w.config(), "COOLDOWN", 0, w.cooldown()));
        }
        for (Shield s : shields.stream().sorted(Comparator.comparing(Shield::config)).toList()) {
            lines.add(String.join("\t", "S", s.config(), Integer.toString(s.health()), Integer.toString(s.regenTime()),
                Integer.toString(s.regenDelay()), Float.toString(s.defendingSpeed()), Float.toString(s.bashHealthPct()),Integer.toString(s.breakDelay())));
            if (s.bash() != null) lines.add(attackLine(s.config(), "BASH", 0, s.bash()));
        }
        Files.createDirectories(file.toAbsolutePath().normalize().getParent());
        Files.write(file, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static String attackLine(String config, String kind, int index, Attack a) {
        return String.join("\t", "A", config, kind, Integer.toString(index), Integer.toString(a.land()), Integer.toString(a.rearm()),
            Integer.toString(a.clear()), Boolean.toString(a.blended()), Integer.toString(a.impulseDelay()),
            Integer.toString(a.impulseDuration()), Float.toString(a.impulseX()), Float.toString(a.impulseY()));
    }

    public List<Weapon> weapons() { return weapons.values().stream().sorted(Comparator.comparing(Weapon::config)).toList(); }
    public List<Shield> shields() { return shields.values().stream().sorted(Comparator.comparing(Shield::config)).toList(); }
    public Weapon find(String config) { return weapons.get(key(config)); }
    public Weapon require(String config) throws IOException {
        Weapon weapon = find(config); if (weapon == null) throw new IOException("Unknown weapon config: " + config); return weapon;
    }
    public Shield findShield(String config) { return shields.get(key(config)); }
    public int dodgeCooldown() { return dodgeCooldown; }

    private static void requireFields(String[] f, int n) { if (f.length != n) throw new IllegalArgumentException("Expected " + n + " columns"); }
    private static int integer(String value) { return Integer.parseInt(value); }
    private static float decimal(String value) { float n = Float.parseFloat(value); if (!Float.isFinite(n)) throw new IllegalArgumentException("Non-finite number"); return n; }
    private static boolean bool(String value) { if (value.equals("true")) return true; if (value.equals("false")) return false; throw new IllegalArgumentException("Invalid boolean"); }
    private static String reference(String value) {
        if (value == null || value.isBlank() || value.length() > 256 || value.indexOf('\t') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Invalid config reference");
        return value;
    }
    private static String key(String value) { return reference(value).toLowerCase(Locale.ROOT); }
    private static void boundedTime(int value, String label) { if (value < 0 || value > 600_000) throw new IllegalArgumentException("Invalid " + label); }
    private static void speed(float value) { if (!Float.isFinite(value) || value < 0 || value > 100) throw new IllegalArgumentException("Invalid weapon speed"); }
}
