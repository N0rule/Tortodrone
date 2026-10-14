package org.tortodrone.world;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.WorldProfile;

/** Stock level-zero control modifiers for grantable items, keyed by item and weapon class. */
public final class EquipmentCatalog {
    private static final String HEADER = "TORTODRONE-EQUIPMENT\t1";
    private static final long MAX_BYTES = 16L * 1024 * 1024;
    private static final int MAX_ROWS = 100_000;
    /** Mirrors stock ItemCodes.GEAR_SLOTS, not the full visible equipment/loadout array. */
    private static final int[] GEAR_SLOTS = {0, 1, 2, 5, 10, 11, 12};

    public enum WeaponClass { NONE, SWORD, HANDGUN, BOMB }

    public record Modifiers(float chargeTime, float speed, float attackSpeed, float defendingSpeed) {
        public static final Modifiers ZERO = new Modifiers(0, 0, 0, 0);
        public Modifiers {
            for (float value : new float[] {chargeTime, speed, attackSpeed, defendingSpeed})
                if (!Float.isFinite(value) || Math.abs(value) > 1000f)
                    throw new IllegalArgumentException("Invalid equipment modifier");
        }
        public Modifiers plus(Modifiers other) {
            return new Modifiers(chargeTime + other.chargeTime, speed + other.speed,
                attackSpeed + other.attackSpeed, defendingSpeed + other.defendingSpeed);
        }
    }

    public record Entry(String config, WorldProfile.Slot slot, Map<WeaponClass, Modifiers> byWeaponClass) {
        public Entry {
            config = reference(config); Objects.requireNonNull(slot);
            var copy = new EnumMap<WeaponClass, Modifiers>(WeaponClass.class);
            copy.putAll(byWeaponClass);
            if (!copy.keySet().equals(EnumSet.allOf(WeaponClass.class)))
                throw new IllegalArgumentException("Incomplete weapon-class modifiers");
            copy.replaceAll((ignored, value) -> Objects.requireNonNull(value));
            byWeaponClass = Collections.unmodifiableMap(copy);
        }
        public Modifiers forClass(WeaponClass weaponClass) { return byWeaponClass.get(weaponClass); }
    }

    public record Pair(Modifiers equipment, Modifiers weapon) { }

    private final Map<String, Entry> entries;

    public EquipmentCatalog(Collection<Entry> entries) {
        var map = new TreeMap<String, Entry>();
        for (Entry entry : entries)
            if (map.put(key(entry.config()), entry) != null) throw new IllegalArgumentException("Duplicate item config");
        this.entries = Map.copyOf(map);
    }

    public static EquipmentCatalog empty() { return new EquipmentCatalog(List.of()); }

