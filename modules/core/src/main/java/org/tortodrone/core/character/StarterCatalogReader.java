package org.tortodrone.core.character;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Ingestion for {@link StarterCatalog}.
 *
 * <p>The runtime catalogue keeps only the immutable verified reference table and character model
 * assembly. This class owns the bundled pipe-delimited {@code starter-models.txt} resource, its
 * field and argument grammar, duplicate rejection and the startup failure used when the resource
 * is missing or malformed.
 */
final class StarterCatalogReader {
    private StarterCatalogReader() {}

    static Map<String, StarterCatalog.Entry> load() {
        Map<String, StarterCatalog.Entry> result = new HashMap<>();
        try (InputStream stream = StarterCatalogReader.class.getResourceAsStream("/starter-models.txt")) {
            if (stream == null) throw new IOException("Missing starter catalog");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; ) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    String[] fields = line.split("\\|", -1);
                    if (fields.length != 5) throw new IOException("Malformed starter catalog");
                    Map<String, String> values = new HashMap<>();
                    for (String pair : fields[4].split(";")) {
                        String[] parts = pair.split("=", 2);
                        if (parts.length != 2 || values.put(parts[0], parts[1]) != null)
                            throw new IOException("Malformed starter arguments");
                    }
                    if (result.put(
                                    fields[1],
                                    new StarterCatalog.Entry(fields[0], fields[2], fields[3], Map.copyOf(values)))
                            != null) throw new IOException("Duplicate starter reference");
                }
            }
            return Map.copyOf(result);
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
