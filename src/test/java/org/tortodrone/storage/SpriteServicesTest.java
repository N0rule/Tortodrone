package org.tortodrone.storage;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.resources.ContentManager;
import static org.tortodrone.support.TestWire.check;

/** Native harness prerequisites, GM perk progression, ownership and refused-payment rollback. */
public final class SpriteServicesTest {
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("tortodrone-sprite-services-");
        try{
            var content=ContentManager.load(Path.of("."));var db=new Database(root,true);var secret=new Passwords.Protection(new byte[16],new byte[32]);
            int gm=db.create("Alice",secret,true),other=db.create("Bob",secret,false);
            for(int account:List.of(gm,other))new CharacterStore(db,account,account==gm?"Alice":"Bob",content.items()).create(new Knight(0,account==gm?"Alice-Knight":"Bob-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0));
            var town=new TownStore(db,content.items(),content.town());
            town.adminSprite(gm,1,1,8,List.of(new SessionDecoder.ConfigReference("Drakon",Map.of())));
            var before=db.worldProfile(gm,1,1);refused(()->town.adminSprite(other,1,1,45,List.of(99)));
            var raised=town.adminSprite(gm,1,1,45,List.of(99)).profile();var sprite=raised.town().sprites().getFirst();
            check(sprite.level()==99&&!TownStore.unlocked(content.town(),sprite.perkLevel(),sprite.perks()).isEmpty(),"GM level setter grants the skipped native perk progression");
            var again=town.adminSprite(gm,1,1,45,List.of(99)).profile().town().sprites().getFirst();check(again.perkLevel()==sprite.perkLevel()&&again.perks().equals(sprite.perks()),"repeating level setter cannot reroll perks");
            int health=TownStore.unlocked(content.town(),sprite.perkLevel(),sprite.perks()).stream().filter(i->content.town().perks().get(i).name().startsWith("HEALTH_")).findFirst().orElseThrow();
            var selected=town.sprite(gm,1,1,3,List.of(sprite.id(),new SessionDecoder.EnumValue("com.threerings.projectx.sprites.data.SpritePerk",health)),true).profile();
            check(content.town().equipmentBonuses(selected,"").health()>0,"selected sprite perk contributes its native health bonus");
            var station=content.town().station("Shop/Haven/Harness Installer");var goods=content.town().goods(station,0,before.worldId());
            check(goods.size()==11,"merchant serves the eleven non-starter, non-development harnesses");
            int base=index(goods,content,"Crystal Harness"),advanced=index(goods,content,"Crystal Harness Adv"),elite=index(goods,content,"Crystal Harness Eli");
            check(town.availability(gm,1,1,station,0).get(base)==1&&town.availability(gm,1,1,station,0).get(advanced)==0,"merchant requires the native predecessor");
            var denied=db.worldProfile(gm,1,1);var failures=new ArrayList<Boolean>();db.observe((operation,millis,failed)->{if(operation.equals("write"))failures.add(failed);});
            refused(()->town.installHarnesses(gm,1,1,station,0,Set.of(base)));check(db.worldProfile(gm,1,1).equals(denied)&&failures.equals(List.of(false)),"insufficient funds rolls back without a database failure warning");
            db.currency("Alice","Alice-Knight",gm,-1,org.tortodrone.character.Currency.CROWNS,200000);
            var bought=town.installHarnesses(gm,1,1,station,0,Set.of(base)).profile();long harness=bought.town().harnesses().getFirst().id();
            check(bought.crowns()==192000,"base harness charges 8000 crowns");refused(()->town.installHarnesses(gm,1,1,station,0,Set.of(base)));refused(()->town.installHarnesses(gm,1,1,station,0,Set.of(elite)));
            town.sprite(gm,1,1,5,List.of(sprite.id(),harness),true);
            var upgraded=town.installHarnesses(gm,1,1,station,0,Set.of(advanced)).profile();
            check(upgraded.crowns()==160000&&upgraded.town().harnesses().getFirst().id()==harness&&upgraded.town().harnesses().getFirst().config().equals("Crystal Harness Adv")&&upgraded.town().sprites().getFirst().harness()==harness,"upgrade preserves shared harness identity and equipped sprites");
            refused(()->town.installHarnesses(other,1,1,station,0,Set.of(advanced)));refused(()->town.installHarnesses(gm,1,99,station,0,Set.of(elite)));
            check(new Database(root,true).worldProfile(gm,1,1).equals(upgraded),"harness and perks survive restart; foreign and stale requests change nothing");
            System.out.println("PASS: native harness catalogue/prerequisites/prices/upgrades, GM perk progression, ownership, restart and expected purchase rollback logging");
        }finally{org.tortodrone.support.ContentFixture.delete(root);}
    }
    private static int index(List<org.tortodrone.town.TownCatalog.Good> goods,org.tortodrone.resources.ContentRegistry content,String config){for(int i=0;i<goods.size();i++)if(config.equals(content.town().item(goods.get(i).item()).get("harness")))return i;throw new AssertionError(config);}
    private interface Work{void run()throws Exception;}
    private static void refused(Work work)throws Exception{try{work.run();throw new AssertionError("Expected refusal");}catch(IOException expected){}}
}
