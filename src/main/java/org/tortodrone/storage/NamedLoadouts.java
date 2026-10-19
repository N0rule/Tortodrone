package org.tortodrone.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.town.TownState;

/** Named equipment snapshots in the existing integer metadata namespace; no schema migration. */
final class NamedLoadouts {
    private NamedLoadouts() { }
    private static String prefix(int world){return "knight.loadout."+world+".";}
    private static String key(int world,String name){return prefix(world)+Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8))+".";}
    private static void validate(String name)throws IOException {
        if(name==null||name.isBlank()||!name.equals(name.strip())||name.codePointCount(0,name.length())>32||name.chars().anyMatch(Character::isISOControl))throw new IOException("Loadout names must contain 1–32 visible characters");
    }
    static List<TownState.Loadout> load(Connection c,int world)throws SQLException,IOException {
        String prefix=prefix(world);var values=new TreeMap<String,Map<String,Long>>();
        try(var s=c.prepareStatement("SELECT key,value FROM metadata WHERE key>=? AND key<? ORDER BY key")) {
            s.setString(1,prefix);s.setString(2,prefix+"~");try(var r=s.executeQuery()){while(r.next()){
                String suffix=r.getString(1).substring(prefix.length());int dot=suffix.lastIndexOf('.');
                if(dot<1)throw new IOException("Invalid saved loadout key");
                String name;try{name=new String(Base64.getUrlDecoder().decode(suffix.substring(0,dot)),StandardCharsets.UTF_8);}catch(IllegalArgumentException invalid){throw new IOException("Invalid saved loadout name",invalid);}
                validate(name);values.computeIfAbsent(name,k->new HashMap<>()).put(suffix.substring(dot+1),r.getLong(2));
            }}
        }
        if(values.size()>10)throw new IOException("Too many saved loadouts");
        var result=new ArrayList<TownState.Loadout>();var names=new HashSet<String>();
        for(var row:values.entrySet()) {
            if(!names.add(row.getKey().toLowerCase(Locale.ROOT)))throw new IOException("Duplicate saved loadout name");
            var fields=row.getValue();if(fields.size()!=19)throw new IOException("Incomplete saved loadout");
            var equipment=new ArrayList<Long>();for(int i=0;i<16;i++)equipment.add(require(fields,"slot"+i));
            long sprite=require(fields,"sprite"),harness=require(fields,"harness"),perk=require(fields,"perk");
            if(equipment.stream().anyMatch(i->i<0)||sprite<0||sprite>Integer.MAX_VALUE||harness<0||perk< -1||perk>Integer.MAX_VALUE)throw new IOException("Invalid saved loadout state");
            result.add(new TownState.Loadout(row.getKey(),equipment,(int)sprite,harness,perk<0?null:(int)perk));
        }
        result.sort(Comparator.comparing(l->l.name().toLowerCase(Locale.ROOT)));
        return List.copyOf(result);
    }
    private static long require(Map<String,Long> fields,String key)throws IOException {var value=fields.get(key);if(value==null)throw new IOException("Incomplete saved loadout");return value;}
    static void save(Connection c,WorldProfile p,String name)throws SQLException,IOException {
        validate(name);
        String storedName=p.town().loadouts().stream().map(TownState.Loadout::name).filter(n->n.equalsIgnoreCase(name)).findFirst().orElse(name);
        if(p.town().loadouts().stream().noneMatch(l->l.name().equalsIgnoreCase(name))&&p.town().loadouts().size()>=10)throw new IOException("At most 10 named loadouts can be saved");
        var sprite=p.town().sprites().stream().filter(s->s.id()==p.town().equippedSprite()).findFirst().orElse(null);
        var values=new LinkedHashMap<String,Long>();long[] equipment=p.equipment();
        for(int i=0;i<16;i++)values.put("slot"+i,equipment[i]);
        values.put("sprite",(long)p.town().equippedSprite());values.put("harness",sprite==null?0:sprite.harness());values.put("perk",sprite==null||sprite.perk()==null?-1L:sprite.perk().longValue());
        try(var s=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            for(var entry:values.entrySet()){s.setString(1,key(p.worldId(),storedName)+entry.getKey());s.setLong(2,entry.getValue());s.executeUpdate();}
        }
    }
    static void equip(Connection c,WorldProfile p,String name,org.tortodrone.town.TownCatalog rules)throws SQLException,IOException {
        validate(name);var saved=p.town().loadouts().stream().filter(l->l.name().equalsIgnoreCase(name)).findFirst().orElseThrow(()->new IOException("No saved loadout with that name"));
        var used=new HashSet<Long>();
        for(int slot=0;slot<16;slot++) {
            long id=saved.equipment().get(slot);if(id==0)continue;
            var item=TownStore.owned(p,id);
            if(!used.add(id)||!item.slot().accepts(slot)||!SlotUpgrades.unlocked(p,slot))throw new IOException("A saved item or equipment slot is no longer available");
        }
        var sprite=p.town().sprites().stream().filter(s->s.id()==saved.sprite()).findFirst().orElse(null);
        if(saved.sprite()!=0) {
            if(sprite==null)throw new IOException("Saved battle sprite is no longer owned");
            if(saved.perk()!=null&&!TownStore.unlocked(rules,sprite.perkLevel(),sprite.perks()).contains(saved.perk()))throw new IOException("Saved sprite perk is no longer unlocked");
            if(saved.harness()!=0) {
                var harness=p.town().harnesses().stream().filter(h->h.id()==saved.harness()).findFirst().orElseThrow(()->new IOException("Saved harness is no longer owned"));
                var definition=rules.sprite(sprite.config());
                if(definition==null||definition.restrictedDamage().equals(rules.harnessDamage(harness.config())))throw new IOException("Saved harness is not compatible with the sprite");
            }
        }
        try(var s=c.prepareStatement("UPDATE inventory_items SET equipped_slot=NULL WHERE world_id=?")){s.setInt(1,p.worldId());s.executeUpdate();}
        try(var s=c.prepareStatement("DELETE FROM metadata WHERE key IN(SELECT 'item.weapon-slot.'||item_id FROM inventory_items WHERE world_id=?)")){s.setInt(1,p.worldId());s.executeUpdate();}
        for(int slot=0;slot<16;slot++) {
            long id=saved.equipment().get(slot);if(id==0)continue;
            try(var s=c.prepareStatement("UPDATE inventory_items SET equipped_slot=?,bound=CASE WHEN bind_on_equip=1 THEN 1 ELSE bound END WHERE world_id=? AND item_id=?")) {
                if(slot==8||slot==9)s.setNull(1,Types.INTEGER);else s.setInt(1,slot);s.setInt(2,p.worldId());s.setLong(3,id);if(s.executeUpdate()!=1)throw new IOException("Saved item changed");
            }
            if(slot==8||slot==9)try(var s=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?)")){s.setString(1,"item.weapon-slot."+id);s.setInt(2,slot);s.executeUpdate();}
        }
        try(var s=c.prepareStatement("INSERT INTO town_equipped(world_id,sprite) VALUES(?,?) ON CONFLICT(world_id) DO UPDATE SET sprite=excluded.sprite")){s.setInt(1,p.worldId());s.setInt(2,saved.sprite());s.executeUpdate();}
        if(sprite!=null)try(var s=c.prepareStatement("UPDATE town_sprites SET harness=?,perk=? WHERE world_id=? AND id=?")) {
            s.setLong(1,saved.harness());if(saved.perk()==null)s.setNull(2,Types.INTEGER);else s.setInt(2,saved.perk());s.setInt(3,p.worldId());s.setInt(4,saved.sprite());s.executeUpdate();
        }
    }
    static void clean(Connection c)throws SQLException {
        try(var s=c.createStatement()){s.executeUpdate("DELETE FROM metadata WHERE key LIKE 'knight.loadout.%' AND CAST(substr(key,16,instr(substr(key,16),'.')-1) AS INTEGER) NOT IN(SELECT world_id FROM world_characters)");}
    }
}
