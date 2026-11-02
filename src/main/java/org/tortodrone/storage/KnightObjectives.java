package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.character.KnightObjective;

/** Bounded objective identities/configuration in existing SQLite metadata, within caller transaction. */
final class KnightObjectives {
    private static String prefix(int world){return "world."+world+".objective.";}
    static Map<String,KnightObjective.State> load(Connection c,int world)throws SQLException,IOException {
        var values=new TreeMap<String,KnightObjective.State>();String prefix=prefix(world);var indices=new HashSet<Integer>();
        try(var s=c.prepareStatement("SELECT key,value FROM metadata WHERE key LIKE ? ORDER BY key")){
            s.setString(1,prefix+"%");try(var r=s.executeQuery()){while(r.next()){
                String name=r.getString(1).substring(prefix.length()),value=r.getString(2);int split=value.indexOf(':');
                try{
                    var objective=new KnightObjective.State(Integer.parseInt(value.substring(0,split)),name,value.substring(split+1));
                    if(values.size()>=256||!indices.add(objective.index()))throw new IllegalArgumentException();values.put(name,objective);
                }catch(RuntimeException invalid){throw new IOException("Invalid stored knight objective",invalid);}
            }}
        }return Collections.unmodifiableMap(values);
    }
    static Map<String,KnightObjective.State> apply(Connection c,int world,Map<String,KnightObjective.State> before,List<KnightObjective> changes)throws SQLException,IOException {
        var after=new TreeMap<>(before);int next=0;String counter="world."+world+".next-objective";
        try(var s=c.prepareStatement("SELECT value FROM metadata WHERE key=?")){s.setString(1,counter);try(var r=s.executeQuery()){if(r.next()){long n=r.getLong(1);if(n<0||n>Integer.MAX_VALUE)throw new IOException("Invalid objective counter");next=(int)n;}}}
        for(var objective:before.values())if(objective.index()>=next)throw new IOException("Objective index exceeds its allocation counter");
        for(var change:changes){
            if(change.text()==null){after.remove(change.name());continue;}
            var previous=after.get(change.name());if(previous==null&&next==Integer.MAX_VALUE)throw new IOException("Objective identity space exhausted");
            after.put(change.name(),new KnightObjective.State(previous==null?next++:previous.index(),change.name(),change.text()));
            if(after.size()>256)throw new IOException("Too many knight objectives");
        }
        if(after.equals(before))return before;
        try(var remove=c.prepareStatement("DELETE FROM metadata WHERE key=?");var put=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){
            for(String name:before.keySet())if(!after.containsKey(name)){remove.setString(1,prefix(world)+name);remove.executeUpdate();}
            for(var entry:after.entrySet())if(!entry.getValue().equals(before.get(entry.getKey()))){var objective=entry.getValue();put.setString(1,prefix(world)+entry.getKey());put.setString(2,objective.index()+":"+objective.text());put.executeUpdate();}
            put.setString(1,counter);put.setInt(2,next);put.executeUpdate();
        }return Collections.unmodifiableMap(after);
    }
    static void clean(Connection c)throws SQLException {
        try(var s=c.createStatement()){
            s.executeUpdate("DELETE FROM metadata WHERE key GLOB 'world.*.objective.*' AND NOT EXISTS(SELECT 1 FROM world_characters w WHERE metadata.key LIKE 'world.'||w.world_id||'.objective.%')");
            s.executeUpdate("DELETE FROM metadata WHERE key GLOB 'world.*.next-objective' AND key NOT IN(SELECT 'world.'||world_id||'.next-objective' FROM world_characters)");
        }
    }
}
