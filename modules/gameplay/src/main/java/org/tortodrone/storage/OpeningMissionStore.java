package org.tortodrone.storage;
import org.tortodrone.character.*;

import java.io.*;
import java.sql.*;
import java.util.*;
import org.tortodrone.core.town.TownCatalog;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.character.KnightObjective;
import org.tortodrone.core.character.OpeningCompletion;
import org.tortodrone.core.character.OpeningMission;
import org.tortodrone.core.character.KnightMemory;
import org.tortodrone.core.mission.MissionCatalog;

/** Authenticated, atomic story-mission settlement. Only production scene/conversation terminal
 * handlers may call complete; there is no client completion RPC. Uses the existing schema. */
public final class OpeningMissionStore {
    private final Database database;private final TownStore inventory;private final TownCatalog town;
    private final MissionCatalog missions;private final ItemCatalog items;
    public OpeningMissionStore(Database database,ItemCatalog items,TownCatalog town,MissionCatalog missions){
        this.database=database;this.town=Objects.requireNonNull(town);this.items=Objects.requireNonNull(items);
        this.missions=missions==null?MissionCatalog.empty():missions;
        inventory=new TownStore(database,items,town);
    }
    public record Settlement(WorldProfile profile,OpeningCompletion completion,boolean first,Set<String> memories,Map<String,KnightObjective.State> objectives){public Settlement{memories=Set.copyOf(memories);objectives=Map.copyOf(objectives);}}
    private static String prefix(int world){return "world."+world+".mission.";}
    public Map<String,OpeningCompletion> load(int account,int knight,long auth)throws IOException {
        requireAuth(auth);return database.read(c->{c.setAutoCommit(false);var profile=WorldProfiles.load(c,account,knight,auth);var result=load(c,profile.worldId());c.commit();return result;});
    }
    private static Map<String,OpeningCompletion> load(Connection c,int world)throws SQLException,IOException {
        var result=new TreeMap<String,OpeningCompletion>();String prefix=prefix(world);
        try(var s=c.prepareStatement("SELECT key,value FROM metadata WHERE key LIKE ?")){s.setString(1,prefix+"%");try(var rows=s.executeQuery()){while(rows.next()){
            String missionId=rows.getString(1).substring(prefix.length());
            try{if(!missionId.matches("[a-z][a-z0-9_]{0,127}"))throw new IllegalArgumentException();
                String[] value=rows.getString(2).split(":",-1);if(value.length!=2)throw new IllegalArgumentException();result.put(missionId,new OpeningCompletion(missionId,Long.parseLong(value[0]),value[1].isEmpty()?null:value[1]));
            }catch(RuntimeException invalid){throw new IOException("Invalid stored mission receipt",invalid);}
        }}}return Collections.unmodifiableMap(result);
    }
    public Settlement complete(int account,int knight,long auth,WorldProfile expected,String missionId,String difficulty)throws IOException {
        return complete(account,knight,auth,expected,missionId,difficulty,0);
    }
    /** The scene supplies collected heat, never the client. The receipt covers heat and rewards. */
    public Settlement complete(int account,int knight,long auth,WorldProfile expected,String missionId,String difficulty,float floorHeat)throws IOException {
        requireAuth(auth);Objects.requireNonNull(missionId);Objects.requireNonNull(expected);
        MissionCatalog.Entry mission=missions.mission(missionId);
        if(mission==null||!mission.rankMission())throw new IOException("Unknown rank mission");
        boolean gate=mission.requirement().type().equals("gate");
        if(!Float.isFinite(floorHeat)||floorHeat<0||floorHeat>1_000_000||floorHeat>0&&!gate)throw new IOException("Invalid mission floor heat");
        if(expected.accountId()!=account||expected.knightId()!=knight)throw new IOException("Invalid mission owner");
        if(gate!=(difficulty!=null))throw new IOException("Invalid mission difficulty");
        return database.write(c->{
            var profile=WorldProfiles.load(c,account,knight,auth);
            if(profile.worldId()!=expected.worldId())throw new IOException("Mission world ownership changed");
            var completed=load(c,profile.worldId());var receipt=completed.get(missionId);
            if(receipt!=null){
                // Archive rank repeat: a higher gate band updates the stored band without paying
                // again; a lower or equal band leaves the stored receipt untouched.
                if(gate&&difficulty!=null&&difficultyBand(difficulty)>difficultyBand(receipt.difficulty())){
                    receipt=new OpeningCompletion(missionId,Math.max(1,System.currentTimeMillis()),difficulty);
                    try(var s=c.prepareStatement("UPDATE metadata SET value=? WHERE key=?")){
                        s.setString(1,receipt.completed()+":"+difficulty);s.setString(2,prefix(profile.worldId())+missionId);if(s.executeUpdate()!=1)throw new IOException("Mission receipt changed");
                    }
                }
                return new Settlement(profile,receipt,false,KnightMemories.load(c,profile.worldId()),KnightObjectives.load(c,profile.worldId()));
            }
            if(profile.revision()!=expected.revision()||!missions.eligible(missionId,profile.progress(),completed.keySet()))throw new IOException("Mission eligibility or revision changed");
            receipt=new OpeningCompletion(missionId,Math.max(1,System.currentTimeMillis()),difficulty);
            var memories=KnightMemories.load(c,profile.worldId());var objectives=KnightObjectives.load(c,profile.worldId());
            if(missionId.equals("securing_camp")){
                if(!memories.contains("talkedRhendonRC"))throw new IOException("Rhendon briefing has not started");
                memories=KnightMemories.apply(c,profile.worldId(),memories,List.of(new KnightMemory("talkto_rhendon_icon",false)));
                objectives=KnightObjectives.apply(c,profile.worldId(),objectives,List.of(new KnightObjective("findRhendon",null)));
            }
            // Rewards are the stock reward table's own concrete entries; variants are explicit
            // names and are never sampled or invented at presentation time. Heat belongs to the
            // equipment carried through the floor, not the newly granted mission reward.
            if(floorHeat>0)TownStore.applyHeat(c,profile,town,floorHeat);
            for(var grant:missions.grants(missionId))inventory.create(c,profile,grant.config(),grant.count(),grant.locked(),0,0,
                grant.variants().stream().map(name->new StarterCatalog.Model(name,Map.of())).toList());
            int prestige=missions.prestige(missionId);
            long total=(long)profile.progress().prestige()+prestige;if(total>Integer.MAX_VALUE)throw new IOException("Prestige limit reached");
            var afterCompletion=new TreeSet<>(completed.keySet());afterCompletion.add(missionId);
            int[] promoted=missions.promotionFor(profile.progress(),afterCompletion);
            try(var s=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){
                put(s,prefix(profile.worldId())+missionId,receipt.completed()+":"+(difficulty==null?"":difficulty));
                if(promoted!=null)put(s,WorldProfiles.progressKey(profile.worldId(),"rank"),promoted[0]*10+promoted[1]);
                put(s,WorldProfiles.progressKey(profile.worldId(),"prestige"),total);
                var opening=OpeningMission.byId(missionId);
                if(opening!=null){
                    put(s,"world."+profile.worldId()+".opening-stage",opening.nextStage());
                    if(opening.nextStage()!=opening.stage())put(s,"world."+profile.worldId()+".last-town-scene",opening.nextStage()==2?218:1);
                }
            }
            try(var s=c.prepareStatement("UPDATE world_characters SET state_revision=state_revision+1 WHERE world_id=? AND account_id=? AND knight_id=? AND state_revision=?")){
                s.setInt(1,profile.worldId());s.setInt(2,account);s.setInt(3,knight);s.setLong(4,profile.revision());if(s.executeUpdate()!=1)throw new IOException("Mission revision changed");
            }
            return firstSettlement(c,account,knight,auth,receipt,memories,objectives);
        });
    }
    /** First completion only: every native receipt it qualifies. */
    private Settlement firstSettlement(Connection c,int account,int knight,long auth,OpeningCompletion receipt,
            Set<String> memories,Map<String,KnightObjective.State> objectives) throws SQLException, IOException {
        var after=WorldProfiles.load(c,account,knight,auth);
        after=AccountAchievements.reconcile(c,after,items,after.progress().tutorialComplete()?32:0,System.currentTimeMillis());
        return new Settlement(after,receipt,true,memories,objectives);
    }
    private static int difficultyBand(String difficulty){return difficulty==null?-1:List.of("EASY","MEDIUM","HARD").indexOf(difficulty);}
    /** Archive mission-service id 1: consume exactly the exchange ingredient list, then pay the
     * first-completion reward bands and store the MEDIUM row an exchange carries. A rank exchange
     * that has already been paid cannot be turned in again. */
    public Settlement exchange(int account,int knight,long auth,WorldProfile expected,String missionId,long[] itemIds)throws IOException {
        requireAuth(auth);Objects.requireNonNull(missionId);Objects.requireNonNull(expected);Objects.requireNonNull(itemIds);
        if(itemIds.length>64)throw new IOException("m.internal_error");
        MissionCatalog.Entry mission=missions.mission(missionId);
        if(mission==null||!mission.requirement().type().equals("exchange"))throw new IOException("Unknown exchange mission");
        if(expected.accountId()!=account||expected.knightId()!=knight)throw new IOException("Invalid mission owner");
        return database.write(c->{
            var profile=WorldProfiles.load(c,account,knight,auth);
            if(profile.worldId()!=expected.worldId())throw new IOException("Mission world ownership changed");
            var completed=load(c,profile.worldId());
            if(completed.containsKey(missionId))throw new IOException("m.elig_not");
            if(profile.revision()!=expected.revision()||!missions.eligible(missionId,profile.progress(),completed.keySet()))throw new IOException("Mission eligibility or revision changed");
            var deletes=verifyExchangeItems(profile,mission,itemIds);
            var receipt=new OpeningCompletion(missionId,Math.max(1,System.currentTimeMillis()),"MEDIUM");
            var memories=KnightMemories.load(c,profile.worldId());var objectives=KnightObjectives.load(c,profile.worldId());
            for(var use:deletes.entrySet())inventory.consume(c,profile,use.getKey(),use.getValue());
            for(var grant:missions.grants(missionId))inventory.create(c,profile,grant.config(),grant.count(),grant.locked(),0,0,
                grant.variants().stream().map(name->new StarterCatalog.Model(name,Map.of())).toList());
            int prestige=missions.prestige(missionId);
            long total=(long)profile.progress().prestige()+prestige;if(total>Integer.MAX_VALUE)throw new IOException("Prestige limit reached");
            var afterCompletion=new TreeSet<>(completed.keySet());afterCompletion.add(missionId);
            int[] promoted=missions.promotionFor(profile.progress(),afterCompletion);
            try(var s=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){
                put(s,prefix(profile.worldId())+missionId,receipt.completed()+":MEDIUM");
                if(promoted!=null)put(s,WorldProfiles.progressKey(profile.worldId(),"rank"),promoted[0]*10+promoted[1]);
                put(s,WorldProfiles.progressKey(profile.worldId(),"prestige"),total);
                var opening=OpeningMission.byId(missionId);
                if(opening!=null){
                    put(s,"world."+profile.worldId()+".opening-stage",opening.nextStage());
                    if(opening.nextStage()!=opening.stage())put(s,"world."+profile.worldId()+".last-town-scene",opening.nextStage()==2?218:1);
                }
            }
            try(var s=c.prepareStatement("UPDATE world_characters SET state_revision=state_revision+1 WHERE world_id=? AND account_id=? AND knight_id=? AND state_revision=?")){
                s.setInt(1,profile.worldId());s.setInt(2,account);s.setInt(3,knight);s.setLong(4,profile.revision());if(s.executeUpdate()!=1)throw new IOException("Mission revision changed");
            }
            return firstSettlement(c,account,knight,auth,receipt,memories,objectives);
        });
    }
    /** MissionManager.verifyExchangeItems over the served rank exchanges: every handed item must
     * exist, be unequipped and non-permanent, be used once, match one demanded ingredient and
     * carry at least that ingredient's count. */
    private static Map<Long,Integer> verifyExchangeItems(WorldProfile profile,MissionCatalog.Entry mission,long[] itemIds)throws IOException {
        var ingredients=mission.requirement().ingredients();
        if(itemIds.length!=ingredients.size())throw new IOException("m.internal_error");
        var deletes=new LinkedHashMap<Long,Integer>();var used=new LinkedHashSet<Long>();
        for(long id:itemIds) {
            if(!used.add(id))throw new IOException("m.internal_error");
            var item=profile.items().stream().filter(i->i.id()==id).findFirst().orElseThrow(()->new IOException("m.internal_error"));
            if(item.equippedSlot()!=null||item.permanent()||item.count()<1)throw new IOException("m.internal_error");
            MissionCatalog.Ingredient match=null;
            for(var ingredient:ingredients) {
                if(!ingredient.item().equals(item.config()))continue;
                if(match!=null)throw new IOException("m.internal_error");
                match=ingredient;
            }
            if(match==null||item.count()<match.count())throw new IOException("m.internal_error");
            deletes.put(id,match.count());
        }
        if(used.size()!=itemIds.length)throw new IOException("m.internal_error");
        return deletes;
    }
    private static void requireAuth(long auth)throws IOException{if(auth<1)throw new IOException("Authenticated mission owner required");}
    private static void put(PreparedStatement s,String key,Object value)throws SQLException{s.setString(1,key);s.setObject(2,value);s.executeUpdate();}
    static void clean(Connection c)throws SQLException{try(var s=c.createStatement()){s.executeUpdate("DELETE FROM metadata WHERE key GLOB 'world.*.mission.*' AND NOT EXISTS(SELECT 1 FROM world_characters w WHERE metadata.key LIKE 'world.'||w.world_id||'.mission.%')");}}
}
