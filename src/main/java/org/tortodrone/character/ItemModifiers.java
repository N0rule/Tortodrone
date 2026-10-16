package org.tortodrone.character;

import java.io.IOException;
import java.util.List;
import org.tortodrone.town.TownCatalog;

/** Explicit local-admin gear state. Levels are stored zero-based; heat is a fraction. */
public record ItemModifiers(int level,float heat,List<String> variants) {
    public static final ItemModifiers NONE=new ItemModifiers(0,0,List.of());
    public ItemModifiers { variants=List.copyOf(variants); }
    public void validate(ItemCatalog.Entry item,TownCatalog rules)throws IOException {
        var gear=rules.gear().gear(item.config());
        if(!Float.isFinite(heat)||heat<0||heat>1||level<0)throw new IOException("Invalid item level or heat");
        if(gear==null ? level!=0||heat!=0 : level>=Math.max(1,gear.levels()))throw new IOException("Level is outside this item's progression table");
        if(gear!=null&&gear.maximum(level)&&heat!=0)throw new IOException("Maximum-level items cannot store heat");
        rules.validateVariants(TownCatalog.itemGroup(item),variants);
    }
}
