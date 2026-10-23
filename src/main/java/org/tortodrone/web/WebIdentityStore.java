package org.tortodrone.web;

import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.tortodrone.resources.ContentFiles;

/** Independent credentials and atomic identity/audit writes. No game account or GM inference. */
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
    @FunctionalInterface private interface Work<T>{T run(Connection c)throws SQLException,IOException;}
    private <T>T write(Work<T> work)throws IOException {
        try(var c=connect();var s=c.createStatement()) {
            s.execute("BEGIN IMMEDIATE");
            try{T result=work.run(c);s.execute("COMMIT");return result;}
            catch(SQLException|IOException|RuntimeException failure){try{s.execute("ROLLBACK");}catch(SQLException rollback){failure.addSuppressed(rollback);}throw failure;}
        }catch(SQLException failure){throw new IOException("WebUI identity change refused",failure);}
    }
    public List<Identity> list()throws IOException {
        try(var c=connect();var s=c.createStatement();var r=s.executeQuery("SELECT username,role,revision FROM identities ORDER BY username")) {
            var rows=new ArrayList<Identity>();while(r.next())rows.add(identity(r));return List.copyOf(rows);
        }catch(SQLException failure){throw new IOException("WebUI accounts unavailable",failure);}
    }
    public Identity find(String username)throws IOException {
        try(var c=connect()){return require(c,username,-1);}
        catch(SQLException failure){throw new IOException("WebUI account unavailable",failure);}
    }
    public boolean bootstrapAvailable()throws IOException {
        try(var c=connect()){return bootstrapAvailable(c);}
        catch(SQLException failure){throw new IOException("WebUI setup unavailable",failure);}
    }
    private static boolean bootstrapAvailable(Connection c)throws SQLException {
        // A committed identity event permanently closes setup, even after local recovery revokes all users.
        try(var s=c.createStatement();var r=s.executeQuery("SELECT NOT EXISTS(SELECT 1 FROM identities) AND NOT EXISTS(SELECT 1 FROM audit WHERE action LIKE 'identity.%')")){r.next();return r.getBoolean(1);}
    }
    public Identity bootstrap(String username,char[] password)throws IOException {
        var protectedPassword=protect(username,password);
        try{return write(c->{
            if(!bootstrapAvailable(c))throw new IOException("First-run setup is already complete; use the server console to recover access");
            var id=save(c,username,Role.ADMINISTRATOR,protectedPassword,false);
            audit(c,username,"identity.bootstrap",username,"committed");return id;
        });}finally{protectedPassword.clear();}
    }
    /** Privileged local-console recovery; replacing credentials always revokes previous sessions. */
    public void put(String username,char[] password,Role role)throws IOException {
        var protectedPassword=protect(username,password);
        try{write(c->{save(c,username,role,protectedPassword,true);audit(c,"local-console","identity.save",username,"committed");return null;});}
        finally{protectedPassword.clear();}
    }
    public void create(Identity actor,String username,char[] password,Role role)throws IOException {
        var protectedPassword=protect(username,password);
        try{write(c->{requireAdministrator(c,actor);save(c,username,role,protectedPassword,false);audit(c,actor.username(),"identity.create",username,"committed");return null;});}
        finally{protectedPassword.clear();}
    }
    public void password(Identity actor,String username,char[] password,long revision)throws IOException {if(actor==null)throw new IOException("WebUI administrator permission required");resetPassword(actor,username,password,revision);}
    public void password(String username,char[] password,long revision)throws IOException {resetPassword(null,username,password,revision);}
    private void resetPassword(Identity actor,String username,char[] password,long revision)throws IOException {
        var protectedPassword=protect(username,password);
        try{write(c->{
            if(actor!=null)requireAdministrator(c,actor);
            var before=require(c,username,revision);save(c,username,before.role(),protectedPassword,true);
            audit(c,actor==null?"local-console":actor.username(),"identity.password",username,"committed");return null;
        });}finally{protectedPassword.clear();}
    }
    public void role(Identity actor,String username,Role role,long revision)throws IOException {
        write(c->{
            requireAdministrator(c,actor);var before=require(c,username,revision);
            if(role!=Role.ADMINISTRATOR)protectLastAdministrator(c,before);
            try(var p=c.prepareStatement("UPDATE identities SET role=?,revision=? WHERE username=?")){p.setString(1,role.name());p.setLong(2,revision());p.setString(3,username);p.executeUpdate();}
            audit(c,actor.username(),"identity.role",username,"committed");return null;
        });
    }
    public void remove(Identity actor,String username,long revision)throws IOException {
        write(c->{requireAdministrator(c,actor);protectLastAdministrator(c,require(c,username,revision));delete(c,username);audit(c,actor.username(),"identity.delete",username,"committed");return null;});
    }
    /** Local access can recover even after intentionally revoking the last administrator. */
    public void revoke(String username)throws IOException {
        write(c->{delete(c,username);audit(c,"local-console","identity.revoke",username,"committed");return null;});
    }
    private static void delete(Connection c,String username)throws SQLException {try(var p=c.prepareStatement("DELETE FROM identities WHERE username=?")){p.setString(1,username);p.executeUpdate();}}
    private static void protectLastAdministrator(Connection c,Identity before)throws SQLException,IOException {
        if(before.role()!=Role.ADMINISTRATOR)return;
        try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM identities WHERE role='ADMINISTRATOR'")){r.next();if(r.getInt(1)<=1)throw new IOException("Keep at least one WebUI administrator");}
    }
    private static void requireAdministrator(Connection c,Identity actor)throws SQLException,IOException {
        if(actor==null||actor.role()!=Role.ADMINISTRATOR)throw new IOException("WebUI administrator permission required");
        var current=require(c,actor.username(),actor.revision());
        if(current.role()!=Role.ADMINISTRATOR)throw new IOException("WebUI administrator permission required");
    }
    private static Identity require(Connection c,String username,long revision)throws SQLException,IOException {
        try(var p=c.prepareStatement("SELECT username,role,revision FROM identities WHERE username=?")) {
            p.setString(1,username);try(var r=p.executeQuery()){
                if(!r.next())throw new IOException("No such WebUI account");var id=identity(r);
                if(revision>=0&&id.revision()!=revision)throw new IOException("WebUI account changed; refresh before editing");return id;
            }
        }
    }
    private static Identity identity(ResultSet r)throws SQLException {return new Identity(r.getString("username"),Role.valueOf(r.getString("role")),r.getLong("revision"));}
    private record Protection(byte[] salt,byte[] verifier){void clear(){Arrays.fill(verifier,(byte)0);}}
    private static Protection protect(String username,char[] password)throws IOException {
        if(!username.matches("[a-zA-Z0-9_-]{3,64}"))throw new IOException("WebUI username needs 3–64 letters, digits, underscores or hyphens");
        if(password.length<8||password.length>128)throw new IOException("WebUI passwords need 8–128 characters");
        byte[] salt=new byte[32];new SecureRandom().nextBytes(salt);return new Protection(salt,derive(password,salt));
    }
    private static long revision(){return new SecureRandom().nextLong(1,Long.MAX_VALUE);}
    private static Identity save(Connection c,String username,Role role,Protection password,boolean replace)throws SQLException,IOException {
        long revision=revision();
        String sql="INSERT INTO identities VALUES(?,?,?,?,?)"+(replace?" ON CONFLICT(username) DO UPDATE SET role=excluded.role,salt=excluded.salt,verifier=excluded.verifier,revision=excluded.revision":"");
        try(var p=c.prepareStatement(sql)){p.setString(1,username);p.setString(2,Objects.requireNonNull(role).name());p.setBytes(3,password.salt());p.setBytes(4,password.verifier());p.setLong(5,revision);p.executeUpdate();}
        return new Identity(username,role,revision);
    }
    public Identity authenticate(String username,char[] password)throws IOException {
        try(var c=connect();var p=c.prepareStatement("SELECT * FROM identities WHERE username=?")) {
            p.setString(1,username);try(var r=p.executeQuery()){
                boolean found=r.next();byte[] salt=found?r.getBytes("salt"):new byte[32];byte[] expected=found?r.getBytes("verifier"):new byte[32];
                byte[] actual=derive(password,salt);boolean matches=MessageDigest.isEqual(actual,expected);Arrays.fill(actual,(byte)0);
                return found&&matches?identity(r):null;
            }
        }catch(SQLException e){throw new IOException("Authentication unavailable",e);}
    }
    public boolean current(Identity id)throws IOException {
        try(var c=connect();var p=c.prepareStatement("SELECT revision,role FROM identities WHERE username=?")) {
            p.setString(1,id.username());try(var r=p.executeQuery()){return r.next()&&r.getLong(1)==id.revision()&&r.getString(2).equals(id.role().name());}
        }catch(SQLException e){throw new IOException("Authentication unavailable",e);}
    }
    public void audit(String actor,String action,String target,String outcome)throws IOException {
        try(var c=connect()){audit(c,actor,action,target,outcome);}
        catch(SQLException e){throw new IOException("Audit unavailable; operation refused",e);}
    }
    private static void audit(Connection c,String actor,String action,String target,String outcome)throws SQLException {
        try(var p=c.prepareStatement("INSERT INTO audit(time,actor,action,target,outcome) VALUES(?,?,?,?,?)")) {
            p.setLong(1,System.currentTimeMillis());p.setString(2,actor);p.setString(3,action);p.setString(4,target);p.setString(5,outcome);p.executeUpdate();
        }
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
