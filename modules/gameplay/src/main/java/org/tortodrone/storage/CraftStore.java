package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.core.Json;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.NativeStats;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.core.town.TownCatalog;

/** Recipe learning and crafting, including ingredient matching and orb insertion. */
final class CraftStore {
    private final ItemCatalog items;
    private final TownCatalog rules;
    CraftStore(ItemCatalog items,TownCatalog rules){this.items=items;this.rules=rules;}

    /** Consume one recipe item and persist the learned recipe, resetting a stale learned row. */
    Object learn(Connection c,WorldProfile p,long item)throws SQLException,IOException{
        var held=TownLedger.owned(p,item);String config=rules.item(held.config()).get("recipe");
        if(config==null||rules.recipe(config)==null)throw new IOException("m.internal_error");
        var prior=p.town().recipes().stream().filter(r->r.config().equals(config)).findFirst().orElse(null);
        long now=System.currentTimeMillis();
        if(prior!=null&&(prior.expires()==0||prior.expires()>now))throw new IOException("Recipe already learned");
        TownLedger.consume(c,p,item,1);learn(c,p,config,now);return null;
    }
    private void learn(Connection c,WorldProfile p,String config,long now)throws SQLException,IOException{
        var r=rules.recipe(config);if(r==null)throw new IOException("Unknown recipe");
        long expires=r.maxHours()==0?0:Math.addExact(now,r.maxHours()*3600000L);
        try(var s=c.prepareStatement("INSERT INTO town_recipes(world_id,config,expires) VALUES(?,?,?) ON CONFLICT(world_id,config) DO UPDATE SET crafted=0,expires=excluded.expires")){s.setInt(1,p.worldId());s.setString(2,config);s.setLong(3,expires);s.executeUpdate();}
    }

    Object craft(Connection c,WorldProfile p,String recipe,long[] selected,boolean upgrade,boolean catalogue)throws SQLException,IOException{
        if(selected.length>128)throw new IOException("Too many ingredients");
        var r=rules.recipe(recipe);if(r==null)throw new IOException("Unknown recipe");
        var learned=p.town().recipes().stream().filter(v->v.config().equals(recipe)).findFirst().orElse(null);long now=System.currentTimeMillis();
        if(!catalogue&&learned==null||learned!=null&&(learned.expires()!=0&&learned.expires()<=now||r.oneTime()&&learned.crafted()>0))throw new IOException("Recipe is not available");
        var output=items.require(r.item());var ingredients=new ArrayList<>(r.ingredients());
        if(r.orbs(output.rarity())&&output.rarity()>0&&output.rarity()<=5){String orb="Rarity/Orb of Alchemy/"+new String[]{"","Flawed","Simple","Advanced","Elite","Eternal"}[output.rarity()];
            if(ingredients.stream().noneMatch(i->i.item().equals(orb)))ingredients.add(new TownCatalog.Ingredient("Item",orb,3,-1,"",1,"NEVER",false,List.of()));}
        // Specific items first, then groups, so a generic food material cannot steal an exact ingredient.
        ingredients.sort(Comparator.comparingInt(i->i.item().isEmpty()?1:0));
        var ids=new LinkedHashSet<Long>();for(long id:selected)if(!ids.add(id))throw new IOException("Duplicate ingredient");
        var remaining=new LinkedHashMap<Long,Integer>();for(long id:ids){var i=TownLedger.owned(p,id);if(i.equippedSlot()!=null||i.permanent()||i.gift()!=null||i.expired(now))throw new IOException("Ingredient must be available and unequipped");remaining.put(id,i.count());}
        var used=new LinkedHashMap<Long,Integer>();int level=0;float heat=0;List<StarterCatalog.Model> variants=List.of();boolean retainVariants=false,bound=r.locked();
        var accessories=new ArrayList<String>();
        for(var ingredient:ingredients){int needed=ingredient.count();for(long id:ids){var item=TownLedger.owned(p,id);int available=remaining.get(id);if(available==0||!matches(ingredient,item))continue;int n=Math.min(needed,available);remaining.put(id,available-n);used.merge(id,n,Integer::sum);needed-=n;
                if(ingredient.kind().endsWith("LevelItem")){
                    bound|=item.bound();
                    if(ingredient.keepHeat()){level=item.level();heat=item.heatProgress();}
                    if(ingredient.upgrade().equals("ALWAYS")||ingredient.upgrade().equals("CHOICE")&&upgrade){variants=item.variants();retainVariants=true;}
                    if(item.slot()==output.slot()){
                        var blocked=new HashSet<>(rules.blockers(output.config()));
                        String costume="";
                        if(item.accessory()!=null)try(var st=c.prepareStatement("SELECT costume FROM knights WHERE account_id=? AND id=?")){
                            st.setInt(1,p.accountId());st.setInt(2,p.knightId());try(var row=st.executeQuery()){if(!row.next())throw new IOException("Missing knight");costume=row.getString(1);}
                        }
                        for(String accessory:item.nativeAccessories(costume)){
                            var definition=rules.accessory(accessory);
                            if(definition!=null&&Collections.disjoint(definition.blocks(),blocked)){
                                accessories.add(accessory);blocked.addAll(definition.blocks());
                            }
                        }
                    }
                }
                if(needed==0)break;}
            if(needed!=0)throw new IOException("Missing recipe ingredients");}
        if(used.size()!=ids.size())throw new IOException("Unexpected recipe ingredient");
        TownLedger.pay(c,p,r.cost(output.rarity()),0);for(var use:used.entrySet())TownLedger.consume(c,p,use.getKey(),use.getValue());
        if(!retainVariants)variants=rules.roll(r.variantTable(),group(output));
        var progression=rules.gear().gear(output.config());
        if(progression==null||progression.levels()<1){level=0;heat=0;}
        else if(progression.maximum(level)){level=progression.levels()-1;heat=0;}
        long result=TownLedger.create(c,p,items,r.item(),r.count(),bound,level,heat,variants);
        if(!accessories.isEmpty()){
            if(r.count()!=1)throw new IOException("Accessory transfer requires one equipment output");
            try(var st=c.prepareStatement("UPDATE inventory_items SET accessories=? WHERE world_id=? AND item_id=?")){
                st.setString(1,Json.stringify(accessories));st.setInt(2,p.worldId());st.setLong(3,result);if(st.executeUpdate()!=1)throw new IOException("Crafted item changed");
            }
        }
        if(learned!=null)try(var s=c.prepareStatement("UPDATE town_recipes SET crafted=crafted+1 WHERE id=? AND world_id=?")){s.setLong(1,learned.id());s.setInt(2,p.worldId());s.executeUpdate();}
        AccountAchievements.increment(c,p.accountId(),org.tortodrone.core.character.NativeStats.ITEMS_CRAFTED,1);
        return result;
    }

    private boolean matches(TownCatalog.Ingredient i,WorldProfile.Item item){
        var d=items.find(item.config());if(d==null)return false;
        if(i.kind().equals("SpriteFoodMaterial")){String role=rules.item(item.config()).getOrDefault("spriteFoodRole","");return (role.equals("MATERIAL_AND_FOOD"))&&(i.rarity()==-1||i.rarity()==d.rarity());}
        if(i.kind().equals("ItemGroup"))return (i.rarity()==-1||i.rarity()==d.rarity())&&group(d).equals(i.group());
        return (item.config().equals(i.item())||i.options().contains(item.config()))&&(!i.kind().endsWith("LevelItem")||item.level()>=i.level()-1);
    }
    static String group(ItemCatalog.Entry entry){return TownCatalog.itemGroup(entry);}
}
