package org.tortodrone.core.wire;

import java.util.Map;

/** Stock ConfigReference: an interned config name plus its streamed argument map. */
public record ConfigReference(String name, Map<String,Object> arguments) {
    public ConfigReference {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