    public static EquipmentCatalog load(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_BYTES) throw new IOException("Equipment catalogue exceeds size limit");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !HEADER.equals(lines.getFirst())) throw new IOException("Unsupported equipment catalogue format");
        if (lines.size() > MAX_ROWS) throw new IOException("Equipment catalogue exceeds row limit");
        var entries = new ArrayList<Entry>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] fields = line.split("\t", -1);
            try {
                if (fields.length != 19 || !fields[0].equals("E")) throw new IllegalArgumentException("Expected 19 columns");
                var values = new EnumMap<WeaponClass, Modifiers>(WeaponClass.class);
                int offset = 3;
                for (WeaponClass weaponClass : WeaponClass.values()) {
                    values.put(weaponClass, new Modifiers(decimal(fields[offset]), decimal(fields[offset + 1]),
                        decimal(fields[offset + 2]), decimal(fields[offset + 3])));
                    offset += 4;
                }
                entries.add(new Entry(fields[1], WorldProfile.Slot.valueOf(fields[2]), values));
            } catch (RuntimeException invalid) {
                throw new IOException("Invalid equipment catalogue row " + (i + 1) + ": " + invalid.getMessage(), invalid);
            }
        }
        try { return new EquipmentCatalog(entries); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid equipment catalogue", invalid); }
    }

    public static void write(Path file, Collection<Entry> entries) throws IOException {
        var lines = new ArrayList<String>(); lines.add(HEADER);
        for (Entry entry : entries.stream().sorted(Comparator.comparing(Entry::config)).toList()) {
            var row = new ArrayList<String>(); row.add("E"); row.add(entry.config()); row.add(entry.slot().name());
            for (WeaponClass weaponClass : WeaponClass.values()) {
                Modifiers value = entry.forClass(weaponClass);
                row.add(Float.toString(value.chargeTime())); row.add(Float.toString(value.speed()));
                row.add(Float.toString(value.attackSpeed())); row.add(Float.toString(value.defendingSpeed()));
            }
            lines.add(String.join("\t", row));
        }
        Files.createDirectories(file.toAbsolutePath().normalize().getParent());
        Files.write(file, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    public List<Entry> entries() { return entries.values().stream().sorted(Comparator.comparing(Entry::config)).toList(); }
    public boolean isEmpty() { return entries.isEmpty(); }
    public Entry find(String config) { return entries.get(key(config)); }
    public Entry require(String config) throws IOException {
        Entry entry = find(config);
        if (entry == null) throw new IOException("No stock control modifiers for item config: " + config);
        return entry;
    }

    /** Sum stock gear slots for the selected weapon context; unknown equipped gear refuses closed. */
    public Modifiers equipment(WorldProfile profile, WeaponClass weaponClass) throws IOException {
        Modifiers total = Modifiers.ZERO;
        for (int slot : GEAR_SLOTS) {
            for (WorldProfile.Item item : profile.items()) {
                if (item.equippedSlot() != null && item.equippedSlot() == slot)
                    total = total.plus(require(item.config()).forClass(weaponClass));
            }
        }
        return total;
    }

    /** Build the separate stock weapon-mod object for the selected slot 6/7 weapon. */
    public Modifiers weapon(WorldProfile.Item item) throws IOException {
        if (item == null) return Modifiers.ZERO;
        WeaponClass weaponClass = switch (item.slot()) {
            case SWORD -> WeaponClass.SWORD;
            case GUN -> WeaponClass.HANDGUN;
            case BOMB -> WeaponClass.BOMB;
            default -> throw new IOException("Selected item is not a stock weapon");
        };
        return require(item.config()).forClass(weaponClass);
    }

    public Pair forKnight(WorldProfile profile, WorldProfile.Item selectedWeapon) throws IOException {
        if(entries.isEmpty())return new Pair(Modifiers.ZERO,Modifiers.ZERO);
        WeaponClass weaponClass = selectedWeapon == null ? WeaponClass.NONE : switch (selectedWeapon.slot()) {
            case SWORD -> WeaponClass.SWORD;
            case GUN -> WeaponClass.HANDGUN;
            case BOMB -> WeaponClass.BOMB;
            default -> throw new IOException("Selected item is not a stock weapon");
        };
        return new Pair(equipment(profile, weaponClass), weapon(selectedWeapon));
    }

    public static float cappedSpeed(float value) { return Math.clamp(value, -0.24f, 0.24f); }
    public static float cappedCharge(float value) { return Math.clamp(value, -0.48f, 0.48f); }
    public static float speedMultiplier(Modifiers equipment, Modifiers weapon) {
        return 1f + cappedSpeed(equipment.speed() + weapon.speed());
    }
    public static float attackSpeedMultiplier(Modifiers equipment, Modifiers weapon) {
        return 1f + cappedSpeed(equipment.attackSpeed() + weapon.attackSpeed());
    }
    public static int chargeTimeMillis(int baseMillis, Modifiers equipment, Modifiers weapon) {
        return (int)(baseMillis * (1f - cappedCharge(equipment.chargeTime() + weapon.chargeTime())));
    }
    public static float defenseSpeedMultiplier(Modifiers equipment) { return 1f + equipment.defendingSpeed(); }

    private static String reference(String value) {
        if (value == null || value.isBlank() || value.length() > 256 || value.indexOf('\t') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) throw new IllegalArgumentException("Invalid item config");
        return value;
    }
    private static String key(String value) { return reference(value).toLowerCase(Locale.ROOT); }
    private static float decimal(String value) { float n = Float.parseFloat(value); if (!Float.isFinite(n)) throw new IllegalArgumentException("Non-finite modifier"); return n; }
}
