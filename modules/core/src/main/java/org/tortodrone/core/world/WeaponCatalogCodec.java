package org.tortodrone.core.world;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Bounded TSV codec for {@link WeaponCatalog}.
 *
 * <p>The runtime catalogue keeps only the immutable weapon/shield lookups and chain policy. This
 * class owns the exported file format, row assembly and the duplicate/contiguity rules applied to
 * untrusted rows, so the runtime value carries no file-format policy.
 */
public final class WeaponCatalogCodec {
    private static final String HEADER = "TORTODRONE-COMBAT\t1";
    private static final long MAX_BYTES = 16L * 1024 * 1024;
    private static final int MAX_ROWS = 100_000, MAX_WEAPONS = 10_000;

    private record AttackRow(String kind, int index, WeaponCatalog.Attack attack) { }

    private WeaponCatalogCodec() { }

    public static WeaponCatalog load(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_BYTES) throw new IOException("WeaponCatalog.Weapon catalogue exceeds size limit");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !HEADER.equals(lines.getFirst())) throw new IOException("Unsupported weapon catalogue format");
        if (lines.size() > MAX_ROWS) throw new IOException("WeaponCatalog.Weapon catalogue exceeds row limit");
        var bases = new TreeMap<String, WeaponCatalog.Weapon>();
        var attacks = new HashMap<String, List<AttackRow>>();
        var shields = new ArrayList<WeaponCatalog.Shield>();
        var shieldRows = new TreeMap<String, WeaponCatalog.Shield>();
        var shieldBashes = new HashMap<String, WeaponCatalog.Attack>();
        Integer dodgeCooldown = null;
        for (int lineNo = 1; lineNo < lines.size(); lineNo++) {
            String line = lines.get(lineNo);
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\t", -1);
            try {
                switch (f[0]) {
                    case "W" -> {
                        requireFields(f, 9);
                        WeaponCatalog.Weapon w = new WeaponCatalog.Weapon(f[1], integer(f[2]), integer(f[3]), integer(f[4]), bool(f[5]), bool(f[6]),
                            decimal(f[7]), decimal(f[8]), List.of(), null, null, null);
                        if (bases.put(WeaponCatalog.key(w.config()), w) != null || bases.size() > MAX_WEAPONS) throw new IllegalArgumentException("Duplicate/too many weapons");
                    }
                    case "A" -> {
                        requireFields(f, 12);
                        String weaponKey = WeaponCatalog.key(WeaponCatalog.reference(f[1]));
                        String kind = f[2]; int index = integer(f[3]);
                        if (!Set.of("NORMAL", "INCOMPLETE", "CHARGED", "COOLDOWN", "BASH").contains(kind)
                                || index < 0 || index >= WeaponCatalog.MAX_ATTACKS || (!kind.equals("NORMAL") && index != 0))
                            throw new IllegalArgumentException("Invalid attack key");
                        WeaponCatalog.Attack attack = new WeaponCatalog.Attack(integer(f[4]), integer(f[5]), integer(f[6]), bool(f[7]), integer(f[8]),
                            integer(f[9]), decimal(f[10]), decimal(f[11]));
                        if (kind.equals("BASH")) {
                            if (shieldBashes.put(weaponKey, attack) != null) throw new IllegalArgumentException("Duplicate shield bash");
                        } else attacks.computeIfAbsent(weaponKey, ignored -> new ArrayList<>()).add(new AttackRow(kind, index, attack));
                    }
                    case "S" -> {
                        if(f.length!=7&&f.length!=8)throw new IllegalArgumentException("Invalid shield row");
                        WeaponCatalog.Shield s = new WeaponCatalog.Shield(f[1], integer(f[2]), integer(f[3]), integer(f[4]), decimal(f[5]), decimal(f[6]), null,f.length==8?integer(f[7]):7000);
                        if (shieldRows.put(WeaponCatalog.key(s.config()), s) != null) throw new IllegalArgumentException("Duplicate shield reference");
                    }
                    case "C" -> {
                        requireFields(f, 2);
                        if (dodgeCooldown != null) throw new IllegalArgumentException("Duplicate cooldown row");
                        dodgeCooldown = integer(f[1]); WeaponCatalog.boundedTime(dodgeCooldown, "dodge cooldown");
                    }
                    default -> throw new IllegalArgumentException("Unknown row kind");
                }
            } catch (RuntimeException invalid) {
                throw new IOException("Invalid weapon catalogue row " + (lineNo + 1) + ": " + invalid.getMessage(), invalid);
            }
        }
        var result = new ArrayList<WeaponCatalog.Weapon>();
        for (var entry : bases.entrySet()) {
            String k = entry.getKey(); WeaponCatalog.Weapon base = entry.getValue();
            var normal = new TreeMap<Integer, WeaponCatalog.Attack>(); WeaponCatalog.Attack incomplete = null, charged = null, cooldown = null;
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
            result.add(new WeaponCatalog.Weapon(base.config(), base.chargeTime(), base.maxChain(), base.chainCooldown(), base.cycle(),
                base.blended(), base.attackingSpeed(), base.chargingSpeed(), List.copyOf(normal.values()), incomplete, charged, cooldown));
        }
        for (String k : attacks.keySet()) if (!bases.containsKey(k)) throw new IOException("WeaponCatalog.Attack metadata has no weapon row");
        for (var entry : shieldRows.entrySet()) {
            WeaponCatalog.Attack bash = shieldBashes.remove(entry.getKey()); WeaponCatalog.Shield s = entry.getValue();
            shields.add(new WeaponCatalog.Shield(s.config(), s.health(), s.regenTime(), s.regenDelay(), s.defendingSpeed(),
                bash == null ? 0f : s.bashHealthPct(), bash,s.breakDelay()));
        }
        if (!shieldBashes.isEmpty()) throw new IOException("WeaponCatalog.Shield bash metadata has no shield row");
        if (dodgeCooldown == null) throw new IOException("Missing knight dodge cooldown");
        try { return new WeaponCatalog(result, shields, dodgeCooldown); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid weapon catalogue", invalid); }
    }

    public static void write(Path file, Collection<WeaponCatalog.Weapon> weapons, Collection<WeaponCatalog.Shield> shields) throws IOException {
        write(file, weapons, shields, 0);
    }

    public static void write(Path file, Collection<WeaponCatalog.Weapon> weapons, Collection<WeaponCatalog.Shield> shields, int dodgeCooldown) throws IOException {
        WeaponCatalog.boundedTime(dodgeCooldown, "dodge cooldown");
        var lines = new ArrayList<String>(); lines.add(HEADER); lines.add("C\t" + dodgeCooldown);
        var sortedWeapons = weapons.stream().sorted(Comparator.comparing(WeaponCatalog.Weapon::config)).toList();
        for (WeaponCatalog.Weapon w : sortedWeapons) {
            lines.add(String.join("\t", "W", w.config(), Integer.toString(w.chargeTime()), Integer.toString(w.maxChain()),
                Integer.toString(w.chainCooldown()), Boolean.toString(w.cycle()), Boolean.toString(w.blended()),
                Float.toString(w.attackingSpeed()), Float.toString(w.chargingSpeed())));
            for (int i = 0; i < w.attacks().size(); i++) lines.add(attackLine(w.config(), "NORMAL", i, w.attacks().get(i)));
            if (w.incomplete() != null) lines.add(attackLine(w.config(), "INCOMPLETE", 0, w.incomplete()));
            if (w.charged() != null) lines.add(attackLine(w.config(), "CHARGED", 0, w.charged()));
            if (w.cooldown() != null) lines.add(attackLine(w.config(), "COOLDOWN", 0, w.cooldown()));
        }
        for (WeaponCatalog.Shield s : shields.stream().sorted(Comparator.comparing(WeaponCatalog.Shield::config)).toList()) {
            lines.add(String.join("\t", "S", s.config(), Integer.toString(s.health()), Integer.toString(s.regenTime()),
                Integer.toString(s.regenDelay()), Float.toString(s.defendingSpeed()), Float.toString(s.bashHealthPct()),Integer.toString(s.breakDelay())));
            if (s.bash() != null) lines.add(attackLine(s.config(), "BASH", 0, s.bash()));
        }
        Files.createDirectories(file.toAbsolutePath().normalize().getParent());
        Files.write(file, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static String attackLine(String config, String kind, int index, WeaponCatalog.Attack a) {
        return String.join("\t", "A", config, kind, Integer.toString(index), Integer.toString(a.land()), Integer.toString(a.rearm()),
            Integer.toString(a.clear()), Boolean.toString(a.blended()), Integer.toString(a.impulseDelay()),
            Integer.toString(a.impulseDuration()), Float.toString(a.impulseX()), Float.toString(a.impulseY()));
    }

    private static void requireFields(String[] f, int n) { if (f.length != n) throw new IllegalArgumentException("Expected " + n + " columns"); }
    private static int integer(String value) { return Integer.parseInt(value); }
    private static float decimal(String value) { float n = Float.parseFloat(value); if (!Float.isFinite(n)) throw new IllegalArgumentException("Non-finite number"); return n; }
    private static boolean bool(String value) { if (value.equals("true")) return true; if (value.equals("false")) return false; throw new IllegalArgumentException("Invalid boolean"); }
}
