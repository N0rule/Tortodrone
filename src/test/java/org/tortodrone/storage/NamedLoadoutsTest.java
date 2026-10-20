package org.tortodrone.storage;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.resources.ContentManager;
import static org.tortodrone.support.TestWire.check;

public final class NamedLoadoutsTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-loadouts-");
        try {
            var content=ContentManager.load(Path.of("."));var db=new Database(home,true);var secret=new Passwords.Protection(new byte[16],new byte[32]);
            int a=db.create("Alice",secret,true),b=db.create("Bob",secret,false);
            var knight=new CharacterStore(db,a,"Alice",content.items()).create(knight("Loadout-Alice"));
            new CharacterStore(db,b,"Bob",content.items()).create(knight("Loadout-Bob"));
            var town=new TownStore(db,content.items(),content.town());var initial=db.worldProfile(a,1,1);
            town.loadout(a,1,1,"Basic",true);
            var p=db.grantItems("Alice",knight.name(),-1,-1,content.items().require("calibur"),1,-1,-1);
            var sword=p.items().stream().filter(i->i.config().equals("Weapon/Sword/Calibur")).findFirst().orElseThrow();
            db.equip(a,1,1,sword.id(),6);town.loadout(a,1,1,"Battle",true);
            var basic=town.loadout(a,1,1,"Basic",false).profile();
            check(Arrays.equals(initial.equipment(),basic.equipment()),"switch restores all original equipment slots atomically");
            check(new Database(home,true).worldProfile(a,1,1).town().loadouts().size()==2,"named loadouts survive database reopen");
            refused(()->town.loadout(b,1,1,"Battle",false));refused(()->town.loadout(a,1,999,"Battle",false));
            refused(()->town.loadout(a,1,1,"\nBad",true));refused(()->town.loadout(a,1,1,"x".repeat(33),true));
            check(db.worldProfile(a,1,1).equals(basic),"ownership, stale authentication and malformed-name failures change nothing");
            db.write(c->{try(var st=c.createStatement()){st.execute("CREATE TRIGGER reject_loadout BEFORE UPDATE OF equipped_slot ON inventory_items BEGIN SELECT RAISE(ABORT,'test'); END");}return null;});
            refused(()->town.loadout(a,1,1,"Battle",false));check(db.worldProfile(a,1,1).equals(basic),"failed equipment write rolls back the complete loadout");
            db.write(c->{try(var st=c.createStatement()){st.execute("DROP TRIGGER reject_loadout");}return null;});
            var battle=town.loadout(a,1,1,"bAtTlE",false).profile();check(battle.equipment()[6]==sword.id(),"switch selects the saved owned item");
            town.loadout(a,1,1,"Basic",false);db.itemQuantity("Alice",knight.name(),a,battle.worldId(),sword.id(),0,content.items());
            var missing=db.worldProfile(a,1,1);refused(()->town.loadout(a,1,1,"Battle",false));check(db.worldProfile(a,1,1).equals(missing),"missing item refuses the complete switch without clearing equipment");
            town.adminSprite(a,1,1,8,List.of(new org.tortodrone.protocol.SessionDecoder.ConfigReference("Drakon",Map.of())));
            int sprite=db.worldProfile(a,1,1).town().equippedSprite();town.loadout(a,1,1,"Pet",true);
            town.sprite(a,1,1,4,List.of(0),false);var pet=town.loadout(a,1,1,"Pet",false).profile();check(pet.town().equippedSprite()==sprite,"saved loadout restores an owned battle sprite");
            town.loadout(a,1,1,"pet",true);check(db.worldProfile(a,1,1).town().loadouts().size()==3,"saving an existing name replaces rather than duplicates it");
            String accessory=content.items().entries().stream().map(e->content.town().item(e.config()).get("accessory")).filter(Objects::nonNull)
                .filter(n->{var def=content.town().accessory(n);return def!=null&&!def.remove()&&def.location().equals("ARMOR_BACK")&&content.town().accessoryModel(n)!=null;}).findFirst().orElseThrow();
            var adorned=town.adminAccessory(a,1,1,1,accessory,"Blue").profile();
            check(adorned.items().stream().filter(i->i.equippedSlot()!=null&&i.equippedSlot()==1).findFirst().orElseThrow().additionalAccessories().contains(accessory),"GM accessorize persists a verified native accessory");
            refused(()->town.adminAccessory(b,1,1,1,accessory,"Blue"));refused(()->town.adminAccessory(a,1,1,1,"Unknown/Accessory","Blue"));
            check(db.worldProfile(a,1,1).equals(adorned),"GM permission and unknown accessory refusals change nothing");
            var cleared=town.adminAccessory(a,1,1,1,null,"Blue").profile();check(cleared.items().stream().filter(i->i.equippedSlot()!=null&&i.equippedSlot()==1).findFirst().orElseThrow().additionalAccessories().isEmpty(),"GM clear removes accessories without deleting gear");
            var pvp=town.loadoutPvp(a,1,1,"Basic",true).profile();
            check(new Database(home,true).worldProfile(a,1,1).town().loadouts().stream().anyMatch(l->l.name().equals("Basic")&&l.pvp()),"PvP designation persists across reopen");
            refused(()->town.loadoutPvp(b,1,1,"Basic",false));refused(()->town.loadoutPvp(a,1,99,"Basic",false));refused(()->town.loadoutPvp(a,1,1,"Missing",true));refused(()->town.loadoutPvp(a,1,1,"",true));
            check(db.worldProfile(a,1,1).equals(pvp),"invalid and foreign PvP changes leave the owned loadout intact");
            db.write(c->{c.createStatement().execute("CREATE TRIGGER reject_pvp BEFORE INSERT ON metadata WHEN NEW.key LIKE 'knight.loadout.%.pvp' BEGIN SELECT RAISE(ABORT,'test'); END");return null;});
            refused(()->town.loadoutPvp(a,1,1,"Basic",false));check(db.worldProfile(a,1,1).equals(pvp),"PvP write failure rolls back state and revision");
            db.write(c->{c.createStatement().execute("DROP TRIGGER reject_pvp");return null;});
            var resaved=town.loadout(a,1,1,"Basic",true).profile();check(resaved.town().loadouts().stream().anyMatch(l->l.name().equals("Basic")&&l.pvp()),"saving gear preserves designation");
            check(town.loadoutPvp(a,1,1,"Basic",false).profile().town().loadouts().stream().noneMatch(l->l.name().equals("Basic")&&l.pvp()),"PvP designation can be cleared");
            db.deleteKnight("Alice",knight.name(),a,pet.worldId());
            check(db.read(c->{try(var st=c.createStatement();var r=st.executeQuery("SELECT count(*) FROM metadata WHERE key LIKE 'knight.loadout.%'")){r.next();return r.getInt(1)==0;}}),"knight deletion removes owned loadout metadata");
            System.out.println("PASS: named loadout save/switch/reopen, sprite restoration, ownership/auth, missing items, rollback, replacement and deletion cleanup");
        }finally{org.tortodrone.support.ContentFixture.delete(home);}
    }
    private static Knight knight(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0);}
    private static void refused(Action action)throws Exception{try{action.run();throw new AssertionError("Expected refusal");}catch(IOException expected){}}
    @FunctionalInterface private interface Action{void run()throws Exception;}
}
