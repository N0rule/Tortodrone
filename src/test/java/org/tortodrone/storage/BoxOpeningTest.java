package org.tortodrone.storage;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.loot.*;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.resources.*;
import static org.tortodrone.support.TestWire.check;

/** Atomicity, identity, concurrent intent and persisted native inventory on disposable SQLite. */
public final class BoxOpeningTest {
    private static final String SILVER="Ticket/Keys/Silver Key",SLIME="Ticket/Keys/Slime Key",IRON="Upgrade/Lockbox",BOX="Upgrade/Lockboxes/Slime/000",PRIZE="Upgrade/Prize Box/Lucky Prize Box";
    private static Database db;private static ItemCatalog items;private static BoxCatalog boxes;private static Path home;private static int serial;
    private record Player(int account,String username,String name) {
        WorldProfile profile()throws IOException{return db.worldProfile(account,1,1);}
        void grant(String config,int count)throws IOException{db.grantItems(username,name,-1,-1,items.require(config),count,-1,-1);}
        TownStore.Result open(String config,int option)throws IOException{var p=profile();return service().open(account,1,1,p.revision(),id(p,config),option);}
    }
    private static BoxOpeningService service(){return new BoxOpeningService(db,items,boxes,new Random(7));}
    public static void main(String[] args)throws Exception {
        home=Files.createTempDirectory("tortodrone-box-test-");
        try {
            items=ItemYaml.load(Path.of("content/items"));
            // Retail boxes are non-stackable; also exercise administrator-authored stackable boxes.
            var nativeItems=items;items=new ItemCatalog(items.entries().stream().map(e->e.config().equals(IRON)
                ?new ItemCatalog.Entry(e.config(),e.stockId(),e.type(),e.nameKey(),e.rarity(),true,e.slot(),e.bindOnEquip(),e.permanent(),e.locked(),e.variant(),e.seed(),e.modelArgs()):e).toList())
                .withReferenceIds(nativeItems.entries().stream().collect(java.util.stream.Collectors.toMap(ItemCatalog.Entry::config,nativeItems::referenceId)));
            BoxFixture.write(home.resolve("content"));boxes=BoxCatalog.load(home.resolve("content"),items);db=new Database(home.resolve("data"),true);
            var p=player();p.grant(IRON,2);var before=p.profile();
            refused(()->p.open(IRON,0));p.grant(SLIME,2);p.grant("Ticket/Keys/Shadow Key",2);before=p.profile();final var missing=before;
            refused(()->p.open(IRON,0));check(p.profile().equals(missing),"ordinary box rejects Slime Key alone without side effects");
            p.grant(SILVER,2);before=p.profile();long iron=id(before,IRON),silver=id(before,SILVER),slime=id(before,SLIME);
            refused(()->p.open(IRON,-1));refused(()->p.open(IRON,1));refused(()->service().open(p.account,1,99,p.profile().revision(),iron,0));
            var stranger=player();refused(()->service().open(stranger.account,1,1,stranger.profile().revision(),iron,0));
            var opened=p.open(IRON,0);check(count(opened.profile(),IRON)==1&&count(opened.profile(),SILVER)==1&&count(opened.profile(),SLIME)==2,"one box and exactly one Silver Key consumed");
            check(id(opened.profile(),IRON)==iron&&id(opened.profile(),SILVER)==silver&&id(opened.profile(),SLIME)==slime,"decrement preserves box/key identities");
            long prior=before.revision();refused(()->service().open(p.account,1,1,prior,iron,0));check(p.profile().equals(opened.profile()),"same revision replay cannot consume remaining stack");
            p.open(IRON,0);check(count(p.profile(),IRON)==0&&count(p.profile(),SILVER)==0,"fresh consecutive intent opens the second stacked box");
            var keylessSlime=player();keylessSlime.grant(BOX,1);var noKeys=keylessSlime.profile();
            refused(()->keylessSlime.open(BOX,0));refused(()->keylessSlime.open(BOX,1));check(keylessSlime.profile().equals(noKeys),"Slime box without either key is preserved");
            var donor=p.profile();long awarded=id(donor,"Weapon/Sword/Calibur");
            db.trade(donor,1,Map.of(awarded,1),0,0,stranger.profile(),1,Map.of(),0,0,items);
            check(stranger.profile().items().stream().anyMatch(i->i.id()==awarded&&!i.bound()&&i.bindOnEquip()),"unbound rewards retain ordinary trade identity and bind-on-equip semantics");
            for(int option:List.of(0,1)) {
                var slimePlayer=player();slimePlayer.grant(BOX,2);slimePlayer.grant(SLIME,2);slimePlayer.grant(SILVER,2);
                var result=slimePlayer.open(BOX,option).profile();check(count(result,BOX)==1&&count(result,SLIME)==(option==0?1:2)&&count(result,SILVER)==(option==1?1:2),"native selected Slime key option is respected");
            }
            for(boolean keys:List.of(false,true)) {
                var prize=player();prize.grant(PRIZE,1);if(keys){prize.grant(SILVER,2);prize.grant(SLIME,2);}
                var initial=prize.profile();refused(()->prize.open(PRIZE,0));check(prize.profile().equals(initial),"keyless box rejects forged key option");
                var result=prize.open(PRIZE,-1);var after=result.profile();
                check(count(after,PRIZE)==0&&count(after,SILVER)==(keys?2:0)&&count(after,SLIME)==(keys?2:0),"Prize Box opens without consuming keys");
                check(count(after,"Weapon/Sword/Calibur")==2&&count(after,"Rarity/Spark of Life")==3&&count(after,"Material/Brimstone")==2,"main quantities, guaranteed and independent bonus rewards delivered");
                check(after.items().stream().filter(i->i.config().equals("Material/Brimstone")).allMatch(WorldProfile.Item::bound),"explicit reward binding persists");
                var receipt=(SessionDecoder.Multiset)result.result();check(receipt.counts().size()==4&&receipt.counts().keySet().stream().allMatch(k->after.items().stream().anyMatch(i->i.id()==(Long)k)),"callback identifies every real granted inventory instance");
                check(new CharacterStore(db,prize.account,prize.username,items).worldProfile(prize.name,1).equals(after),"reconnect reloads all rewards");
                check(new Database(home.resolve("data"),true).worldProfile(prize.account,1,1).equals(after),"database reopen preserves all rewards");
                refused(()->service().open(prize.account,1,1,after.revision(),id(initial,PRIZE),-1));
            }
            var failing=player();failing.grant(IRON,1);failing.grant(SILVER,1);failing.grant("Material/Brimstone",2);
            long rollbackStack=id(failing.profile(),"Material/Brimstone");
            db.write(c->{try(var update=c.prepareStatement("UPDATE inventory_items SET bound=1 WHERE item_id=?")){update.setLong(1,rollbackStack);update.executeUpdate();}return null;});
            var original=failing.profile();long nextId=scalar("SELECT value FROM metadata WHERE key='next-item-id'"),events=scalar("SELECT count(*) FROM item_events");
            db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_box_reward BEFORE INSERT ON inventory_items WHEN NEW.config='Rarity/Spark of Life' BEGIN SELECT RAISE(ABORT,'injected grant failure'); END");}return null;});
            refused(()->failing.open(IRON,0));check(failing.profile().equals(original),"late grant failure rolls back box, key, existing stack, earlier rewards and revision");
            check(scalar("SELECT value FROM metadata WHERE key='next-item-id'")==nextId&&scalar("SELECT count(*) FROM item_events")==events,"rollback preserves identity allocation and item audit events");
            db.write(c->{c.createStatement().execute("DROP TRIGGER reject_box_reward");return null;});
            var racing=player();racing.grant(IRON,2);racing.grant(SILVER,2);var snapshot=racing.profile();var start=new CountDownLatch(1);
            try(var executor=Executors.newFixedThreadPool(2)) {
                Callable<Boolean> open=()->{start.await();try{service().open(racing.account,1,1,snapshot.revision(),id(snapshot,IRON),0);return true;}catch(IOException refused){return false;}};
                var first=executor.submit(open);var second=executor.submit(open);start.countDown();check(first.get()!=second.get(),"simultaneous stale intents cannot consume one stack twice");
            }
            check(count(racing.profile(),IRON)==1&&count(racing.profile(),SILVER)==1&&count(racing.profile(),"Weapon/Sword/Calibur")==1,"race awards once and consumes once");
            var full=player();full.grant(PRIZE,1);full.grant(IRON,1);full.grant(SILVER,1);
            while(full.profile().items().size()<ItemCatalog.INVENTORY_LIMIT){int n=Math.min(20,ItemCatalog.INVENTORY_LIMIT-full.profile().items().size());full.grant("Weapon/Sword/Calibur",n);}
            var packed=full.profile();refused(()->full.open(PRIZE,-1));check(full.profile().equals(packed),"full inventory rejects the complete bundle without loss");
            refused(()->full.open(IRON,0));check(full.profile().equals(packed),"full inventory preserves the keyed box and Silver Key");
            // Complete delivery can reuse matching existing stacks even at the inventory limit.
            Path stacked=home.resolve("stacked");BoxFixture.write(stacked);
            Path loot=stacked.resolve("loot/boxes/test.yml");var data=Yaml.read(loot);
            var definition=Yaml.map(Yaml.map(data.get("boxes"),"boxes").get("lucky_prize_box"),"box");
            definition.put("groups",Map.of("main",BoxFixture.group(100,List.of(BoxFixture.item("brimstone",3,"bound")))));
            Files.delete(loot);Yaml.write(loot,data);
            var merger=new BoxOpeningService(db,items,BoxCatalog.load(stacked,items),new Random(9));
            var merging=player();merging.grant(PRIZE,1);merging.grant("Material/Brimstone",2);
            long material=id(merging.profile(),"Material/Brimstone");
            db.write(c->{try(var st=c.prepareStatement("UPDATE inventory_items SET bound=1 WHERE item_id=?")){st.setLong(1,material);st.executeUpdate();}return null;});
            while(merging.profile().items().size()<ItemCatalog.INVENTORY_LIMIT)merging.grant("Weapon/Sword/Calibur",Math.min(20,ItemCatalog.INVENTORY_LIMIT-merging.profile().items().size()));
            var mergedBefore=merging.profile();var merged=merger.open(merging.account,1,1,mergedBefore.revision(),id(mergedBefore,PRIZE),-1);
            check(id(merged.profile(),"Material/Brimstone")==material&&count(merged.profile(),"Material/Brimstone")==7,"main and guaranteed rewards merge into the matching bound stack");
            check(merged.profile().items().size()==ItemCatalog.INVENTORY_LIMIT-1&&((SessionDecoder.Multiset)merged.result()).counts().equals(Map.of(material,5)),"full inventory uses stack capacity and callback reports only awarded quantity");
            var unsupported=player();unsupported.grant("Upgrade/Prize Box/Solstice Prize Box",1);var saved=unsupported.profile();refused(()->unsupported.open("Upgrade/Prize Box/Solstice Prize Box",-1));check(unsupported.profile().equals(saved),"missing pool never awards fallback prizes");
            System.out.println("PASS: keyed/keyless/Slime options, ownership/auth, stack replay/race, atomic rollback, inventory capacity, quantities/binding and reconnect/reopen persistence");
        }finally{org.tortodrone.support.ContentFixture.delete(home);}
    }
    private static long scalar(String query)throws IOException {
        return db.read(c->{try(var statement=c.createStatement();var result=statement.executeQuery(query)){result.next();return result.getLong(1);}});
    }
    private static Player player()throws Exception {
        String user="BoxPlayer"+(++serial),name="Box-Knight"+(char)('a'+serial);int account=db.create(user,new Passwords.Protection(new byte[16],new byte[32]),false);
        new CharacterStore(db,account,user,items).create(new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));return new Player(account,user,name);
    }
    private static long id(WorldProfile p,String config){return p.items().stream().filter(i->i.config().equals(config)).findFirst().orElseThrow().id();}
    private static int count(WorldProfile p,String config){return p.items().stream().filter(i->i.config().equals(config)).mapToInt(WorldProfile.Item::count).sum();}
    @FunctionalInterface private interface Work {void run()throws Exception;}
    private static void refused(Work work)throws Exception{try{work.run();throw new AssertionError("Expected opening refusal");}catch(IOException expected){ }}
}
