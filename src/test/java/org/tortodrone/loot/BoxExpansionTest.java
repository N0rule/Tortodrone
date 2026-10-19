package org.tortodrone.loot;

import java.nio.file.*;
import java.util.*;
import org.tortodrone.resources.*;
import static org.tortodrone.support.TestWire.check;

/** Analytical graph equivalence; sampling cannot establish rare event or bundle fidelity. */
public final class BoxExpansionTest {
    public static void main(String[] args)throws Exception {
        var items=ItemYaml.load(Path.of("content/items"));var boxes=BoxCatalog.load(Path.of("content"),items);
        var root=Yaml.map(Json.parseCatalog(Files.readString(Path.of("content/_internal/box-expectations.json"))),"expectations");
        var expected=Yaml.map(root.get("boxes"),"boxes");
        check(expected.size()==223,"all additional archived graphs have independent item-frequency expectations");
        for(var row:expected.entrySet()) {
            var box=boxes.find(items.require(row.getKey()).config());var actual=new TreeMap<String,Double>();
            rewards(box,box.guaranteed(),1,actual);
            for(String name:box.groups().keySet())if(name.equals("main")||name.startsWith("bonus_"))group(box,name,1,actual);
            var wanted=Yaml.map(row.getValue(),row.getKey());check(actual.size()==wanted.size(),"complete candidate pool: "+row.getKey());
            for(var reward:wanted.entrySet()) {
                double mean=((Number)reward.getValue()).doubleValue();double value=actual.getOrDefault(items.require(reward.getKey()).config(),-1d);
                check(Math.abs(value-mean)<=Math.max(0.000002,0.00002*mean),"archived expected quantity: "+row.getKey()+" / "+reward.getKey()+" "+value+" vs "+mean);
            }
        }
        check(boxes.entries().stream().filter(BoxDefinition::enabled).count()==243,"every indexed real box has an explicit approximation");
        check(boxes.entries().stream().filter(b->!b.enabled()).count()==7,"internal templates and empty Mystery remain disabled");
        System.out.println("PASS: 223 archived graphs analytically match independent reward frequencies; 243 real boxes enabled, seven templates/placeholders disabled");
    }
    private static void group(BoxDefinition box,String name,double p,Map<String,Double> out) {
        var g=box.groups().get(name);double probability=p*g.rolls()*g.chance()/1_000_000d;
        for(var e:g.entries())rewards(box,e.rewards(),probability*e.weight()/g.weight(),out);
    }
    private static void rewards(BoxDefinition box,List<BoxDefinition.Reward> rewards,double p,Map<String,Double> out) {
        for(var r:rewards)if(r instanceof BoxDefinition.Item i)out.merge(i.config(),p*(i.minimum()+i.maximum())/2,Double::sum);
        else group(box,((BoxDefinition.Reference)r).group(),p,out);
    }
}
