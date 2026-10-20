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
    /** GM Spawn All enumerates alternatives; it does not perform probability rolls. */
    public static List<Prize> all(BoxDefinition box){
        var prizes=new LinkedHashMap<String,Prize>();
        var rewards=new ArrayList<Reward>(box.guaranteed());
        rewards.add(new Reference("main"));for(String name:box.groups().keySet())if(name.startsWith("bonus_"))rewards.add(new Reference(name));
        all(box,rewards,new HashSet<>(),prizes);
        return List.copyOf(prizes.values());
    }
    private static void all(BoxDefinition box,List<Reward> rewards,Set<String> visited,Map<String,Prize> prizes){
        for(var reward:rewards)if(reward instanceof Reference ref){
            var group=box.groups().get(ref.group());if(group.chance()==0||!visited.add(ref.group()))continue;
            for(var entry:group.entries())all(box,entry.rewards(),visited,prizes);
        }else if(reward instanceof Item item){
            var prize=new Prize(item.config(),item.maximum(),item.binding());String key=item.config()+"|"+item.binding();var prior=prizes.get(key);if(prior==null||prior.count()<prize.count())prizes.put(key,prize);
        }
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
