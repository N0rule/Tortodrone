package org.tortodrone.character;

import java.util.*;

/** Modern opening cards only: installed mission/spawn-table configs and live 2026-09-10 route.
 * Definitions are not proof of floor gameplay support. */
public enum OpeningMission {
    SECURING_CAMP("securing_camp","Rank/01/1.1/Rhendon Mission Intro",1,1,10,1,1,
        List.of(new Reward("Rarity/Fire Crystal/Cracked",15),new Reward("Rarity/Spark of Life",5))),
    ANCIENT_GENERATOR("ancient_generator","Rank/01/1.2/The Ancient Generator",1,2,10,1,2,
        List.of(new Reward("Gear/Armor/Vitasuit",1),new Reward("Rarity/Fire Crystal/Cracked",10))),
    FIRST_CONTACT("first_contact_b","Rank/01/1.3/First Contact B",1,3,5,2,2,
        List.of(new Reward("Weapon/Sword/Robo Wrecker",1),new Reward("Gear/Helm/Cyclops Cap",1),new Reward("Rarity/Spark of Life",3))),
    CROSSING_CHASM("crossing_chasm_b","Rank/01/1.4/Crossing the Chasm B",1,4,10,2,3,
        List.of(new Reward("Weapon/Sword/Slime Slasher",1),new Reward("Gear/Armor/Fencing Jacket",1),new Reward("Weapon/Bomb/Proto Bomb",1),new Reward("Rarity/Fire Crystal/Cracked",10))),
    WELCOME_HAVEN("welcome_to_haven","Rank/02/2.1/Welcome to Haven!",2,1,10,3,3,
        List.of(new Reward("Rarity/Spark of Life",3),new Reward("Recipe/Rarity/Fire Crystal/Cracked Recipe",1)));

    public record Reward(String config,int count){public Reward{Objects.requireNonNull(config);if(count<1||count>9999)throw new IllegalArgumentException();}}
    private final String id,config;private final int rank,subrank,prestige,stage,nextStage;private final List<Reward> rewards;
    OpeningMission(String id,String config,int rank,int subrank,int prestige,int stage,int nextStage,List<Reward> rewards){this.id=id;this.config=config;this.rank=rank;this.subrank=subrank;this.prestige=prestige;this.stage=stage;this.nextStage=nextStage;this.rewards=List.copyOf(rewards);}
    public String id(){return id;}public String config(){return config;}public int rank(){return rank;}public int subrank(){return subrank;}public int prestige(){return prestige;}public int stage(){return stage;}public int nextStage(){return nextStage;}public List<Reward> rewards(){return rewards;}
    public boolean gate(){return this==ANCIENT_GENERATOR||this==CROSSING_CHASM;}
    public OpeningMission prerequisite(){return ordinal()==0?null:values()[ordinal()-1];}
    public int nextRank(){return this==CROSSING_CHASM?2:rank;}
    public int nextSubrank(){return this==WELCOME_HAVEN||this==CROSSING_CHASM?1:subrank+1;}
    public static OpeningMission byId(String id){return Arrays.stream(values()).filter(m->m.id.equals(id)).findFirst().orElse(null);}
    public boolean eligible(KnightProgress progress,Set<OpeningMission> completed){return !completed.contains(this)&&progress.openingStage()==stage&&progress.rank()==rank&&progress.subrank()==subrank&&(prerequisite()==null||completed.contains(prerequisite()));}
}
