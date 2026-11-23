package org.tortodrone.core.mission;

import java.util.*;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.world.SceneConversation;

/** Imported comlink dialogue graphs keyed by mission id. Only message/response keys,
 * jump edges and presentation configs; the stock client localizes every displayed key. */
public final class MissionComlinks {
    private static final String ACTOR_PREFIX = "@comlink/";
    private final Map<String, SceneConversation> conversations;

    public MissionComlinks(Map<String, SceneConversation> conversations) {
        var checked = new TreeMap<String, SceneConversation>();
        for (var entry : conversations.entrySet()) {
            String id = entry.getKey();
            if (!id.matches("[a-z][a-z0-9_]{0,127}"))
                throw new IllegalArgumentException("Invalid comlink mission id: " + id);
            SceneConversation conversation = Objects.requireNonNull(entry.getValue());
            if (!conversation.nodes().containsKey(conversation.initial()))
                throw new IllegalArgumentException("Comlink " + id + " has no initial node");
            for (var node : conversation.nodes().values()) {
                for (var response : node.responses()) {
                    if (!response.destination().isEmpty()
                            && !conversation.nodes().containsKey(response.destination()))
                        throw new IllegalArgumentException(
                                "Comlink " + id + " jumps to missing node: " + response.destination());
                }
            }
            checked.put(id, conversation);
        }
        this.conversations = Collections.unmodifiableMap(checked);
    }

    public static MissionComlinks empty() {
        return new MissionComlinks(Map.of());
    }

    public static SceneConversation conversation(
            String missionId,
            String name,
            StarterCatalog.Model model,
            StarterCatalog.Model music,
            String initial,
            Map<String, SceneConversation.Node> nodes) {
        return new SceneConversation(
                ACTOR_PREFIX + missionId, name, "mission", initial, model, nodes, null, music, null);
    }

    public SceneConversation get(String missionId) {
        return conversations.get(missionId);
    }

    public boolean contains(String missionId) {
        return conversations.containsKey(missionId);
    }

    public Map<String, SceneConversation> conversations() {
        return conversations;
    }

    public int size() {
        return conversations.size();
    }
}
