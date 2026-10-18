package org.tortodrone.integration;

import java.io.*;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.RSAPublicKeySpec;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.storage.Database;
import org.tortodrone.support.*;
import static org.tortodrone.support.TestWire.check;

/** The actual distributed admin JAR shares a live server's database, not its memory. */
public final class AdminIntegrationTest {
    // A separate admin JVM has to start and derive the new password hash before the server can
    // notice the revocation, so the idle session must outwait that work, not just the server poll.
    private static final int REVOCATION_TIMEOUT_MILLIS = 30_000;
    private static final long REVOCATION_WAIT_SECONDS = 40;
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("tortodrone-admin-test-");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Path server = directory.resolve("tortodrone.jar"), admin = directory.resolve("tortodrone-admin.jar");
            org.tortodrone.support.TestDeployment.copyServer(Path.of(args[0]),server); Files.copy(Path.of(args[1]),admin);
            command(admin,directory,"",true,"Tortodrone Game Master", "--version"); check(!Files.exists(directory.resolve("data")),"version command creates no runtime state");
            command(admin,directory,"",true,"ID\tUsername", "--list-accounts");
            command(admin,directory,"owner-password\nowner-password\n",true,"Account created.","--create-account","Owner","--gm");
            command(admin,directory,"",false,"Use --help","--set-admin","Owner","false");
            command(admin,directory,"",false,"Use --help","--create-account","OldAlias","--admin");
            command(admin,directory,"owner-password\n",true,"Password matches.","--check-password","Owner");
            command(admin,directory,"wrong-password\n",true,"Password does not match.","--check-password","Owner");
            command(admin,directory,"",true,"1\tOwner\ttrue\t","--list-accounts");
            command(admin,directory,"invalid\n",false,"did not match","--delete-account","Owner");
            command(admin,directory,"bad\nbad\n",false,"8-128","--set-password","Owner");
            Path data = directory.resolve("data"), config = data.resolve("server.properties");
            Files.writeString(config,"server-ip=0.0.0.0\nserver-port=0\nlog-mode=debug\n");
            try (var running = new ServerIntegrationTest.Running(server,config,directory)) {
                int port = running.ready(); PublicKey key = key(Files.readString(data.resolve("keys/public.txt")).trim());
                command(admin,directory,"player-password\nplayer-password\n",true,"Account created.","--create-account","Player");
                try (Socket socket = new Socket("127.0.0.1",port)) {
                    TestClient.authenticate(socket,key,"Player","player-password",true,(peer,wire,oid) -> CharacterScenario.session(peer,wire,oid,true));
                }
                Database database = new Database(data,false); check(database.roster(2).size() == 1,"live-created account owns saved characters");
                command(admin,directory,"",true,"2\tPlayer\tfalse\tSaved-Knight","--list-accounts");
                command(admin,directory,"",true,"Game Master status changed.","--set-gm","Player","true");
                check(database.identity("Player").gameMaster(),"desktop service role change visible while server runs");
                var ready = new CompletableFuture<Void>();
                Future<?> disconnected = worker.submit(() -> {
                    try (Socket socket = new Socket("127.0.0.1",port)) {
                        TestClient.authenticate(socket,key,"Player","player-password","success",REVOCATION_TIMEOUT_MILLIS,(peer,wire,oid) -> { ready.complete(null); check(peer.getInputStream().read() == -1,"password reset closes idle active session"); });
                    } catch (Exception failure) { ready.completeExceptionally(failure); throw new RuntimeException(failure); }
                });
                ready.get(REVOCATION_WAIT_SECONDS,TimeUnit.SECONDS);
                command(admin,directory,"changed-password\nchanged-password\n",true,"Password changed.","--set-password","Player");
                disconnected.get(REVOCATION_WAIT_SECONDS,TimeUnit.SECONDS);
                try (Socket socket = new Socket("127.0.0.1",port)) { TestClient.authenticate(socket,key,"Player","player-password",false,(a,b,c) -> {}); }
                command(admin,directory,"",true,"Saved-Knight","--list-knights","Player");
                command(admin,directory,"",true,"Proto Sword [Sword] [id=1132943168] proto_sword","--items","Proto Sword");
                command(admin,directory,"",true,"Granted 2","--give-item","Player","Saved-Knight","2","proto_sword");
                check(database.worldProfile(2,1,2).items().size()==7,"CLI grants persist instances while server remains running");
                command(admin,directory,"wrong-name\n",false,"did not match","--delete-knight","Player","Saved-Knight");
                check(database.roster(2).size()==1,"incorrect knight confirmation preserves owned state");
                var knightReady=new CompletableFuture<Void>();
                Future<?> knightDeleted=worker.submit(()->{
                    try(Socket socket=new Socket("127.0.0.1",port)) {
                        TestClient.authenticate(socket,key,"Player","changed-password","success",REVOCATION_TIMEOUT_MILLIS,(peer,wire,oid)->{knightReady.complete(null);check(peer.getInputStream().read()==-1,"local knight deletion revokes account session");});
                    }catch(Exception failure){knightReady.completeExceptionally(failure);throw new RuntimeException(failure);}
                });
                knightReady.get(REVOCATION_WAIT_SECONDS,TimeUnit.SECONDS);
                command(admin,directory,"Saved-Knight\n",true,"Knight deleted.","--delete-knight","Player","Saved-Knight");
                knightDeleted.get(REVOCATION_WAIT_SECONDS,TimeUnit.SECONDS);
                check(database.identity("Player")!=null&&database.roster(2).isEmpty(),"individual deletion preserves account credentials and clears its roster");
                try(Socket socket=new Socket("127.0.0.1",port)) {
                    TestClient.authenticate(socket,key,"Player","changed-password",true,(peer,wire,oid)->{
                        var writer=new TestWire.Writer(TestWire.NET+"SecureRequest",TestWire.NET+"PublicKeyCredentials","[Ljava.lang.String;",TestWire.NET+"AESAuthRequest");
                        writer.send(peer.getOutputStream(),out->{writer.message(out,"SubscribeRequest",40);out.writeInt(oid);});
                        check(((List<?>)wire.snapshot(wire.frame(peer.getInputStream()),(short)40,oid).get("availableKnights")).isEmpty(),"reconnect refreshes cached roster after external knight deletion");
                        writer.send(peer.getOutputStream(),out->writer.message(out,"LogoffRequest",41));
                    });
                }
                var deletionReady = new CompletableFuture<Void>();
                Future<?> deleted = worker.submit(() -> {
                    try (Socket socket = new Socket("127.0.0.1",port)) {
                        TestClient.authenticate(socket,key,"Player","changed-password","success",REVOCATION_TIMEOUT_MILLIS,(peer,wire,oid) -> { deletionReady.complete(null); check(peer.getInputStream().read() == -1,"delete closes idle active session"); });
                    } catch (Exception failure) { deletionReady.completeExceptionally(failure); throw new RuntimeException(failure); }
                });
                deletionReady.get(REVOCATION_WAIT_SECONDS,TimeUnit.SECONDS);
                command(admin,directory,"Player\n",true,"Account deleted.","--delete-account","Player"); deleted.get(REVOCATION_WAIT_SECONDS,TimeUnit.SECONDS);
                check(database.identity("Player") == null && database.roster(2).isEmpty(),"delete cascades character rows");
                try (Socket socket = new Socket("127.0.0.1",port)) { TestClient.authenticate(socket,key,"Player","changed-password","m.no_such_user",(a,b,c) -> {}); }
                check(running.process.isAlive(),"administration does not stop server");
            }
            String log = Files.readString(data.resolve("logs/latest.log")); check(!log.contains("player-password") && !log.contains("changed-password") && !log.contains(TestClient.passwordHash("player-password")),"passwords and transforms never logged");
            command(admin,directory,"",true,"1\tOwner\ttrue\t","--list-accounts");
            Path elsewhere = directory.resolve("elsewhere"); Files.createDirectories(elsewhere);
            command(admin,elsewhere,"",true,"Owner","--data",data.toString(),"--list-accounts"); check(!Files.exists(elsewhere.resolve("data")),"explicit data selection never uses shell working directory");
            System.out.println("PASS: packaged admin JAR, create/list/check/reset/admin/delete, shared live SQLite, session revocation and data paths");
        } finally {
            worker.shutdownNow();
            try (var paths = Files.walk(directory)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
    }
    private static void command(Path jar,Path cwd,String input,boolean success,String expected,String... args) throws Exception {
        var argv = new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-jar",jar.toString())); argv.addAll(List.of(args));
        Process process = new ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true).start();
        try {
            try (var stream = process.getOutputStream()) { stream.write(input.getBytes(StandardCharsets.UTF_8)); }
            check(process.waitFor(15,TimeUnit.SECONDS),"admin command timeout");
            String output = new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            check((process.exitValue() == 0) == success && output.contains(expected),"admin result: "+output);
            check(!output.contains("owner-password") && !output.contains("player-password") && !output.contains("changed-password"),"CLI never echoes stdin passwords");
        } finally { process.destroyForcibly(); process.waitFor(); }
    }
    private static PublicKey key(String text) throws Exception {
        String[] parts = text.split("#"); return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(new BigInteger(parts[0],16),new BigInteger(parts[1],16)));
    }
}
