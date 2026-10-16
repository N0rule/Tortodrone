package org.tortodrone.town;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.character.Currency;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.storage.*;
import static org.tortodrone.support.TestWire.check;

/** Small disposable end-to-end ledger fixture: craft, sprite care and auction attachment ownership. */
public final class TownEconomyTest {
    public static void main(String[] args)throws Exception{
        Path home=Files.createTempDirectory("tortodrone-town-economy-");
        try{
            var db=new Database(home,true);var password=new Passwords.Protection(new byte[16],new byte[32]);int a=db.create("Alice",password,false),b=db.create("Bob",password,false);
            var items=ItemCatalog.load(Path.of("content/packs/vanilla/items/catalog.json"));var rules=TownCatalog.load(Path.of("content/packs/vanilla/town/catalog.json"));
            var alice=new CharacterStore(db,a,"Alice",items);var bob=new CharacterStore(db,b,"Bob",items);alice.create(choices("Alice-Knight"));bob.create(choices("Bob-Knight"));
            db.currency("Alice","Alice-Knight",a,-1,Currency.CROWNS,100000);db.currency("Bob","Bob-Knight",b,-1,Currency.CROWNS,100000);
            var town=new TownStore(db,items,rules);var house=new AuctionHouse(db,items,rules);
            grant(db,items,"Alice","Alice-Knight","Recipe/Armor/Padded Armor Recipe",1);
            long recipe=id(db.worldProfile(a,1,1),"Recipe/Armor/Padded Armor Recipe");town.learn(a,1,1,recipe);
            var r=rules.recipe("Armor/Padded Armor");var output=items.require(r.item());var selected=new ArrayList<Long>();
            for(var ingredient:r.ingredients()){grant(db,items,"Alice","Alice-Knight",ingredient.item(),ingredient.count());selected.add(id(db.worldProfile(a,1,1),ingredient.item()));}
            if(r.orbs(output.rarity())&&output.rarity()>0){String orb="Rarity/Orb of Alchemy/"+new String[]{"","Flawed","Simple","Advanced","Elite","Eternal"}[output.rarity()];grant(db,items,"Alice","Alice-Knight",orb,3);selected.add(id(db.worldProfile(a,1,1),orb));}
            long[] supplied=selected.stream().mapToLong(Long::longValue).toArray();var before=db.worldProfile(a,1,1);
            refused(()->town.craft(b,1,1,r.config(),supplied,false,false));check(db.worldProfile(a,1,1).equals(before),"foreign crafting leaves owner unchanged");
            long[] duplicate={supplied[0],supplied[0]};refused(()->town.craft(a,1,1,r.config(),duplicate,false,false));check(db.worldProfile(a,1,1).equals(before),"invalid craft rolls back inventory and crowns");
            var crafted=town.craft(a,1,1,r.config(),supplied,false,false);check(crafted.profile().crowns()==before.crowns()-r.cost(output.rarity())&&crafted.profile().town().recipes().getFirst().crafted()==1,"craft price and learned recipe progression commit together");
            long craftedGear=(Long)crafted.result();var beforeVariant=db.worldProfile(a,1,1);
            refused(()->town.variant(b,1,1,1,craftedGear,0,0,false));refused(()->town.variant(a,1,1,4,craftedGear,0,0,false));
            check(beforeVariant.equals(db.worldProfile(a,1,1)),"foreign and invalid variant operations preserve resources");
            var varied=town.variant(a,1,1,1,craftedGear,0,0,false);
            check(varied.profile().crowns()==beforeVariant.crowns()-20000&&varied.profile().items().stream().filter(i->i.id()==craftedGear).findFirst().orElseThrow().variants().size()==1,"variant service charges the stock crown price and updates the owned item");
            String custom=items.entries().stream().map(ItemCatalog.Entry::config).filter(v->rules.item(v).containsKey("variant")&&Arrays.asList(rules.item(v).getOrDefault("variantGroups","").split(",")).contains("ARMOR")&&varied.profile().items().stream().filter(i->i.id()==craftedGear).findFirst().orElseThrow().variants().stream().noneMatch(m->m.name().equals(rules.item(v).get("variant")))).findFirst().orElseThrow();grant(db,items,"Alice","Alice-Knight",custom,1);
            town.variant(a,1,1,1,craftedGear,id(db.worldProfile(a,1,1),custom),1,true);
            check(db.worldProfile(a,1,1).items().stream().filter(i->i.id()==craftedGear).findFirst().orElseThrow().variants().stream().anyMatch(v->v.name().equals(rules.item(custom).get("variant"))),"custom ticket preserves the locked variant and adds its verified group-specific variant");
            int depot=-1;for(int i=0;i<rules.depot().size();i++){var good=rules.depot().get(i).good();var definition=items.find(good.item());if(definition!=null&&definition.grantable()&&good.energy()>0&&good.energy()<10000){depot=i;break;}}
            check(depot>=0,"captured depot contains a supported offer");final int offered=depot;var good=rules.depot().get(offered).good();
            db.currency("Alice","Alice-Knight",a,-1,Currency.ENERGY,good.energy());var beforeDepot=db.worldProfile(a,1,1);
            refused(()->town.depot(a,1,1,offered,good.energy()-1));check(db.worldProfile(a,1,1).equals(beforeDepot),"depot refuses a stale or forged price without spending");
            var depotPurchase=town.depot(a,1,1,offered,good.energy());check(depotPurchase.profile().energy()==beforeDepot.energy()-good.energy()&&depotPurchase.profile().crowns()==beforeDepot.crowns(),"energy depot offer charges only its displayed currency");
            crafted=new TownStore.Result(depotPurchase.profile(),craftedGear);
            var shop=new TownCatalog.Station("fixture","OpenShop","CROWN","NORMAL","~","~","","",List.of(),new TownCatalog.Shop("Captured/Hall of Heroes/Cobalt",10,"BASIC","CONSTANT",false));
            var goods=rules.goods(shop.shop(),0,crafted.profile().worldId());check(!goods.isEmpty()&&goods.getFirst().price()==1000,"captured merchant price preserved");
            var bought=town.shop(a,1,1,shop,0,Map.of(0,1),Map.of());check(bought.profile().crowns()==crafted.profile().crowns()-1000,"merchant purchase debits the displayed price");
            var accessory=items.entries().stream().filter(ItemCatalog.Entry::grantable).filter(e->{String name=rules.item(e.config()).get("accessory");var definition=name==null?null:rules.accessory(name);return definition!=null&&!definition.remove()&&definition.location().equals("ARMOR_BACK");}).findFirst().orElseThrow();
            grant(db,items,"Alice","Alice-Knight",accessory.config(),2);long gear=(Long)crafted.result(),ticket=id(db.worldProfile(a,1,1),accessory.config());town.accessorize(a,1,1,1,gear,ticket,"Blue");
            var accessorized=db.worldProfile(a,1,1);long duplicateTicket=id(accessorized,accessory.config());refused(()->town.accessorize(a,1,1,1,gear,duplicateTicket,"Blue"));check(db.worldProfile(a,1,1).equals(accessorized),"occupied accessory slot refuses without consuming the ticket");
            town.accessorize(a,1,1,2,gear,new SessionDecoder.EnumValue("com.threerings.projectx.item.data.ItemCodes$AccessoryLocation",6),"Blue");
            grant(db,items,"Alice","Alice-Knight","Sprite/Drakon Egg",1);long egg=id(db.worldProfile(a,1,1),"Sprite/Drakon Egg");int sprite=(Integer)town.hatch(a,1,1,egg).result();
            grant(db,items,"Alice","Alice-Knight","Food/Power Mote",1);long food=id(db.worldProfile(a,1,1),"Food/Power Mote");var fed=town.sprite(a,1,1,6,List.of(food,sprite),false);check(fed.profile().town().sprites().getFirst().heat()>0,"feeding consumes food and grants heat");
            refused(()->town.sprite(b,1,1,4,List.of(sprite),false));refused(()->town.sprite(a,1,1,7,List.of(sprite,0L,true),false));
            town.sprite(a,1,1,4,List.of(sprite),false);town.sprite(a,1,1,8,List.of(sprite,"Sparky"),false);
            String kit="Harness/Crystal Harness Kit";grant(db,items,"Alice","Alice-Knight",kit,1);long kitId=id(db.worldProfile(a,1,1),kit);
            refused(()->town.installHarness(b,1,1,kitId));var installed=town.installHarness(a,1,1,kitId);long harness=(Long)installed.result();
            check(installed.profile().items().stream().anyMatch(i->i.id()==kitId),"archive harness kit is retained");
            town.sprite(a,1,1,5,List.of(sprite,harness),false);
            check(new Database(home,true).worldProfile(a,1,1).town().sprites().getFirst().harness()==harness,"installed harness and sprite selection persist");
            grant(db,items,"Alice","Alice-Knight","Weapon/Sword/Calibur",1);long sword=id(db.worldProfile(a,1,1),"Weapon/Sword/Calibur");
            var listed=house.operate(a,1,1,6,List.of(sword,1,100,500,new SessionDecoder.EnumValue("com.threerings.projectx.auction.data.Duration",0),false),false);long listing=((AuctionState.Listing)listed.result()).id();
            var bobBefore=db.worldProfile(b,1,1);refused(()->house.operate(b,1,1,1,List.of(listing,50),false));check(db.worldProfile(b,1,1).equals(bobBefore),"failed bid rolls back crowns");
            house.operate(b,1,1,1,List.of(listing,500),false);var mail=db.worldProfile(b,1,1).town().mail().stream().filter(m->m.item()!=null).findFirst().orElseThrow();
            refused(()->house.mail(a,1,1,1,List.of(mail.id())));house.mail(b,1,1,1,List.of(mail.id()));refused(()->house.mail(b,1,1,1,List.of(mail.id())));
            check(db.worldProfile(b,1,1).items().stream().anyMatch(i->i.config().equals("Weapon/Sword/Calibur")),"buyout delivers owned attachment exactly once");
            check(new Database(home,true).worldProfile(a,1,1).town().sprites().getFirst().name().equals("Sparky"),"sprite state survives reopening");
            check(AuctionHouse.nextBid(199,0,false)==208&&AuctionHouse.listingFee(0,1,0,100,500)==5,"archive auction arithmetic");
            grant(db,items,"Alice","Alice-Knight","Rarity/Spark of Life",2);
            var beforeRevive=db.worldProfile(a,1,1);long spark=id(beforeRevive,"Rarity/Spark of Life");
            refused(()->town.revive(b,1,1,-1));refused(()->town.revive(a,1,999,-1));refused(()->town.revive(a,1,1,49));
            check(db.worldProfile(a,1,1).equals(beforeRevive),"foreign/missing Sparks, stale authentication and insufficient approval preserve the inventory");
            var revived=town.revive(a,1,1,-1).profile();
            check(revived.items().stream().filter(i->i.id()==spark).findFirst().orElseThrow().count()==1&&revived.energy()==beforeRevive.energy(),"Spark revive consumes exactly one owned unit without charging energy");
            db.currency("Alice","Alice-Knight",a,-1,Currency.ENERGY,100);
            check(town.revive(a,1,1,50).profile().energy()==revived.energy()+50,"native buy-and-use approval charges the archive's 50 energy cost");
            // Stock machine-catalog crafting: the Battle Sprite alchemy machine serves its own recipes without learning them.
            var station=rules.station("scene.1.6837.39");check(station!=null&&!station.recipes().isEmpty(),"the Battle Sprite machine serves a native catalog");
            for(String text:station.recipes())check(rules.recipe(text)!=null,"machine catalog recipe "+text+" resolves");
            var mote=rules.recipe("Sprite/Food/Power Mote");check(mote!=null,"sprite food recipe imported from the stock machine");
            refused(()->town.craft(a,1,1,"Sprite/Food/Power Mote",new long[0],false,false));
            grant(db,items,"Alice","Alice-Knight","Material/Beast Scale",2);grant(db,items,"Alice","Alice-Knight","Mineral/Inventory/Crimsonite",5);
            long scale=id(db.worldProfile(a,1,1),"Material/Beast Scale"),crimsonite=id(db.worldProfile(a,1,1),"Mineral/Inventory/Crimsonite");
            var beforeFood=db.worldProfile(a,1,1);refused(()->town.craft(b,1,1,"Sprite/Food/Power Mote",new long[]{scale,crimsonite},false,false));
            var craftedFood=town.craft(a,1,1,"Sprite/Food/Power Mote",new long[]{scale,crimsonite},false,true);
            check(craftedFood.profile().crowns()==beforeFood.crowns()-mote.cost(items.require("Food/Power Mote").rarity()),"machine-catalog sprite food charges the stock rarity price");
            check(!db.worldProfile(a,1,1).items().stream().anyMatch(i->i.id()==scale||i.id()==crimsonite)&&db.worldProfile(a,1,1).items().stream().anyMatch(i->i.config().equals("Food/Power Mote")),"machine-catalog sprite food consumes its native ingredients exactly once");
            // Reported five-star failure: exact stock recipe, matching material, mineral and cost.
            var star=rules.recipe("Sprite/Food/Divine Star");
            check(star!=null&&star.cost(5)==500&&star.count()==1&&star.ingredients().getFirst().rarity()==5,"Divine Star uses the verified stock five-star recipe");
            grant(db,items,"Alice","Alice-Knight","Material/Silverweave",2);grant(db,items,"Alice","Alice-Knight","Mineral/Inventory/Luminite",20);grant(db,items,"Alice","Alice-Knight","Mineral/Inventory/Luminite",5);
            var starBefore=db.worldProfile(a,1,1);long material=id(starBefore,"Material/Silverweave");
            refused(()->town.craft(a,1,1,star.config(),new long[]{material},false,true));
            check(db.worldProfile(a,1,1).equals(starBefore),"incomplete five-star craft rolls back every resource");
            long[] starIngredients=starBefore.items().stream().filter(i->i.id()==material||i.config().equals("Mineral/Inventory/Luminite")).mapToLong(WorldProfile.Item::id).toArray();
            var madeStar=town.craft(a,1,1,star.config(),starIngredients,false,true).profile();
            check(madeStar.crowns()==starBefore.crowns()-500&&madeStar.items().stream().anyMatch(i->i.config().equals("Food/Divine Star")),"Divine Star crafts through the machine catalogue");
            var brinks=rules.station("scene.2.2354.21");var tokenGoods=rules.goods(brinks.shop(),0,madeStar.worldId());
            check(tokenGoods.size()==128&&tokenGoods.getFirst().tokenCount()==10,"Brinks documented original token stock is bound to the spawned merchant");
            grant(db,items,"Alice","Alice-Knight",tokenGoods.getFirst().token(),10);
            var tokenBefore=db.worldProfile(a,1,1);refused(()->town.shop(b,1,1,brinks,0,Map.of(0,1),Map.of()));
            var tokenPurchase=town.shop(a,1,1,brinks,0,Map.of(0,1),Map.of()).profile();
            check(tokenPurchase.crowns()==tokenBefore.crowns()&&tokenPurchase.items().stream().anyMatch(i->i.config().equals(tokenGoods.getFirst().item())&&i.bound()),"token purchase consumes tokens and binds equipment without charging crowns");
            // Rename failures never spend the ticket or partially publish an identity.
            grant(db,items,"Alice","Alice-Knight","Upgrade/Rename Knight",1);var renameBefore=db.worldProfile(a,1,1);
            refused(()->town.rename(a,1,1,"Bob-Knight"));refused(()->town.rename(a,1,1,"invalid name"));refused(()->town.rename(a,1,999,"Changed-Knight"));
            check(db.worldProfile(a,1,1).equals(renameBefore),"duplicate, invalid and stale-auth renames preserve the ticket and inventory");
            town.rename(a,1,1,"Changed-Knight");alice.refresh();
            check(alice.roster().getFirst().name().equals("Changed-Knight")&&db.worldProfile(a,1,1).items().stream().noneMatch(i->i.config().equals("Upgrade/Rename Knight")),"rename and ticket consumption persist atomically");
            refused(()->town.rename(a,1,1,"Another-Knight"));
            System.out.println("PASS: Town crafting, sprite persistence, auction escrow, mail ownership and rollback");
        }finally{try(var paths=Files.walk(home)){for(var file:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
    }
    private static void grant(Database db,ItemCatalog items,String user,String name,String item,int count)throws IOException{db.grantItems(user,name,-1,-1,items.require(item),count,-1,-1);}
    private static long id(WorldProfile p,String config){return p.items().stream().filter(i->i.config().equals(config)).findFirst().orElseThrow().id();}
    private static Knight choices(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0);}
    private interface Checked{void run()throws Exception;}
    private static void refused(Checked work)throws Exception{try{work.run();throw new AssertionError("Expected refusal");}catch(IOException expected){}}
}
