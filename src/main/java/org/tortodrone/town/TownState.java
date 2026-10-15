package org.tortodrone.town;

import java.util.*;

/** Owned Town progression. Values are immutable snapshots, published only after commit. */
public record TownState(List<LearnedRecipe> recipes,List<Sprite> sprites,int equippedSprite,List<Harness> harnesses,List<AuctionState.Mail> mail) {
    public TownState(List<LearnedRecipe> recipes,List<Sprite> sprites,int equippedSprite,List<Harness> harnesses){this(recipes,sprites,equippedSprite,harnesses,List.of());}
    public static final TownState EMPTY=new TownState(List.of(),List.of(),0,List.of());
    public TownState{recipes=List.copyOf(recipes);sprites=List.copyOf(sprites);harnesses=List.copyOf(harnesses);mail=List.copyOf(mail);}
    public record LearnedRecipe(long id,String config,int crafted,long expires) { }
    public record Harness(long id,String config) { }
    public record Sprite(int id,String config,String name,int level,float heat,Long lastFull,long fullStamp,
            int perkLevel,Set<Integer> perks,Integer perk,List<Integer> powers,List<Integer> ultimates,long harness) {
        public Sprite{perks=Set.copyOf(perks);powers=List.copyOf(powers);ultimates=List.copyOf(ultimates);}
        public int spent(){return powers.stream().mapToInt(Integer::intValue).sum();}
    }
}
