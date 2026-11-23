package org.tortodrone.core.world;

import java.util.List;
import org.tortodrone.core.character.OpeningMission;
import org.tortodrone.core.mission.MissionGates;

/** Connection-independent scene state the downstream codec reads. */
public interface SceneSource {
    SceneInstanceView entry();

    SceneCatalogView catalog();

    boolean hasTutorial();

    int openingGateId(OpeningMission mission);

    List<MissionGates.Gate> availableGates();
}
