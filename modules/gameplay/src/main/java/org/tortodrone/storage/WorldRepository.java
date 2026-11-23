package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.core.character.WorldProfile;

/** Knight world profiles, tutorials and story rows, split from the transaction owner.
 *
 * <p>Reads that must see one consistent inventory snapshot still set auto-commit off
 * on their own connection; writes still run through the single writer transaction,
 * so expiry, rollback and commit-before-publish are unchanged.
 */
final class WorldRepository {
    private final Database database;

    WorldRepository(Database database) { this.database = database; }

    WorldProfile worldProfile(int accountId,int knightId,long authRevision) throws IOException {
        return worldProfile(accountId,knightId,authRevision,null);
    }

    WorldProfile worldProfile(int accountId,int knightId,long authRevision,WorldProfile cached) throws IOException {
        return database.read(c -> {
            c.setAutoCommit(false); // One read snapshot for identity and inventory, even during deletion.
            var profile = WorldProfiles.load(c,accountId,knightId,authRevision,cached); c.commit(); return profile;
        });
    }

    /** Lightweight consistent metadata for active world observers; inventories load only on change. */
    Map<Integer,org.tortodrone.core.character.WorldSummary> worldStates()throws IOException {
        if(database.read(c->InventoryExpiry.due(c,System.currentTimeMillis())))database.sweepExpired();
        return database.read(c->{
            var states=new HashMap<Integer,org.tortodrone.core.character.WorldSummary>();
            try(var s=c.createStatement();var rows=s.executeQuery("SELECT w.account_id,w.knight_id,w.world_id,k.name,w.state_revision,a.auth_revision,a.game_master FROM world_characters w JOIN accounts a ON a.id=w.account_id JOIN knights k ON k.account_id=w.account_id AND k.id=w.knight_id")) {
                while(rows.next()) {
                    var state=new org.tortodrone.core.character.WorldSummary(rows.getInt(1),rows.getInt(2),rows.getInt(3),rows.getString(4),rows.getLong(5),rows.getLong(6),rows.getBoolean(7));
                    states.put(state.worldId(),state);
                }
            }
            return Map.copyOf(states);
        });
    }

    /** Indexed revision probe; does not materialize inventory or town state. */
    org.tortodrone.core.character.WorldSummary knightState(String username,String knight)throws IOException {
        if(database.read(c->InventoryExpiry.due(c,System.currentTimeMillis())))database.sweepExpired();
        return database.read(c->{try(var statement=c.prepareStatement("SELECT w.account_id,w.knight_id,w.world_id,k.name,w.state_revision,a.auth_revision,a.game_master FROM world_characters w JOIN accounts a ON a.id=w.account_id JOIN knights k ON k.account_id=w.account_id AND k.id=w.knight_id WHERE a.name_key=? AND k.name_key=?")){
            statement.setString(1,username.toLowerCase(Locale.ROOT));statement.setString(2,knight.toLowerCase(Locale.ROOT));
            try(var r=statement.executeQuery()){if(!r.next())throw new IOException("No such knight");return new org.tortodrone.core.character.WorldSummary(r.getInt(1),r.getInt(2),r.getInt(3),r.getString(4),r.getLong(5),r.getLong(6),r.getBoolean(7));}
        }});
    }

    /** Current world state without an authentication-revision check, for read-only publications. */
    WorldProfile profile(int accountId,int knightId) throws IOException {
        return worldProfile(accountId,knightId,-1);
    }

    WorldProfile completeTutorial(int accountId,int knightId,long authRevision,int worldId,long revision)throws IOException {
        return completeTutorial(accountId,knightId,authRevision,worldId,revision,0,null);
    }

