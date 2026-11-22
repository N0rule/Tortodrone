package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.core.character.Knight;

/** Knight roster rows and their compare-and-swap saves, split from the transaction owner.
 *
 * <p>The repository keeps the existing atomic roster transaction intact: revision
 * check, row replacement, fresh world creation and appearance all commit together.
 */
final class RosterRepository {
    private final Database database;

    RosterRepository(Database database) { this.database = database; }

    /** Availability is advisory; the unique index arbitrates competing writes. */
    boolean knightNameInUse(String name,int accountId,int knightId)throws IOException {
        return database.read(c->{try(var s=c.prepareStatement("SELECT 1 FROM knights WHERE name_key=? AND NOT(account_id=? AND id=?) LIMIT 1")) {
            s.setString(1,name.toLowerCase(Locale.ROOT));s.setInt(2,accountId);s.setInt(3,knightId);
            try(var r=s.executeQuery()){return r.next();}
        }});
    }

    List<Knight> roster(int id) throws IOException {
        return database.read(c -> { List<Knight> result = new ArrayList<>();
            try (PreparedStatement s = c.prepareStatement("SELECT * FROM knights WHERE account_id=? ORDER BY id")) {
                s.setInt(1,id); try (ResultSet r = s.executeQuery()) { while (r.next()) result.add(knight(r)); }
            } return List.copyOf(result);
        });
    }

    /** One SQLite snapshot for the roster, allocation counter and compare-and-swap revision. */
    Database.RosterState rosterState(int id)throws IOException {
        return database.read(c->{
            c.setAutoCommit(false);
            int next;long revision;
            try(var s=c.prepareStatement("SELECT next_knight_id,revision FROM accounts WHERE id=?")) {
                s.setInt(1,id);try(var r=s.executeQuery()){if(!r.next())throw new IOException("Account no longer exists");next=r.getInt(1);revision=r.getLong(2);}
            }
            var knights=new ArrayList<Knight>();
            try(var s=c.prepareStatement("SELECT * FROM knights WHERE account_id=? ORDER BY id")) {
                s.setInt(1,id);try(var r=s.executeQuery()){while(r.next())knights.add(knight(r));}
            }
            return new Database.RosterState(List.copyOf(knights),next,revision);
        });
    }

    void deleteKnight(String username,String name,int expectedAccount,int expectedWorld)throws IOException {
        database.write(c->{
            int account=AccountRepository.requireId(c,username);
            if(account!=expectedAccount)throw new IOException("Account changed; refresh the account list");
            int knight,world;
            try(var s=c.prepareStatement("SELECT k.id,w.world_id FROM knights k JOIN world_characters w ON w.account_id=k.account_id AND w.knight_id=k.id WHERE k.account_id=? AND k.name_key=?")) {
                s.setInt(1,account);s.setString(2,name.toLowerCase(Locale.ROOT));
                try(var r=s.executeQuery()){if(!r.next())throw new IOException("No such knight");knight=r.getInt(1);world=r.getInt(2);}
            }
            if(world!=expectedWorld)throw new IOException("Knight changed; refresh the knight list");
            try(var s=c.prepareStatement("DELETE FROM currency_events WHERE world_id=?")){s.setInt(1,world);s.executeUpdate();}
            try(var s=c.prepareStatement("DELETE FROM knights WHERE account_id=? AND id=?")){s.setInt(1,account);s.setInt(2,knight);s.executeUpdate();}
            WorldProfiles.cleanProgress(c);
            try(var s=c.prepareStatement("UPDATE accounts SET auth_revision=auth_revision+1,revision=revision+1 WHERE id=?")){s.setInt(1,account);s.executeUpdate();}
            AccountRepository.event(c,account,"knight deleted; world="+world);return null;
        });
    }

    void saveRoster(int id,List<Knight> knights,int next,long revision,long rosterRevision) throws IOException {
        saveRoster(id,knights,next,revision,rosterRevision,0);
    }

