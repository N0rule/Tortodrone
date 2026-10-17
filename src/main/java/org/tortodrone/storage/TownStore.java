package org.tortodrone.storage;

import java.io.*;
import java.sql.*;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.resources.Json;
import org.tortodrone.town.*;

/** Town inventory/progression mutations share the existing authenticated SQLite write transaction. */
public final class TownStore {
    private final Database database;
    private final ItemCatalog items;
    private final TownCatalog rules;
    public TownStore(Database database,ItemCatalog items,TownCatalog rules){this.database=database;this.items=items;this.rules=rules;}
    static void initialize(Connection c)throws SQLException{
        GiftStore.initialize(c);
        DepotStore.initialize(c);
        EnergyExchange.initialize(c);
        try(var s=c.createStatement()){
            s.execute("ALTER TABLE inventory_items ADD COLUMN level INTEGER NOT NULL DEFAULT 0 CHECK(level BETWEEN 0 AND 99)");
            s.execute("ALTER TABLE inventory_items ADD COLUMN heat_progress REAL NOT NULL DEFAULT 0 CHECK(heat_progress BETWEEN 0 AND 1)");
            s.execute("ALTER TABLE inventory_items ADD COLUMN accessories TEXT NOT NULL DEFAULT '[]'");
            s.execute("ALTER TABLE inventory_items ADD COLUMN variants TEXT NOT NULL DEFAULT '[]'");
            s.execute("CREATE TABLE town_consumed_starters(world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE,slot TEXT NOT NULL,PRIMARY KEY(world_id,slot))");
            s.execute("CREATE TABLE town_purchases(world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE,catalog TEXT NOT NULL,item TEXT NOT NULL,cycle INTEGER NOT NULL,purchased INTEGER NOT NULL,PRIMARY KEY(world_id,catalog,item,cycle))");
            s.execute("CREATE TABLE town_recipes(id INTEGER PRIMARY KEY AUTOINCREMENT,world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE,config TEXT NOT NULL,crafted INTEGER NOT NULL DEFAULT 0,expires INTEGER NOT NULL DEFAULT 0,UNIQUE(world_id,config))");
            s.execute("CREATE TABLE town_sprites(id INTEGER PRIMARY KEY AUTOINCREMENT,world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE,config TEXT NOT NULL,name TEXT,level INTEGER NOT NULL DEFAULT 0,heat REAL NOT NULL DEFAULT 0,last_full INTEGER,full_stamp INTEGER NOT NULL DEFAULT 0,perk_level INTEGER NOT NULL DEFAULT 0,perks TEXT NOT NULL DEFAULT '[]',perk INTEGER,powers TEXT NOT NULL DEFAULT '[0,0,0,0,0,0,0,0,0]',ultimates TEXT NOT NULL DEFAULT '[0,0,0]',harness INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE town_harnesses(id INTEGER PRIMARY KEY AUTOINCREMENT,world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE,config TEXT NOT NULL)");
            s.execute("CREATE TABLE town_equipped(world_id INTEGER PRIMARY KEY REFERENCES world_characters(world_id) ON DELETE CASCADE,sprite INTEGER NOT NULL DEFAULT 0)");
            AuctionSchema.create(c);
            s.execute("CREATE TABLE town_mail(id INTEGER PRIMARY KEY AUTOINCREMENT,world_id INTEGER NOT NULL REFERENCES world_characters(world_id) ON DELETE CASCADE,sent INTEGER NOT NULL,subject TEXT NOT NULL,body TEXT NOT NULL,crowns INTEGER NOT NULL DEFAULT 0,item TEXT,claimed INTEGER NOT NULL DEFAULT 0,is_read INTEGER NOT NULL DEFAULT 0,auction INTEGER REFERENCES town_auctions(id))");
            s.execute("CREATE TABLE town_auction_bids(auction INTEGER NOT NULL REFERENCES town_auctions(id),world_id INTEGER NOT NULL REFERENCES world_characters(world_id),watching INTEGER NOT NULL DEFAULT 0,has_bid INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(auction,world_id))");
            knightRenameClock(c);
        }
    }
    /** Stored thirty-day rename clock; 0 means the knight has never been renamed. Idempotent:
     *  databases created at schema 14 already carry the column, so the 13&rarr;14 upgrade and
     *  fresh initialization share one guarded ALTER. */
    static void knightRenameClock(Connection c)throws SQLException{
        try(var r=c.createStatement().executeQuery("SELECT 1 FROM pragma_table_info('knights') WHERE name='last_renamed'")){
            if(r.next())return;
        }
        try(var s=c.prepareStatement("ALTER TABLE knights ADD COLUMN last_renamed INTEGER NOT NULL DEFAULT 0")){s.executeUpdate();}
    }
    public static final long RENAME_INTERVAL=30L*24*60*60*1000;
    /** Stock canRename: the window only opens inside the thirty-day rename interval. */
    public void canRename(int account,int knight,long auth)throws IOException {
        database.read(c->{checkRenamable(c,account,knight,auth);return null;});
    }
    private static void checkRenamable(Connection c,int account,int knight,long auth)throws SQLException,IOException {
        WorldProfiles.load(c,account,knight,auth);
        long last;try(var s=c.prepareStatement("SELECT last_renamed FROM knights WHERE account_id=? AND id=?")){s.setInt(1,account);s.setInt(2,knight);try(var r=s.executeQuery()){if(!r.next())throw new IOException("m.internal_error");last=r.getLong(1);}}
        if(last>0&&System.currentTimeMillis()-last<RENAME_INTERVAL)throw new IOException("m.rename_too_soon");
    }
    /** The rename ticket and globally unique identity commit in the same authenticated write. */
    public Result rename(int account,int knight,long auth,String name)throws IOException {
        String error=CharacterStore.nameError(name);if(error!=null)throw new IOException(error);
        return change(account,knight,auth,(c,p)->{
            checkRenamable(c,account,knight,auth);
            var ticket=p.items().stream().filter(i->i.config().equals("Upgrade/Rename Knight")).findFirst().orElseThrow(()->new IOException("m.no_rename_item"));
            try(var st=c.prepareStatement("UPDATE knights SET name=?,name_key=?,last_renamed=? WHERE account_id=? AND id=? AND name_key<>?")){
                st.setString(1,name);st.setString(2,name.toLowerCase(Locale.ROOT));st.setLong(3,System.currentTimeMillis());st.setInt(4,account);st.setInt(5,knight);st.setString(6,name.toLowerCase(Locale.ROOT));
                if(st.executeUpdate()!=1)throw new IOException("Choose a different knight name");
            }
            consume(c,p,ticket.id(),1);
            try(var st=c.prepareStatement("UPDATE accounts SET revision=revision+1 WHERE id=?")){st.setInt(1,account);st.executeUpdate();}
            return name;
        });
    }
    static TownState load(Connection c,int world)throws SQLException,IOException{
        var recipes=new ArrayList<TownState.LearnedRecipe>();var sprites=new ArrayList<TownState.Sprite>();var harnesses=new ArrayList<TownState.Harness>();int equipped=0;
        try(var s=c.prepareStatement("SELECT * FROM town_recipes WHERE world_id=? ORDER BY id")){s.setInt(1,world);try(var r=s.executeQuery()){while(r.next())recipes.add(new TownState.LearnedRecipe(r.getLong("id"),r.getString("config"),r.getInt("crafted"),r.getLong("expires")));}}
        try(var s=c.prepareStatement("SELECT * FROM town_sprites WHERE world_id=? ORDER BY id")){s.setInt(1,world);try(var r=s.executeQuery()){while(r.next())sprites.add(new TownState.Sprite(r.getInt("id"),r.getString("config"),r.getString("name"),r.getInt("level"),r.getFloat("heat"),r.getObject("last_full")==null?null:r.getLong("last_full"),r.getLong("full_stamp"),r.getInt("perk_level"),new HashSet<>(numbers(r.getString("perks"))),(Integer)r.getObject("perk"),numbers(r.getString("powers")),numbers(r.getString("ultimates")),r.getLong("harness")));}}
        try(var s=c.prepareStatement("SELECT * FROM town_harnesses WHERE world_id=? ORDER BY id")){s.setInt(1,world);try(var r=s.executeQuery()){while(r.next())harnesses.add(new TownState.Harness(r.getLong("id"),r.getString("config")));}}
        try(var s=c.prepareStatement("SELECT sprite FROM town_equipped WHERE world_id=?")){s.setInt(1,world);try(var r=s.executeQuery()){if(r.next())equipped=r.getInt(1);}}
        return new TownState(recipes,sprites,equipped,harnesses,AuctionHouse.loadMail(c,world));
    }
    private static List<Integer> numbers(String json)throws IOException{return ((List<?>)Json.parse(json)).stream().map(v->((Number)v).intValue()).toList();}
    static List<String> strings(String json)throws IOException{return ((List<?>)Json.parse(json)).stream().map(v->(String)v).toList();}
    static List<StarterCatalog.Model> variants(String json)throws IOException{var result=new ArrayList<StarterCatalog.Model>();for(var v:(List<?>)Json.parse(json))result.add(new StarterCatalog.Model((String)v,Map.of()));return List.copyOf(result);}
    public record Result(WorldProfile profile,Object result) { }
    public static final int SPARK_COST=50;
    /** Official ItemMarshaller.deleteItem: one owned, non-permanent, unequipped item goes whole. */
    public Result trash(int account,int knight,long auth,long item)throws IOException {
        return change(account,knight,auth,(c,p)->{
            var held=owned(p,item);
            if(held.permanent()||held.equippedSlot()!=null)throw new IOException("m.internal_error");
            consume(c,p,item,held.count());
            return null;
        });
    }
    /** Native PartyMarshaller.revive: -1 spends one owned Spark; a positive approval buys its use. */
    public Result revive(int account,int knight,long auth,int approvedCost)throws IOException{
        if(approvedCost < -1)throw new IOException("m.costupdate");
        return change(account,knight,auth,(c,p)->{
            if(approvedCost<0){
                var spark=p.items().stream().filter(i->i.config().equals("Rarity/Spark of Life")&&i.count()>0).findFirst().orElseThrow(()->new IOException("m.costupdate"));
                consume(c,p,spark.id(),1);
            }else{
                if(approvedCost<SPARK_COST)throw new IOException("m.costupdate");
                pay(c,p,0,SPARK_COST);
            }
            return null;
        });
    }
    @FunctionalInterface private interface Mutation{Object run(Connection c,WorldProfile profile)throws SQLException,IOException;}
    private Result change(int account,int knight,long auth,Mutation mutation)throws IOException{
        return database.write(c->{var profile=WorldProfiles.load(c,account,knight,auth);Object result=mutation.run(c,profile);touch(c,profile.worldId());return new Result(WorldProfiles.load(c,account,knight,auth),result);});
    }
    static void touch(Connection c,int world)throws SQLException{try(var s=c.prepareStatement("UPDATE world_characters SET state_revision=state_revision+1 WHERE world_id=?")){s.setInt(1,world);if(s.executeUpdate()!=1)throw new SQLException("Missing Town owner");}}
    static WorldProfile.Item owned(WorldProfile p,long id)throws IOException{return p.items().stream().filter(i->i.id()==id).findFirst().orElseThrow(()->new IOException("m.internal_error"));}
    static void consume(Connection c,WorldProfile p,long id,int count)throws SQLException,IOException{
        var i=owned(p,id);if(count<1||count>i.count()||i.equippedSlot()!=null||i.permanent())throw new IOException("m.internal_error");
        if(i.starter())try(var s=c.prepareStatement("INSERT INTO town_consumed_starters(world_id,slot) VALUES(?,?)")){s.setInt(1,p.worldId());s.setString(2,i.slot().name());s.executeUpdate();}
        try(var s=c.prepareStatement(count==i.count()?"DELETE FROM inventory_items WHERE world_id=? AND item_id=?":"UPDATE inventory_items SET count=count-"+count+" WHERE world_id=? AND item_id=?")){s.setInt(1,p.worldId());s.setLong(2,id);if(s.executeUpdate()!=1)throw new IOException("Item changed");}
    }
    static void pay(Connection c,WorldProfile p,int crowns,int energy)throws SQLException,IOException{
        if(crowns<0||energy<0||p.crowns()<crowns||p.energy()<energy)throw new IOException("m.insufficient_funds");
        credit(c,p.worldId(),-crowns,-energy);
    }
    static void credit(Connection c,int world,int crowns,int energy)throws SQLException,IOException{
        try(var s=c.prepareStatement("UPDATE world_characters SET crowns=crowns+?,energy=energy+?,state_revision=state_revision+1 WHERE world_id=? AND crowns+? BETWEEN 0 AND 2147483647 AND energy+? BETWEEN 0 AND 2147483647")){
            s.setInt(1,crowns);s.setInt(2,energy);s.setInt(3,world);s.setInt(4,crowns);s.setInt(5,energy);if(s.executeUpdate()!=1)throw new IOException("Currency balance exceeds supported limits");
        }
        for(var delta:Map.of("crowns",crowns,"energy",energy).entrySet())if(delta.getValue()!=0)try(var s=c.prepareStatement("INSERT INTO currency_events(world_id,currency,delta,balance,created_at) SELECT world_id,?,?,"+delta.getKey()+",? FROM world_characters WHERE world_id=?")){s.setString(1,delta.getKey());s.setInt(2,delta.getValue());s.setLong(3,System.currentTimeMillis());s.setInt(4,world);s.executeUpdate();}
    }
    long create(Connection c,WorldProfile p,String config,int count,boolean locked,int level,float heat,List<StarterCatalog.Model> variants)throws SQLException,IOException{
        var def=items.require(config);if(count<1||count>9999)throw new IOException("Invalid item count");
        int size;try(var s=c.prepareStatement("SELECT count(*) FROM inventory_items WHERE world_id=?")){s.setInt(1,p.worldId());try(var r=s.executeQuery()){r.next();size=r.getInt(1);}}
        if(size+(def.stackable()?1:count)>ItemCatalog.INVENTORY_LIMIT)throw new IOException("m.inventory_full");
        long first=0;
        for(int n=0;n<(def.stackable()?1:count);n++){
            long id=WorldProfiles.allocate(c,"next-item-id",Long.MAX_VALUE);if(first==0)first=id;
            try(var s=c.prepareStatement("INSERT INTO inventory_items(item_id,world_id,slot,config,count,bound,bind_on_equip,permanent,level,heat_progress,variants) VALUES(?,?,?,?,?,?,?,?,?,?,?)")){
                s.setLong(1,id);s.setInt(2,p.worldId());s.setString(3,def.slot().name());s.setString(4,config);s.setInt(5,def.stackable()?count:1);s.setBoolean(6,locked||def.locked());s.setBoolean(7,def.bindOnEquip());s.setBoolean(8,def.permanent());s.setInt(9,level);s.setFloat(10,heat);s.setString(11,Json.stringify(variants.stream().map(StarterCatalog.Model::name).toList()));s.executeUpdate();
            }
            try(var s=c.prepareStatement("INSERT INTO item_events(world_id,config,quantity,first_item_id,created_at) VALUES(?,?,?,?,?)")){s.setInt(1,p.worldId());s.setString(2,config);s.setInt(3,def.stackable()?count:1);s.setLong(4,id);s.setLong(5,System.currentTimeMillis());s.executeUpdate();}
        }
        return first;
    }
    public List<Integer> availability(int account,int knight,long auth,TownCatalog.Station station,long cycle)throws IOException{
        return database.read(c->{c.setAutoCommit(false);var p=WorldProfiles.load(c,account,knight,auth);var result=availability(c,p,station,cycle);c.commit();return result;});
    }
    private List<Integer> availability(Connection c,WorldProfile p,TownCatalog.Station station,long cycle)throws SQLException{
        var result=new ArrayList<Integer>();for(var good:rules.goods(station.shop(),cycle,p.worldId())){
            int available=-1;if(good.limit()>0){int purchased=0;try(var s=c.prepareStatement("SELECT purchased FROM town_purchases WHERE world_id=? AND catalog=? AND item=? AND cycle=?")){s.setInt(1,p.worldId());s.setString(2,station.shop().catalog());s.setString(3,good.item());s.setLong(4,cycle);try(var r=s.executeQuery()){if(r.next())purchased=r.getInt(1);}}available=Math.max(0,good.limit()-purchased);}result.add(available);
        }return List.copyOf(result);
    }
    public Result shop(int account,int knight,long auth,TownCatalog.Station station,long cycle,Map<Integer,Integer> buys,Map<Long,Integer> sells)throws IOException{
        return change(account,knight,auth,(c,p)->{
            if(cycle!=0&&cycle<=System.currentTimeMillis())throw new IOException("Shop catalogue changed; reopen the shop");
            var goods=rules.goods(station.shop(),cycle,p.worldId());var available=availability(c,p,station,cycle);var consume=new HashMap<Long,Integer>(sells);var tokens=new HashMap<String,Integer>();
            var purchased=new HashMap<String,Integer>();var limits=new HashMap<String,Integer>();for(int i=0;i<goods.size();i++)if(available.get(i)>=0)limits.merge(goods.get(i).item(),available.get(i),Math::min);
            long crowns=0,energy=0;
            for(var sale:sells.entrySet()){var item=owned(p,sale.getKey());var def=items.require(item.config());int price=rules.price(item.config(),def.rarity(),false);if(sale.getValue()<1||sale.getValue()>item.count()||item.equippedSlot()!=null||item.permanent()||price<=0)throw new IOException("Item cannot be sold");crowns+=(long)price*sale.getValue();}
            for(var buy:buys.entrySet()){
                int index=buy.getKey(),count=buy.getValue();if(index<0||index>=goods.size()||count<1||count>9999||available.get(index)>=0&&count>available.get(index))throw new IOException("This good is not available");
                var good=goods.get(index);var def=items.require(good.item());int price=good.price()>=0?good.price():rules.price(good.item(),def.rarity(),true);
                purchased.merge(good.item(),count,Math::addExact);
                if(!good.token().isEmpty()){long tokenCount=(long)good.tokenCount()*count;if(tokenCount>9999)throw new IOException("Token requirement exceeds limit");tokens.merge(good.token(),(int)tokenCount,Math::addExact);}else{crowns-=(long)price*count;energy+=(long)good.energy()*count;}
            }
            for(var entry:purchased.entrySet())if(limits.containsKey(entry.getKey())&&entry.getValue()>limits.get(entry.getKey()))throw new IOException("This good is not available");
            for(var required:tokens.entrySet()){int needed=required.getValue();for(var held:p.items())if(held.config().equals(required.getKey())&&held.equippedSlot()==null&&!held.permanent()){
                    int free=held.count()-consume.getOrDefault(held.id(),0),used=Math.min(Math.max(0,free),needed);if(used>0){consume.merge(held.id(),used,Math::addExact);needed-=used;}if(needed==0)break;}
                if(needed!=0)throw new IOException("m.nsf_tokens");}
            if(crowns+ p.crowns()<0||crowns+p.crowns()>Integer.MAX_VALUE||energy<0||energy>p.energy())throw new IOException("m.insufficient_funds");
            credit(c,p.worldId(),Math.toIntExact(crowns),-(int)energy);for(var use:consume.entrySet())consume(c,p,use.getKey(),use.getValue());
            for(var buy:buys.entrySet()){var good=goods.get(buy.getKey());long quantity=(long)good.count()*buy.getValue();if(quantity>9999)throw new IOException("Purchase count exceeds limit");var d=items.require(good.item());boolean locked=station.shop().preparer().equals("LOCKED")||station.shop().preparer().equals("LOCK_NON_STACKABLE")&&!d.stackable();create(c,p,good.item(),(int)quantity,locked,0,0,List.of());
                if(limits.containsKey(good.item()))try(var s=c.prepareStatement("INSERT INTO town_purchases(world_id,catalog,item,cycle,purchased) VALUES(?,?,?,?,?) ON CONFLICT(world_id,catalog,item,cycle) DO UPDATE SET purchased=purchased+excluded.purchased")){s.setInt(1,p.worldId());s.setString(2,station.shop().catalog());s.setString(3,good.item());s.setLong(4,cycle);s.setInt(5,buy.getValue());s.executeUpdate();}
            }return null;
        });
    }