    WorldProfile completeTutorial(int accountId,int knightId,long authRevision,int worldId,long revision,float heat,org.tortodrone.core.town.TownCatalog rules)throws IOException {
        if(!Float.isFinite(heat)||heat<0||heat>1_000_000||heat>0&&rules==null)throw new IOException("Invalid tutorial heat settlement");
        return database.write(c->{
            requirePreferenceOwner(c,accountId,authRevision);
            var profile=WorldProfiles.load(c,accountId,knightId,authRevision);
            if(profile.worldId()!=worldId)throw new IOException("Tutorial ownership changed");
            String receipt="world."+worldId+".tutorial-completed";
            try(var s=c.prepareStatement("SELECT value FROM metadata WHERE key=?")){
                s.setString(1,receipt);try(var r=s.executeQuery()){
                    if(r.next()){
                        if(r.getInt(1)!=1)throw new IOException("Invalid tutorial receipt");
                        return profile; // A retry must not overwrite a later saved town.
                    }
                }
            }
            if(profile.revision()!=revision)throw new IOException("Knight changed before tutorial settlement");
            if(heat>0)TownStore.applyHeat(c,profile,rules,heat);
            if(profile.progress().crashSiteIncomplete()){
                try(var s=c.prepareStatement("UPDATE metadata SET value=3 WHERE key=? AND value=0")){
                    s.setString(1,"world."+worldId+".opening-stage");if(s.executeUpdate()!=1)throw new IOException("Opening checkpoint changed");
                }
                var progress=profile.progress();
                if(progress.rank()<Database.ARRIVAL_RANK||progress.rank()==Database.ARRIVAL_RANK&&progress.subrank()<Database.ARRIVAL_SUBRANK)
                    try(var s=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){
                        s.setString(1,WorldProfiles.progressKey(worldId,"rank"));s.setInt(2,Database.ARRIVAL_RANK*10+Database.ARRIVAL_SUBRANK);s.executeUpdate();
                    }
                String knight;try(var s=c.prepareStatement("SELECT name FROM knights WHERE account_id=? AND id=?")){
                    s.setInt(1,accountId);s.setInt(2,knightId);try(var r=s.executeQuery()){if(!r.next())throw new IOException("Tutorial knight missing");knight=r.getString(1);}
                }
                var bomb=new WorldProfile.Item(0,WorldProfile.Slot.BOMB,Database.SKIPPED_CAMP_ATTACHMENT,null,0,null,false,true,false,1,false,0,0,List.of(),List.of(),false);
                TownMail.systemMail(c,worldId,Database.ORIENTATION_SENDER,"s.skipped_rescue_camp",TownMail.composeMessage("m.skipped_rescue_camp","~"+knight),List.of(bomb));
            }
            try(var s=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){
                s.setString(1,receipt);s.setInt(2,1);s.executeUpdate();
                s.setString(1,"world."+worldId+".last-town-scene");s.setInt(2,org.tortodrone.core.mission.TutorialRoute.HAVEN);s.executeUpdate();
            }
            try(var s=c.prepareStatement("UPDATE world_characters SET state_revision=state_revision+1 WHERE world_id=? AND account_id=? AND knight_id=? AND state_revision=?")){
                s.setInt(1,worldId);s.setInt(2,accountId);s.setInt(3,knightId);s.setLong(4,revision);
                if(s.executeUpdate()!=1)throw new IOException("Tutorial settlement revision changed");
            }
            var settled=WorldProfiles.load(c,accountId,knightId,authRevision);
            return AccountAchievements.reconcile(c,settled,null,settled.progress().tutorialComplete()?32:0,System.currentTimeMillis());
        });
    }

    Set<String> knightMemories(int accountId,int knightId,long authRevision)throws IOException {
        return database.read(c->{c.setAutoCommit(false);requirePreferenceOwner(c,accountId,authRevision);var profile=WorldProfiles.load(c,accountId,knightId,authRevision);
            var result=KnightMemories.load(c,profile.worldId());c.commit();return result;});
    }

    org.tortodrone.core.character.KnightMemoryState knightMemories(int accountId,int knightId,long authRevision,int worldId,long revision,List<org.tortodrone.core.character.KnightMemory> operations)throws IOException {
        var result=knightStory(accountId,knightId,authRevision,worldId,revision,operations,List.of());return new org.tortodrone.core.character.KnightMemoryState(result.profile(),result.memories());
    }

    Map<String,org.tortodrone.core.character.KnightObjective.State> knightObjectives(int accountId,int knightId,long authRevision)throws IOException {
        return database.read(c->{c.setAutoCommit(false);requirePreferenceOwner(c,accountId,authRevision);var profile=WorldProfiles.load(c,accountId,knightId,authRevision);var result=KnightObjectives.load(c,profile.worldId());c.commit();return result;});
    }

    org.tortodrone.core.character.KnightStory knightStory(int accountId,int knightId,long authRevision,int worldId,long revision,List<org.tortodrone.core.character.KnightMemory> operations,List<org.tortodrone.core.character.KnightObjective> objectives)throws IOException {
        var changes=List.copyOf(operations);if(changes.size()>256)throw new IOException("Too many knight memory operations");
        var objectiveChanges=List.copyOf(objectives);if(objectiveChanges.size()>256)throw new IOException("Too many objective operations");
        return database.write(c->{
            requirePreferenceOwner(c,accountId,authRevision);
            var profile=WorldProfiles.load(c,accountId,knightId,authRevision);
            if(profile.worldId()!=worldId||profile.revision()!=revision)throw new IOException("Knight memory ownership or revision changed");
            var before=KnightMemories.load(c,worldId);var after=KnightMemories.apply(c,worldId,before,changes);
            var oldObjectives=KnightObjectives.load(c,worldId);var newObjectives=KnightObjectives.apply(c,worldId,oldObjectives,objectiveChanges);
            if(!before.equals(after)||!oldObjectives.equals(newObjectives))try(var s=c.prepareStatement("UPDATE world_characters SET state_revision=state_revision+1 WHERE world_id=? AND account_id=? AND knight_id=? AND state_revision=?")){
                s.setInt(1,worldId);s.setInt(2,accountId);s.setInt(3,knightId);s.setLong(4,revision);
                if(s.executeUpdate()!=1)throw new IOException("Knight memory revision changed");
            }
            return new org.tortodrone.core.character.KnightStory(WorldProfiles.load(c,accountId,knightId,authRevision),after,newObjectives);
        });
    }

    static void requirePreferenceOwner(Connection c,int accountId,long authRevision)throws SQLException,IOException {
        if(authRevision<0)throw new IOException("Authenticated preference owner required");
        try(var s=c.prepareStatement("SELECT 1 FROM accounts WHERE id=? AND auth_revision=?")) {
            s.setInt(1,accountId);s.setLong(2,authRevision);
            try(var r=s.executeQuery()){if(!r.next())throw new IOException("Account changed; reconnect");}
        }
    }
}
