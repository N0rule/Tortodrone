package org.tortodrone.town;

import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.storage.DepotSeed;
import static org.tortodrone.support.TestWire.check;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.KnightProgress;
import org.tortodrone.core.town.DepotGate;

/** Supply Depot rank availability and the plain default catalogue. */
public final class DepotGateTest {
    public static void main(String[] args)throws Exception {
        var content=ContentManager.load(Path.of("."));var items=content.items();var seed=DepotSeed.bundled();
        check(seed.offers().stream().noneMatch(o->o.featured()),"no bundled offer ships as featured");
        check(seed.offers().size()==328&&seed.unsupported().isEmpty(),"complete, fully supported official catalogue");
        var enabled=seed.offers().stream().filter(o->o.enabled()).toList();
        for(var offer:enabled) {
            var def=items.find(offer.good().item());
            check(def!=null&&def.grantable(),"every enabled offer resolves to a grantable item: "+offer.id());
        }
        Map<String,Integer> expected=new LinkedHashMap<>();
        expected.put("1-2",44);expected.put("3-1",59);expected.put("4-1",101);
        expected.put("5-2",171);expected.put("6-2",240);expected.put("8-2",328);
        for(var e:expected.entrySet()) {
            var rank=e.getKey().split("-");var progress=progress(Integer.parseInt(rank[0]),Integer.parseInt(rank[1]));
            long visible=enabled.stream().filter(o->DepotGate.unlocked(progress,items.find(o.good().item()))).count();
            check(visible==e.getValue(),"rank "+e.getKey()+" advertises "+e.getValue()+" offers, saw "+visible);
        }
        var recruit=progress(1,2);
        check(enabled.stream().noneMatch(o->isGear(items.find(o.good().item()))&&DepotGate.unlocked(recruit,items.find(o.good().item()))),"1-2 sees no gear");
        check(enabled.stream().noneMatch(o->items.find(o.good().item()).type().equals("SpriteEgg")&&DepotGate.unlocked(recruit,items.find(o.good().item()))),"1-2 sees no sprite eggs");
        for(int rarity=1;rarity<=5;rarity++) {
            int tier=rarity;var sample=enabled.stream().map(o->items.find(o.good().item())).filter(d->isGear(d)&&d.rarity()==tier).findFirst().orElseThrow();
            var minimum=DepotGate.required(sample);check(minimum!=null,"gear tier has a minimum rank");
            check(DepotGate.unlocked(progress(minimum.rank(),minimum.subrank()),sample),"tier "+rarity+" is visible at its own minimum "+minimum);
            var justBefore=minimum.subrank()==1?progress(minimum.rank()-1,4):progress(minimum.rank(),minimum.subrank()-1);
            check(!DepotGate.unlocked(justBefore,sample),"tier "+rarity+" is hidden one subrank earlier");
        }
        var egg=items.require("Sprite/Drakon Egg");
        check(DepotGate.required(egg).equals(DepotGate.SPRITE_MINIMUM)&&DepotGate.unlocked(progress(3,1),egg)&&!DepotGate.unlocked(progress(2,4),egg),"sprite eggs sell from 3-1");
        var crystal=items.require("Rarity/Fire Crystal/Radiant");
        check(DepotGate.required(crystal)==null&&DepotGate.unlocked(recruit,crystal),"currencies are always advertised");
        var accessory=items.require("Accessory/Recover/Aura");
        check(DepotGate.required(accessory)==null&&DepotGate.unlocked(recruit,accessory),"accessories are always advertised");
        System.out.println("PASS: 328 official offers, no default featured flags, 44/59/101/171/240/328 by rank, gear tier boundaries, eggs at 3-1 and always-on currencies/accessories");
    }
    private static boolean isGear(ItemCatalog.Entry entry) {
        return DepotGate.required(entry)!=null&&!entry.type().equals("SpriteEgg");
    }
    private static KnightProgress progress(int rank,int subrank) { return new KnightProgress(rank,subrank,0); }
}
