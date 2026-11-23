package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;

/** Account-scoped preference rows kept in the existing metadata table. Both reads and writes
 *  revalidate the preferred owner under the transaction the caller opened, so a stale session
 *  cannot read or change another account's preference. */
final class PreferenceRepository {
    private final Database database;

    PreferenceRepository(Database database){this.database=database;}

    boolean inspectionHidden(int accountId,long authRevision)throws IOException {
        return database.read(c->{WorldRepository.requirePreferenceOwner(c,accountId,authRevision);
            try(var s=c.prepareStatement("SELECT value FROM metadata WHERE key=?")) {
                s.setString(1,"account."+accountId+".inspection-hidden");
                try(var r=s.executeQuery()) {
                    if(!r.next())return false;
                    int value=r.getInt(1);if(value!=0&&value!=1)throw new IOException("Invalid inspection preference");return value==1;
                }
            }
        });
    }

    void inspectionHidden(int accountId,long authRevision,boolean hidden)throws IOException {
        database.write(c->{WorldRepository.requirePreferenceOwner(c,accountId,authRevision);
            try(var s=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
                s.setString(1,"account."+accountId+".inspection-hidden");s.setInt(2,hidden?1:0);s.executeUpdate();
            }return null;
        });
    }

    /** Last acknowledged permanent scene, keyed by the non-recycled world identity. */
    int lastTownScene(int accountId,int knightId,long authRevision)throws IOException {
        return database.read(c->{
            c.setAutoCommit(false);
            WorldRepository.requirePreferenceOwner(c,accountId,authRevision);
            var profile=WorldProfiles.load(c,accountId,knightId,authRevision);
            try(var s=c.prepareStatement("SELECT value FROM metadata WHERE key=?")){
                s.setString(1,"world."+profile.worldId()+".last-town-scene");
                int result;try(var r=s.executeQuery()){result=r.next()?r.getInt(1):0;}
                c.commit();return result;
            }
        });
    }

    void lastTownScene(int accountId,int knightId,long authRevision,int scene)throws IOException {
        if(scene<=0||scene==Integer.MAX_VALUE)throw new IOException("Invalid permanent scene");
        database.write(c->{
            WorldRepository.requirePreferenceOwner(c,accountId,authRevision);
            var profile=WorldProfiles.load(c,accountId,knightId,authRevision);
            try(var s=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){
                s.setString(1,"world."+profile.worldId()+".last-town-scene");s.setInt(2,scene);s.executeUpdate();
            }
            return null;
        });
    }
}
