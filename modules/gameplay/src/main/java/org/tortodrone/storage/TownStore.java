package org.tortodrone.storage;
import org.tortodrone.character.*;

import java.io.*;
import java.sql.*;
import java.util.*;
import org.tortodrone.core.Json;
import org.tortodrone.core.town.*;
import org.tortodrone.core.WireTypes;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.character.SlotUpgrades;
import org.tortodrone.core.character.KnightProgress;
import org.tortodrone.core.character.Currency;
import org.tortodrone.core.character.AnimatedItems;
import org.tortodrone.core.character.Confetti;
import org.tortodrone.core.town.TownState;
import org.tortodrone.core.town.SpriteRules;
import org.tortodrone.core.town.TownCatalog;

/** Town inventory/progression mutations share the existing authenticated SQLite write transaction. */
public final class TownStore {
    private final Database database;
    private final ItemCatalog items;
    private final TownCatalog rules;
    private final SpriteStore sprites;
    private final CraftStore crafts;
    private final GearStore gears;
    private final ShopStore shops;
    private final GiftWrapStore gifts;
    private final CustomizeStore customize;
    public TownStore(Database database,ItemCatalog items,TownCatalog rules){this.database=database;this.items=items;this.rules=rules;this.sprites=new SpriteStore(items,rules);this.crafts=new CraftStore(items,rules);this.gears=new GearStore(items,rules);this.shops=new ShopStore(database,items,rules);this.gifts=new GiftWrapStore(items,rules);this.customize=new CustomizeStore(items,rules);}
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
            TownLedger.consume(c,p,ticket.id(),1);
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
        return new TownState(recipes,sprites,equipped,harnesses,AuctionHouse.loadMail(c,world),NamedLoadouts.load(c,world),AuctionHouse.loadOutbids(c,world));
    }
    private static List<Integer> numbers(String json)throws IOException{return ((List<?>)Json.parse(json)).stream().map(v->((Number)v).intValue()).toList();}
    static List<String> strings(String json)throws IOException{return ((List<?>)Json.parse(json)).stream().map(v->(String)v).toList();}
    static List<StarterCatalog.Model> variants(String json)throws IOException{var result=new ArrayList<StarterCatalog.Model>();for(var v:(List<?>)Json.parse(json))result.add(new StarterCatalog.Model((String)v,Map.of()));return List.copyOf(result);}
    public record Result(WorldProfile profile,Object result) { }
    /** Scene-issued receipt, never accepted from a client invocation. Commit before actor removal.
     * The receipt is global to the pickup, so a reconnect/competing account cannot claim it twice. */
    public Result pickup(int account,int knight,long auth,String receipt,String config)throws IOException {
        if(receipt==null||!receipt.matches("[0-9a-f-]{36}:[0-9]{1,10}"))throw new IOException("Invalid scene reward receipt");
        return database.write(c->{
            var profile=WorldProfiles.load(c,account,knight,auth);String key="scene-pickup:"+receipt;
            try(var st=c.prepareStatement("SELECT value FROM metadata WHERE key=?")){
                st.setString(1,key);try(var row=st.executeQuery()){if(row.next()){
                    if(!row.getString(1).equals(Integer.toString(profile.worldId())))throw new IOException("Pickup already claimed");
                    return new Result(profile,null);
                }}
            }
            int crowns=switch(config){
                // Original Treasure/Crowns/Value/Base denominations, also item.xml's Crowns argument.
                case "Instant/Crown/Copper Crown"->1;case "Instant/Crown/Copper-Silver Crown"->5;
                case "Instant/Crown/Silver Crown"->10;case "Instant/Crown/Silver-Gold Crown"->25;
                case "Instant/Crown/Gold Crown"->50;default->0;
            };
            if(crowns>0)TownLedger.credit(c,profile.worldId(),crowns,0);
            else{
                var definition=items.require(config);
                if(!definition.grantable()||definition.permanent()||definition.type().equals("InstantAction"))throw new IOException("Unsupported persistent scene reward");
                create(c,profile,config,1,false,0,0,List.of());
            }
            try(var st=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?)")){st.setString(1,key);st.setInt(2,profile.worldId());st.executeUpdate();}
            TownLedger.touch(c,profile.worldId());return new Result(WorldProfiles.load(c,account,knight,auth),null);
        });
    }
    public static final int SPARK_COST=org.tortodrone.core.economy.Fees.SPARK_COST;
    /** Official ItemMarshaller.deleteItem: one owned, non-permanent, unequipped item goes whole. */
    public Result trash(int account,int knight,long auth,long item)throws IOException {
        return change(account,knight,auth,(c,p)->{
            var held=TownLedger.owned(p,item);
            if(held.permanent()||held.equippedSlot()!=null)throw new IOException("m.internal_error");
            TownLedger.consume(c,p,item,held.count());
            return null;
        });
    }
    /** Only verified cosmetic AnimatedAction items; revision/ownership checked in the same write. */
    public Result confetti(int account,int knight,long auth,long revision,long item)throws IOException {
        return animatedItem(account,knight,auth,revision,item,true);
    }
    public Result animatedItem(int account,int knight,long auth,long revision,long item)throws IOException {
        return animatedItem(account,knight,auth,revision,item,false);
    }
    private Result animatedItem(int account,int knight,long auth,long revision,long item,boolean confetti)throws IOException {
        return change(account,knight,auth,(c,p)->{
            if(p.revision()!=revision)throw new IOException("Your inventory changed. Please try again.");
            var held=TownLedger.owned(p,item);
            if(!(confetti?Confetti.supports(held.config()):AnimatedItems.supports(held.config()))||!items.require(held.config()).type().equals("AnimatedAction")
                    ||held.gift()!=null||held.expired(System.currentTimeMillis()))throw new IOException("m.internal_error");
            TownLedger.consume(c,p,item,1);return held.config();
        });
    }
    /** Consume one owned scene-usable item; the scene applies its prepared effect under
     *  the same lock, so a refused revision or item leaves both sides unchanged. */
    public Result useItem(int account,int knight,long auth,long revision,long item)throws IOException{
        return changeBounded(account,knight,auth,(c,p)->{
            if(p.revision()!=revision)throw new IOException("Your inventory changed. Please try again.");
            var held=TownLedger.owned(p,item);
            var definition=items.find(held.config());
            if(definition==null||!Set.of("InstantAction","SpawnActor","Lift").contains(definition.type())
                    ||held.gift()!=null||held.expired(System.currentTimeMillis()))throw new IOException("m.internal_error");
            TownLedger.consume(c,p,item,1);return held.config();
        });
    }
    /**
     * Credit the account-scoped lifetime counters that native scene actions attach to a
     * consumed capsule. The scene effect has already run; the receipt is one transaction
     * and reconciles achievements before the caller publishes the updated profile.
     */
    public Result stats(int account,int knight,long auth,Map<String,Integer> counters)throws IOException{
        if(counters==null||counters.isEmpty())throw new IOException("m.internal_error");
        var ordinals=new TreeMap<Integer,Integer>();
        for(var entry:counters.entrySet()){
            int ordinal;
            try{ordinal=org.tortodrone.core.character.NativeStats.ordinal(entry.getKey());}
            catch(IllegalArgumentException invalid){throw new IOException("Unknown lifetime stat "+entry.getKey(),invalid);}
            int delta=entry.getValue()==null?0:entry.getValue();
            if(delta<1||delta>1_000_000)throw new IOException("Invalid lifetime stat increment");
            ordinals.merge(ordinal,delta,Integer::sum);
            if(ordinals.get(ordinal)>1_000_000)throw new IOException("Invalid lifetime stat increment");
        }
        return change(account,knight,auth,(c,p)->{
            for(var entry:ordinals.entrySet())AccountAchievements.increment(c,p.accountId(),entry.getKey(),entry.getValue());
            return null;
        });
    }
    /** Native PartyMarshaller.revive: -1 spends one owned Spark; a positive approval buys its use. */
    public Result revive(int account,int knight,long auth,int approvedCost)throws IOException{
        if(approvedCost < -1)throw new IOException("m.costupdate");
        return changeBounded(account,knight,auth,(c,p)->{
            if(approvedCost<0){
                var spark=p.items().stream().filter(i->i.config().equals("Rarity/Spark of Life")&&i.count()>0).findFirst().orElseThrow(()->new IOException("m.costupdate"));
                TownLedger.consume(c,p,spark.id(),1);
            }else{
                if(approvedCost<SPARK_COST)throw new IOException("m.costupdate");
                TownLedger.pay(c,p,0,SPARK_COST);
            }
            return null;
        });
    }
    @FunctionalInterface private interface Mutation{Object run(Connection c,WorldProfile profile)throws SQLException,IOException;}
    @FunctionalInterface private interface Transaction{Result run(Database.Work<Result> work)throws IOException;}
    private Result change(int account,int knight,long auth,Mutation mutation)throws IOException{
        return change(account,knight,auth,mutation,database::write);
    }
    /** Scene-locked item use and revive: refuse before opening a connection when the
     *  shared writer is still busy after the queue budget. */
    private Result changeBounded(int account,int knight,long auth,Mutation mutation)throws IOException{
        return change(account,knight,auth,mutation,
            work->database.writeBounded(Database.LOCKED_WRITE_TIMEOUT_MILLIS,work));
    }
    private Result change(int account,int knight,long auth,Mutation mutation,Transaction transaction)throws IOException{
        return transaction.run(c->{var profile=WorldProfiles.load(c,account,knight,auth);Object result=mutation.run(c,profile);InventoryExpiry.reconcile(c,profile.worldId(),System.currentTimeMillis());TownLedger.touch(c,profile.worldId());
            var updated=WorldProfiles.load(c,account,knight,auth);
            updated=AccountAchievements.reconcile(c,updated,items,updated.progress().tutorialComplete()?32:0,System.currentTimeMillis());
            return new Result(updated,result);});
    }
    long create(Connection c,WorldProfile p,String config,int count,boolean locked,int level,float heat,List<StarterCatalog.Model> variants)throws SQLException,IOException{
        return TownLedger.create(c,p,items,config,count,locked,level,heat,variants);
    }
    public List<Integer> availability(int account,int knight,long auth,TownCatalog.Station station,long cycle)throws IOException{
        return shops.availability(account,knight,auth,station,cycle);
    }
    public Result shop(int account,int knight,long auth,TownCatalog.Station station,long cycle,Map<Integer,Integer> buys,Map<Long,Integer> sells)throws IOException{
        return change(account,knight,auth,(c,p)->shops.shop(c,p,station,cycle,buys,sells));
    }
    public Result depot(int account,int knight,long auth,int index,int quotedPrice)throws IOException{
        return depot(account,knight,auth,index,quotedPrice,null);
    }
    public Result depot(int account,int knight,long auth,int index,int quotedPrice,DepotStore.Quote quote)throws IOException{
        return change(account,knight,auth,(c,p)->shops.depot(c,p,index,quotedPrice,quote));
    }
    public Result forge(int account,int knight,long auth,long itemId,int crystals,int expectedLevel)throws IOException{
        return change(account,knight,auth,(c,p)->gears.forge(c,p,itemId,crystals,expectedLevel));
    }
    /** Trusted heat awards split only across equipped gear still able to receive heat. */
    public Result addHeat(int account,int knight,long auth,float amount)throws IOException{
        if(!Float.isFinite(amount)||amount<=0||amount>1_000_000)throw new IOException("Invalid heat award");
        return change(account,knight,auth,(c,p)->gears.addHeat(c,p,amount));
    }
    static void applyHeat(Connection c,WorldProfile p,TownCatalog rules,float amount)throws SQLException,IOException {
        GearStore.applyHeat(c,p,rules,amount);
    }
    static void requireGameMaster(Connection c,int account,long auth)throws SQLException,IOException{
        try(var st=c.prepareStatement("SELECT game_master FROM accounts WHERE id=? AND auth_revision=?")){st.setInt(1,account);st.setLong(2,auth);try(var r=st.executeQuery()){if(!r.next()||!r.getBoolean(1))throw new IOException("Game Master permission required");}}
    }
    /** Read-only recheck for tools whose effect is not a town mutation. */
    public void requireGameMaster(int account,long auth)throws IOException {
        database.read(c->{requireGameMaster(c,account,auth);return null;});
    }
    public Result loadout(int account,int knight,long auth,String name,boolean save)throws IOException {
        return change(account,knight,auth,(c,p)->{if(save)NamedLoadouts.save(c,p,name);else NamedLoadouts.equip(c,p,name,rules);return null;});
    }
    public Result loadoutPvp(int account,int knight,long auth,String name,boolean enabled)throws IOException {
        return change(account,knight,auth,(c,p)->{NamedLoadouts.pvp(c,p,name,enabled);return null;});
    }
    public Result removeLoadout(int account,int knight,long auth,String name)throws IOException {
        return change(account,knight,auth,(c,p)->{NamedLoadouts.remove(c,p,name);return null;});
    }
    public Result adminAccessory(int account,int knight,long auth,int slot,String name,String costume)throws IOException {
        return change(account,knight,auth,(c,p)->customize.adminAccessory(c,p,account,auth,slot,name,costume));
    }
    static void editAccessory(Connection c,WorldProfile p,WorldProfile.Item gear,String name,String costume,TownCatalog rules)throws IOException,SQLException {
        CustomizeStore.editAccessory(c,p,gear,name,costume,rules);
    }
    /** Native /grant and /set_rank: role and ownership checked inside the write transaction. */
    public Result adminProgress(int account,int knight,long auth,int method,List<Object> args)throws IOException {
        return change(account,knight,auth,(c,p)->{
            requireGameMaster(c,account,auth);
            if(method==17) {
                int amount=(Integer)args.getFirst();
                if(amount<0)throw new IOException("Crown grant must be nonnegative");
                TownLedger.credit(c,p.worldId(),amount,0);
            }else if(method==44) {
                org.tortodrone.core.character.KnightProgress progress;
                try{progress=new org.tortodrone.core.character.KnightProgress((Integer)args.get(0),(Integer)args.get(1),p.progress().prestige());}
                catch(IllegalArgumentException invalid){throw new IOException("Rank must be 1–10 and subrank 1–4");}
                try(var st=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
                    st.setString(1,WorldProfiles.progressKey(p.worldId(),"rank"));st.setInt(2,progress.rank()*10+progress.subrank());st.executeUpdate();
                }
            }else throw new IOException("Unknown progress admin operation");
            return null;
        });
    }
    /** Current stock /heat, /level and /forge commands; setters never grant normal-player authority. */
    public Result adminGear(int account,int knight,long auth,int method,List<Object> args)throws IOException{
        return change(account,knight,auth,(c,p)->{
            requireGameMaster(c,account,auth);int slot=(Integer)args.get(0);
            if(method==41&&slot>=6&&slot<=9){
                // The stock /heat command exposes only a "weapon" group and always
                // sends the first weapon slot. Apply the value to every equipped
                // weapon so slots 7-9 are reachable through the same command.
                float percent=(Float)args.get(1);if(!Float.isFinite(percent)||percent<0||percent>100)throw new IOException("Heat progress must be between 0 and 100");
                var weapons=p.items().stream().filter(i->i.equippedSlot()!=null&&i.equippedSlot()>=6&&i.equippedSlot()<=9).toList();
                if(weapons.isEmpty())throw new IOException("Equip a weapon first");
                boolean applied=false;
                for(var weapon:weapons){var weaponGear=rules.gear().gear(weapon.config());if(weaponGear==null||weaponGear.levels()<1)continue;GearStore.setGearProgress(c,p.worldId(),weapon.id(),weapon.level(),percent/100f);applied=true;}
                if(!applied)throw new IOException("This item has no heat progression");
                return null;
            }
            var item=p.items().stream().filter(i->i.equippedSlot()!=null&&i.equippedSlot()==slot).findFirst().orElseThrow(()->new IOException("Equip an item in that slot first"));
            var gear=rules.gear().gear(item.config());if(gear==null||gear.levels()<1)throw new IOException("This item has no heat progression");
            if(method==41){float percent=(Float)args.get(1);if(!Float.isFinite(percent)||percent<0||percent>100)throw new IOException("Heat progress must be between 0 and 100");GearStore.setGearProgress(c,p.worldId(),item.id(),item.level(),percent/100f);return null;}
            if(method==42){int level=(Integer)args.get(1);if(level<0||level>=gear.levels())throw new IOException("Item level is outside its progression table");GearStore.setGearProgress(c,p.worldId(),item.id(),level,0);return null;}
            if(method!=23)throw new IOException("Unknown gear admin operation");
            if(gear.maximum(item.level()))return null;var props=rules.gear().forge(gear,item.level());if(props==null)return null;
            int crystals=(Integer)args.get(1);if(crystals<0||crystals>9999)throw new IOException("Invalid forge crystal tier");
            float chance=props.chance(crystals,false);if(java.util.concurrent.ThreadLocalRandom.current().nextFloat()>=chance)return null;
            GearStore.setGearProgress(c,p.worldId(),item.id(),item.level()+1,0);return item.level()+1;
        });
    }
    /** Native extendUpgrade(active item, unused item), ItemMarshaller method five. */
    public Result extendUpgrade(int account,int knight,long auth,long active,long unused)throws IOException {
        return change(account,knight,auth,(c,p)->{
            var target=TownLedger.owned(p,active);var source=TownLedger.owned(p,unused);var a=SlotUpgrades.find(target.config());var b=SlotUpgrades.find(source.config());
            if(active==unused||a==null||b==null||a.weapons()!=b.weapons()||a.trinkets()!=b.trinkets()
                    ||a.heatBonus()!=b.heatBonus()||a.pvpTokens()!=b.pvpTokens()||target.expiresAt()==null||source.expiresAt()!=null||source.count()!=1)throw new IOException("These upgrades cannot be combined");
            long deadline;
            try{deadline=Math.addExact(target.expiresAt(),Math.multiplyExact((long)b.days(),86_400_000L));}
            catch(ArithmeticException overflow){throw new IOException("Upgrade duration exceeds supported limits");}
            TownLedger.consume(c,p,unused,1);InventoryExpiry.save(c,active,deadline);return null;
        });
    }
    public Result activateUpgrade(int account,int knight,long auth,long id)throws IOException {
        return change(account,knight,auth,(c,p)->{
            var item=TownLedger.owned(p,id);var def=SlotUpgrades.find(item.config());
            if(def==null||item.expiresAt()!=null||item.count()!=1||item.equippedSlot()!=null||item.gift()!=null)throw new IOException("This upgrade cannot be activated");
            if(def.weapons()>0&&SlotUpgrades.weapons(p)-2>=2||def.trinkets()>0&&SlotUpgrades.trinkets(p)>=2)throw new IOException("All slots of this type are already unlocked");
            if(def.heatBonus()&&SlotUpgrades.heatBonusActive(p))throw new IOException("A Heat Bonus is already active");
            if(def.pvpTokens()>0&&SlotUpgrades.pvpTokenBonus(p)+def.pvpTokens()>6)throw new IOException("The Krogmo Coin Bonus is already at its maximum");
            InventoryExpiry.save(c,id,InventoryExpiry.deadline(def.days()));
            try(var st=c.prepareStatement("UPDATE inventory_items SET bound=1 WHERE world_id=? AND item_id=?")){st.setInt(1,p.worldId());st.setLong(2,id);st.executeUpdate();}
            return null;
        });
    }
    public Result learn(int account,int knight,long auth,long item)throws IOException{
        return change(account,knight,auth,(c,p)->crafts.learn(c,p,item));
    }
    public Result craft(int account,int knight,long auth,String recipe,long[] selected,boolean upgrade,boolean catalogue)throws IOException{
        return change(account,knight,auth,(c,p)->crafts.craft(c,p,recipe,selected,upgrade,catalogue));
    }
    public Result wrap(int account,int knight,long auth,long paperId,Map<Long,Integer> contents,String message,String costume)throws IOException {
        if(message==null||message.length()>300||message.chars().anyMatch(ch->ch==0)||contents.isEmpty()||contents.size()>256)throw new IOException("e.not_wrapable");
        var selected=Map.copyOf(contents);
        return change(account,knight,auth,(c,p)->gifts.wrap(c,p,account,knight,paperId,selected,message,costume));
    }
    public Result unwrap(int account,int knight,long auth,long boxId)throws IOException {
        return change(account,knight,auth,(c,p)->gifts.unwrap(c,p,boxId));
    }
    /** Native VariantMarshaller: custom ticket, or a selected random-variant count. */
    public Result variant(int account,int knight,long auth,int count,long gearId,long ticketId,int keepMask,boolean custom)throws IOException{
        return change(account,knight,auth,(c,p)->customize.variant(c,p,count,gearId,ticketId,keepMask,custom));
    }
    public Result unbind(int account,int knight,long auth,long item,long ticket)throws IOException{
        return change(account,knight,auth,(c,p)->customize.unbind(c,p,item,ticket));
    }
    public Result accessorize(int account,int knight,long auth,int method,long gearId,Object argument,String costume)throws IOException{
        return change(account,knight,auth,(c,p)->customize.accessorize(c,p,method,gearId,argument,costume));
    }

    /** Archive grantStarterSprite: the pod gate checks sprite count, not mission completion. */
    static boolean grantStarterSprite(Connection c,WorldProfile p,String config)throws SQLException,IOException{
        return SpriteStore.grantStarterSprite(c,p,config);
    }
    /** Stock ProjectXAdminMarshaller IDs; GM setters bypass normal point/catalyst requirements. */
    public Result adminSprite(int account,int knight,long auth,int method,List<Object> args)throws IOException{
        return change(account,knight,auth,(c,p)->sprites.adminSprite(c,p,account,auth,method,args));
    }
    public Result hatch(int account,int knight,long auth,long egg)throws IOException{
        return change(account,knight,auth,(c,p)->sprites.hatch(c,p,egg));
    }
    public Result installHarnesses(int account,int knight,long auth,TownCatalog.Station station,long cycle,Set<Integer> selected)throws IOException{
        return change(account,knight,auth,(c,p)->sprites.installHarnesses(c,p,station,cycle,selected));
    }
    /** Current archive harness installation consumes one kit and persists one owned harness. */
    public Result installHarness(int account,int knight,long auth,long kit)throws IOException{
        return change(account,knight,auth,(c,p)->sprites.installHarness(c,p,kit));
    }
    /** Sprite service IDs and argument order are the current stock BattleSpriteMarshaller's. */
    public Result sprite(int account,int knight,long auth,int method,List<Object> args,boolean staff)throws IOException{
        return change(account,knight,auth,(c,p)->sprites.sprite(c,p,method,args,staff));
    }
    static Set<Integer> unlocked(TownCatalog rules,int level,Set<Integer> perks){var result=new HashSet<Integer>();for(int i=0;i<rules.perks().size();i++)if(rules.perks().get(i).tier()<level||perks.contains(i))result.add(i);
        var remove=new HashSet<Integer>();for(int i:result){String replaced=rules.perks().get(i).previous();for(int j:result)if(rules.perks().get(j).name().equals(replaced))remove.add(j);}result.removeAll(remove);return result;}
}
