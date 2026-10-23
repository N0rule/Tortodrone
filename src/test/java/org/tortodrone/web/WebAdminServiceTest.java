package org.tortodrone.web;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.character.*;
import org.tortodrone.resources.*;
import org.tortodrone.server.*;
import org.tortodrone.storage.*;
import static org.tortodrone.support.TestWire.check;

/** Domain adapter parity with ownership, stale-revision and transactional failure assertions. */
public final class WebAdminServiceTest {
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("tortodrone-web-service-");
        try {
            var content=ContentManager.load(Path.of("."));Path data=root.resolve("data");
            try(var accounts=new AccountStore(data,false,content.items(),content.town());var log=new ServerLog(data.resolve("logs"));var service=new AdminService(accounts,new ServerControl("fixture",data),log,root)) {
                write(service,"account-create","username","Alice","password","alice-password","gm",true);
                int account=accounts.list().getFirst().id();var db=new Database(data,false);
                new CharacterStore(db,account,"Alice",content.items()).create(new Knight(0,"Alice-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
                var profile=accounts.worldProfile("Alice","Alice-Knight");
                var base=new HashMap<String,String>(Map.of("username","Alice","knight","Alice-Knight","accountId",""+account,"worldId",""+profile.worldId()));
                var currency=new HashMap<>(base);currency.putAll(Map.of("currency","CROWNS","operation","set","amount","300","revision",""+profile.revision()));
                service.write("knight-currency",input("knight-currency",currency));check(accounts.worldProfile("Alice","Alice-Knight").crowns()==300,"Web currency service");
                refused(()->service.write("knight-currency",input("knight-currency",currency)));
                var wrong=new HashMap<>(currency);wrong.put("worldId","99999");refused(()->service.write("knight-currency",input("knight-currency",wrong)));check(accounts.worldProfile("Alice","Alice-Knight").crowns()==300,"ownership refusal rolls back");
                var progress=new HashMap<>(base);progress.putAll(Map.of("rank","3","subrank","2","prestige","1200","revision",""+accounts.worldProfile("Alice","Alice-Knight").revision()));service.write("knight-progress",input("knight-progress",progress));check(accounts.worldProfile("Alice","Alice-Knight").progress().prestige()==1200,"progress edit");
                refused(()->service.write("knight-progress",input("knight-progress",progress)));
                var grant=new HashMap<>(base);grant.putAll(Map.of("config","Material/Gel Drop","quantity","3","level","0","heat","0","bound","false","expireDays","0"));service.write("item-grant",input("item-grant",grant));
                profile=accounts.worldProfile("Alice","Alice-Knight");var gel=profile.items().stream().filter(i->i.config().equals("Material/Gel Drop")).findFirst().orElseThrow();
                var quantity=new HashMap<>(base);quantity.putAll(Map.of("itemId",""+gel.id(),"quantity","2","revision",""+profile.revision()));service.write("item-quantity",input("item-quantity",quantity));
                refused(()->service.write("item-quantity",input("item-quantity",quantity)));
                quantity.put("revision",""+accounts.worldProfile("Alice","Alice-Knight").revision());
                try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
                    java.util.concurrent.Callable<Boolean> action=()->{try{service.write("item-quantity",input("item-quantity",quantity));return true;}catch(IOException refused){return false;}};
                    var results=pool.invokeAll(List.of(action,action));check(results.get(0).get()!=results.get(1).get(),"concurrent inventory edits commit exactly once");
                }
                var unchanged=accounts.worldProfile("Alice","Alice-Knight");check(unchanged.items().stream().filter(i->i.id()==gel.id()).findFirst().orElseThrow().count()==2,"stale quantity rejected");
                var modifiers=new HashMap<>(base);modifiers.putAll(Map.of("itemId",""+gel.id(),"level","99","heat","0","revision",""+unchanged.revision()));refused(()->service.write("item-modifiers",input("item-modifiers",modifiers)));check(accounts.worldProfile("Alice","Alice-Knight").equals(unchanged),"invalid modifier rollback");
                var accessory=new HashMap<>(base);accessory.putAll(Map.of("itemId",""+gel.id(),"accessory","invalid","revision",""+unchanged.revision()));refused(()->service.write("item-accessory",input("item-accessory",accessory)));check(accounts.worldProfile("Alice","Alice-Knight").equals(unchanged),"accessory validation rollback");
                var gear=unchanged.items().stream().filter(i->i.slot()==WorldProfile.Slot.HELM).findFirst().orElseThrow();accessory.put("itemId",""+gear.id());accessory.put("accessory","");service.write("item-accessory",input("item-accessory",accessory));check(accounts.worldProfile("Alice","Alice-Knight").revision()>unchanged.revision(),"accessory change publishes revision");
                long rev=accounts.depot().snapshot().revision();
                var offer=new HashMap<String,String>(Map.of("id","-1","config","Material/Gel Drop","quantity","1","crowns","20","energy","0","limit","2","featured","true","enabled","true","promotionOnly","false","revision",""+rev));
                var saved=(DepotStore.Snapshot)service.write("depot-save",input("depot-save",offer));refused(()->service.write("depot-save",input("depot-save",offer)));check(accounts.depot().snapshot().equals(saved),"stale Depot revision rollback");
                long now=System.currentTimeMillis();int offerId=saved.offers().getLast().id();
                write(service,"promotion-create","revision",saved.revision(),"offerId",offerId,"starts",now-1000,"ends",now+3600000,"crowns",10,"energy",0,"featured",true);
                var promo=accounts.depot().promotions().getFirst();check(accounts.depot().catalog().offers().getLast().good().price()==10,"effective promotion");
                write(service,"promotion-cancel","revision",accounts.depot().snapshot().revision(),"id",promo.id());check(accounts.depot().promotions().getFirst().cancelled(),"promotion cancellation");
                write(service,"auction-create","config","Material/Gel Drop","quantity",1,"bid",100,"buy",200,"stock",1,"starts",now+60000,"expires",now+3600000);
                var auction=accounts.auctions().overview().getFirst().listing();check(auction.availableAt()>now&&auction.featured(),"scheduled system auction");write(service,"auction-cancel","id",auction.id());check(accounts.auctions().overview().getFirst().listing().cancelled(),"authoritative auction cancellation");
                write(service,"auction-settle");check(accounts.auctions().deliveries(auction.id()).isEmpty(),"system cancellation creates no duplicate grants");
                Path news=root.resolve("content/shared/news");Files.createDirectories(news);Files.writeString(news.resolve("news.yml"),"enabled: false\npacks: []\n");String digest=ContentFiles.digest(news.resolve("news.yml"));
                write(service,"news-save","revision",digest,"yaml","enabled: false\npacks: []\n# reviewed\n");String changed=Files.readString(news.resolve("news.yml"));refused(()->write(service,"news-save","revision",digest,"yaml","enabled: true\npacks: []\n"));check(Files.readString(news.resolve("news.yml")).equals(changed),"stale News update preserved");
                refused(()->write(service,"news-save","revision",ContentFiles.digest(news.resolve("news.yml")),"yaml","enabled: true\npacks: []\n"));check(Files.readString(news.resolve("news.yml")).equals(changed),"invalid News update rolled back");
            }
            System.out.println("PASS: WebUI domain actions, ownership, revision guards, inventory/accessory rollback, Depot, auctions and atomic News validation");
        }finally{try(var walk=Files.walk(root)){for(var p:walk.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    private static Object write(AdminService service,String route,Object... pairs)throws IOException {var p=new HashMap<String,String>();for(int i=0;i<pairs.length;i+=2)p.put(pairs[i].toString(),pairs[i+1].toString());return service.write(route,input(route,p));}
    private static AdminService.Input input(String route,Map<String,String> p){var copy=new HashMap<>(p);copy.put("confirmation",route);return new AdminService.Input(copy);}
    private interface Action{void run()throws Exception;}
    private static void refused(Action a)throws Exception{try{a.run();throw new AssertionError("Unsafe action accepted");}catch(IOException expected){}}
}
