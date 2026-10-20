package org.tortodrone.storage;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import org.tortodrone.character.*;

/** Native cosmetic kits and saved styles, committed with inventory and roster revisions. */
public final class StyleStore {
    public enum Kind { Color, Eye, Height }
    public record Style(Kind kind,int value) {
        public Style {
            Objects.requireNonNull(kind);
            if(switch(kind){case Color->value<256||value>511;case Eye->value<0||value>=47;case Height->value<0||value>=5;})throw new IllegalArgumentException("Invalid style");
        }
    }
    public record Saved(List<Integer> colors,List<Integer> eyes,List<Integer> heights) {
        public Saved { colors=List.copyOf(colors);eyes=List.copyOf(eyes);heights=List.copyOf(heights); }
    }
    private final Database database;
    private final ItemCatalog items;
    private static final Map<String,Style> KITS=loadKits();
    public StyleStore(Database database,ItemCatalog items){this.database=database;this.items=items;}
    public static Style kit(String config){return KITS.get(config);}
    private static Map<String,Style> loadKits(){
        var result=new HashMap<String,Style>();
        try(var stream=StyleStore.class.getResourceAsStream("/seeds/style-kits.tsv")){
            if(stream==null)throw new IOException("Missing cosmetic kit mappings");
            for(String line:new String(stream.readAllBytes(),StandardCharsets.UTF_8).lines().toList()){
                if(line.isBlank()||line.startsWith("#"))continue;
                var fields=line.split("\t");
                if(fields.length!=3||result.put(fields[0],new Style(Kind.valueOf(fields[1]),Integer.parseInt(fields[2])))!=null)throw new IOException("Invalid cosmetic kit mapping");
            }
            return Map.copyOf(result);
        }catch(IOException|RuntimeException failure){throw new ExceptionInInitializerError(failure);}
    }
    private static String prefix(int world,Kind kind){return "style."+world+"."+kind+".";}
    private static List<Integer> values(Connection c,int world,Kind kind)throws SQLException,IOException {
        var values=new TreeSet<Integer>();String prefix=prefix(world,kind);
        try(var s=c.prepareStatement("SELECT value FROM metadata WHERE key LIKE ?")){
            s.setString(1,prefix+"%");try(var r=s.executeQuery()){while(r.next()){
                int value=r.getInt(1);try{new Style(kind,value);}catch(IllegalArgumentException invalid){throw new IOException("Invalid saved style",invalid);}values.add(value);
            }}
        }
        return List.copyOf(values);
    }
    public Saved saved(int account,int knight,long auth)throws IOException {
        if(auth<0)throw new IOException("Authenticated style owner required");
        return database.read(c->{c.setAutoCommit(false);var p=WorldProfiles.load(c,account,knight,auth);
            var result=new Saved(values(c,p.worldId(),Kind.Color),values(c,p.worldId(),Kind.Eye),values(c,p.worldId(),Kind.Height));c.commit();return result;});
    }
    /** Null item means native applyStyle; otherwise consume exactly one owned cosmetic kit. */
    public TownStore.Result change(int account,int knight,long auth,long revision,Long item,Style requested)throws IOException {
        if(auth<0)throw new IOException("Authenticated style owner required");
        return database.write(c->{
            var p=WorldProfiles.load(c,account,knight,auth);
            if(p.revision()!=revision)throw new IOException("Your inventory changed. Please try again.");
            Style style=requested;
            if(item!=null){
                var held=TownStore.owned(p,item);style=kit(held.config());
                if(style==null||!items.require(held.config()).type().equals(style.kind().name())||held.gift()!=null||held.expired(System.currentTimeMillis()))throw new IOException("m.internal_error");
            }
            if(style==null)throw new IOException("m.internal_error");
            String column=style.kind().name().toLowerCase(Locale.ROOT);int current;
            try(var s=c.prepareStatement("SELECT "+column+" FROM knights WHERE account_id=? AND id=?")){
                s.setInt(1,account);s.setInt(2,knight);try(var r=s.executeQuery()){if(!r.next())throw new IOException("Knight unavailable");current=r.getInt(1);}
            }
            int stored=style.kind()==Kind.Color?style.value()-256:style.value();
            if(current==stored)throw new IOException("m.internal_error");
            var saved=values(c,p.worldId(),style.kind());
            if(item==null){if(!saved.contains(style.value()))throw new IOException("m.internal_error");TownStore.pay(c,p,1000,0);}
            else {
                // A previously unlocked style is reapplied through the style kit, not another consumable.
                if(saved.contains(style.value()))throw new IOException("m.internal_error");
                TownStore.consume(c,p,item,1);
                try(var s=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?)")){
                    s.setString(1,prefix(p.worldId(),style.kind())+style.value());s.setInt(2,style.value());s.executeUpdate();
                }
            }
            try(var s=c.prepareStatement("UPDATE knights SET "+column+"=? WHERE account_id=? AND id=?")){
                s.setInt(1,stored);s.setInt(2,account);s.setInt(3,knight);if(s.executeUpdate()!=1)throw new IOException("Knight changed");
            }
            try(var s=c.prepareStatement("UPDATE accounts SET revision=revision+1 WHERE id=?")){s.setInt(1,account);s.executeUpdate();}
            TownStore.touch(c,p.worldId());return new TownStore.Result(WorldProfiles.load(c,account,knight,auth),null);
        });
    }
}