    public Result depot(int account,int knight,long auth,int index,int quotedPrice)throws IOException{
        return depot(account,knight,auth,index,quotedPrice,null);
    }
    public Result depot(int account,int knight,long auth,int index,int quotedPrice,DepotStore.Quote quote)throws IOException{
        return change(account,knight,auth,(c,p)->{
            var snapshot=DepotStore.load(c,rules);var offer=snapshot.offers().stream().filter(o->o.id()==index&&o.enabled()).findFirst().orElseThrow(()->new IOException("e.good_unavailable"));
            if(quote!=null&&(quote.revision()!=snapshot.revision()||!offer.equals(quote.offer())))throw new IOException("e.good_unavailable");var good=offer.good();var def=items.require(good.item());
            int price=good.price()>=0?good.price():rules.price(good.item(),def.rarity(),true),cost=good.energy()>0?good.energy():price;
            if(cost<0||quotedPrice!=cost||!def.grantable())throw new IOException("e.good_unavailable");
            if(good.limit()>0){try(var st=c.prepareStatement("SELECT purchased FROM town_purchases WHERE world_id=? AND catalog='Supply Depot' AND item=? AND cycle=0")){st.setInt(1,p.worldId());st.setString(2,good.item());try(var r=st.executeQuery()){if(r.next()&&r.getInt(1)>=good.limit())throw new IOException("e.good_unavailable");}}}
            pay(c,p,good.energy()>0?0:cost,good.energy()>0?cost:0);
            boolean locked=def.variant()==ItemCatalog.Variant.LEVEL_ITEM&&!def.type().endsWith("Costume");create(c,p,good.item(),good.count(),locked,0,0,List.of());
            try(var st=c.prepareStatement("INSERT INTO town_purchases(world_id,catalog,item,cycle,purchased) VALUES(?,'Supply Depot',?,0,1) ON CONFLICT(world_id,catalog,item,cycle) DO UPDATE SET purchased=purchased+1")){st.setInt(1,p.worldId());st.setString(2,good.item());st.executeUpdate();}return null;
        });
    }
    private static final String[] FIRE_CRYSTALS={"Cracked","Dim","Warm","Glowing","Shining","Radiant"};
    public Result forge(int account,int knight,long auth,long itemId,int crystals,int expectedLevel)throws IOException{
        return change(account,knight,auth,(c,p)->{
            var item=owned(p,itemId);var definition=items.require(item.config());var gear=rules.gear().gear(item.config());
            if(gear==null||gear.maximum(item.level()))throw new IOException("m.forge_already");
            if(item.level()!=expectedLevel)throw new IOException("m.costupdate");
            if(item.heatProgress()!=1)throw new IOException("m.forge_locked");
            var props=rules.gear().forge(gear,item.level());if(props==null)throw new IOException("m.forge_already");
            float chance=props.chance(crystals,true);int rarity=definition.rarity();if(rarity<0||rarity>=FIRE_CRYSTALS.length)throw new IOException("m.internal_error");
            int remaining=crystals;var consumes=new LinkedHashMap<Long,Integer>();String config="Rarity/Fire Crystal/"+FIRE_CRYSTALS[rarity];
            for(var held:p.items())if(held.config().equals(config)&&held.equippedSlot()==null&&!held.permanent()&&remaining>0){int take=Math.min(held.count(),remaining);consumes.put(held.id(),take);remaining-=take;}
            if(remaining!=0)throw new IOException("m.insufficient_crystals");
            for(var consume:consumes.entrySet())consume(c,p,consume.getKey(),consume.getValue());
            // Like the archive, failed attempts spend crystals but preserve the full heat bar.
            if(java.util.concurrent.ThreadLocalRandom.current().nextFloat()>=chance)return null;
            setGearProgress(c,p.worldId(),item.id(),item.level()+1,0);return "m.forge_success";
        });
    }
    /** Trusted heat awards split only across equipped gear still able to receive heat. */
    public Result addHeat(int account,int knight,long auth,float amount)throws IOException{
        if(!Float.isFinite(amount)||amount<=0||amount>1_000_000)throw new IOException("Invalid heat award");
        return change(account,knight,auth,(c,p)->{
            var eligible=p.items().stream().filter(i->i.equippedSlot()!=null&&Set.of(0,1,2,6,7,8,9).contains(i.equippedSlot())).filter(i->{var g=rules.gear().gear(i.config());return g!=null&&!g.maximum(i.level())&&i.heatProgress()<1;}).toList();
            for(var item:eligible){var g=rules.gear().gear(item.config());setGearProgress(c,p.worldId(),item.id(),item.level(),g.addHeat(item.level(),item.heatProgress(),amount/eligible.size()));}return null;
        });
    }
    private static void setGearProgress(Connection c,int world,long item,int level,float heat)throws SQLException,IOException{
        try(var st=c.prepareStatement("UPDATE inventory_items SET level=?,heat_progress=? WHERE world_id=? AND item_id=?")){st.setInt(1,level);st.setFloat(2,heat);st.setInt(3,world);st.setLong(4,item);if(st.executeUpdate()!=1)throw new IOException("Item changed");}
    }
    private static void requireGameMaster(Connection c,int account,long auth)throws SQLException,IOException{
        try(var st=c.prepareStatement("SELECT game_master FROM accounts WHERE id=? AND auth_revision=?")){st.setInt(1,account);st.setLong(2,auth);try(var r=st.executeQuery()){if(!r.next()||!r.getBoolean(1))throw new IOException("Game Master permission required");}}
    }
    /** Current stock /heat, /level and /forge commands; setters never grant normal-player authority. */
    public Result adminGear(int account,int knight,long auth,int method,List<Object> args)throws IOException{
        return change(account,knight,auth,(c,p)->{
            requireGameMaster(c,account,auth);int slot=(Integer)args.get(0);
            var item=p.items().stream().filter(i->i.equippedSlot()!=null&&i.equippedSlot()==slot).findFirst().orElseThrow(()->new IOException("Equip an item in that slot first"));
            var gear=rules.gear().gear(item.config());if(gear==null||gear.levels()<1)throw new IOException("This item has no heat progression");
            if(method==41){float heat=(Float)args.get(1);if(!Float.isFinite(heat)||heat<0||heat>1)throw new IOException("Heat progress must be between 0 and 1");setGearProgress(c,p.worldId(),item.id(),item.level(),heat);return null;}
            if(method==42){int level=(Integer)args.get(1);if(level<0||level>=gear.levels())throw new IOException("Item level is outside its progression table");setGearProgress(c,p.worldId(),item.id(),level,0);return null;}
            if(method!=23)throw new IOException("Unknown gear admin operation");
            if(gear.maximum(item.level()))return null;var props=rules.gear().forge(gear,item.level());if(props==null)return null;
            int crystals=(Integer)args.get(1);if(crystals<0||crystals>9999)throw new IOException("Invalid forge crystal tier");
            float chance=props.chance(crystals,false);if(java.util.concurrent.ThreadLocalRandom.current().nextFloat()>=chance)return null;
            setGearProgress(c,p.worldId(),item.id(),item.level()+1,0);return item.level()+1;
        });
    }
    public Result learn(int account,int knight,long auth,long item)throws IOException{
        return change(account,knight,auth,(c,p)->{var held=owned(p,item);String config=rules.item(held.config()).get("recipe");if(config==null||rules.recipe(config)==null)throw new IOException("m.internal_error");
            var prior=p.town().recipes().stream().filter(r->r.config().equals(config)).findFirst().orElse(null);
            long now=System.currentTimeMillis();if(prior!=null&&(prior.expires()==0||prior.expires()>now))throw new IOException("Recipe already learned");
            consume(c,p,item,1);learn(c,p,config,now);return null;});
    }
    public Result wrap(int account,int knight,long auth,long paperId,Map<Long,Integer> contents,String message,String costume)throws IOException {
        if(message==null||message.length()>300||message.chars().anyMatch(ch->ch==0)||contents.isEmpty()||contents.size()>256)throw new IOException("e.not_wrapable");
        var selected=Map.copyOf(contents);
        return change(account,knight,auth,(c,p)->{
            var paper=owned(p,paperId);String box=rules.item(paper.config()).get("giftBox");
            if(box==null||!items.require(paper.config()).type().equals("WrappingPaper")||!items.require(box).type().equals("GiftBox"))throw new IOException("e.not_wrapable");
            var gifts=new ArrayList<WorldProfile.Item>();int total=0;
            for(var entry:selected.entrySet()){
                var item=owned(p,entry.getKey());var def=items.require(item.config());int count=entry.getValue();
                if(item.id()==paperId||item.bound()||def.locked()||item.permanent()||item.starter()||item.cursed()||item.equippedSlot()!=null||def.type().equals("GiftBox")||count<1||count>item.count()||(total+=count)>9999)throw new IOException("e.not_wrapable");
                gifts.add(new WorldProfile.Item(0,item.slot(),item.config(),null,item.heat(),null,false,item.bindOnEquip(),false,count,false,item.level(),item.heatProgress(),item.variants(),item.nativeAccessories(costume)));
            }
            String name;try(var st=c.prepareStatement("SELECT name FROM knights WHERE account_id=? AND id=?")){st.setInt(1,account);st.setInt(2,knight);try(var row=st.executeQuery()){if(!row.next())throw new IOException("m.internal_error");name=row.getString(1);}}
            consume(c,p,paperId,1);for(var entry:selected.entrySet())consume(c,p,entry.getKey(),entry.getValue());
            long id=create(c,p,box,1,false,0,0,List.of());GiftStore.save(c,id,new WorldProfile.Gift(p.worldId(),name,message,gifts));return null;
        });
    }
    public Result unwrap(int account,int knight,long auth,long boxId)throws IOException {
        return change(account,knight,auth,(c,p)->{
            var box=owned(p,boxId);if(!items.require(box.config()).type().equals("GiftBox")||box.gift()==null)throw new IOException("m.internal_error");
            var contents=box.gift().contents();consume(c,p,boxId,1);var result=new LinkedHashMap<Object,Integer>();
            for(var item:contents){
                long id=create(c,p,item.config(),item.count(),item.bound(),item.level(),item.heatProgress(),item.variants());
                try(var st=c.prepareStatement("UPDATE inventory_items SET heat=?,accessory=?,accessories=?,bind_on_equip=? WHERE world_id=? AND item_id=?")){st.setInt(1,item.heat());st.setString(2,item.accessory());st.setString(3,Json.stringify(item.additionalAccessories()));st.setBoolean(4,item.bindOnEquip());st.setInt(5,p.worldId());st.setLong(6,id);st.executeUpdate();}
                result.put(id,item.count());
            }
            return new org.tortodrone.protocol.SessionDecoder.Multiset(result);
        });
    }
    private void learn(Connection c,WorldProfile p,String config,long now)throws SQLException,IOException{
        var r=rules.recipe(config);if(r==null)throw new IOException("Unknown recipe");
        long expires=r.maxHours()==0?0:Math.addExact(now,r.maxHours()*3600000L);
        try(var s=c.prepareStatement("INSERT INTO town_recipes(world_id,config,expires) VALUES(?,?,?) ON CONFLICT(world_id,config) DO UPDATE SET crafted=0,expires=excluded.expires")){s.setInt(1,p.worldId());s.setString(2,config);s.setLong(3,expires);s.executeUpdate();}
    }
    public Result craft(int account,int knight,long auth,String recipe,long[] selected,boolean upgrade,boolean catalogue)throws IOException{
        if(selected.length>128)throw new IOException("Too many ingredients");
        return change(account,knight,auth,(c,p)->{
            var r=rules.recipe(recipe);if(r==null)throw new IOException("Unknown recipe");
            var learned=p.town().recipes().stream().filter(v->v.config().equals(recipe)).findFirst().orElse(null);long now=System.currentTimeMillis();
            if(!catalogue&&learned==null||learned!=null&&(learned.expires()!=0&&learned.expires()<=now||r.oneTime()&&learned.crafted()>0))throw new IOException("Recipe is not available");
            var output=items.require(r.item());var ingredients=new ArrayList<>(r.ingredients());
            if(r.orbs(output.rarity())&&output.rarity()>0&&output.rarity()<=5){String orb=new String[]{"","Flawed","Simple","Advanced","Elite","Eternal"}[output.rarity()];ingredients.add(new TownCatalog.Ingredient("Item","Rarity/Orb of Alchemy/"+orb,3,-1,"",1,"NEVER",false,List.of()));}
            // Specific items first, then groups, so a generic food material cannot steal an exact ingredient.
            ingredients.sort(Comparator.comparingInt(i->i.item().isEmpty()?1:0));
            var ids=new LinkedHashSet<Long>();for(long id:selected)if(!ids.add(id))throw new IOException("Duplicate ingredient");
            var remaining=new LinkedHashMap<Long,Integer>();for(long id:ids){var i=owned(p,id);if(i.equippedSlot()!=null||i.permanent())throw new IOException("Ingredient must be unequipped");remaining.put(id,i.count());}
            var used=new LinkedHashMap<Long,Integer>();int level=0;float heat=0;List<StarterCatalog.Model> variants=List.of();boolean retainVariants=false;
            for(var ingredient:ingredients){int needed=ingredient.count();for(long id:ids){var item=owned(p,id);int available=remaining.get(id);if(available==0||!matches(ingredient,item))continue;int n=Math.min(needed,available);remaining.put(id,available-n);used.merge(id,n,Integer::sum);needed-=n;
                    if(ingredient.kind().endsWith("LevelItem")){if(ingredient.keepHeat()){level=item.level();heat=item.heatProgress();}if(ingredient.upgrade().equals("ALWAYS")||ingredient.upgrade().equals("CHOICE")&&upgrade){variants=item.variants();retainVariants=true;}}
                    if(needed==0)break;}
                if(needed!=0)throw new IOException("Missing recipe ingredients");}
            if(used.size()!=ids.size())throw new IOException("Unexpected recipe ingredient");
            pay(c,p,r.cost(output.rarity()),0);for(var use:used.entrySet())consume(c,p,use.getKey(),use.getValue());
            if(!retainVariants)variants=rules.roll(r.variantTable(),group(output));
            long result=create(c,p,r.item(),r.count(),r.locked(),level,heat,variants);
            if(learned!=null)try(var s=c.prepareStatement("UPDATE town_recipes SET crafted=crafted+1 WHERE id=? AND world_id=?")){s.setLong(1,learned.id());s.setInt(2,p.worldId());s.executeUpdate();}
            return result;
        });
    }
    private boolean matches(TownCatalog.Ingredient i,WorldProfile.Item item){
        var d=items.find(item.config());if(d==null)return false;
        if(i.kind().equals("SpriteFoodMaterial")){String role=rules.item(item.config()).getOrDefault("spriteFoodRole","");return (role.equals("MATERIAL_AND_FOOD"))&&(i.rarity()==-1||i.rarity()==d.rarity());}
        if(i.kind().equals("ItemGroup"))return (i.rarity()==-1||i.rarity()==d.rarity())&&group(d).equals(i.group());
        return (item.config().equals(i.item())||i.options().contains(item.config()))&&(!i.kind().endsWith("LevelItem")||item.level()>=i.level()-1);
    }
    static String group(ItemCatalog.Entry entry){return TownCatalog.itemGroup(entry);}
    /** Native VariantMarshaller: custom ticket, or a selected random-variant count. */
    public Result variant(int account,int knight,long auth,int count,long gearId,long ticketId,int keepMask,boolean custom)throws IOException{
        return change(account,knight,auth,(c,p)->{
            var gear=owned(p,gearId);var def=items.require(gear.config());String group=group(def);
            if(!Set.of("SWORD","HANDGUN","BOMB","HELM","ARMOR","SHIELD","TRINKET").contains(group)||gear.variants().size()>3||keepMask<0||(keepMask>>>gear.variants().size())!=0)throw new IOException("e.wrong_item_group");
            var kept=new ArrayList<StarterCatalog.Model>();for(int i=0;i<gear.variants().size();i++)if((keepMask&(1<<i))!=0)kept.add(gear.variants().get(i));
            if(custom){
                var ticket=owned(p,ticketId);var metadata=rules.item(ticket.config());String variant=metadata.get("variant");
                if(variant==null||!Arrays.asList(metadata.getOrDefault("variantGroups","").split(",")).contains(group))throw new IOException("e.wrong_item_group");
                if(gear.variants().stream().anyMatch(v->v.name().equals(variant)))throw new IOException("e.already_have_variant");
                if(gear.variants().size()<3&&kept.size()!=gear.variants().size()||kept.size()>2)throw new IOException("Invalid retained variants");
                consume(c,p,ticketId,1);kept.add(new StarterCatalog.Model(variant,Map.of()));
            }else{
                if(count<1||count>3||kept.size()>=count)throw new IOException("Invalid variant count");
                if(ticketId==0)pay(c,p,new int[]{20000,75000,225000}[count-1],0);
                else{var ticket=owned(p,ticketId);if(!ticket.config().equals("Ticket/Variants/Variants "+count))throw new IOException("Invalid variant ticket");consume(c,p,ticketId,1);}
                var rolled=rules.roll("Shop/Variant "+count,group,kept);kept.clear();kept.addAll(rolled);
                if(kept.size()!=count)throw new IOException("Variant table did not supply the selected outcome");
            }
            try(var st=c.prepareStatement("UPDATE inventory_items SET variants=? WHERE world_id=? AND item_id=?")){st.setString(1,Json.stringify(kept.stream().map(StarterCatalog.Model::name).toList()));st.setInt(2,p.worldId());st.setLong(3,gearId);if(st.executeUpdate()!=1)throw new IOException("Item changed");}
            return gearId;
        });
    }
    public Result unbind(int account,int knight,long auth,long item,long ticket)throws IOException{
        return change(account,knight,auth,(c,p)->{var i=owned(p,item);var def=items.require(i.config());int rarity=def.rarity();
            if(!i.bound())throw new IOException("e.not_bound");if(i.permanent()||i.starter()||def.variant()!=ItemCatalog.Variant.LEVEL_ITEM&&i.slot()==WorldProfile.Slot.OTHER||rarity<1||rarity>5)throw new IOException("e.cannot_unbind");if(i.equippedSlot()!=null)throw new IOException("e.equipped");
            if(ticket!=0){if(!owned(p,ticket).config().equals("Ticket/Unbind/Unbind "+rarity))throw new IOException("e.invalid_ticket");consume(c,p,ticket,1);}else pay(c,p,0,new int[]{0,100,200,600,1800,4000}[rarity]);
            try(var s=c.prepareStatement("UPDATE inventory_items SET bound=0,heat=0,level=0,heat_progress=0 WHERE item_id=? AND world_id=?")){s.setLong(1,item);s.setInt(2,p.worldId());s.executeUpdate();}return item;});
    }
    private static final List<String> ACCESSORY_SLOTS=List.of("HELM_TOP","HELM_BROW","HELM_FRONT","HELM_BACK","HELM_SIDE","ARMOR_FRONT","ARMOR_BACK","ARMOR_REAR","ARMOR_ARMS","ARMOR_ANKLE","ARMOR_AURA");
    public Result accessorize(int account,int knight,long auth,int method,long gearId,Object argument,String costume)throws IOException{
        return change(account,knight,auth,(c,p)->{var gear=owned(p,gearId);if(gear.slot()!=WorldProfile.Slot.HELM&&gear.slot()!=WorldProfile.Slot.HELM_COSTUME&&gear.slot()!=WorldProfile.Slot.ARMOR&&gear.slot()!=WorldProfile.Slot.ARMOR_COSTUME)throw new IOException("e.cant_use_accessory");
            var accessories=new ArrayList<>(gear.nativeAccessories(costume));long result=0;
            if(method==1){long ticket=(Long)argument;var held=owned(p,ticket);String name=rules.item(held.config()).get("accessory");var accessory=name==null?null:rules.accessory(name);
                if(accessory==null||accessory.remove())throw new IOException("e.cant_use_accessory");
                var blocked=new HashSet<>(rules.blockers(gear.config()));boolean helm=gear.slot()==WorldProfile.Slot.HELM||gear.slot()==WorldProfile.Slot.HELM_COSTUME;
                for(String slot:ACCESSORY_SLOTS)if(helm?slot.startsWith("ARMOR_"):slot.startsWith("HELM_"))blocked.add(slot);
                if(accessory.blocks().stream().anyMatch(blocked::contains))throw new IOException("e.cant_use_accessory");
                for(String existing:accessories){var worn=rules.accessory(existing);if(worn==null)continue;if(worn.location().equals(accessory.location()))throw new IOException("e.existing_accessory");if(worn.blocks().stream().anyMatch(accessory.blocks()::contains))throw new IOException("e.existing_multislot");}
                consume(c,p,ticket,1);accessories.add(name);
            }else{
                String slot;TownCatalog.Accessory tool=null;long toolId=0;
                if(method==2){int ordinal=((org.tortodrone.protocol.SessionDecoder.EnumValue)argument).ordinal();if(ordinal<0||ordinal>=ACCESSORY_SLOTS.size())throw new IOException("Invalid accessory slot");slot=ACCESSORY_SLOTS.get(ordinal);}
                else if(method==3){toolId=(Long)argument;String name=rules.item(owned(p,toolId).config()).get("accessory");tool=name==null?null:rules.accessory(name);if(tool==null||!tool.remove())throw new IOException("e.cant_use_accessory");slot=tool.location();}else throw new IOException("Invalid accessory operation");
                String worn=null;for(String existing:accessories){var a=rules.accessory(existing);if(a!=null&&a.location().equals(slot)){worn=existing;break;}}if(worn==null)throw new IOException("e.no_accessory");
                if(tool!=null){consume(c,p,toolId,1);if(!tool.destroy()){String recovered=rules.accessoryTicket(worn);if(recovered==null)throw new IOException("Accessory has no recoverable ticket");result=create(c,p,recovered,1,false,0,0,List.of());}}
                accessories.remove(worn);
            }
            try(var s=c.prepareStatement("UPDATE inventory_items SET accessory=NULL,accessories=? WHERE item_id=? AND world_id=?")){s.setString(1,Json.stringify(accessories));s.setLong(2,gearId);s.setInt(3,p.worldId());s.executeUpdate();}return result;
        });
    }

