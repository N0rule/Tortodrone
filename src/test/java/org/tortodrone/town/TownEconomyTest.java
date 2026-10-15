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
            System.out.println("PASS: Town crafting, sprite persistence, auction escrow, mail ownership and rollback");
        }finally{try(var paths=Files.walk(home)){for(var file:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
    }
    private static void grant(Database db,ItemCatalog items,String user,String name,String item,int count)throws IOException{db.grantItems(user,name,-1,-1,items.require(item),count,-1,-1);}
    private static long id(WorldProfile p,String config){return p.items().stream().filter(i->i.config().equals(config)).findFirst().orElseThrow().id();}
    private static Knight choices(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0);}
    private interface Checked{void run()throws Exception;}
    private static void refused(Checked work)throws Exception{try{work.run();throw new AssertionError("Expected refusal");}catch(IOException expected){}}
}
