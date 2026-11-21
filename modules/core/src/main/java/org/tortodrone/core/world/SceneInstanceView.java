package org.tortodrone.core.world;

/** The narrow scene-instance shape the wire codec needs; the live simulation stays in world. */
public interface SceneInstanceView {
    int oid();
    MapScene map();
    boolean hasTutorial();
    boolean hasMission();
    int missionGateId();
    int missionFloor();
    String missionId();
    int missionDifficulty();
}
