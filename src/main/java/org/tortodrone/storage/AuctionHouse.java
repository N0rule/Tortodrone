package org.tortodrone.storage;

import java.io.*;
import java.sql.*;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.resources.Json;
import org.tortodrone.town.*;

/** Durable auction escrow and mail settlement, atomically protected by SQLite's write lock. */
public final class AuctionHouse {
    private final Database database;private final ItemCatalog items;private final TownStore town;
    public AuctionHouse(Database database,ItemCatalog items,TownCatalog rules){this.database=database;this.items=items;town=new TownStore(database,items,rules);}
    private static final int[] RARITY_FEES={10,25,50,100,200,500},HOURS={4,12,24,48,72,96,120,144,168};
    private static final float[] MULTIPLIERS={.5f,.6f,.75f,1,0,0,0,0,0,0};
    /** ServerObject mail fees from the archive: a letter costs 25, any attachment adds 75. */
    public static final int MAIL_FEE=25,MAIL_ATTACH_FEE=75;
    private static final int MAX_MAIL_ATTACHMENTS=14;
    public static int listingFee(int rarity,int count,int duration,int bid,int buy){long base=(long)count*RARITY_FEES[Math.clamp(rarity,0,5)];return (int)Math.ceil(Math.max(base,Math.max(wonFee(bid),wonFee(buy/20)))*MULTIPLIERS[duration]);}
    public static int wonFee(int crowns){return Math.max(0,(int)Math.ceil(crowns*.1f));}
    public static int nextBid(int bid,int buy,boolean first){if(first)return bid;int raw=Math.max(1,(int)Math.floor(.05f*bid)),mag=(int)Math.pow(10,(int)Math.log10(raw));int next=(int)Math.min(Integer.MAX_VALUE,(long)bid+Math.max(1,mag*(raw/mag)));return buy==0||buy>next?next:buy;}
    public TownStore.Result operate(int account,int knight,long auth,int method,List<Object> args,boolean staff)throws IOException{
        return operate(account,knight,auth,method,args,staff,Map.of());
    }
    /** Server-owned presence snapshot, scoped to this database. Values are authenticated session
     * revisions, rechecked in the bid transaction; no client can choose the refund destination.
     * A disconnect after the snapshot still retains a committed credit on reconnect. */
    public TownStore.Result operate(int account,int knight,long auth,int method,List<Object> args,boolean staff,Map<Integer,Long> online)throws IOException{
        var presence=Map.copyOf(online);
        return database.write(c->{var p=WorldProfiles.load(c,account,knight,auth);sweep(c);Object result;
            switch(method){
                case 1->{long id=(Long)args.get(0);int amount=(Integer)args.get(1);var listing=require(c,id,p.worldId());
                    if(listing.availableAt()>System.currentTimeMillis())throw new IOException("e.auction_ended");
                    if(listing.seller()==p.worldId()||amount<=0)throw new IOException("m.internal_error");
                    if(listing.ended()||listing.expires()<=System.currentTimeMillis()){result=new AuctionState.Update(listing,"e.auction_ended");break;}
                    int minimum=nextBid(listing.bid(),listing.buy(),listing.bidder()==0);if(amount<minimum||listing.buy()!=0&&amount>listing.buy())throw new IOException("e.bid_insufficient");
                    if(listing.stock()){
                        if(amount!=listing.buy()||listing.featuredRemaining()<1)throw new IOException("e.bid_insufficient");
                        TownStore.pay(c,p,amount,0);
                        try(var st=c.prepareStatement("UPDATE town_auctions SET featured_remaining=featured_remaining-1,ended=CASE WHEN featured_remaining=1 THEN 1 ELSE 0 END WHERE id=? AND ended=0 AND featured_remaining>0")){st.setLong(1,id);if(st.executeUpdate()!=1)throw new IOException("e.auction_ended");}
                        mail(c,id,p.worldId(),compose("s.bid_complete",display(listing.item())),"m.bid_complete",0,listing.item());
                        recordBid(c,id,p.worldId());result=new AuctionState.Update(require(c,id,p.worldId()),null);break;
                    }
                    TownStore.pay(c,p,amount-(listing.bidder()==p.worldId()?listing.bid():0),0);
                    if(listing.bidder()!=0&&listing.bidder()!=p.worldId())refundOutbid(c,listing,presence.get(listing.bidder()));
                    boolean buy=listing.buy()!=0&&amount==listing.buy();try(var s=c.prepareStatement("UPDATE town_auctions SET bid=?,bidder=?,expires=CASE WHEN ? THEN ? ELSE expires END WHERE id=? AND ended=0")){s.setInt(1,amount);s.setInt(2,p.worldId());s.setBoolean(3,buy);s.setLong(4,System.currentTimeMillis());s.setLong(5,id);if(s.executeUpdate()!=1)throw new IOException("e.auction_busy");}
                    try(var s=c.prepareStatement("INSERT INTO town_auction_bids(auction,world_id,has_bid) VALUES(?,?,1) ON CONFLICT(auction,world_id) DO UPDATE SET has_bid=1")){s.setLong(1,id);s.setInt(2,p.worldId());s.executeUpdate();}
                    if(buy)settle(c,id);result=new AuctionState.Update(require(c,id,p.worldId()),null);
                }
                case 2->{long id=(Long)args.get(0);var listing=require(c,id,p.worldId());if(listing.featured()||listing.seller()!=p.worldId()){if(!staff)throw new IOException("m.access_denied");TownStore.requireGameMaster(c,account,auth);}if(listing.ended()){result=new AuctionState.Update(listing,"e.auction_ended");break;}if(listing.bidder()!=0){if(!staff)throw new IOException("e.auction_bid");TownStore.requireGameMaster(c,account,auth);}
                    try(var s=c.prepareStatement("UPDATE town_auctions SET cancelled=1,expires=? WHERE id=? AND ended=0")){s.setLong(1,System.currentTimeMillis());s.setLong(2,id);s.executeUpdate();}settle(c,id);result=new AuctionState.Update(require(c,id,p.worldId()),null);}
                case 3,4->{var all=list(c,p.worldId());result=all.stream().filter(v->method==3?v.seller()==p.worldId():v.participated()||v.watching()).toList();if(method==4)clearOutbids(c,p.worldId());}
                case 5->{var search=(SessionDecoder.AuctionSearch)args.get(0);int page=(Integer)args.get(1);if(page<0||page>100000)throw new IOException("Invalid auction page");var all=new ArrayList<>(list(c,p.worldId(),true,search.featured()).stream().filter(v->!v.ended()&&v.availableAt()<=System.currentTimeMillis()&&v.expires()>System.currentTimeMillis()&&v.featured()==search.featured()).filter(v->search.configs()==null||items.find(v.item().config())!=null&&search.configs().contains(items.find(v.item().config()).stockId())).filter(v->search.group()==null||groupOrdinal(items.find(v.item().config()))==search.group().ordinal()).toList());
                    Comparator<AuctionState.Listing> sort=switch(search.sort()){case 1->AuctionHouse::compareBidUnit;case 2->AuctionHouse::compareBuyUnit;case 3->Comparator.comparingInt(v->v.item().count());case 4->Comparator.comparingInt(v->{var d=items.find(v.item().config());return d==null?0:d.rarity();});case 5->Comparator.comparingInt(v->v.item().variants().size());default->Comparator.comparingLong(AuctionState.Listing::expires);};if(!search.ascending())sort=sort.reversed();all.sort(sort.thenComparingLong(AuctionState.Listing::id));int pages=(all.size()+49)/50,start=Math.min(all.size(),page*50);result=new AuctionState.Page(pages,all.subList(start,Math.min(start+50,all.size())));}
                case 6->{long itemId=(Long)args.get(0);int count=(Integer)args.get(1),bid=(Integer)args.get(2),buy=(Integer)args.get(3),duration=((SessionDecoder.EnumValue)args.get(4)).ordinal();boolean featured=(Boolean)args.get(5);
                    if(duration<0||duration>=10||count<1||bid<1||buy<0||buy!=0&&buy<bid)throw new IOException("Invalid auction listing");
                    if(duration>=4||featured){if(!staff)throw new IOException("m.access_denied");TownStore.requireGameMaster(c,account,auth);}
                    long expires=duration==9?Long.MAX_VALUE:System.currentTimeMillis()+HOURS[duration]*3600000L;
                    var item=TownStore.owned(p,itemId);var d=items.require(item.config());if(count>item.count()||item.bound()||item.permanent()||item.starter()||item.equippedSlot()!=null||d.locked())throw new IOException("Item cannot be auctioned");
                    if(item.expiresAt()!=null&&item.expiresAt()<=expires)throw new IOException("Item expires before the auction ends");
                    int fee=featured?0:listingFee(d.rarity(),count,duration,bid,buy);if(!featured){TownStore.pay(c,p,fee,0);TownStore.consume(c,p,itemId,count);}
                    var held=new WorldProfile.Item(item.id(),item.slot(),item.config(),item.accessory(),item.heat(),null,item.bound(),item.bindOnEquip(),false,count,item.permanent(),item.level(),item.heatProgress(),item.variants(),item.additionalAccessories()).withGift(item.gift()).withExpiration(item.expiresAt());
                    try(var s=c.prepareStatement("INSERT INTO town_auctions(seller,item,start_bid,buy_price,bid,expires,fee,created,featured_total,featured_remaining) VALUES(?,?,?,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){if(featured)s.setNull(1,Types.INTEGER);else s.setInt(1,p.worldId());s.setString(2,encode(held));s.setInt(3,bid);s.setInt(4,buy);s.setInt(5,bid);s.setLong(6,expires);s.setInt(7,fee);s.setLong(8,System.currentTimeMillis());s.setInt(9,featured?1:0);s.setInt(10,featured?1:0);s.executeUpdate();try(var keys=s.getGeneratedKeys()){keys.next();result=require(c,keys.getLong(1),p.worldId());}}
                }
                case 7->{long id=(Long)args.get(0);require(c,id,p.worldId());try(var s=c.prepareStatement("INSERT INTO town_auction_bids(auction,world_id,watching) VALUES(?,?,1) ON CONFLICT(auction,world_id) DO UPDATE SET watching=1-watching")){s.setLong(1,id);s.setInt(2,p.worldId());s.executeUpdate();}result=new AuctionState.Update(require(c,id,p.worldId()),null);}
                default->throw new IOException("Unknown auction operation");
            }TownStore.touch(c,p.worldId());return new TownStore.Result(WorldProfiles.load(c,account,knight,auth),result);
        });
    }
    private static String outbidPrefix(int world){return "auction.outbid."+world+".";}
    private void refundOutbid(Connection c,AuctionState.Listing listing,Long sessionAuth)throws SQLException,IOException {
        boolean direct=false;
        if(sessionAuth!=null)try(var s=c.prepareStatement("SELECT a.auth_revision,w.crowns FROM world_characters w JOIN accounts a ON a.id=w.account_id WHERE w.world_id=?")) {
            s.setInt(1,listing.bidder());try(var r=s.executeQuery()){direct=r.next()&&r.getLong(1)==sessionAuth&&(long)r.getInt(2)+listing.bid()<=Integer.MAX_VALUE;}
        }
        if(!direct){mail(c,listing.id(),listing.bidder(),compose("s.bid_outbid",display(listing.item())),"m.bid_outbid",listing.bid(),null);return;}
        TownStore.credit(c,listing.bidder(),listing.bid(),0);
        try(var s=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            s.setString(1,outbidPrefix(listing.bidder())+listing.id());s.setInt(2,listing.bid());s.executeUpdate();
        }
    }
    static List<AuctionState.Outbid> loadOutbids(Connection c,int world)throws SQLException,IOException {
        var result=new ArrayList<AuctionState.Outbid>();String prefix=outbidPrefix(world);
        try(var s=c.prepareStatement("SELECT a.id,a.item,m.value FROM metadata m JOIN town_auctions a ON a.id=CAST(substr(m.key,?) AS INTEGER) WHERE m.key LIKE ? ORDER BY a.id")) {
            s.setInt(1,prefix.length()+1);s.setString(2,prefix+"%");
            try(var r=s.executeQuery()){while(r.next())result.add(new AuctionState.Outbid(r.getLong(1),decode(r.getString(2)).config(),r.getInt(3)));}
        }
        return List.copyOf(result);
    }
    private static void clearOutbids(Connection c,int world)throws SQLException {
        try(var s=c.prepareStatement("DELETE FROM metadata WHERE key LIKE ?")){s.setString(1,outbidPrefix(world)+"%");s.executeUpdate();}
    }
    static void cleanOutbids(Connection c)throws SQLException {
        try(var s=c.createStatement()){s.executeUpdate("DELETE FROM metadata WHERE key LIKE 'auction.outbid.%' AND key NOT IN (SELECT 'auction.outbid.'||w.world_id||'.'||a.id FROM world_characters w JOIN town_auction_bids b ON b.world_id=w.world_id JOIN town_auctions a ON a.id=b.auction)");}
    }
    private static int compareBidUnit(AuctionState.Listing a,AuctionState.Listing b){return Long.compare((long)a.bid()*b.item().count(),(long)b.bid()*a.item().count());}
    private static int compareBuyUnit(AuctionState.Listing a,AuctionState.Listing b){return Long.compare((long)a.buy()*b.item().count(),(long)b.buy()*a.item().count());}
    private static int groupOrdinal(ItemCatalog.Entry d){if(d==null)return 22;if(d.auctionGroup()>=0)return d.auctionGroup();return switch(d.type()){case "Prop","Actor","KnightStatue","Placeable","ModelProp","SpecialProp"->28;case "Dungeon"->26;case "Sword"->2;case "Gun","Handgun","SwingingHandgun"->3;case "Bomb","BlastNetworkBomb"->4;case "Helm"->5;case "Armor"->6;case "Shield","LockdownShield"->7;case "Trinket"->8;case "HelmCostume"->9;case "ArmorCostume"->10;case "ShieldCostume"->11;case "Rarity"->12;case "Mineral"->13;case "Material","Craft"->14;case "Recipe"->15;case "Token"->16;case "Upgrade","Usable","AnimatedAction","Action","Lockbox","Color","Eye","GiftBox","Lift","WrappingPaper","Height","GuildBond","Pack","SpawnActor","RenameGuild","RenameKnight"->17;case "SpriteEgg","Harness"->18;case "AccessoryTicket"->19;case "Key","Ticket"->20;case "GuildHallGlobalTicket","VariantTicket"->21;case "Artifact","SanctuaryArtifact","TokenArtifact"->23;case "Capsule"->24;case "Vial"->25;default->22;};}
    private static List<AuctionState.Listing> list(Connection c,int world)throws SQLException,IOException{return list(c,world,false,false);}
    private static List<AuctionState.Listing> list(Connection c,int world,boolean active,boolean featured)throws SQLException,IOException{var result=new ArrayList<AuctionState.Listing>();try(var s=c.prepareStatement("SELECT a.*,EXISTS(SELECT 1 FROM town_auction_bids b WHERE b.auction=a.id AND b.world_id=? AND b.has_bid=1) participated,EXISTS(SELECT 1 FROM town_auction_bids w WHERE w.auction=a.id AND w.world_id=? AND w.watching=1) watching FROM town_auctions a "+(active?"WHERE a.ended=0 AND a.available_at<=? AND a.expires>? AND (a.featured_total>0)=? ":"")+"ORDER BY a.id DESC")){s.setInt(1,world);s.setInt(2,world);if(active){long now=System.currentTimeMillis();s.setLong(3,now);s.setLong(4,now);s.setBoolean(5,featured);}try(var r=s.executeQuery()){while(r.next())result.add(row(r,world));}}return result;}
    private static AuctionState.Listing require(Connection c,long id,int world)throws SQLException,IOException{try(var s=c.prepareStatement("SELECT a.*,EXISTS(SELECT 1 FROM town_auction_bids b WHERE b.auction=a.id AND b.world_id=? AND b.has_bid=1) participated,EXISTS(SELECT 1 FROM town_auction_bids w WHERE w.auction=a.id AND w.world_id=? AND w.watching=1) watching FROM town_auctions a WHERE a.id=?")){s.setInt(1,world);s.setInt(2,world);s.setLong(3,id);try(var r=s.executeQuery()){if(!r.next())throw new IOException("Unknown auction");return row(r,world);}}}
    private static AuctionState.Listing row(ResultSet r,int world)throws SQLException,IOException{return new AuctionState.Listing(r.getLong("id"),r.getInt("seller"),decode(r.getString("item")),r.getInt("bid"),r.getInt("buy_price"),r.getInt("bidder"),r.getLong("expires"),r.getBoolean("ended"),r.getBoolean("cancelled"),r.getInt("seller")==world,r.getBoolean("participated"),r.getInt("fee"),r.getBoolean("watching"),r.getInt("featured_total"),r.getInt("featured_remaining"),r.getLong("created"),r.getLong("available_at"));}
    private void sweep(Connection c)throws SQLException,IOException{
        var expired=new ArrayList<Long>();try(var s=c.prepareStatement("SELECT id FROM town_auctions WHERE ended=0 AND expires<=? ORDER BY id LIMIT 100")){s.setLong(1,System.currentTimeMillis());try(var r=s.executeQuery()){while(r.next())expired.add(r.getLong(1));}}
        for(long id:expired)settle(c,id);
    }
    private void settle(Connection c,long id)throws SQLException,IOException{
        var a=require(c,id,0);if(a.ended()||a.expires()>System.currentTimeMillis())return;
        try(var s=c.prepareStatement("UPDATE town_auctions SET ended=1 WHERE id=? AND ended=0")){s.setLong(1,id);if(s.executeUpdate()!=1)return;}
        if(!a.cancelled()&&a.bidder()!=0){
            if(a.seller()!=0){
                long payout=(long)a.bid()-wonFee(a.bid())+a.fee();if(payout>Integer.MAX_VALUE)throw new IOException("Auction payout exceeds limit");
                mail(c,id,a.seller(),compose("s.auction_complete",display(a.item())),compose("m.auction_complete","~"+a.bid(),"~"+a.fee(),"~"+wonFee(a.bid())),(int)payout,null);
            }
            mail(c,id,a.bidder(),compose("s.bid_complete",display(a.item())),"m.bid_complete",0,a.item());
        }else{
            if(a.seller()!=0)mail(c,id,a.seller(),compose(a.cancelled()?"s.auction_cancelled":"s.auction_incomplete",display(a.item())),a.cancelled()?"m.auction_cancelled":"m.auction_incomplete",0,a.item());
            if(a.cancelled()&&a.bidder()!=0)mail(c,id,a.bidder(),compose("s.bid_cancelled",display(a.item())),"m.bid_cancelled",a.bid(),null);
        }
    }
    private static void recordBid(Connection c,long id,int world)throws SQLException{
        try(var s=c.prepareStatement("INSERT INTO town_auction_bids(auction,world_id,has_bid) VALUES(?,?,1) ON CONFLICT(auction,world_id) DO UPDATE SET has_bid=1")){s.setLong(1,id);s.setInt(2,world);s.executeUpdate();}
    }
    /** Trusted local administration only; never called by a game-client invocation. */
    public AuctionState.Listing createFeatured(String config,int quantity,int bid,int buy,int duration,int stock)throws IOException{
        if(duration<0||duration>=HOURS.length)throw new IOException("Invalid featured duration");
        long start=System.currentTimeMillis();return scheduleFeatured(config,quantity,bid,buy,stock,start,start+HOURS[duration]*3600000L);
    }
    /** Tortodrone administration extension; no new client packet or official rotation is implied. */
    public AuctionState.Listing scheduleFeatured(String config,int quantity,int bid,int buy,int stock,long starts,long expires)throws IOException{
        var def=items.require(config);
        if(!def.grantable()||quantity<1||quantity>9999||!def.stackable()&&quantity!=1||starts<0||expires<=starts||stock<1||stock>32767||bid<1||buy<0||buy!=0&&buy<bid||stock>1&&(buy==0||bid!=buy))throw new IOException("Invalid featured item, quantity, price, duration or stock");
        return database.write(c->{
            var held=new WorldProfile.Item(0,def.slot(),def.config(),null,0,null,def.locked(),def.bindOnEquip(),false,quantity,def.permanent(),0,0,List.of(),List.of());
            try(var s=c.prepareStatement("INSERT INTO town_auctions(seller,item,start_bid,buy_price,bid,expires,created,featured_total,featured_remaining,available_at) VALUES(NULL,?,?,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){
                s.setString(1,encode(held));s.setInt(2,bid);s.setInt(3,buy);s.setInt(4,bid);s.setLong(5,expires);s.setLong(6,System.currentTimeMillis());s.setInt(7,stock);s.setInt(8,stock);s.setLong(9,starts);s.executeUpdate();
                try(var keys=s.getGeneratedKeys()){keys.next();return require(c,keys.getLong(1),0);}
            }
        });
    }
    public record Overview(AuctionState.Listing listing,String seller,String bidder) { }
    public List<Overview> overview()throws IOException{
        return database.read(c->{c.setAutoCommit(false);var names=new HashMap<Integer,String>();
            try(var s=c.createStatement();var r=s.executeQuery("SELECT w.world_id,k.name FROM world_characters w JOIN knights k ON k.account_id=w.account_id AND k.id=w.knight_id")){while(r.next())names.put(r.getInt(1),r.getString(2));}
            var rows=list(c,0).stream().map(a->new Overview(a,a.featured()?"System":names.getOrDefault(a.seller(),"#"+a.seller()),names.getOrDefault(a.bidder(),a.bidder()==0?"—":"#"+a.bidder()))).toList();c.commit();return rows;});
    }
    public AuctionState.Listing cancel(long id)throws IOException{
        return database.write(c->{var a=require(c,id,0);if(!a.ended()){
            try(var s=c.prepareStatement("UPDATE town_auctions SET cancelled=1,expires=? WHERE id=? AND ended=0")){s.setLong(1,System.currentTimeMillis());s.setLong(2,id);s.executeUpdate();}settle(c,id);
        }return require(c,id,0);});
    }
    public void settleExpired()throws IOException{database.write(c->{sweep(c);return null;});}
    public record Delivery(long id,int recipient,int crowns,boolean item,boolean claimed) { }
    public List<Delivery> deliveries(long auction)throws IOException{
        return database.read(c->{var result=new ArrayList<Delivery>();try(var s=c.prepareStatement("SELECT id,world_id,crowns,item IS NOT NULL,claimed FROM town_mail WHERE auction=? ORDER BY id")){s.setLong(1,auction);try(var r=s.executeQuery()){while(r.next())result.add(new Delivery(r.getLong(1),r.getInt(2),r.getInt(3),r.getBoolean(4),r.getBoolean(5)));}}return List.copyOf(result);});
    }
    private String display(WorldProfile.Item item){var definition=items.find(item.config());return definition==null?"~"+item.config().substring(item.config().lastIndexOf('/')+1):"%item:"+definition.nameKey();}
    /** One SYSTEM-type uplink letter from a native SystemSender, inside the caller's transaction;
     * the caller owns the knight's single revision increment. */
    static void systemMail(Connection c,int world,int senderOrdinal,String subject,String body,List<WorldProfile.Item> items)throws SQLException,IOException{
        if(senderOrdinal<1)throw new IOException("Invalid system sender");
        try(var s=c.prepareStatement("INSERT INTO town_mail(world_id,sent,subject,body,attachments,sender,mail_type) VALUES(?,?,?,?,?,?,3)")){
            s.setInt(1,world);s.setLong(2,System.currentTimeMillis());s.setString(3,"%uplink:"+subject);s.setString(4,"%uplink:"+body);
            s.setString(5,items.isEmpty()?null:encodeItems(items));s.setInt(6,-senderOrdinal);s.executeUpdate();
        }
    }
    static String composeMessage(String key,String... args){return compose(key,args);}
    private static String compose(String key,String... args){var result=new StringBuilder(key);for(String arg:args)result.append('|').append(arg.replace("\\","\\\\").replace("|","\\!"));return result.toString();}
    private static void mail(Connection c,long auction,int world,String subject,String body,int crowns,WorldProfile.Item item)throws SQLException,IOException{
        try(var s=c.prepareStatement("INSERT INTO town_mail(world_id,sent,subject,body,crowns,item,auction) VALUES(?,?,?,?,?,?,?)")){s.setInt(1,world);s.setLong(2,System.currentTimeMillis());s.setString(3,"%uplink:"+subject);s.setString(4,"%uplink:"+body);s.setInt(5,crowns);s.setString(6,item==null?null:encode(item));s.setLong(7,auction);s.executeUpdate();}TownStore.touch(c,world);
    }
    public TownStore.Result mail(int account,int knight,long auth,int method,List<Object> args)throws IOException{
        return database.write(c->{var p=WorldProfiles.load(c,account,knight,auth);sweep(c);Object result=null;
            if(method==5)deliver(c,p,(SessionDecoder.SentMail)args.getFirst());
            else if(method==6){long[] ids=(long[])args.getFirst();var messages=loadMail(c,p.worldId());result=ids==null?messages:messages.stream().filter(m->java.util.stream.LongStream.of(ids).anyMatch(id->id==m.id())).toList();}
            else if(method==11){long[] ids=(long[])args.getFirst();for(long id:ids)try(var s=c.prepareStatement("UPDATE town_mail SET is_read=1 WHERE id=? AND world_id=?")){s.setLong(1,id);s.setInt(2,p.worldId());s.executeUpdate();}}
            else{long id=(Long)args.getFirst();var mail=loadMail(c,p.worldId()).stream().filter(m->m.id()==id).findFirst().orElseThrow(()->new IOException("Unknown owned mail"));
                if(method==1||method==2)claim(c,p,mail);
                if(method==1||method==4){
                    if(method==4&&!mail.claimed()&&!mail.friendRequest()&&hasAttachment(mail))throw new IOException("Accept the attachment before deleting this mail");
                    try(var s=c.prepareStatement("DELETE FROM town_mail WHERE id=? AND world_id=?")){s.setLong(1,id);s.setInt(2,p.worldId());s.executeUpdate();}FriendStore.deletedMail(c,id);
                }
            }TownStore.touch(c,p.worldId());return new TownStore.Result(WorldProfiles.load(c,account,knight,auth),result);});
    }
    private static boolean hasAttachment(AuctionState.Mail mail){return !mail.items().isEmpty()||mail.crowns()>0||mail.energy()>0;}
    private void claim(Connection c,WorldProfile p,AuctionState.Mail mail)throws SQLException,IOException {
        if(mail.claimed())throw new IOException("%uplink:b.accepted");
        if(mail.friendRequest())FriendStore.accept(c,p.worldId(),mail);
        TownStore.credit(c,p.worldId(),mail.crowns(),mail.energy());
        long now=System.currentTimeMillis();
        for(var item:mail.items())if(item!=null&&!item.expired(now))grant(c,p,item);
        try(var s=c.prepareStatement("UPDATE town_mail SET claimed=1,is_read=1 WHERE id=? AND world_id=? AND claimed=0")){s.setLong(1,mail.id());s.setInt(2,p.worldId());if(s.executeUpdate()!=1)throw new IOException("Attachment changed");}
    }
    private void grant(Connection c,WorldProfile p,WorldProfile.Item i)throws SQLException,IOException {
        long created=town.create(c,p,i.config(),i.count(),i.bound(),i.level(),i.heatProgress(),i.variants());
        InventoryExpiry.save(c,created,i.expiresAt());
        try(var s=c.prepareStatement("UPDATE inventory_items SET accessory=?,accessories=? WHERE item_id=? AND world_id=?")){s.setString(1,i.accessory());s.setString(2,Json.stringify(i.additionalAccessories()));s.setLong(3,created);s.setInt(4,p.worldId());s.executeUpdate();}
        GiftStore.save(c,created,i.gift());
    }
    private record Recipient(int world,boolean gameMaster) { }
    private static Recipient recipient(Connection c,String name)throws SQLException,IOException {
        if(CharacterStore.nameError(name)!=null)throw new IOException("m.unknown_knight");
        try(var s=c.prepareStatement("SELECT w.world_id,a.game_master FROM world_characters w JOIN accounts a ON a.id=w.account_id JOIN knights k ON k.account_id=w.account_id AND k.id=w.knight_id WHERE k.name_key=?")){
            s.setString(1,name.toLowerCase(Locale.ROOT));
            try(var r=s.executeQuery()){if(!r.next())throw new IOException("m.unknown_knight");return new Recipient(r.getInt(1),r.getBoolean(2));}
        }
    }
    /** Uplink method 5: validate text, fee and item ownership, then escrow everything in one write. */
    private void deliver(Connection c,WorldProfile p,SessionDecoder.SentMail sent)throws SQLException,IOException {
        if(sent==null||sent.subject()==null||sent.body()==null||sent.subject().length()>50||sent.body().length()>8096||sent.crowns()<0||sent.energy()<0)
            throw new IOException("Invalid mail text or attachment");
        long units=0;
        for(var count:sent.items().values()){if(count==null||count<1)throw new IOException("Invalid mail attachment");units+=count;}
        if(units>MAX_MAIL_ATTACHMENTS)throw new IOException("A mail can carry at most 14 item units");
        var target=recipient(c,sent.recipient());
        if(target.gameMaster){
            // Stock first-market behaviour: a GM badge bounces the letter back without charging.
            try(var s=c.prepareStatement("INSERT INTO town_mail(world_id,sent,subject,body,sender,mail_type) VALUES(?,?,?,?,?,3)")){
                s.setInt(1,p.worldId());s.setLong(2,System.currentTimeMillis());
                s.setString(3,"%uplink:"+compose("s.bounced",sent.recipient(),sent.subject()));
                s.setString(4,"%uplink:"+compose("m.bounced",sent.body()));s.setInt(5,-2);s.executeUpdate();
            }
            TownStore.touch(c,p.worldId());return;
        }
        boolean attached=!sent.items().isEmpty()||sent.crowns()>0||sent.energy()>0;
        long cost=(long)(MAIL_FEE+(attached?MAIL_ATTACH_FEE:0))+sent.crowns();
        if(cost>Integer.MAX_VALUE)throw new Database.Refused("m.nsf_crowns");
        if(p.crowns()<cost)throw new Database.Refused("m.nsf_crowns");
        if(p.energy()<sent.energy())throw new Database.Refused("m.nsf_energy");
        TradeTransfer.validate(p,sent.items(),0,0,items);
        var held=new ArrayList<WorldProfile.Item>();
        for(var entry:sent.items().entrySet()){
            var item=TownStore.owned(p,entry.getKey());int count=entry.getValue();
            held.add(new WorldProfile.Item(item.id(),item.slot(),item.config(),item.accessory(),item.heat(),null,
                item.bound(),item.bindOnEquip(),false,count,item.permanent(),item.level(),item.heatProgress(),
                item.variants(),item.additionalAccessories()).withGift(item.gift()).withExpiration(item.expiresAt()));
        }
        TownStore.pay(c,p,(int)cost,sent.energy());
        for(var entry:sent.items().entrySet())TownStore.consume(c,p,entry.getKey(),entry.getValue());
        try(var s=c.prepareStatement("INSERT INTO town_mail(world_id,sent,subject,body,crowns,energy,attachments,sender,mail_type) VALUES(?,?,?,?,?,?,?,?,4)")){
            s.setInt(1,target.world());s.setLong(2,System.currentTimeMillis());s.setString(3,sent.subject());s.setString(4,sent.body());
            s.setInt(5,sent.crowns());s.setInt(6,sent.energy());s.setString(7,held.isEmpty()?null:encodeItems(held));
            s.setInt(8,p.worldId());s.executeUpdate();
        }
        TownStore.touch(c,target.world());
    }
    static List<AuctionState.Mail> loadMail(Connection c,int world)throws SQLException,IOException {
        var messages=new ArrayList<AuctionState.Mail>();
        try(var s=c.prepareStatement("SELECT m.*,r.value AS friend_world,k.name AS friend_name,s.name AS sender_name FROM town_mail m LEFT JOIN metadata r ON r.key='social.request.'||m.id LEFT JOIN world_characters fw ON fw.world_id=r.value LEFT JOIN knights k ON k.account_id=fw.account_id AND k.id=fw.knight_id LEFT JOIN world_characters sw ON sw.world_id=m.sender LEFT JOIN knights s ON s.account_id=sw.account_id AND s.id=sw.knight_id WHERE m.world_id=? ORDER BY m.sent DESC,m.id DESC LIMIT 500")) {
            s.setInt(1,world);try(var r=s.executeQuery()){while(r.next()){
                var held=new ArrayList<WorldProfile.Item>();
                if(r.getString("item")!=null)held.add(decode(r.getString("item")));
                held.addAll(decodeItems(r.getString("attachments")));
                messages.add(new AuctionState.Mail(r.getLong("id"),world,r.getLong("sent"),r.getString("subject"),r.getString("body"),
                    r.getInt("crowns"),r.getInt("energy"),held,r.getBoolean("claimed"),r.getBoolean("is_read"),
                    r.getInt("friend_world"),r.getString("friend_name"),r.getInt("sender"),r.getInt("mail_type"),r.getString("sender_name")));
            }}
        }
        return List.copyOf(messages);
    }
    private static String encodeItems(List<WorldProfile.Item> items)throws IOException {
        var encoded=new ArrayList<String>(items.size());
        for(var item:items)encoded.add(encode(item));
        return Json.stringify(encoded);
    }
    @SuppressWarnings("unchecked") private static List<WorldProfile.Item> decodeItems(String json)throws IOException {
        if(json==null)return List.of();
        try{
            var encoded=(List<Object>)Json.parse(json);var items=new ArrayList<WorldProfile.Item>(encoded.size());
            for(var value:encoded){if(!(value instanceof String text))throw new IOException("Invalid mail attachment");items.add(decode(text));}
            return List.copyOf(items);
        }catch(RuntimeException bad){throw new IOException("Invalid mail attachment",bad);}
    }
    static String encode(WorldProfile.Item i)throws IOException{var m=new TreeMap<String,Object>();m.put("id",i.id());m.put("expiresAt",i.expiresAt());m.put("gift",i.gift()==null?null:GiftStore.encode(i.gift()));m.put("config",i.config());m.put("slot",i.slot().name());m.put("accessory",i.accessory());m.put("heat",i.heat());m.put("count",i.count());m.put("bound",i.bound());m.put("bindOnEquip",i.bindOnEquip());m.put("permanent",i.permanent());m.put("level",i.level());m.put("progress",i.heatProgress());m.put("accessories",i.additionalAccessories());m.put("variants",i.variants().stream().map(StarterCatalog.Model::name).toList());return Json.stringify(m);}
    @SuppressWarnings("unchecked") static WorldProfile.Item decode(String json)throws IOException{try{var m=(Map<String,Object>)Json.parse(json);return new WorldProfile.Item(((Number)m.getOrDefault("id",0)).longValue(),WorldProfile.Slot.valueOf((String)m.get("slot")),(String)m.get("config"),(String)m.get("accessory"),((Number)m.get("heat")).intValue(),null,(Boolean)m.get("bound"),(Boolean)m.get("bindOnEquip"),false,((Number)m.get("count")).intValue(),(Boolean)m.get("permanent"),((Number)m.get("level")).intValue(),((Number)m.get("progress")).floatValue(),((List<String>)m.get("variants")).stream().map(v->new StarterCatalog.Model(v,Map.of())).toList(),(List<String>)m.getOrDefault("accessories",List.of())).withGift(m.get("gift")==null?null:GiftStore.decode((String)m.get("gift"))).withExpiration(m.get("expiresAt")==null?null:((Number)m.get("expiresAt")).longValue());}catch(RuntimeException bad){throw new IOException("Invalid escrow item",bad);}}
}
