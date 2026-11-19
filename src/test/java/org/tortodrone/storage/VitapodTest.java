package org.tortodrone.storage;

import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.support.ContentFixture;
import static org.tortodrone.support.TestWire.check;

/** Native Vitapods are slot-12 dungeon gear with a passive capsule health bonus. */
public final class VitapodTest {
    public static void main(String[] args)throws Exception {
        var content=ContentManager.load(Path.of("."));var items=content.items();
        var levels=new TreeMap<Integer,String>();
        for(var entry:items.entries())if(entry.type().equals("Dungeon")&&DungeonGear.vitapodTier(entry.config())>0)levels.put(DungeonGear.vitapodTier(entry.config()),entry.config());
        check(levels.size()==24&&levels.firstEntry().getKey()==1&&levels.lastEntry().getKey()==30,"all native Vitapod tiers retained");
        for(var tier:levels.entrySet()) {
            var entry=items.require(tier.getValue());
            check(entry.grantable()&&entry.slot()==WorldProfile.Slot.DUNGEON&&entry.slot().accepts(12),"Vitapod is slot-12 dungeon gear: "+tier.getValue());
            check(DungeonGear.healthBonus(entry.config())==tier.getKey()*40,"Vitapod capsule health bonus: "+tier.getValue());
        }
        Path root=Files.createTempDirectory("tortodrone-vitapod-");
        try {
            var db=new Database(root,false);
            int account=db.create("Pod",new Passwords.Protection(new byte[16],new byte[32]),false);
            new CharacterStore(db,account,"Pod",items).create(new Knight(0,"Pod-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0));
            var small=items.require(levels.get(1));var large=items.require(levels.get(2));
            var profile=db.grantItems(null,"Pod-Knight",-1,-1,small,1,-1,-1);
            long smallId=profile.items().stream().filter(i->i.config().equals(small.config())).findFirst().orElseThrow().id();
            profile=db.equip(account,1,1,smallId,12);
            check(profile.items().stream().anyMatch(i->i.id()==smallId&&Integer.valueOf(12).equals(i.equippedSlot())),"Vitapod equips in dungeon slot 12");
            check(profile.equipment()[12]==smallId,"player equipment exposes slot 12");
            profile=db.grantItems(null,"Pod-Knight",-1,-1,large,1,-1,-1);
            long largeId=profile.items().stream().filter(i->i.config().equals(large.config())).findFirst().orElseThrow().id();
            profile=db.equip(account,1,1,largeId,12);
            check(profile.items().stream().anyMatch(i->i.id()==largeId&&Integer.valueOf(12).equals(i.equippedSlot())),"better Vitapod replaces the equipped pod");
            check(profile.items().stream().noneMatch(i->i.id()==smallId),"native replacement removes the smaller pod");
            refused(()->db.equip(account,1,1,smallId,12));
            check(db.worldProfile(account,1,1).equipment()[12]==largeId,"slot 12 survives a database reload");
            System.out.println("PASS: 24 Vitapod tiers, slot-12 equip/replacement, better-pod refusal and capsule health values");
        }finally{ContentFixture.delete(root);}
    }
    private interface Action {void run()throws Exception;}
    private static void refused(Action action)throws Exception{try{action.run();throw new AssertionError("Expected refusal");}catch(java.io.IOException expected){}}
}
