package org.tortodrone.loot;

import java.util.*;
import java.util.random.RandomGenerator;
import static org.tortodrone.loot.BoxDefinition.*;

/** Stateless, server-owned weighted selection. Every bonus group has an independent chance. */
public final class BoxRewardSelector {
    public record Prize(String config,int count,Binding binding) { }
    private BoxRewardSelector() { }
    public static List<Prize> select(BoxDefinition box,RandomGenerator random) {
        if(!box.enabled())throw new IllegalArgumentException("Incomplete box");
        var result=new ArrayList<Prize>();
        rewards(box,box.guaranteed(),random,result);
        group(box,"main",random,result);
        for(String name:box.groups().keySet())if(name.startsWith("bonus_"))group(box,name,random,result);
        return List.copyOf(result);
    }
    private static void group(BoxDefinition box,String name,RandomGenerator random,List<Prize> out) {
        var group=box.groups().get(name);
        if(group.chance()<1_000_000&&random.nextInt(1_000_000)>=group.chance())return;
        for(int roll=0;roll<group.rolls();roll++) {
            long choice=random.nextLong(group.weight());
            for(var entry:group.entries()) {
                if(choice<entry.weight()){rewards(box,entry.rewards(),random,out);break;}
                choice-=entry.weight();
            }
        }
    }
    private static void rewards(BoxDefinition box,List<Reward> rewards,RandomGenerator random,List<Prize> out) {
        for(var reward:rewards) {
            if(reward instanceof Reference reference)group(box,reference.group(),random,out);
            else if(reward instanceof Item item)out.add(new Prize(item.config(),item.minimum()+random.nextInt(item.maximum()-item.minimum()+1),item.binding()));
        }
    }
}
