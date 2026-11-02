package org.tortodrone.character;

/** Durable native completion receipt; conversation completions have no difficulty band. */
public record OpeningCompletion(OpeningMission mission,long completed,String difficulty) {
    public OpeningCompletion {
        java.util.Objects.requireNonNull(mission);
        if(completed<1||mission.gate()!=(difficulty!=null)||difficulty!=null&&!java.util.Set.of("EASY","MEDIUM","HARD").contains(difficulty))throw new IllegalArgumentException("Invalid opening mission receipt");
    }
}
