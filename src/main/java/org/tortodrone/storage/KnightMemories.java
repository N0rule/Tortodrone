package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.character.KnightMemory;

/** KNIGHT-scoped native memory names in existing metadata; no schema migration. */
final class KnightMemories {
    private KnightMemories() { }
    private static String prefix(int world){return "world."+world+".memory.";}
    static Set<String> load(Connection c,int world)throws SQLException,IOException {
        String prefix=prefix(world);var names=new TreeSet<String>();
        try(var s=c.prepareStatement("SELECT key,value FROM metadata WHERE key LIKE ? ORDER BY key")){
            s.setString(1,prefix+"%");try(var r=s.executeQuery()){while(r.next()){
                String name=r.getString(1).substring(prefix.length());
                try{new KnightMemory(name,true);}catch(IllegalArgumentException invalid){throw new IOException("Invalid stored knight memory",invalid);}
                if(r.getInt(2)!=1||!names.add(name)||names.size()>256)throw new IOException("Invalid stored knight memories");
            }}
        }
        return Collections.unmodifiableSet(names);
    }
    static Set<String> apply(Connection c,int world,Set<String> before,List<KnightMemory> changes)throws SQLException,IOException {
        var after=new TreeSet<>(before);
        for(var change:changes){if(change.remember())after.add(change.name());else after.remove(change.name());if(after.size()>256)throw new IOException("Too many knight memories");}
        try(var remove=c.prepareStatement("DELETE FROM metadata WHERE key=?");var add=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,1)")){
            for(String name:before)if(!after.contains(name)){remove.setString(1,prefix(world)+name);remove.executeUpdate();}
            for(String name:after)if(!before.contains(name)){add.setString(1,prefix(world)+name);add.executeUpdate();}
        }
        return Collections.unmodifiableSet(after);
    }
}
