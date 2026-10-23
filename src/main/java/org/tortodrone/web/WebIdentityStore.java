package org.tortodrone.web;

import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.tortodrone.resources.ContentFiles;

/** Independent credentials and durable audit ledger. No game account or GM inference. */
public final class WebIdentityStore {
    public enum Role { VIEWER, MODERATOR, ADMINISTRATOR }
    public record Identity(String username,Role role,long revision) { }
    private final Path file;
    public WebIdentityStore(Path data)throws IOException {
        file=data.resolve("webui.sqlite");ContentFiles.safe(file);
        try {
            Files.createDirectories(data);
            try(var c=connect();var s=c.createStatement()) {
                int version;try(var r=s.executeQuery("PRAGMA user_version")){r.next();version=r.getInt(1);}
                if(version!=0&&version!=1)throw new IOException("Unknown WebUI database schema");
                if(version==0){
                    try(var r=s.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table'")){if(r.next()&&r.getInt(1)!=0)throw new IOException("Unknown WebUI database schema");}
                    c.setAutoCommit(false);
                    s.execute("CREATE TABLE identities(username TEXT PRIMARY KEY,role TEXT NOT NULL,salt BLOB NOT NULL,verifier BLOB NOT NULL,revision INTEGER NOT NULL)");
                    s.execute("CREATE TABLE audit(id INTEGER PRIMARY KEY,time INTEGER NOT NULL,actor TEXT NOT NULL,action TEXT NOT NULL,target TEXT NOT NULL,outcome TEXT NOT NULL)");
                    s.execute("PRAGMA user_version=1");c.commit();
                }
            }
            privateFile(file);
        }catch(SQLException e){throw new IOException("WebUI identity storage unavailable",e);}
    }
    private Connection connect()throws SQLException {var c=DriverManager.getConnection("jdbc:sqlite:"+file);try(var s=c.createStatement()){s.execute("PRAGMA busy_timeout=5000");}return c;}
    public static void privateFile(Path p)throws IOException {try{Files.setPosixFilePermissions(p,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}}
    public void put(String username,char[] password,Role role)throws IOException {
        if(!username.matches("[a-zA-Z0-9_-]{3,64}"))throw new IOException("WebUI username needs 3–64 letters, digits, underscores or hyphens");
        if(password.length<14||password.length>128)throw new IOException("WebUI passwords need 14–128 characters");
        byte[] salt=new byte[32];new SecureRandom().nextBytes(salt);byte[] hash=derive(password,salt);
        try(var c=connect();var p=c.prepareStatement("INSERT INTO identities VALUES(?,?,?,?,?) ON CONFLICT(username) DO UPDATE SET role=excluded.role,salt=excluded.salt,verifier=excluded.verifier,revision=excluded.revision")) {
            p.setString(1,username);p.setString(2,role.name());p.setBytes(3,salt);p.setBytes(4,hash);p.setLong(5,new SecureRandom().nextLong(1,Long.MAX_VALUE));p.executeUpdate();
        }catch(SQLException e){throw new IOException("Cannot save WebUI identity",e);}finally{Arrays.fill(hash,(byte)0);}
        audit("local-cli","identity.save",username,"committed");
    }
    public Identity authenticate(String username,char[] password)throws IOException {
        try(var c=connect();var p=c.prepareStatement("SELECT * FROM identities WHERE username=?")) {
            p.setString(1,username);try(var r=p.executeQuery()){
                boolean found=r.next();byte[] salt=found?r.getBytes("salt"):new byte[32];byte[] expected=found?r.getBytes("verifier"):new byte[32];
                byte[] actual=derive(password,salt);boolean matches=MessageDigest.isEqual(actual,expected);Arrays.fill(actual,(byte)0);
                return found&&matches?new Identity(username,Role.valueOf(r.getString("role")),r.getLong("revision")):null;
            }
        }catch(SQLException e){throw new IOException("Authentication unavailable",e);}
    }
    public boolean current(Identity id)throws IOException {
        try(var c=connect();var p=c.prepareStatement("SELECT revision,role FROM identities WHERE username=?")) {
            p.setString(1,id.username());try(var r=p.executeQuery()){return r.next()&&r.getLong(1)==id.revision()&&r.getString(2).equals(id.role().name());}
        }catch(SQLException e){throw new IOException("Authentication unavailable",e);}
    }
    public void revoke(String username)throws IOException {
        try(var c=connect();var p=c.prepareStatement("DELETE FROM identities WHERE username=?")){p.setString(1,username);p.executeUpdate();}
        catch(SQLException e){throw new IOException("Cannot revoke identity",e);}
        audit("local-cli","identity.revoke",username,"committed");
    }
    public void audit(String actor,String action,String target,String outcome)throws IOException {
        try(var c=connect();var p=c.prepareStatement("INSERT INTO audit(time,actor,action,target,outcome) VALUES(?,?,?,?,?)")) {
            p.setLong(1,System.currentTimeMillis());p.setString(2,actor);p.setString(3,action);p.setString(4,target);p.setString(5,outcome);p.executeUpdate();
        }catch(SQLException e){throw new IOException("Audit unavailable; operation refused",e);}
    }
    public List<Map<String,Object>> audit()throws IOException {
        var result=new ArrayList<Map<String,Object>>();
        try(var c=connect();var s=c.createStatement();var r=s.executeQuery("SELECT * FROM audit ORDER BY id DESC LIMIT 500")){
            while(r.next())result.add(Map.of("id",r.getLong("id"),"time",r.getLong("time"),"actor",r.getString("actor"),"action",r.getString("action"),"target",r.getString("target"),"outcome",r.getString("outcome")));
        }catch(SQLException e){throw new IOException("Audit unavailable",e);}return result;
    }
    private static byte[] derive(char[] password,byte[] salt)throws IOException {
        var spec=new PBEKeySpec(password,salt,600_000,256);
        try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}
        catch(GeneralSecurityException e){throw new IOException("Password hashing unavailable",e);}finally{spec.clearPassword();}
    }
    public static void createProxyKey(Path file)throws IOException {
        ContentFiles.safe(file);
        java.nio.channels.FileChannel channel;
        var options=Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
        try{channel=java.nio.channels.FileChannel.open(file,options,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));}
        catch(UnsupportedOperationException windows){channel=java.nio.channels.FileChannel.open(file,options);}
        try(var output=channel){var bytes=java.nio.ByteBuffer.wrap((token()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));while(bytes.hasRemaining())output.write(bytes);output.force(true);}
    }
    public static String token(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
}
