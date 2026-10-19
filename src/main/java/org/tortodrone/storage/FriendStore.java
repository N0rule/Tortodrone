package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.town.AuctionState;

/** Mutual knight friendships and native request mail in existing storage namespaces. */
public final class FriendStore {
    public static final int LIMIT=250;
    public record Peer(int world,String name,int color,long lastSession) { }
    public record Snapshot(Map<Integer,List<Peer>> lists,Map<Integer,Long> authentication,Map<Integer,Long> revisions) {
        public Snapshot { lists=Map.copyOf(lists);authentication=Map.copyOf(authentication);revisions=Map.copyOf(revisions); }
    }
    private final Database database;
    public FriendStore(Database database){this.database=database;}
    private static String prefix(int world){return "social.friend."+world+".";}
    private static int owner(Connection c,int account,int knight,long auth)throws SQLException,IOException {
        if(auth<0)throw new IOException("Authenticated friend-list owner required");
        try(var s=c.prepareStatement("SELECT w.world_id FROM world_characters w JOIN accounts a ON a.id=w.account_id WHERE w.account_id=? AND w.knight_id=? AND a.auth_revision=?")) {
            s.setInt(1,account);s.setInt(2,knight);s.setLong(3,auth);
            try(var r=s.executeQuery()){if(!r.next())throw new IOException("Knight or account changed; reconnect");return r.getInt(1);}
        }
    }
    private static int named(Connection c,String name)throws SQLException,IOException {
        if(name==null||name.length()<3||name.length()>16||!name.matches("[A-Za-z]+(?:-[A-Za-z]+)*"))throw new IOException("Enter a valid knight name");
        try(var s=c.prepareStatement("SELECT w.world_id FROM world_characters w JOIN knights k ON k.account_id=w.account_id AND k.id=w.knight_id WHERE k.name_key=?")) {
            s.setString(1,name.toLowerCase(Locale.ROOT));try(var r=s.executeQuery()){if(!r.next())throw new IOException("No such knight");return r.getInt(1);}
        }
    }
    public void session(int account,int knight,long auth)throws IOException {
        database.write(c->{int world=owner(c,account,knight,auth);put(c,"social.seen."+world,System.currentTimeMillis());return null;});
    }
    public List<Peer> list(int account,int knight,long auth)throws IOException {
        return database.read(c->{int world=owner(c,account,knight,auth);return lists(c).getOrDefault(world,List.of());});
    }
    /** One consistent, batched social read per deployment; no socket/session callbacks. */
    public Snapshot snapshot()throws IOException {
        return database.read(c->{c.setAutoCommit(false);var authentication=new HashMap<Integer,Long>();var revisions=new HashMap<Integer,Long>();
            try(var s=c.createStatement();var r=s.executeQuery("SELECT w.world_id,a.auth_revision,w.state_revision FROM world_characters w JOIN accounts a ON a.id=w.account_id")){while(r.next()){authentication.put(r.getInt(1),r.getLong(2));revisions.put(r.getInt(1),r.getLong(3));}}
            var result=new Snapshot(lists(c),authentication,revisions);c.commit();return result;
        });
    }
    private static Map<Integer,List<Peer>> lists(Connection c)throws SQLException,IOException {
        var result=new HashMap<Integer,List<Peer>>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT m.key,m.value,k.name,k.color,COALESCE(seen.value,k.created_at) FROM metadata m JOIN world_characters w ON w.world_id=m.value JOIN knights k ON k.account_id=w.account_id AND k.id=w.knight_id LEFT JOIN metadata seen ON seen.key='social.seen.'||w.world_id WHERE m.key LIKE 'social.friend.%' ORDER BY k.name_key")) {
            while(r.next()) {
                String key=r.getString(1);int dot=key.lastIndexOf('.'),world;
                try{world=Integer.parseInt(key.substring(14,dot));if(world<1||!key.substring(dot+1).equals(Integer.toString(r.getInt(2))))throw new NumberFormatException();}
                catch(RuntimeException invalid){throw new IOException("Invalid saved friendship",invalid);}
                var peers=result.computeIfAbsent(world,k->new ArrayList<>());peers.add(new Peer(r.getInt(2),r.getString(3),r.getInt(4),r.getLong(5)));
                if(peers.size()>LIMIT)throw new IOException("Saved friend list exceeds its limit");
            }
        }
        result.replaceAll((world,peers)->List.copyOf(peers));return Map.copyOf(result);
    }
    public void request(int account,int knight,long auth,String name,String subject,String body)throws IOException {
        text(subject,120);text(body,2000);
        database.write(c->{int world=owner(c,account,knight,auth),peer=named(c,name);
            if(world==peer)throw new IOException("You cannot add yourself as a friend");
            if(linked(c,world,peer))throw new IOException("That knight is already a friend");
            capacity(c,world);capacity(c,peer);
            try(var s=c.prepareStatement("SELECT 1 FROM town_mail m JOIN metadata r ON r.key='social.request.'||m.id WHERE m.world_id=? AND r.value=? AND m.claimed=0")) {
                s.setInt(1,peer);s.setInt(2,world);try(var r=s.executeQuery()){if(r.next())throw new IOException("A friend request is already pending");}
            }
            try(var s=c.prepareStatement("SELECT (SELECT count(*) FROM town_mail WHERE world_id=?),(SELECT count(*) FROM metadata r JOIN town_mail m ON r.key='social.request.'||m.id WHERE r.value=? AND m.claimed=0)")) {
                s.setInt(1,peer);s.setInt(2,world);try(var r=s.executeQuery()){r.next();if(r.getInt(1)>=500||r.getInt(2)>=LIMIT)throw new IOException("Friend request limit reached");}
            }
            try(var s=c.prepareStatement("INSERT INTO town_mail(world_id,sent,subject,body) VALUES(?,?,?,?) RETURNING id")) {
                s.setInt(1,peer);s.setLong(2,System.currentTimeMillis());s.setString(3,subject);s.setString(4,body);
                long id;try(var r=s.executeQuery()){r.next();id=r.getLong(1);}put(c,"social.request."+id,world);
            }
            TownStore.touch(c,peer);return null;
        });
    }
    public void remove(int account,int knight,long auth,String name)throws IOException {
        database.write(c->{int world=owner(c,account,knight,auth),peer=named(c,name);
            if(!linked(c,world,peer))throw new IOException("That knight is not a friend");
            try(var s=c.prepareStatement("DELETE FROM metadata WHERE key IN(?,?)")){s.setString(1,prefix(world)+peer);s.setString(2,prefix(peer)+world);s.executeUpdate();}
            TownStore.touch(c,world);TownStore.touch(c,peer);return null;
        });
    }
    private static boolean linked(Connection c,int world,int peer)throws SQLException {
        try(var s=c.prepareStatement("SELECT 1 FROM metadata WHERE key=? AND value=?")){s.setString(1,prefix(world)+peer);s.setInt(2,peer);try(var r=s.executeQuery()){return r.next();}}
    }
    private static void capacity(Connection c,int world)throws SQLException,IOException {
        String prefix=prefix(world);try(var s=c.prepareStatement("SELECT count(*) FROM metadata WHERE key>=? AND key<?")){s.setString(1,prefix);s.setString(2,prefix+"~");try(var r=s.executeQuery()){r.next();if(r.getInt(1)>=LIMIT)throw new IOException("Friend list is full (250 knights)");}}
    }
    static void accept(Connection c,int world,AuctionState.Mail mail)throws SQLException,IOException {
        int peer=mail.friendWorld();if(peer==world||peer<1)throw new IOException("Invalid friend request");
        try(var s=c.prepareStatement("SELECT 1 FROM world_characters WHERE world_id=?")){s.setInt(1,peer);try(var r=s.executeQuery()){if(!r.next())throw new IOException("The requesting knight no longer exists");}}
        if(!linked(c,world,peer)){capacity(c,world);capacity(c,peer);put(c,prefix(world)+peer,peer);put(c,prefix(peer)+world,world);}
        // Claim both crossed requests together, so old mail cannot recreate a removed friendship.
        try(var s=c.prepareStatement("UPDATE town_mail SET claimed=1 WHERE id IN(SELECT m.id FROM town_mail m JOIN metadata r ON r.key='social.request.'||m.id WHERE m.id<>? AND ((m.world_id=? AND r.value=?) OR (m.world_id=? AND r.value=?)))")) {
            s.setLong(1,mail.id());s.setInt(2,world);s.setInt(3,peer);s.setInt(4,peer);s.setInt(5,world);s.executeUpdate();
        }
        TownStore.touch(c,peer);
    }
    static void deletedMail(Connection c,long id)throws SQLException {try(var s=c.prepareStatement("DELETE FROM metadata WHERE key=?")){s.setString(1,"social.request."+id);s.executeUpdate();}}
    static void clean(Connection c)throws SQLException {
        try(var s=c.createStatement()) {
            s.executeUpdate("UPDATE world_characters SET state_revision=state_revision+1 WHERE world_id IN(SELECT m.world_id FROM town_mail m JOIN metadata r ON r.key='social.request.'||m.id WHERE r.value NOT IN(SELECT world_id FROM world_characters))");
            s.executeUpdate("DELETE FROM town_mail WHERE id IN(SELECT CAST(substr(key,16) AS INTEGER) FROM metadata WHERE key LIKE 'social.request.%' AND value NOT IN(SELECT world_id FROM world_characters))");
            s.executeUpdate("DELETE FROM metadata WHERE key LIKE 'social.request.%' AND CAST(substr(key,16) AS INTEGER) NOT IN(SELECT id FROM town_mail)");
            s.executeUpdate("DELETE FROM metadata WHERE key LIKE 'social.friend.%' AND (value NOT IN(SELECT world_id FROM world_characters) OR CAST(substr(key,15,instr(substr(key,15),'.')-1) AS INTEGER) NOT IN(SELECT world_id FROM world_characters))");
            s.executeUpdate("DELETE FROM metadata WHERE key LIKE 'social.seen.%' AND CAST(substr(key,13) AS INTEGER) NOT IN(SELECT world_id FROM world_characters)");
        }
    }
    private static void put(Connection c,String key,long value)throws SQLException {
        try(var s=c.prepareStatement("INSERT INTO metadata(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")){s.setString(1,key);s.setLong(2,value);s.executeUpdate();}
    }
    private static void text(String value,int max)throws IOException {
        if(value==null||value.codePointCount(0,value.length())>max||value.chars().anyMatch(ch->ch==0||Character.isISOControl(ch)&&ch!='\n'&&ch!='\t'))throw new IOException("Invalid friend request text");
        for(int i=0;i<value.length();i++)if(Character.isSurrogate(value.charAt(i))&&(!Character.isHighSurrogate(value.charAt(i))||++i==value.length()||!Character.isLowSurrogate(value.charAt(i))))throw new IOException("Invalid friend request text");
    }
}
