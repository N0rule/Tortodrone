package org.tortodrone.storage;

import java.io.*;
import java.util.*;
import java.util.random.RandomGenerator;
import java.security.SecureRandom;
import org.tortodrone.character.*;
import org.tortodrone.loot.*;
import org.tortodrone.protocol.SessionDecoder;

/** One authenticated BEGIN IMMEDIATE write: consume, grant, reload, commit; never publish here. */
public final class BoxOpeningService {
    private static final SecureRandom RANDOM=new SecureRandom();
    private final Database database;
    private final ItemCatalog items;
    private final BoxCatalog boxes;
    private final RandomGenerator random;
    public BoxOpeningService(Database database,ItemCatalog items,BoxCatalog boxes){this(database,items,boxes,RANDOM);}
    public BoxOpeningService(Database database,ItemCatalog items,BoxCatalog boxes,RandomGenerator random){
        this.database=database;this.items=items;this.boxes=boxes;this.random=Objects.requireNonNull(random);
    }
    /** keyOption is the native menu index, not a key instance ID. Revision rejects stale concurrent intent. */
    public TownStore.Result open(int account,int knight,long auth,long revision,long boxId,int keyOption)throws IOException {
        if(auth<0||revision<1||boxId<1)throw new IOException("m.internal_error");
        return database.write(c->{
            var profile=WorldProfiles.load(c,account,knight,auth);
            if(profile.revision()!=revision)throw new IOException("Your inventory changed. Please try again.");
            var held=TownStore.owned(profile,boxId);var box=boxes.find(held.config());
            if(box==null||!box.enabled())throw new IOException("This box has no complete supported reward distribution.");
            if(held.equippedSlot()!=null||held.permanent()||held.gift()!=null||!items.require(held.config()).type().equals("Lockbox"))throw new IOException("m.internal_error");
            WorldProfile.Item key=null;
            if(box.kind()==BoxDefinition.Kind.KEYLESS) {
                if(keyOption!=-1)throw new IOException("m.internal_error");
            }else {
                if(keyOption<0||keyOption>=box.keys().size())throw new IOException("m.internal_error");
                String config=box.keys().get(keyOption);
                // The wire identifies a key type only. Choose a deterministic owned stack of that exact type.
                key=profile.items().stream().filter(i->i.config().equals(config)&&i.count()>0&&i.equippedSlot()==null&&!i.permanent()).min(Comparator.comparingLong(WorldProfile.Item::id)).orElseThrow(()->new IOException("m.internal_error"));
            }
            var prizes=BoxRewardSelector.select(box,random);
            if(prizes.isEmpty())throw new IOException("m.internal_error");
            var delivery=plan(profile,held,key,prizes);
            TownStore.consume(c,profile,boxId,1);
            if(key!=null)TownStore.consume(c,profile,key.id(),1);
            var inventory=new TownStore(database,items,org.tortodrone.town.TownCatalog.empty());
            var result=new LinkedHashMap<Object,Integer>();
            for(var grant:delivery) {
                if(grant.granted==0)continue;
                if(grant.existing!=0) {
                    try(var update=c.prepareStatement("UPDATE inventory_items SET count=count+? WHERE world_id=? AND item_id=? AND count+?<=9999")) {
                        update.setInt(1,grant.granted);update.setInt(2,profile.worldId());update.setLong(3,grant.existing);update.setInt(4,grant.granted);
                        if(update.executeUpdate()!=1)throw new IOException("Item changed while opening");
                    }
                    try(var audit=c.prepareStatement("INSERT INTO item_events(world_id,config,quantity,first_item_id,created_at) VALUES(?,?,?,?,?)")) {
                        audit.setInt(1,profile.worldId());audit.setString(2,grant.item.config());audit.setInt(3,grant.granted);audit.setLong(4,grant.existing);audit.setLong(5,System.currentTimeMillis());audit.executeUpdate();
                    }
                    result.put(grant.existing,grant.granted);
                }else {
                    // Return every actual non-stackable identity to the native reveal window.
                    for(int n=0;n<(grant.item.stackable()?1:grant.granted);n++) {
                        int count=grant.item.stackable()?grant.granted:1;
                        long id=inventory.create(c,profile,grant.item.config(),count,grant.bound,0,0,List.of());result.put(id,count);
                    }
                }
            }
            TownStore.touch(c,profile.worldId());
            return new TownStore.Result(WorldProfiles.load(c,account,knight,auth),new SessionDecoder.Multiset(result));
        });
    }
    private static final class Grant {
        final ItemCatalog.Entry item;
        final long existing;
        final boolean bound;
        int count,granted;
        Grant(ItemCatalog.Entry item,long existing,boolean bound,int count){this.item=item;this.existing=existing;this.bound=bound;this.count=count;}
    }
    /** Project consumption and compatible stack merges before changing any inventory row. */
    private List<Grant> plan(WorldProfile profile,WorldProfile.Item box,WorldProfile.Item key,List<BoxRewardSelector.Prize> prizes)throws IOException {
        int slots=profile.items().size()-(box.count()==1?1:0)-(key!=null&&key.count()==1?1:0);
        var delivery=new ArrayList<Grant>();
        for(var held:profile.items()) {
            int remaining=held.count()-(held.id()==box.id()||key!=null&&held.id()==key.id()?1:0);
            var entry=items.find(held.config());
            if(remaining>0&&entry!=null&&entry.stackable()&&held.slot()==WorldProfile.Slot.OTHER&&!held.starter()
                    &&held.equippedSlot()==null&&held.accessory()==null&&held.additionalAccessories().isEmpty()&&!held.cursed()&&held.variants().isEmpty()
                    &&held.gift()==null&&held.level()==0&&held.heat()==0&&held.heatProgress()==0
                    &&held.permanent()==entry.permanent()&&held.bindOnEquip()==entry.bindOnEquip())
                delivery.add(new Grant(entry,held.id(),held.bound(),remaining));
        }
        for(var prize:prizes) {
            var entry=items.require(prize.config());
            if(prize.count()<1||prize.count()>9999||entry.type().equals("GiftBox")||prize.binding()==BoxDefinition.Binding.UNBOUND&&entry.locked())throw new IOException("Invalid box reward");
            boolean bound=prize.binding()==BoxDefinition.Binding.BOUND||entry.locked();
            Grant merge=null;
            if(entry.stackable())for(var candidate:delivery)if(candidate.item.config().equals(entry.config())&&candidate.bound==bound&&candidate.count+prize.count()<=9999){merge=candidate;break;}
            if(merge==null) {
                merge=new Grant(entry,0,bound,0);delivery.add(merge);slots+=entry.stackable()?1:prize.count();
            }
            merge.count+=prize.count();merge.granted+=prize.count();
        }
        if(slots>ItemCatalog.INVENTORY_LIMIT)throw new IOException("Your inventory is full. Make room for all box rewards.");
        return delivery;
    }

}
