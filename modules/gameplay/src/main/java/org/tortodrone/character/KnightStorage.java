package org.tortodrone.character;

import java.io.IOException;
import java.util.*;
import org.tortodrone.core.character.*;
import org.tortodrone.storage.Database;

/** Storage-facing reads and writes for one owned knight. Kept out of
 *  {@link CharacterStore} so the roster model owns identity and selection while this
 *  collaborator owns the per-knight SQLite calls. */
final class KnightStorage {
    private final Database database;
    private final int accountId;
    private final ItemCatalog catalog;

    KnightStorage(Database database,int accountId,ItemCatalog catalog){
        this.database=database;this.accountId=accountId;this.catalog=catalog;
    }

    WorldProfile worldProfile(int knightId,long authRevision,WorldProfile cached)throws IOException {
        if(database==null)throw new IOException("World profiles require SQLite storage");
        return database.worldProfile(accountId,knightId,authRevision,cached);
    }

    WorldProfile equip(int knightId,long revision,long itemId,Integer slot)throws IOException {
        if(database==null)throw new IOException("Equipment requires SQLite storage");
        return database.equip(accountId,knightId,revision,itemId,slot);
    }

    WorldProfile tradeProfile(int knightId,long authRevision)throws IOException {
        if(database==null)throw new IOException("Trading requires SQLite storage");
        return database.worldProfile(accountId,knightId,authRevision);
    }

    boolean inspectionHidden(long authRevision)throws IOException {
        return database!=null&&database.inspectionHidden(accountId,authRevision);
    }

    int lastTownScene(int knightId,long authRevision)throws IOException {
        return database.lastTownScene(accountId,knightId,authRevision);
    }

    void lastTownScene(int knightId,long authRevision,int scene)throws IOException {
        database.lastTownScene(accountId,knightId,authRevision,scene);
    }

    WorldProfile completeTutorial(int knightId,long authRevision,WorldProfile expected,float heat,org.tortodrone.core.town.TownCatalog rules)throws IOException {
        if(database==null||expected.accountId()!=accountId||expected.knightId()!=knightId)throw new IOException("Invalid tutorial owner");
        return database.completeTutorial(accountId,knightId,authRevision,expected.worldId(),expected.revision(),heat,rules);
    }

    Set<String> knightMemories(int knightId,long authRevision)throws IOException {
        if(database==null)throw new IOException("Knight memories require SQLite storage");
        return database.knightMemories(accountId,knightId,authRevision);
    }

    Map<String,KnightObjective.State> knightObjectives(int knightId,long authRevision)throws IOException {
        if(database==null)throw new IOException("Objectives require SQLite storage");
        return database.knightObjectives(accountId,knightId,authRevision);
    }

    KnightStory knightStory(int knightId,long authRevision,WorldProfile expected,List<KnightMemory> memories,List<KnightObjective> objectives)throws IOException {
        if(database==null||expected.accountId()!=accountId||expected.knightId()!=knightId)throw new IOException("Invalid story owner");
        return database.knightStory(accountId,knightId,authRevision,expected.worldId(),expected.revision(),memories,objectives);
    }

    KnightMemoryState knightMemories(int knightId,long authRevision,WorldProfile expected,List<KnightMemory> operations)throws IOException {
        if(database==null||expected.accountId()!=accountId||expected.knightId()!=knightId)throw new IOException("Invalid knight memory owner");
        return database.knightMemories(accountId,knightId,authRevision,expected.worldId(),expected.revision(),operations);
    }

    void inspectionHidden(long authRevision,boolean hidden)throws IOException {
        if(database==null)throw new IOException("Preferences require SQLite storage");
        database.inspectionHidden(accountId,authRevision,hidden);
    }

    List<WorldProfile> trade(WorldProfile first,long firstAuth,Map<Long,Integer> firstItems,int firstCrowns,int firstEnergy,
            WorldProfile second,long secondAuth,Map<Long,Integer> secondItems,int secondCrowns,int secondEnergy)throws IOException {
        if(database==null||first.accountId()!=accountId)throw new IOException("Invalid trade owner");
        return database.trade(first,firstAuth,firstItems,firstCrowns,firstEnergy,second,secondAuth,secondItems,secondCrowns,secondEnergy,catalog);
    }
}
