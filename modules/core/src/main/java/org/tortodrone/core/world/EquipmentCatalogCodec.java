package org.tortodrone.core.world;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import org.tortodrone.core.character.WorldProfile;

/**
 * Bounded TSV codec for {@link EquipmentCatalog}.
 *
 * <p>The runtime catalogue keeps the immutable per-item modifier lookups and the stock
 * modifier arithmetic. This class owns the exported file format, the weapon-class
 * column order and the size/row/field rules applied to untrusted rows.
 */
public final class EquipmentCatalogCodec {
    private static final String HEADER = "TORTODRONE-EQUIPMENT\t1";
    private static final long MAX_BYTES = 16L * 1024 * 1024;
    private static final int MAX_ROWS = 100_000;

    private EquipmentCatalogCodec() { }

    public static EquipmentCatalog load(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_BYTES) throw new IOException("Equipment catalogue exceeds size limit");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !HEADER.equals(lines.getFirst())) throw new IOException("Unsupported equipment catalogue format");
        if (lines.size() > MAX_ROWS) throw new IOException("Equipment catalogue exceeds row limit");
        var entries = new ArrayList<EquipmentCatalog.Entry>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] fields = line.split("\t", -1);
            try {
                if (fields.length != 19 || !fields[0].equals("E")) throw new IllegalArgumentException("Expected 19 columns");
                var values = new EnumMap<EquipmentCatalog.WeaponClass, EquipmentCatalog.Modifiers>(EquipmentCatalog.WeaponClass.class);
                int offset = 3;
                for (EquipmentCatalog.WeaponClass weaponClass : EquipmentCatalog.WeaponClass.values()) {
                    values.put(weaponClass, new EquipmentCatalog.Modifiers(decimal(fields[offset]), decimal(fields[offset + 1]),
                        decimal(fields[offset + 2]), decimal(fields[offset + 3])));
                    offset += 4;
                }
                entries.add(new EquipmentCatalog.Entry(fields[1], WorldProfile.Slot.valueOf(fields[2]), values));
            } catch (RuntimeException invalid) {
                throw new IOException("Invalid equipment catalogue row " + (i + 1) + ": " + invalid.getMessage(), invalid);
            }
        }
        try { return new EquipmentCatalog(entries); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid equipment catalogue", invalid); }
    }

    public static void write(Path file, Collection<EquipmentCatalog.Entry> entries) throws IOException {
        var lines = new ArrayList<String>(); lines.add(HEADER);
        for (EquipmentCatalog.Entry entry : entries.stream().sorted(Comparator.comparing(EquipmentCatalog.Entry::config)).toList()) {
            var row = new ArrayList<String>(); row.add("E"); row.add(entry.config()); row.add(entry.slot().name());
            for (EquipmentCatalog.WeaponClass weaponClass : EquipmentCatalog.WeaponClass.values()) {
                EquipmentCatalog.Modifiers value = entry.forClass(weaponClass);
                row.add(Float.toString(value.chargeTime())); row.add(Float.toString(value.speed()));
                row.add(Float.toString(value.attackSpeed())); row.add(Float.toString(value.defendingSpeed()));
            }
            lines.add(String.join("\t", row));
        }
        Files.createDirectories(file.toAbsolutePath().normalize().getParent());
        Files.write(file, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
    private static float decimal(String value) { float n = Float.parseFloat(value); if (!Float.isFinite(n)) throw new IllegalArgumentException("Non-finite modifier"); return n; }
}
