package org.tortodrone.resources;

import static org.tortodrone.support.TestWire.check;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** The mission layout: one folder per mission, shared rows, and the refusal rules. */
public final class MissionLayoutTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("tortodrone-mission-layout-");
        try {
            Path content = root.resolve("content");
            write(content, "shared/missions/groups.yml", GROUPS);
            write(content, "shared/missions/properties.yml", PROPERTIES);
            write(content, "missions/first/mission.yml", RANK);
            write(content, "missions/first/grants.yml", GRANTS);
            write(content, "missions/chat/mission.yml", CHAT);
            write(content, "missions/chat/comlink.yml", COMLINK);
            write(content, "missions/gate_mission/mission.yml", GATE);
            write(content, "missions/gate_mission/route.yml", ROUTE);

            var definitions = MissionYaml.definitions(content);
            check(definitions.missions().missions().size() == 3, "every mission folder is read");
            check(
                    definitions.missions().groups().size() == 1
                            && definitions.missions().properties().size() == 1,
                    "shared groups and properties are read");
            check(definitions.missions().grants("first").size() == 1, "per-mission grants are read");
            check(definitions.comlinks().conversations().size() == 1, "per-mission comlinks are read");
            check(gates(definitions).size() == 1, "per-mission routes become gates");

            Path flat = root.resolve("flat-file");
            write(flat, "missions/first/mission.yml", RANK);
            write(flat, "missions/rank.yml", RANK);
            fails(() -> MissionYaml.definitions(flat), "flat mission files are not supported");

            Path unknownFile = root.resolve("unknown-file");
            write(unknownFile, "missions/first/mission.yml", RANK);
            write(unknownFile, "missions/first/invented.yml", "missions:\n  first: {}\n");
            fails(() -> MissionYaml.definitions(unknownFile), "unknown mission file invented.yml");

            Path objectives = root.resolve("objectives");
            write(objectives, "missions/first/mission.yml", RANK);
            write(objectives, "missions/first/objectives.yml", "objectives: []\n");
            fails(() -> MissionYaml.definitions(objectives), "objectives.yml is not supported");

            Path wrongId = root.resolve("wrong-id");
            write(
                    wrongId,
                    "missions/first/mission.yml",
                    "missions:\n  other:\n    name: Rank/01/1.1/First\n    type: rank\n    rank: 1\n    subrank: 1\n");
            fails(() -> MissionYaml.definitions(wrongId), "does not match folder first");

            Path missingMission = root.resolve("missing-mission");
            write(missingMission, "missions/first/grants.yml", "grants:\n  first: []\n");
            fails(() -> MissionYaml.definitions(missingMission), "missing mission.yml");

            Path wrongSection = root.resolve("wrong-section");
            write(wrongSection, "missions/first/mission.yml", RANK);
            write(wrongSection, "missions/first/grants.yml", "missions:\n  first: []\n");
            fails(() -> MissionYaml.definitions(wrongSection), "expected one grants section");

            Path many = root.resolve("many");
            for (int i = 0; i < 17; i++) {
                String id = "m" + i;
                write(
                        many,
                        "missions/" + id + "/mission.yml",
                        "missions:\n  " + id + ":\n    name: Rank/01/1.1/" + id
                                + "\n    type: rank\n    rank: 1\n    subrank: 1\n");
            }
            check(MissionYaml.definitions(many).missions().size() == 17, "there is no mission-count cap");
        } finally {
            delete(root);
        }
        System.out.println(
                "PASS: mission folders, shared rows, unknown entries and flat files are handled as specified");
    }

    private static Map<String, org.tortodrone.core.mission.MissionGates.Gate> gates(
            MissionYaml.Definitions definitions) {
        var result = new LinkedHashMap<String, org.tortodrone.core.mission.MissionGates.Gate>();
        for (var gate : definitions.gates().gates()) result.put(gate.missionId(), gate);
        return result;
    }

    private static void write(Path root, String name, String text) throws Exception {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static void fails(Action action, String message) throws Exception {
        try {
            action.run();
            throw new AssertionError("Invalid mission tree accepted: " + message);
        } catch (IOException expected) {
            check(
                    expected.getMessage() != null
                            && expected.getMessage()
                                    .toLowerCase(Locale.ROOT)
                                    .contains(message.toLowerCase(Locale.ROOT)),
                    "actionable error for '" + message + "': " + expected);
        }
    }

    private static void delete(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private interface Action {
        void run() throws Exception;
    }

    private static final String RANK = """
        missions:
          first:
            name: Rank/01/1.1/First
            type: rank
            rank: 1
            subrank: 1
            group: starters
            missionProps: Rank/Rank 1
            rewards:
            - difficulty: EASY
              rewards:
                name: Iron Sword
            requirement:
              type: exchange
              ingredients:
              - item: Iron Sword
                count: 1
        """;
    private static final String CHAT = """
        missions:
          chat:
            name: Prestige/01/Chat
            type: prestige
            requirement:
              type: conversation
              conversation:
                conversation: chat
        """;
    private static final String GATE = """
        missions:
          gate_mission:
            name: Expansion/Gate
            type: expansion
            group: starters
            tier: first
            requirement:
              type: gate
        """;
    private static final String GROUPS = """
        groups:
          starters:
            name: Starters
            id: starters
        """;
    private static final String PROPERTIES = """
        properties:
          Rank/Rank 1:
            name: Rank/Rank 1
            crownCost: 0
            prestigeLow: 0
            prestigeAvg: 0
            prestigeHigh: 0
            prestigeMax: 0
        """;
    private static final String GRANTS = """
        grants:
          first:
          - config: Iron Sword
            count: 1
            locked: true
        """;
    private static final String COMLINK = """
        comlinks:
          chat:
            name: Chat
            initial: start
            model: character/npc/model.dat
            music: music/theme.dat
            nodes:
              start:
                message: Hello
                responses:
                - text: Bye
                  terminal: true
        """;
    private static final String ROUTE = """
        routes:
          gate_mission:
            gateId: 1
            icon: ui/gate
            colors:
            - 2052
            description: Gate
            sourceId: 1
            destId: 2
            priority: 0
            tokens: 0
            flags: 0
            wheels:
            - skip: 0
              velocity: 1.0
              start: 0
              lengths:
              - 1.0
              wedges:
              - scene: 1
                level: LEVEL
                name: Floor
                icon: ui/floor
                description: Floor
                type: LEVEL
                restricted: false
        """;
}
