package org.tortodrone.town;

import java.util.*;
import org.tortodrone.character.ItemCatalog;
import org.tortodrone.support.ContentFixture;
import static org.tortodrone.support.TestWire.check;

/** Hall of Heroes tier stock derived from captured families and original item rarity. */
public final class MerchantCatalogTest {
    private static final List<String> FAMILIES=List.of("Bomber","Cobalt","Elemental Def","Gunner","Heavy","Piercing Def","Shadow Def","Specialist","Striker");
    /** Captured 5-star goods per family, in the order the live screenshots served them. */
    private static final Map<String,Integer> CAPTURED=Map.of("Bomber",66,"Cobalt",46,"Elemental Def",16,"Gunner",47,"Heavy",23,"Piercing Def",12,"Shadow Def",12,"Specialist",35,"Striker",50);
    /** Derivable 2-, 3- and 4-star goods per family under the "N stars and below" rule. */
    private static final Map<String,int[]> TIERS=Map.of(
            "Bomber",new int[]{12,28,45},"Cobalt",new int[]{7,18,32},"Elemental Def",new int[]{3,6,9},
            "Gunner",new int[]{7,18,29},"Heavy",new int[]{4,9,14},"Piercing Def",new int[]{3,6,9},
            "Shadow Def",new int[]{3,6,9},"Specialist",new int[]{2,10,22},"Striker",new int[]{6,20,34});
    public static void main(String[] args)throws Exception{
        var items=ItemCatalog.load(ContentFixture.path("items/catalog.json"));
        var rules=TownCatalog.load(ContentFixture.path("town/catalog.json"));
        for(String family:FAMILIES){
            var captured=goods(rules,"Captured/Hall of Heroes/"+family);
            check(captured.size()==CAPTURED.get(family),"captured 5-star stock for "+family+" is unchanged");
            for(int tier=2;tier<=4;tier++){
                final int stars=tier;
                var actual=goods(rules,"Rule/Hall of Heroes/"+family+" "+tier+" Star");
                var expected=captured.stream().filter(g->items.find(g.item()).rarity()<=stars).toList();
                check(actual.size()==TIERS.get(family)[tier-2],"tier "+tier+" "+family+" good count follows the rarity rule");
                check(actual.equals(expected),"tier "+tier+" "+family+" is the captured stock filtered by item rarity, order and price intact");
                check(actual.stream().allMatch(g->items.find(g.item()).rarity()<=stars),"tier "+tier+" "+family+" never sells a good above its star");
            }
        }
        System.out.println("PASS: Hall of Heroes tier stock with captured family order and prices preserved");
    }
    private static List<TownCatalog.Good> goods(TownCatalog rules,String catalog){return rules.goods(new TownCatalog.Shop(catalog,10,"BASIC","CONSTANT",false),0,0);}
}