    /** Archive grantStarterSprite: the pod gate checks sprite count, not mission completion. */
    static boolean grantStarterSprite(Connection c,WorldProfile p,String config)throws SQLException,IOException{
        if(!Set.of("Drakon","Seraphynx","Maskeraith").contains(config))throw new IOException("Choose Drakon, Seraphynx or Maskeraith for the starter sprite");
        if(!p.town().sprites().isEmpty())return false;
        createSprite(c,p,config);
        return true;
    }
    private static int createSprite(Connection c,WorldProfile p,String config)throws SQLException{
        int id;
        try(var s=c.prepareStatement("INSERT INTO town_sprites(world_id,config) VALUES(?,?)",Statement.RETURN_GENERATED_KEYS)){
            s.setInt(1,p.worldId());s.setString(2,config);s.executeUpdate();
            try(var keys=s.getGeneratedKeys()){if(!keys.next())throw new SQLException("Missing starter sprite ID");id=keys.getInt(1);}
        }
        if(p.town().equippedSprite()==0)try(var s=c.prepareStatement("INSERT INTO town_equipped(world_id,sprite) VALUES(?,?) ON CONFLICT(world_id) DO UPDATE SET sprite=excluded.sprite")){
            s.setInt(1,p.worldId());s.setInt(2,id);s.executeUpdate();
        }
        return id;
    }
    /** Stock ProjectXAdminMarshaller IDs; GM setters bypass normal point/catalyst requirements. */
    public Result adminSprite(int account,int knight,long auth,int method,List<Object> args)throws IOException{
        return change(account,knight,auth,(c,p)->{
            requireGameMaster(c,account,auth);
            if(method==8){
                String config=((org.tortodrone.protocol.SessionDecoder.ConfigReference)args.get(0)).name();
                if(config==null||rules.sprite(config)==null)throw new IOException("Unknown sprite configuration");
                return createSprite(c,p,config);
            }
            var sprite=p.town().sprites().stream().filter(s->s.id()==p.town().equippedSprite()).findFirst().orElseThrow(()->new IOException("Equip a battle sprite first; /sprite_list shows owned sprite IDs"));
            String field,json=null;int level=sprite.level();
            switch(method){
                case 45->{level=(Integer)args.get(0);if(level<0||level>99)throw new IOException("Sprite level must be between 1 and 100");field="level";}
                case 46->{int skill=(Integer)args.get(0),power=(Integer)args.get(1),value=(Integer)args.get(2);
                    if(skill<0||skill>=3||power<0||power>=3||value<0||value>3)throw new IOException("Sprite skill and power must be 1-3; value must be 0-3");
                    var powers=new ArrayList<>(sprite.powers());powers.set(skill*3+power,value);field="powers";json=Json.stringify(powers);}
                case 47->{int skill=(Integer)args.get(0),ultimate=(Integer)args.get(1);
                    if(skill<0||skill>=3||ultimate<0||ultimate>2)throw new IOException("Sprite skill must be 1-3; ultimate must be 0 (none), 1 (A) or 2 (B)");
                    var ultimates=new ArrayList<>(sprite.ultimates());ultimates.set(skill,ultimate);field="ultimates";json=Json.stringify(ultimates);}
                default->throw new IOException("Unknown sprite admin operation");
            }
            try(var s=c.prepareStatement("UPDATE town_sprites SET "+field+"=? WHERE id=? AND world_id=?")){
                if(json==null)s.setInt(1,level);else s.setString(1,json);s.setInt(2,sprite.id());s.setInt(3,p.worldId());
                if(s.executeUpdate()!=1)throw new IOException("Sprite changed; retry");
            }
            return null;
        });
    }
    public Result hatch(int account,int knight,long auth,long egg)throws IOException{
        return change(account,knight,auth,(c,p)->{var item=owned(p,egg);String config=rules.item(item.config()).get("sprite");if(config==null||rules.sprite(config)==null)throw new IOException("m.internal_error");
            consume(c,p,egg,1);try(var s=c.prepareStatement("INSERT INTO town_sprites(world_id,config) VALUES(?,?)",Statement.RETURN_GENERATED_KEYS)){s.setInt(1,p.worldId());s.setString(2,config);s.executeUpdate();try(var keys=s.getGeneratedKeys()){keys.next();return keys.getInt(1);}}});
    }
    public Result installHarnesses(int account,int knight,long auth,TownCatalog.Station station,long cycle,Set<Integer> selected)throws IOException{
        return change(account,knight,auth,(c,p)->{
            if(selected.isEmpty()||selected.size()>100||cycle!=0&&cycle<=System.currentTimeMillis())throw new IOException("e.good_unavailable");
            var goods=rules.goods(station.shop(),cycle,p.worldId());var configs=new ArrayList<String>();long crowns=0,energy=0;
            for(int index:selected){
                if(index<0||index>=goods.size())throw new IOException("e.good_unavailable");var good=goods.get(index);String config=rules.item(good.item()).get("harness");
                if(config==null||rules.harnessDamage(config)==null||!good.token().isEmpty()||good.count()!=1||good.limit()!=0)throw new IOException("e.good_unavailable");
                var def=items.require(good.item());int price=good.price()<0?rules.price(good.item(),def.rarity(),true):good.price();if(price<0||good.energy()<0)throw new IOException("e.good_unavailable");
                crowns+=price;energy+=good.energy();configs.add(config);
            }
            if(crowns>Integer.MAX_VALUE||energy>Integer.MAX_VALUE)throw new IOException("m.insufficient_funds");pay(c,p,(int)crowns,(int)energy);
            for(String config:configs)try(var st=c.prepareStatement("INSERT INTO town_harnesses(world_id,config) VALUES(?,?)")){st.setInt(1,p.worldId());st.setString(2,config);st.executeUpdate();}return null;
        });
    }
    /** Archive HarnessManager.installFromKit keeps the kit and persists a newly allocated harness. */
    public Result installHarness(int account,int knight,long auth,long kit)throws IOException{
        return change(account,knight,auth,(c,p)->{
            String config=rules.item(owned(p,kit).config()).get("harness");
            if(config==null||rules.harnessDamage(config)==null)throw new IOException("m.internal_error");
            try(var s=c.prepareStatement("INSERT INTO town_harnesses(world_id,config) VALUES(?,?)",Statement.RETURN_GENERATED_KEYS)){
                s.setInt(1,p.worldId());s.setString(2,config);s.executeUpdate();
                try(var keys=s.getGeneratedKeys()){if(!keys.next())throw new SQLException("Missing installed harness ID");return keys.getLong(1);}
            }
        });
    }
    /** Sprite service IDs and argument order are the current stock BattleSpriteMarshaller's. */
    public Result sprite(int account,int knight,long auth,int method,List<Object> args,boolean staff)throws IOException{
        return change(account,knight,auth,(c,p)->{
            int id=((Number)args.get(method==6?1:0)).intValue();
            if(method==4){if(id!=0&&p.town().sprites().stream().noneMatch(s->s.id()==id))throw new IOException("Unknown owned sprite");try(var s=c.prepareStatement("INSERT INTO town_equipped(world_id,sprite) VALUES(?,?) ON CONFLICT(world_id) DO UPDATE SET sprite=excluded.sprite")){s.setInt(1,p.worldId());s.setInt(2,id);s.executeUpdate();}return null;}
            var sprite=p.town().sprites().stream().filter(s->s.id()==id).findFirst().orElseThrow(()->new IOException("Unknown owned sprite"));
            var definition=rules.sprite(sprite.config());if(definition==null)throw new IOException("Missing sprite configuration");
            int level=sprite.level(),perkLevel=sprite.perkLevel();float heat=sprite.heat();Long lastFull=sprite.lastFull();long fullStamp=sprite.fullStamp(),harness=sprite.harness();String name=sprite.name();Integer perk=sprite.perk();Object result=null;
            var powers=new ArrayList<>(sprite.powers());var ultimates=new ArrayList<>(sprite.ultimates());var perks=new HashSet<>(sprite.perks());
            switch(method){
                case 1,2->{int skill=(Integer)args.get(1);if(!SpriteRules.unlocked(level,skill))throw new IOException("Skill not unlocked");int[] add=new int[3];
                    if(method==1){int power=(Integer)args.get(2);if(power<0||power>=3)throw new IOException("Invalid skill power");add[power]=1;}else for(int i=0;i<3;i++)add[i]=(Integer)args.get(i+2);
                    long spend=0;for(int i=0;i<3;i++){if(add[i]<0||add[i]>3||powers.get(skill*3+i)+add[i]>3)throw new IOException("Invalid skill power");spend+=add[i];}
                    if(spend>SpriteRules.points(level)-sprite.spent())throw new IOException("Insufficient skill points");for(int i=0;i<3;i++)powers.set(skill*3+i,powers.get(skill*3+i)+add[i]);}
                case 3->{Integer requested=args.get(1)==null?null:((org.tortodrone.protocol.SessionDecoder.EnumValue)args.get(1)).ordinal();
                    var unlocked=unlocked(perkLevel,perks);if(requested!=null&&!unlocked.contains(requested))throw new IOException("Perk not unlocked");perk=requested;}
                case 5->{harness=(Long)args.get(1);if(harness!=0){var h=p.town().harnesses().stream().filter(v->v.id()==(Long)args.get(1)).findFirst().orElseThrow(()->new IOException("Unknown owned harness"));if(definition.restrictedDamage().equals(rules.harnessDamage(h.config())))throw new IOException("This sprite cannot wear that harness");}}
                case 6->{long foodId=(Long)args.get(0);var food=owned(p,foodId);if(food.config().equals("Food/Special/Reset Star")){if(sprite.spent()==0)throw new IOException("e.no_points_to_reset");Collections.fill(powers,0);Collections.fill(ultimates,0);}else{
                        String role=rules.item(food.config()).getOrDefault("spriteFoodRole","");if(!role.equals("FOOD_ONLY")&&!role.equals("MATERIAL_AND_FOOD"))throw new IOException("This item is not sprite food");
                        long now=System.currentTimeMillis();if(heat>=1)throw new IOException("Sprite heat is full");if(!SpriteRules.appetite(lastFull,now))throw new IOException("e.not_enough_appetite");
                        var fd=items.require(food.config());heat=Math.min(1,heat+SpriteRules.feedGain(fd.rarity(),definition.preferredFood().contains(food.config())));lastFull=SpriteRules.fedAt(lastFull,now);if(heat==1)fullStamp=now;
                    }consume(c,p,foodId,1);}
                case 7->{long foodId=(Long)args.get(1);boolean force=(Boolean)args.get(2);if((force||foodId==0)&&!staff)throw new IOException(force?"m.access_denied":"e.wrong_level_item");
                    if(!force&&(heat!=1||level>=99&&perkLevel>5))throw new IOException("Sprite cannot level up");
                    if(!staff&&p.progress().rank()<SpriteRules.requiredRank(level))throw new IOException("e.need_higher_rank");
                    if(foodId!=0&&!force){var food=owned(p,foodId);if(!food.config().equals(SpriteRules.levelItem(level,definition.preferredFood())))throw new IOException("e.wrong_level_item");consume(c,p,foodId,1);}
                    if(level>=99&&perkLevel>5)return null;
                    boolean grant=SpriteRules.grantsPerk(level);level=Math.min(99,level+1);heat=0;
                    if(grant&&perkLevel<=5){var candidates=new ArrayList<Integer>();for(int i=0;i<rules.perks().size();i++)if(rules.perks().get(i).tier()==perkLevel&&!perks.contains(i))candidates.add(i);
                        if(!candidates.isEmpty()){int granted=candidates.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(candidates.size()));perks.add(granted);result=new org.tortodrone.protocol.SessionDecoder.EnumValue("com.threerings.projectx.sprites.data.SpritePerk",granted);
                            if(perk!=null&&rules.perks().get(granted).previous().equals(rules.perks().get(perk).name()))perk=granted;
                            int total=0;for(var candidate:rules.perks())if(candidate.tier()==perkLevel)total++;if(perks.size()==total){perkLevel++;perks.clear();}
                        }
                    }}
                case 8->{name=(String)args.get(1);if(name==null||!name.matches("[A-Za-z]+(?:[ -][A-Za-z]+)*")||name.length()>64)throw new IOException("m.invalid_name");}
                case 9->{int skill=(Integer)args.get(1),ultimate=((org.tortodrone.protocol.SessionDecoder.EnumValue)args.get(2)).ordinal();if(!SpriteRules.ultimate(level,skill)||powers.subList(skill*3,skill*3+3).stream().mapToInt(Integer::intValue).sum()!=9||ultimates.get(skill)!=0||ultimate<1||ultimate>2)throw new IOException("Ultimate not available");ultimates.set(skill,ultimate);}
                default->throw new IOException("Unknown sprite operation");
            }
            try(var s=c.prepareStatement("UPDATE town_sprites SET name=?,level=?,heat=?,last_full=?,full_stamp=?,perk_level=?,perks=?,perk=?,powers=?,ultimates=?,harness=? WHERE id=? AND world_id=?")){
                s.setString(1,name);s.setInt(2,level);s.setFloat(3,heat);if(lastFull==null)s.setNull(4,Types.BIGINT);else s.setLong(4,lastFull);s.setLong(5,fullStamp);s.setInt(6,perkLevel);s.setString(7,Json.stringify(perks.stream().sorted().toList()));if(perk==null)s.setNull(8,Types.INTEGER);else s.setInt(8,perk);s.setString(9,Json.stringify(powers));s.setString(10,Json.stringify(ultimates));s.setLong(11,harness);s.setInt(12,id);s.setInt(13,p.worldId());s.executeUpdate();
            }return result;
        });
    }
    private Set<Integer> unlocked(int level,Set<Integer> perks){var result=new HashSet<Integer>();for(int i=0;i<rules.perks().size();i++)if(rules.perks().get(i).tier()<level||perks.contains(i))result.add(i);
        var remove=new HashSet<Integer>();for(int i:result){String replaced=rules.perks().get(i).previous();for(int j:result)if(rules.perks().get(j).name().equals(replaced))remove.add(j);}result.removeAll(remove);return result;}
}
