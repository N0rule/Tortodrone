package org.tortodrone.character;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Small server-owned catalog of verified references; the client supplies its own game assets. */
public final class StarterCatalog {
    public record Model(String name, Map<String, Object> arguments) {
        public Model { arguments = Map.copyOf(arguments); }
    }
    public record Colorization(int value) { }
    private record Entry(String category, String parameter, String model, Map<String, String> arguments) { }
    private static final Map<String, Entry> ENTRIES = load();
    private static final Set<String> COSTUMES = Set.of("Blue", "Gold", "Green", "Heavy", "Pink", "Purple", "Red");
    private static final String[] EYES = ("default almond cheeky closed determined dot eks happy jolly pill plus rage sad shifty sleepy spiral sultry squint wat blank lash star club diamond heart spade snow clover skull circuit flower_round flower_sharp sparkle cheer flower_tri lotus propeller square triangle hexagon sun moon bug bee gear joy bright").split(" ");

    private StarterCatalog() { }

    /** New characters can only request equipment offered by the verified character creator. */
    public static boolean accepts(Knight knight) {
        return COSTUMES.contains(knight.costume())
            && matches(knight.helm(), "HELM", " (" + knight.costume() + ")")
            && matches(knight.armor(), "ARMOR", " (" + knight.costume() + ")")
            && accessory(knight.helmAccessory(), "HELM_ACCESSORY", knight.costume())
            && accessory(knight.armorAccessory(), "BACK_ACCESSORY", knight.costume());
    }

    private static boolean matches(String name, String category, String suffix) {
        Entry entry = ENTRIES.get(name);
        return entry != null && entry.category().equals(category) && name.endsWith(suffix);
    }
    private static boolean accessory(String name, String category, String costume) {
        return name == null || name.isEmpty() || matches(name + ", " + costume, category, ", " + costume);
    }

    public static Model preview(Knight knight) {
        int color = (1 << 8) | knight.color();
        Map<String, Object> args = new TreeMap<>();
        args.put("Colorization", color);
        args.put("Height", new String[] {"-2", "-1", "0", "+1", "+2"}[knight.height()]);
        String eye = "character/pc/face/eye_" + EYES[knight.eye()];
        args.put("Face Texture", eye + ".png");
        if (knight.eye() == 19) args.put("Texture - Closed", eye + ".png");
        else if (knight.eye() == 29 || knight.eye() == 46) args.put("Texture - Closed", eye + "_closed.png");
        // Unknown legacy choices stay saved; never guess an asset path for them.
        add(args, knight.helm(), color); add(args, knight.armor(), color);
        if (knight.helmAccessory() != null) add(args, knight.helmAccessory() + ", " + knight.costume(), color);
        if (knight.armorAccessory() != null) add(args, knight.armorAccessory() + ", " + knight.costume(), color);
        return new Model("character/pc/model_level.dat", args);
    }

    private static void add(Map<String, Object> args, String name, int color) {
        Entry entry = ENTRIES.get(name);
        if (entry == null) return;
        Map<String, Object> values = new TreeMap<>();
        entry.arguments().forEach((key, value) -> values.put(key, value.equals("PLAYER_COLOR") ? color
            : value.startsWith("I:") ? Integer.parseInt(value.substring(2))
            : value.startsWith("C:") ? new Colorization(Integer.parseInt(value.substring(2)))
            : value.substring(2)));
        args.put(entry.parameter(), new Model(entry.model(), values));
    }

    private static Map<String, Entry> load() {
        Map<String, Entry> result = new HashMap<>();
        try (InputStream stream = StarterCatalog.class.getResourceAsStream("/starter-models.txt")) {
            if (stream == null) throw new IOException("Missing starter catalog");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null;) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    String[] fields = line.split("\\|", -1);
                    if (fields.length != 5) throw new IOException("Malformed starter catalog");
                    Map<String, String> values = new HashMap<>();
                    for (String pair : fields[4].split(";")) {
                        String[] parts = pair.split("=", 2);
                        if (parts.length != 2 || values.put(parts[0], parts[1]) != null) throw new IOException("Malformed starter arguments");
                    }
                    if (result.put(fields[1], new Entry(fields[0], fields[2], fields[3], Map.copyOf(values))) != null) throw new IOException("Duplicate starter reference");
                }
            }
            return Map.copyOf(result);
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
}
