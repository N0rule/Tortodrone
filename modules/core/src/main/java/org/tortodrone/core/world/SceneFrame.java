package org.tortodrone.core.world;

import java.util.List;

/** One encoded scene delta whose symbol references are left as placeholders. Both sides
 * maintain a per-connection class/string registry, so the bytes may only be shared as a
 * template: each recipient materializes them with its own codes, and a recipient that has
 * not seen every symbol yet falls back to a direct per-connection encode. */
public record SceneFrame(
        byte[] template,
        int oidOffset,
        int acknowledgeOffset,
        int pingOffset,
        int referenceOffset,
        int elapsedOffset,
        List<Symbol> types,
        List<Symbol> interns) {
    public SceneFrame {
        template = template.clone();
        types = List.copyOf(types);
        interns = List.copyOf(interns);
    }

    @Override
    public byte[] template() {
        return template.clone();
    }

    public record Symbol(int offset, String name) {}
}
