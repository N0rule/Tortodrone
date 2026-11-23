package org.tortodrone.world;

import static org.tortodrone.support.TestWire.check;

import java.nio.file.*;
import org.tortodrone.core.character.OpeningMission;
import org.tortodrone.core.mission.MissionRun;
import org.tortodrone.core.mission.OpeningGate;
import org.tortodrone.resources.ContentManager;

/** Shipped content advertises the pinned opening gate only when its original floor,
 * destination and generator controls are all present. */
public final class OpeningGateContentTest {
    private OpeningGateContentTest() {}

    public static void main(String[] args) throws Exception {
        var content = ContentManager.load(Path.of("."));
        var registry = org.tortodrone.world.SceneRegistry.create(content);
        var gate = registry.openingGate(OpeningMission.ANCIENT_GENERATOR);
        check(gate != null, "shipped content carries the verified Ancient Generator gate");
        check(
                gate.source().id() == OpeningGate.GENERATOR_SCENE
                        && gate.destination().id() == 218,
                "the gate keeps its original floor and powered Camp destination");
        check(
                gate.entrance().id() == 82 && gate.arrival().id() == 5158,
                "the authored Generator entrance and Camp arrival are retained");
        check(
                gate.destination().town()
                        && gate.destination().spawnX() == 9.5f
                        && gate.destination().spawnY() == 2.5f,
                "the Camp arrival remains walkable at the authored point");
        var routes = registry.gates();
        check(routes.gate("ancient_generator") != null, "the live Generator gate is imported");
        var shadow = routes.gate("shadow_beast");
        check(shadow != null, "the live Shadow Beast gate is imported");
        var run = registry.missionRoute("shadow_beast", 1, 1, "MEDIUM");
        check(run != null && run.lastFloor() == false, "Shadow Beast route is playable and starts on its lobby");
        check(
                run.floors().stream().map(f -> f.scene().id()).toList().equals(java.util.List.of(351, 380, 382, 384)),
                "Shadow Beast keeps the live floor order");
        check(
                run.floors().stream()
                        .map(MissionRun.Floor::exit)
                        .toList()
                        .equals(java.util.List.of("524", "4661", "5638", "537")),
                "each floor keeps its authored completing elevator");
        var second = run.advance();
        check(
                !run.lastFloor()
                        && second.floor() == 1
                        && second.source().id() == 380
                        && second.exitKey().equals("4661")
                        && second.destination() == run.destination(),
                "a run advances one authored floor at a time");
        var finalFloor = run.advance().advance().advance();
        check(finalFloor.lastFloor() && finalFloor.source().id() == 384, "the final authored floor is last");
        boolean refused = false;
        try {
            finalFloor.advance();
        } catch (IllegalStateException expected) {
            refused = true;
        }
        check(refused, "the final floor has no successor");
        check(
                registry.availableGates().stream().anyMatch(g -> g.missionId().equals("shadow_beast")),
                "the fully bundled route is advertised");
        check(
                registry.availableGates().stream().noneMatch(g -> g.missionId().equals("ancient_generator")),
                "the pinned opening gate is not advertised twice");
        check(
                registry.missionRoute("choose_your_sprite", 1, 1, "EASY") == null,
                "gate-conversation missions wait for their lobby semantics");
        check(
                registry.availableGates().stream().noneMatch(g -> g.missionId().equals("choose_your_sprite")),
                "an unimplemented gate-conversation mission is not advertised");
        check(
                registry.missionRoute("hall_of_heroes_4", 1, 1, "EASY") == null,
                "a lone lobby ring is not a playable mission route");
        check(
                registry.availableGates().stream()
                        .allMatch(
                                g -> g.wedges().stream().anyMatch(w -> !w.type().equals("LOBBY"))),
                "every advertised gate reaches a real floor");
        for (var route : registry.availableGates()) {
            var playable = registry.missionRoute(route.missionId(), 1, 1, "EASY");
            check(playable != null && !playable.floors().isEmpty(), "every advertised route is playable");
        }
        System.out.println(
                "PASS: shipped Ancient Generator floor, generator controls, entrance and Camp arrival satisfy the pinned opening gate");
        System.out.println("PASS: imported mission routes expose only fully bundled floors");
    }
}
