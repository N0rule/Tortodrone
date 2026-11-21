package org.tortodrone.core.character;

/** Durable native completion receipt keyed by mission id; conversation completions have no
 * difficulty band. Definitions come from the imported mission catalogue, never this record. */
public record OpeningCompletion(String missionId,long completed,String difficulty) {
    public OpeningCompletion {
        if(missionId==null||missionId.isBlank()||missionId.length()>128)throw new IllegalArgumentException("Invalid completed mission id");
        if(completed<1)throw new IllegalArgumentException("Invalid mission completion time");
        if(difficulty!=null&&!java.util.Set.of("EASY","MEDIUM","HARD").contains(difficulty))throw new IllegalArgumentException("Invalid mission difficulty");
    }
    public OpeningCompletion(OpeningMission mission,long completed,String difficulty){this(mission.id(),completed,difficulty);}
}
