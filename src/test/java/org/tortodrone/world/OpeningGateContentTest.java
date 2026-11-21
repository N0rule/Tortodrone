package org.tortodrone.world;

import java.nio.file.*;
import org.tortodrone.character.OpeningMission;
import org.tortodrone.resources.ContentManager;
import static org.tortodrone.support.TestWire.check;

/** Shipped content advertises the pinned opening gate only when its original floor,
 * destination and generator controls are all present. */
public final class OpeningGateContentTest {
    private OpeningGateContentTest() { }
    public static void main(String[] args)throws Exception {
        var content=ContentManager.load(Path.of("."));
        var registry=content.createScenes();
        var gate=registry.openingGate(OpeningMission.ANCIENT_GENERATOR);
        check(gate!=null,"shipped content carries the verified Ancient Generator gate");
        check(gate.source().id()==OpeningGate.GENERATOR_SCENE&&gate.destination().id()==218,
            "the gate keeps its original floor and powered Camp destination");
        check(gate.entrance().id()==82&&gate.arrival().id()==5158,
            "the authored Generator entrance and Camp arrival are retained");
        check(gate.destination().town()&&gate.destination().spawnX()==9.5f&&gate.destination().spawnY()==2.5f,
            "the Camp arrival remains walkable at the authored point");
        System.out.println("PASS: shipped Ancient Generator floor, generator controls, entrance and Camp arrival satisfy the pinned opening gate");
    }
}
