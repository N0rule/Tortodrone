package org.tortodrone.resources;

import static org.tortodrone.support.TestWire.check;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** v2 mission folders: domain equivalence with v1, layout marker and refusal rules. */
public final class MissionV2Test {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("tortodrone-mission-v2-");
        try {
            Path v1 = root.resolve("v1"), v2 = root.resolve("v2");
            write(v1, "missions/rank.yml", RANK);
            write(v1, "missions/prestige.yml", CHAT);
            write(v1, "missions/expansion.yml", GATE);
            write(v1, "missions/groups.yml", GROUPS);
            write(v1, "missions/properties.yml", PROPERTIES);
            write(v1, "missions/grants.yml", GRANTS);
            write(v1, "missions/comlinks.yml", COMLINK);
            write(v1, "missions/routes.yml", ROUTE);

            write(v2, "format.txt", "2\n");
            write(v2, "shared/missions/groups.yml", GROUPS);
            write(v2, "shared/missions/properties.yml", PROPERTIES);
            write(v2, "missions/first/mission.yml", RANK);
            write(v2, "missions/first/grants.yml", GRANTS);
            write(v2, "missions/chat/mission.yml", CHAT);
            write(v2, "missions/chat/comlink.yml", COMLINK);
            write(v2, "missions/gate_mission/mission.yml", GATE);
            write(v2, "missions/gate_mission/route.yml", ROUTE);

            check(MissionYaml.format(v1) == 1, "a tree without a marker stays v1");
            check(MissionYaml.format(v2) == 2, "format.txt 2 selects v2");
            var a = MissionYaml.definitions(v1);
            var b = MissionYaml.definitions(v2);
            check(a.missions().missions().equals(b.missions().missions()), "v1/v2 mission rows are identical");
            check(a.missions().groups().equals(b.missions().groups()), "v1/v2 groups are identical");
            check(a.missions().properties().equals(b.missions().properties()), "v1/v2 properties are identical");
            for (var mission : a.missions().missions())
                check(
                        a.missions().grants(mission.id()).equals(b.missions().grants(mission.id())),
                        "v1/v2 grants identical: " + mission.id());
            check(a.comlinks().conversations().equals(b.comlinks().conversations()), "v1/v2 comlinks are identical");
            check(gates(a).equals(gates(b)), "v1/v2 gate routes are identical");

            Path badMarker = root.resolve("bad-marker");
            write(badMarker, "format.txt", "3\n");
            fails(() -> MissionYaml.definitions(badMarker), "expected exactly one line containing 1 or 2");
            Path noNewline = root.resolve("no-newline");
            write(noNewline, "format.txt", "2");
            fails(() -> MissionYaml.definitions(noNewline), "expected exactly one line containing 1 or 2");
            Path mixed = root.resolve("mixed");
            write(mixed, "format.txt", "2\n");
            write(mixed, "missions/first/mission.yml", RANK);
            write(mixed, "missions/rank.yml", RANK);
            fails(() -> MissionYaml.definitions(mixed), "mixed v1 flat mission files and v2 mission folders");
            Path v1Folders = root.resolve("v1-folders");
            write(v1Folders, "missions/first/mission.yml", RANK);
            fails(() -> MissionYaml.definitions(v1Folders), "v2 mission folders require content/format.txt 2");
            Path unknownFile = root.resolve("unknown-file");
            write(unknownFile, "format.txt", "2\n");
            write(unknownFile, "missions/first/mission.yml", RANK);
            write(unknownFile, "missions/first/invented.yml", "missions:\n  first: {}\n");
            fails(() -> MissionYaml.definitions(unknownFile), "unknown mission file invented.yml");
            Path objectives = root.resolve("objectives");
            write(objectives, "format.txt", "2\n");
            write(objectives, "missions/first/mission.yml", RANK);
            write(objectives, "missions/first/objectives.yml", "objectives: []\n");
            fails(() -> MissionYaml.definitions(objectives), "objectives.yml is not supported");
            Path wrongId = root.resolve("wrong-id");
            write(wrongId, "format.txt", "2\n");
            write(
                    wrongId,
                    "missions/first/mission.yml",
                    "missions:\n  other:\n    name: Rank/01/1.1/First\n    type: rank\n    rank: 1\n    subrank: 1\n");
            fails(() -> MissionYaml.definitions(wrongId), "does not match folder first");
            Path missingMission = root.resolve("missing-mission");
            write(missingMission, "format.txt", "2\n");
            write(missingMission, "missions/first/grants.yml", "grants:\n  first: []\n");
            fails(() -> MissionYaml.definitions(missingMission), "missing mission.yml");
            Path wrongSection = root.resolve("wrong-section");
            write(wrongSection, "format.txt", "2\n");
            write(wrongSection, "missions/first/mission.yml", RANK);
            write(wrongSection, "missions/first/grants.yml", "missions:\n  first: []\n");
            fails(() -> MissionYaml.definitions(wrongSection), "expected one grants section");

            Path many = root.resolve("many");
            write(many, "format.txt", "2\n");
            for (int i = 0; i < 17; i++) {
                String id = "m" + i;
                write(
                        many,
                        "missions/" + id + "/mission.yml",
                        "missions:\n  " + id + ":\n    name: Rank/01/1.1/" + id
                                + "\n    type: rank\n    rank: 1\n    subrank: 1\n");
            }
            check(MissionYaml.definitions(many).missions().size() == 17, "v2 removes the 16-file cap");
        } finally {
            delete(root);
        }
        System.out.println(
                "PASS: v2 mission folders match v1 domains, reject mixed/unknown trees and keep no file cap");
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
