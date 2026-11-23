package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.core.Json;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.core.town.SpriteRules;
import org.tortodrone.core.town.TownCatalog;

/** Owned sprites, harnesses and perks, plus the stock BattleSpriteMarshaller operations. */
final class SpriteStore {
    private final ItemCatalog items;
    private final TownCatalog rules;
    SpriteStore(ItemCatalog items,TownCatalog rules){this.items=items;this.rules=rules;}

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
    Object adminSprite(Connection c,WorldProfile p,int account,long auth,int method,List<Object> args)throws SQLException,IOException{
        TownStore.requireGameMaster(c,account,auth);
        if(method==8){
            String config=((org.tortodrone.core.wire.ConfigReference)args.get(0)).name();
            if(config==null||rules.sprite(config)==null)throw new IOException("Unknown sprite configuration");
            return createSprite(c,p,config);
        }
        var sprite=p.town().sprites().stream().filter(s->s.id()==p.town().equippedSprite()).findFirst().orElseThrow(()->new IOException("Equip a battle sprite first; /sprite_list shows owned sprite IDs"));
        String field,json=null;int level=sprite.level();
        switch(method){
            case 45->{level=(Integer)args.get(0);if(level<0||level>99)throw new IOException("Sprite level must be between 1 and 100");field="level";
                // Advance the same perk draws skipped by the direct GM level setter.
                // Preserve already earned perks, including post-level-100 training.
                int expected=0;for(int at=0;at<level;at++)if(SpriteRules.grantsPerk(at))expected++;
                int earned=sprite.perks().size();for(var candidate:rules.perks())if(candidate.tier()<sprite.perkLevel())earned++;
                int tier=sprite.perkLevel();var perks=new HashSet<>(sprite.perks());Integer selected=sprite.perk();
                for(int draw=earned;draw<expected&&tier<=5;draw++){
                    var candidates=new ArrayList<Integer>();for(int i=0;i<rules.perks().size();i++)if(rules.perks().get(i).tier()==tier&&!perks.contains(i))candidates.add(i);
                    if(candidates.isEmpty())throw new IOException("Missing sprite perk tier");
                    int granted=candidates.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(candidates.size()));perks.add(granted);
                    if(selected!=null&&rules.perks().get(granted).previous().equals(rules.perks().get(selected).name()))selected=granted;
                    int total=0;for(var candidate:rules.perks())if(candidate.tier()==tier)total++;
                    if(perks.size()==total){tier++;perks.clear();}
                }
                try(var st=c.prepareStatement("UPDATE town_sprites SET perk_level=?,perks=?,perk=? WHERE id=? AND world_id=?")){
                    st.setInt(1,tier);st.setString(2,Json.stringify(perks.stream().sorted().toList()));if(selected==null)st.setNull(3,Types.INTEGER);else st.setInt(3,selected);st.setInt(4,sprite.id());st.setInt(5,p.worldId());if(st.executeUpdate()!=1)throw new IOException("Sprite changed; retry");
                }
            }
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
    }
    Object hatch(Connection c,WorldProfile p,long egg)throws SQLException,IOException{
        var item=TownStore.owned(p,egg);String config=rules.item(item.config()).get("sprite");if(config==null||rules.sprite(config)==null)throw new IOException("m.internal_error");
        TownStore.consume(c,p,egg,1);
        try(var s=c.prepareStatement("INSERT INTO town_sprites(world_id,config) VALUES(?,?)",Statement.RETURN_GENERATED_KEYS)){
            s.setInt(1,p.worldId());s.setString(2,config);s.executeUpdate();try(var keys=s.getGeneratedKeys()){keys.next();return keys.getInt(1);}
        }
    }
    Object installHarnesses(Connection c,WorldProfile p,TownCatalog.Station station,long cycle,Set<Integer> selected)throws SQLException,IOException{
        if(selected.isEmpty()||selected.size()>100||cycle!=0&&cycle<=System.currentTimeMillis())throw new IOException("e.good_unavailable");
        var goods=rules.goods(station,cycle,p.worldId());var configs=new ArrayList<String>();long crowns=0,energy=0;
        for(int index:selected){
            if(index<0||index>=goods.size())throw new IOException("e.good_unavailable");var good=goods.get(index);String config=rules.item(good.item()).get("harness");
            if(config==null||!rules.harnessAvailable(p,config)||rules.harnessDamage(config)==null||!good.token().isEmpty()||good.count()!=1||good.limit()!=0)throw new IOException("e.good_unavailable");
            var def=items.require(good.item());int price=good.price()<0?rules.price(good.item(),def.rarity(),true):good.price();if(price<0||good.energy()<0)throw new IOException("e.good_unavailable");
            crowns+=price;energy+=good.energy();configs.add(config);
        }
        if(crowns>Integer.MAX_VALUE||energy>Integer.MAX_VALUE)throw new IOException("Currency balance exceeds supported limits");TownStore.pay(c,p,(int)crowns,(int)energy);
        for(String config:configs){
            String prerequisite=rules.harnessPrerequisite(config);var prior=p.town().harnesses().stream().filter(h->h.config().equals(prerequisite)).findFirst().orElse(null);
            if(prior!=null){try(var st=c.prepareStatement("UPDATE town_harnesses SET config=? WHERE world_id=? AND id=?")){st.setString(1,config);st.setInt(2,p.worldId());st.setLong(3,prior.id());if(st.executeUpdate()!=1)throw new IOException("Harness changed");}}
            else try(var st=c.prepareStatement("INSERT INTO town_harnesses(world_id,config) VALUES(?,?)",Statement.RETURN_GENERATED_KEYS)){
                st.setInt(1,p.worldId());st.setString(2,config);st.executeUpdate();
                if(prerequisite.equals("Iron Harness"))try(var keys=st.getGeneratedKeys()){if(!keys.next())throw new SQLException("Missing harness ID");try(var equip=c.prepareStatement("UPDATE town_sprites SET harness=? WHERE world_id=? AND harness=0")){equip.setLong(1,keys.getLong(1));equip.setInt(2,p.worldId());equip.executeUpdate();}}
            }
        }
        return null;
    }
    /** Current archive harness installation consumes one kit and persists one owned harness. */
    long installHarness(Connection c,WorldProfile p,long kit)throws SQLException,IOException{
        var item=TownStore.owned(p,kit);String config=rules.item(item.config()).get("harness");
        if(config==null||rules.harnessDamage(config)==null)throw new IOException("m.internal_error");
        if(item.gift()!=null||item.expired(System.currentTimeMillis()))throw new IOException("m.internal_error");
        // Archive security-fixes S8: one kit buys one harness, atomically.
        TownStore.consume(c,p,kit,1);
        try(var s=c.prepareStatement("INSERT INTO town_harnesses(world_id,config) VALUES(?,?)",Statement.RETURN_GENERATED_KEYS)){
            s.setInt(1,p.worldId());s.setString(2,config);s.executeUpdate();
            try(var keys=s.getGeneratedKeys()){if(!keys.next())throw new SQLException("Missing installed harness ID");return keys.getLong(1);}
        }
    }
    /** Sprite service IDs and argument order are the current stock BattleSpriteMarshaller's. */
    Object sprite(Connection c,WorldProfile p,int method,List<Object> args,boolean staff)throws SQLException,IOException{
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
            case 3->{Integer requested=args.get(1)==null?null:((org.tortodrone.core.wire.EnumValue)args.get(1)).ordinal();
                var unlocked=unlocked(rules,perkLevel,perks);if(requested!=null&&!unlocked.contains(requested))throw new IOException("Perk not unlocked");perk=requested;}
            case 5->{harness=(Long)args.get(1);if(harness!=0){var h=p.town().harnesses().stream().filter(v->v.id()==(Long)args.get(1)).findFirst().orElseThrow(()->new IOException("Unknown owned harness"));if(definition.restrictedDamage().equals(rules.harnessDamage(h.config())))throw new IOException("This sprite cannot wear that harness");}}
            case 6->{long foodId=(Long)args.get(0);var food=TownStore.owned(p,foodId);if(food.config().equals("Food/Special/Reset Star")){if(sprite.spent()==0)throw new IOException("e.no_points_to_reset");Collections.fill(powers,0);Collections.fill(ultimates,0);}else{
                    String role=rules.item(food.config()).getOrDefault("spriteFoodRole","");if(!role.equals("FOOD_ONLY")&&!role.equals("MATERIAL_AND_FOOD"))throw new IOException("This item is not sprite food");
                    long now=System.currentTimeMillis();if(heat>=1)throw new IOException("Sprite heat is full");if(!SpriteRules.appetite(lastFull,now))throw new IOException("e.not_enough_appetite");
                    var fd=items.require(food.config());heat=Math.min(1,heat+SpriteRules.feedGain(fd.rarity(),definition.preferredFood().contains(food.config())));lastFull=SpriteRules.fedAt(lastFull,now);if(heat==1)fullStamp=now;
                }TownStore.consume(c,p,foodId,1);}
            case 7->{long foodId=(Long)args.get(1);boolean force=(Boolean)args.get(2);if((force||foodId==0)&&!staff)throw new IOException(force?"m.access_denied":"e.wrong_level_item");
                if(!force&&(heat!=1||level>=99&&perkLevel>5))throw new IOException("Sprite cannot level up");
                if(!staff&&p.progress().rank()<SpriteRules.requiredRank(level))throw new IOException("e.need_higher_rank");
                if(foodId!=0&&!force){var food=TownStore.owned(p,foodId);if(!food.config().equals(SpriteRules.levelItem(level,definition.preferredFood())))throw new IOException("e.wrong_level_item");TownStore.consume(c,p,foodId,1);}
                if(level>=99&&perkLevel>5)return null;
                boolean grant=SpriteRules.grantsPerk(level);level=Math.min(99,level+1);heat=0;
                if(grant&&perkLevel<=5){var candidates=new ArrayList<Integer>();for(int i=0;i<rules.perks().size();i++)if(rules.perks().get(i).tier()==perkLevel&&!perks.contains(i))candidates.add(i);
                    if(!candidates.isEmpty()){int granted=candidates.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(candidates.size()));perks.add(granted);result=new org.tortodrone.core.wire.EnumValue(org.tortodrone.core.WireTypes.SPRITE_PERK,granted);
                        if(perk!=null&&rules.perks().get(granted).previous().equals(rules.perks().get(perk).name()))perk=granted;
                        int total=0;for(var candidate:rules.perks())if(candidate.tier()==perkLevel)total++;if(perks.size()==total){perkLevel++;perks.clear();}
                    }
                }}
            case 8->{name=(String)args.get(1);if(name==null||!name.matches("[A-Za-z]+(?:[ -][A-Za-z]+)*")||name.length()>64)throw new IOException("m.invalid_name");}
            case 9->{int skill=(Integer)args.get(1),ultimate=((org.tortodrone.core.wire.EnumValue)args.get(2)).ordinal();if(!SpriteRules.ultimate(level,skill)||powers.subList(skill*3,skill*3+3).stream().mapToInt(Integer::intValue).sum()!=9||ultimates.get(skill)!=0||ultimate<1||ultimate>2)throw new IOException("Ultimate not available");ultimates.set(skill,ultimate);}
            default->throw new IOException("Unknown sprite operation");
        }
        try(var s=c.prepareStatement("UPDATE town_sprites SET name=?,level=?,heat=?,last_full=?,full_stamp=?,perk_level=?,perks=?,perk=?,powers=?,ultimates=?,harness=? WHERE id=? AND world_id=?")){
            s.setString(1,name);s.setInt(2,level);s.setFloat(3,heat);if(lastFull==null)s.setNull(4,Types.BIGINT);else s.setLong(4,lastFull);s.setLong(5,fullStamp);s.setInt(6,perkLevel);s.setString(7,Json.stringify(perks.stream().sorted().toList()));if(perk==null)s.setNull(8,Types.INTEGER);else s.setInt(8,perk);s.setString(9,Json.stringify(powers));s.setString(10,Json.stringify(ultimates));s.setLong(11,harness);s.setInt(12,id);s.setInt(13,p.worldId());s.executeUpdate();
        }return result;
    }
    private static Set<Integer> unlocked(TownCatalog rules,int level,Set<Integer> perks){var result=new HashSet<Integer>();for(int i=0;i<rules.perks().size();i++)if(rules.perks().get(i).tier()<level||perks.contains(i))result.add(i);
        var remove=new HashSet<Integer>();for(int i:result){String replaced=rules.perks().get(i).previous();for(int j:result)if(rules.perks().get(j).name().equals(replaced))remove.add(j);}result.removeAll(remove);return result;}
}
