package org.tortodrone.storage;

import java.io.*;
import java.sql.*;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.town.TownCatalog;

/** Authenticated, atomic opening settlement. Only production scene/conversation terminal
 * handlers may call complete; there is no client completion RPC. Uses the existing schema. */
public final class OpeningMissionStore {
    private final Database database;private final TownStore inventory;private final TownCatalog town;
    public OpeningMissionStore(Database database,ItemCatalog items,TownCatalog town){this.database=database;this.town=Objects.requireNonNull(town);inventory=new TownStore(database,items,town);}
    public record Settlement(WorldProfile profile,OpeningCompletion completion,boolean first,Set<String> memories,Map<String,KnightObjective.State> objectives){public Settlement{memories=Set.copyOf(memories);objectives=Map.copyOf(objectives);}}
    private static String prefix(int world){return "world."+world+".mission.";}
    public Map<OpeningMission,OpeningCompletion> load(int account,int knight,long auth)throws IOException {
        requireAuth(auth);return database.read(c->{c.setAutoCommit(false);var profile=WorldProfiles.load(c,account,knight,auth);var result=load(c,profile.worldId());c.commit();return result;});
    }
    private static Map<OpeningMission,OpeningCompletion> load(Connection c,int world)throws SQLException,IOException {
        var result=new EnumMap<OpeningMission,OpeningCompletion>(OpeningMission.class);String prefix=prefix(world);
        try(var s=c.prepareStatement("SELECT key,value FROM metadata WHERE key LIKE ?")){s.setString(1,prefix+"%");try(var rows=s.executeQuery()){while(rows.next()){
            try{var mission=OpeningMission.byId(rows.getString(1).substring(prefix.length()));if(mission==null)throw new IllegalArgumentException();
                String[] value=rows.getString(2).split(":",-1);if(value.length!=2)throw new IllegalArgumentException();result.put(mission,new OpeningCompletion(mission,Long.parseLong(value[0]),value[1].isEmpty()?null:value[1]));
            }catch(RuntimeException invalid){throw new IOException("Invalid stored opening mission receipt",invalid);}
        }}}return Collections.unmodifiableMap(result);
    }
    public Settlement complete(int account,int knight,long auth,WorldProfile expected,OpeningMission mission,String difficulty)throws IOException {
        return complete(account,knight,auth,expected,mission,difficulty,0);
    }
    /** The scene supplies collected heat, never the client. The receipt covers heat and rewards. */
    public Settlement complete(int account,int knight,long auth,WorldProfile expected,OpeningMission mission,String difficulty,float floorHeat)throws IOException {
        requireAuth(auth);Objects.requireNonNull(mission);Objects.requireNonNull(expected);
        if(!Float.isFinite(floorHeat)||floorHeat<0||floorHeat>1_000_000||floorHeat>0&&!mission.gate())throw new IOException("Invalid mission floor heat");
        if(expected.accountId()!=account||expected.knightId()!=knight)throw new IOException("Invalid mission owner");
        return database.write(c->{
            var profile=WorldProfiles.load(c,account,knight,auth);
            if(profile.worldId()!=expected.worldId())throw new IOException("Mission world ownership changed");
            var completed=load(c,profile.worldId());var receipt=completed.get(mission);
            if(receipt!=null)return new Settlement(profile,receipt,false,KnightMemories.load(c,profile.worldId()),KnightObjectives.load(c,profile.worldId()));
            if(profile.revision()!=expected.revision()||!mission.eligible(profile.progress(),completed.keySet()))throw new IOException("Mission eligibility or revision changed");
            try{receipt=new OpeningCompletion(mission,Math.max(1,System.currentTimeMillis()),difficulty);}catch(IllegalArgumentException invalid){throw new IOException("Invalid mission difficulty",invalid);}
            var memories=KnightMemories.load(c,profile.worldId());var objectives=KnightObjectives.load(c,profile.worldId());
            if(mission==OpeningMission.SECURING_CAMP){
                if(!memories.contains("talkedRhendonRC"))throw new IOException("Rhendon briefing has not started");
                memories=KnightMemories.apply(c,profile.worldId(),memories,List.of(new KnightMemory("talkto_rhendon_icon",false)));
                objectives=KnightObjectives.apply(c,profile.worldId(),objectives,List.of(new KnightObjective("findRhendon",null)));
            }
            // All currently sourced VariantItemEntry rows have a null variant table.
            // Grant exact references once; never invent UVs or sample at presentation time.
            // Heat belongs to the equipment carried through this floor, not the
            // newly granted mission reward. Both mutations roll back with the receipt.
            if(floorHeat>0)TownStore.applyHeat(c,profile,town,floorHeat);
            for(var reward:mission.rewards())inventory.create(c,profile,reward.config(),reward.count(),true,0,0,List.of());
            long prestige=(long)profile.progress().prestige()+mission.prestige();if(prestige>Integer.MAX_VALUE)throw new IOException("Prestige limit reached");
            try(var s=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){
                put(s,prefix(profile.worldId())+mission.id(),receipt.completed()+":"+(difficulty==null?"":difficulty));
                put(s,WorldProfiles.progressKey(profile.worldId(),"rank"),mission.nextRank()*10+mission.nextSubrank());
                put(s,WorldProfiles.progressKey(profile.worldId(),"prestige"),prestige);
                put(s,"world."+profile.worldId()+".opening-stage",mission.nextStage());
                if(mission.nextStage()!=mission.stage())put(s,"world."+profile.worldId()+".last-town-scene",mission.nextStage()==2?218:1);
            }
            try(var s=c.prepareStatement("UPDATE world_characters SET state_revision=state_revision+1 WHERE world_id=? AND account_id=? AND knight_id=? AND state_revision=?")){
                s.setInt(1,profile.worldId());s.setInt(2,account);s.setInt(3,knight);s.setLong(4,profile.revision());if(s.executeUpdate()!=1)throw new IOException("Mission revision changed");
            }
            return new Settlement(WorldProfiles.load(c,account,knight,auth),receipt,true,memories,objectives);
        });
    }
    private static void requireAuth(long auth)throws IOException{if(auth<1)throw new IOException("Authenticated mission owner required");}
    private static void put(PreparedStatement s,String key,Object value)throws SQLException{s.setString(1,key);s.setObject(2,value);s.executeUpdate();}
    static void clean(Connection c)throws SQLException{try(var s=c.createStatement()){s.executeUpdate("DELETE FROM metadata WHERE key GLOB 'world.*.mission.*' AND NOT EXISTS(SELECT 1 FROM world_characters w WHERE metadata.key LIKE 'world.'||w.world_id||'.mission.%')");}}
}
