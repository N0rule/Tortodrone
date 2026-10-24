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
            try(var accounts=new AccountStore(data,false,content.items(),content.town());var log=new ServerLog(data.resolve("logs"));var service=new AdminService(accounts,new ServerControl("fixture",data),log,root,content)) {
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
                var view=(AdminViews.KnightView)service.read("knight-view",new AdminService.Input(Map.of("username","Alice","knight","Alice-Knight")));
                check(view.gameMaster()&&!view.online()&&view.guild().equals("No guild"),"profile permission, offline state and neutral guild come from authoritative sources");
                check(view.profile().crowns()==300&&view.profile().progress().prestige()==1200,"profile currency and prestige are exact");
                check(view.profile().items().equals(accounts.worldProfile("Alice","Alice-Knight").items()),"inspection preserves owned and equipped distinctions");
                var page=(AdminViews.ItemPage)service.read("catalogue",new AdminService.Input(Map.of("search","Gel Drop","size","1","page","1")));
                check(page.entries().size()==1&&page.total()>=1&&page.entries().getFirst().name().equals(content.items().displayName(content.items().find(page.entries().getFirst().config()))),"catalogue pages use authoritative display names");
                try{service.read("catalogue",new AdminService.Input(Map.of("size","101")));throw new AssertionError("unbounded catalogue page accepted");}catch(IllegalArgumentException expected){}
                var preview=new HashMap<String,String>(Map.of("config","Material/Gel Drop","level","0","heat","0"));
                check(WebJson.encode(service.read("item-preview",new AdminService.Input(preview))).contains("true"),"read-only modifier preview");
                preview.put("level","1");refused(()->service.read("item-preview",new AdminService.Input(preview)));
                String helmConfig=profile.items().stream().filter(i->i.slot()==WorldProfile.Slot.HELM).findFirst().orElseThrow().config();
                var helmDefinition=(AdminViews.Item)service.read("item",new AdminService.Input(Map.of("config",helmConfig)));
                check(helmDefinition.levels()==content.town().gear().gear(helmConfig).levels(),"visual level bounds use the domain progression table");
                var helmPreview=new HashMap<String,String>(Map.of("config",helmConfig,"level","0","heat","0.375"));
                var validPreview=(Map<?,?>)service.read("item-preview",new AdminService.Input(helmPreview));
                check(validPreview.get("heat").equals(0.375f)&&validPreview.get("displayedLevel").equals(1),"precise heat and zero-based level conversion");
                check(validPreview.get("stats").equals(helmDefinition.stats()),"base preview uses the same authoritative stats");
                check(validPreview.get("compatibleVariants").equals(helmDefinition.variants()),"unmodified gear offers every authoritative UV choice");
                helmPreview.put("level",String.valueOf(helmDefinition.levels()-1));refused(()->service.read("item-preview",new AdminService.Input(helmPreview)));
                helmPreview.put("level","0");
                if(!helmDefinition.variants().isEmpty()) {
                    String variant=helmDefinition.variants().getFirst();helmPreview.put("variants",variant);
                    var chosenPreview=(Map<?,?>)service.read("item-preview",new AdminService.Input(helmPreview));
                    var compatible=(List<?>)chosenPreview.get("compatibleVariants");
                    check(compatible.contains(variant),"selected UV remains removable in the picker");
                    for(String candidate:helmDefinition.variants())if(!candidate.equals(variant)) {
                        boolean accepted=true;
                        try{content.town().validateVariants(org.tortodrone.town.TownCatalog.itemGroup(content.items().find(helmConfig)),List.of(variant,candidate));}
                        catch(IOException rejected){accepted=false;}
                        check(compatible.contains(candidate)==accepted,"picker compatibility agrees with save validation");
                    }
                    helmPreview.put("variants",variant+"\n"+variant);refused(()->service.read("item-preview",new AdminService.Input(helmPreview)));
                    preview.put("level","0");preview.put("variants",variant);refused(()->service.read("item-preview",new AdminService.Input(preview)));
                }
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
                var image=new java.awt.image.BufferedImage(570,300,java.awt.image.BufferedImage.TYPE_INT_ARGB);
                var encoded=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",encoded);
                String imageData=Base64.getEncoder().encodeToString(encoded.toByteArray());
                var uploaded=(Map<?,?>)write(service,"news-upload","name","featured.png","data",imageData);
                String imageName=(String)uploaded.get("image");check(Files.exists(news.resolve(imageName)),"safe image upload stays in News root");
                check(write(service,"news-upload","name","featured.png","data",imageData).equals(uploaded),"uploads are hash deduplicated");
                refused(()->write(service,"news-upload","name","../escape.png","data",imageData));
                refused(()->write(service,"news-upload","name","image.jpg","data",imageData));
                String visual=Json.stringify(Map.of("enabled",true,"packs",List.of(Map.of("featured",Map.of("image",imageName,"tooltip","Featured & useful","action","depot","argument","","url",""),"spotlights",Map.of()))));
                write(service,"news-visual-save","revision",ContentFiles.digest(news.resolve("news.yml")),"catalog",visual);
                var published=NewsYaml.load(news);check(published.enabled()&&published.packs().getFirst().featured().tooltip().equals("Featured & useful")&&published.propertyList(0).contains("depot"),"visual editor preserves native News schema and action");
                var sprites=accounts.worldProfile("Alice","Alice-Knight");
                accounts.progress("Alice","Alice-Knight",3,2,1200,account,sprites.worldId(),"Drakon",sprites.revision());
                view=(AdminViews.KnightView)service.read("knight-view",new AdminService.Input(Map.of("username","Alice","knight","Alice-Knight")));
                check(view.sprites().size()==1&&view.sprites().getFirst().displayedLevel()==view.sprites().getFirst().state().level()+1&&Boolean.FALSE.equals(view.sprites().getFirst().active())&&view.sprites().getFirst().live()==null,"Sprite inspection exposes exact progression and offline deployment state");
                check(view.sprites().getFirst().state().harness()==0&&view.sprites().getFirst().harness().config().equals("Iron Harness"),"implicit base harness matches native serialization and simulation");
            }
            System.out.println("PASS: WebUI domain actions, ownership, revision guards, inventory/accessory rollback, Depot, auctions and atomic News validation");
        }finally{try(var walk=Files.walk(root)){for(var p:walk.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    private static Object write(AdminService service,String route,Object... pairs)throws IOException {var p=new HashMap<String,String>();for(int i=0;i<pairs.length;i+=2)p.put(pairs[i].toString(),pairs[i+1].toString());return service.write(route,input(route,p));}
    private static AdminService.Input input(String route,Map<String,String> p){var copy=new HashMap<>(p);copy.put("confirmation",route);return new AdminService.Input(copy);}
    private interface Action{void run()throws Exception;}
    private static void refused(Action a)throws Exception{try{a.run();throw new AssertionError("Unsafe action accepted");}catch(IOException expected){}}
}