    void saveRoster(int id,List<Knight> knights,int next,long revision,long rosterRevision,int openingKnight)throws IOException {
        database.write(c -> {
            try(var s=c.prepareStatement("SELECT revision FROM accounts WHERE id=?")) {
                s.setInt(1,id);try(var r=s.executeQuery()) {
                    if(!r.next()||r.getLong(1)!=rosterRevision)throw new IOException("Roster changed; refresh or reconnect before saving characters");
                }
            }
            if(openingKnight!=0){
                if(openingKnight<1||knights.stream().noneMatch(k->k.id()==openingKnight))throw new IOException("Invalid opening knight");
                try(var s=c.prepareStatement("SELECT 1 FROM knights WHERE account_id=? AND id=?")){
                    s.setInt(1,id);s.setInt(2,openingKnight);try(var r=s.executeQuery()){if(r.next())throw new IOException("Existing knight cannot restart onboarding");}
                }
            }
            replaceRoster(c,id,knights,next,revision);
            for (Knight knight : knights) { boolean fresh;
                try(PreparedStatement statement=c.prepareStatement("SELECT world_id FROM world_characters WHERE account_id=? AND knight_id=?")) {
                    statement.setInt(1,id);statement.setInt(2,knight.id());try(ResultSet r=statement.executeQuery()){fresh=!r.next();}
                }
                WorldProfiles.ensure(c,id,knight);
                if(fresh)try(PreparedStatement statement=c.prepareStatement("UPDATE inventory_items SET bound=1,starter=1,equipped_slot=CASE slot WHEN 'HELM' THEN 0 WHEN 'ARMOR' THEN 1 WHEN 'SHIELD' THEN 2 WHEN 'SWORD' THEN 6 WHEN 'GUN' THEN 7 END WHERE world_id=(SELECT world_id FROM world_characters WHERE account_id=? AND knight_id=?)")) {statement.setInt(1,id);statement.setInt(2,knight.id());statement.executeUpdate();}
                WorldProfiles.appearance(c,id,knight); }
            if(openingKnight!=0)try(var s=c.prepareStatement("INSERT INTO metadata(key,value) SELECT 'world.'||world_id||'.opening-stage',0 FROM world_characters WHERE account_id=? AND knight_id=?")){
                s.setInt(1,id);s.setInt(2,openingKnight);if(s.executeUpdate()!=1)throw new IOException("Opening owner was not created");
            }
            return null;
        });
    }

    private static Knight knight(ResultSet r) throws SQLException {
        return new Knight(r.getInt("id"),r.getString("name"),r.getString("helm"),r.getString("armor"),r.getString("helm_accessory"),r.getString("armor_accessory"),r.getString("costume"),r.getInt("color"),r.getInt("height"),r.getInt("eye"),r.getLong("created_at"));
    }

    private static void replaceRoster(Connection c,int id,List<Knight> knights,int next,long revision) throws IOException, SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE accounts SET next_knight_id=?,revision=revision+1 WHERE id=?" + (revision >= 0 ? " AND auth_revision=?" : ""))) {
            s.setInt(1,next); s.setInt(2,id); if (revision >= 0) s.setLong(3,revision);
            if (s.executeUpdate() != 1) throw new IOException("Account changed; reconnect before saving characters");
        }
        // Delete removed rows first so a rename can reuse a deleted name without a false unique conflict.
        String placeholders = String.join(",", Collections.nCopies(knights.size(),"?"));
        try (PreparedStatement s = c.prepareStatement("DELETE FROM knights WHERE account_id=?" + (knights.isEmpty() ? "" : " AND id NOT IN(" + placeholders + ")"))) {
            s.setInt(1,id); for (int i=0;i<knights.size();i++) s.setInt(i+2,knights.get(i).id()); s.executeUpdate();
        }
        for (Knight k : knights) try (PreparedStatement s = c.prepareStatement("INSERT INTO knights(account_id,id,name,name_key,helm,armor,helm_accessory,armor_accessory,costume,color,height,eye,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(account_id,id) DO UPDATE SET name=excluded.name,name_key=excluded.name_key,helm=excluded.helm,armor=excluded.armor,helm_accessory=excluded.helm_accessory,armor_accessory=excluded.armor_accessory,costume=excluded.costume,color=excluded.color,height=excluded.height,eye=excluded.eye,created_at=excluded.created_at,last_renamed=knights.last_renamed")) {
            s.setInt(1,id); s.setInt(2,k.id()); s.setString(3,k.name()); s.setString(4,k.name().toLowerCase(Locale.ROOT)); s.setString(5,k.helm()); s.setString(6,k.armor()); s.setString(7,k.helmAccessory()); s.setString(8,k.armorAccessory()); s.setString(9,k.costume()); s.setInt(10,k.color()); s.setInt(11,k.height()); s.setInt(12,k.eye()); s.setLong(13,k.createdAt()); s.executeUpdate();
        }
        WorldProfiles.cleanProgress(c);
    }
}
