package org.tortodrone.integration;

import java.math.BigInteger;
import java.net.Socket;
import java.nio.file.*;
import java.security.*;
import java.security.spec.RSAPublicKeySpec;
import java.util.Comparator;
import java.util.concurrent.*;
import org.tortodrone.storage.Database;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.character.Knight;
import org.tortodrone.support.TestClient;
import static org.tortodrone.support.TestWire.check;

/** Commands run on stdin of the distributed server while actual network sessions remain active. */
public final class ConsoleIntegrationTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("tortodrone-console-");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Path jar = directory.resolve("tortodrone.jar"); org.tortodrone.support.TestDeployment.copyServer(Path.of(args[0]),jar);
            Path config = directory.resolve("data/server.properties"); Files.createDirectories(config.getParent());
            Files.writeString(config,"server-ip=0.0.0.0\nserver-port=0\nlog-mode=debug\n");
            try (var server = new ServerIntegrationTest.Running(jar,config,directory)) {
                int port = server.ready(); Database db = new Database(directory.resolve("data"),false);
                check(db.list().isEmpty(),"fresh server does not seed a default account");
                server.send("help\nstatus\n"); server.await("Commands: help"); server.await("Server running. Accounts: 0");
                server.send("account create Alice inline-secret\n"); server.await("Invalid account command");
                server.send("account create Alice gm\nconsole-password\nconsole-password\n"); server.await("Account created: Alice");
                check(db.identity("Alice").gameMaster(),"create with Game Master true persists role");
                server.send("account admin Alice false\naccount create OldAlias admin\n");
                server.await("Invalid account command");
                check(db.identity("Alice").gameMaster() && db.identity("OldAlias")==null,"obsolete role aliases cannot mutate accounts");
                server.send("ac list\n"); server.await("1 | Alice | true | 0"); server.await("Accounts: 1");
                new CharacterStore(db,1,"Alice").create(new Knight(0,"Console-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
                server.send("knight list Alice\nknight info Alice Console-Knight\n");
                server.await("1 | Console-Knight"); server.await("roster ID=1, world ID=1"); server.await("Weapon/Sword/Proto Sword");
                server.send("currency give Alice Console-Knight crowns 100\ncurrency give Alice Console-Knight energy 25\n");
                server.await("Console-Knight | Crowns: 100 | Energy: 25");
                server.send("currency withdraw Alice Console-Knight crowns 40\n");
                server.await("Console-Knight | Crowns: 60 | Energy: 25");
                server.send("currency withdraw Alice Console-Knight energy 26\n");
                server.await("Insufficient energy");
                check(db.worldProfile(1,1,1).crowns()==60 && db.worldProfile(1,1,1).energy()==25,
                    "console gives and withdraws persist, insufficient funds leave counters unchanged");
                server.send("knight info Alice Missing-Knight\n"); server.await("No such knight");
                check(db.worldProfile(1,1,1).items().size()==5,"console inspection does not modify or regrant inventory");
                server.send("items Proto Sword\ngiveitem Alice Console-Knight 2 Weapon/Sword/Proto Sword\n");
                server.await("1 matches; Weapon/Sword/Proto Sword");server.await("Granted 2 x Weapon/Sword/Proto Sword");
                check(db.worldProfile(1,1,1).items().size()==7,"console grants persist distinct owned item instances");
                server.send("giveitem Alice Console-Knight 0 Weapon/Sword/Proto Sword\n");server.await("Quantity must be between 1 and 20");
                check(db.worldProfile(1,1,1).items().size()==7,"invalid console quantity does not grant items");
                server.send("giveitem console-knight 1 Weapon/Sword/Proto Sword\n");server.await("to console-knight (unequipped)");
                check(db.worldProfile(1,1,1).items().size()==8,"name-only grant resolves global knight case-insensitively");
                server.send("giveitem Missing-Knight 1 Weapon/Sword/Proto Sword\n");server.await("No such knight");
                check(db.worldProfile(1,1,1).items().size()==8,"missing global target cannot mutate inventory");
                server.send("account create ALICE\nother-password\nother-password\n"); server.await("already exists");
                server.send("account password Alice\nmismatch-one\nmismatch-two\n"); server.await("Passwords do not match");
                server.send("account check Alice\nconsole-password\n"); server.await("Password matches.");
                server.send("account check Alice\nincorrect-password\n"); server.await("Password does not match.");
                server.send("account gm Alice false\n"); server.await("Game Master status changed: Alice");
                check(!db.identity("Alice").gameMaster(),"Game Master toggle visible through shared database");
                server.send("gm Alice\n");server.await("Game Master status changed: Alice = true");
                check(db.identity("Alice").gameMaster(),"short GM command toggles on");
                server.send("ac gm Alice\n");server.await("Game Master status changed: Alice = false");
                check(!db.identity("Alice").gameMaster(),"account alias toggles off");
                server.send("gm Alice true\ngm Alice false\n");server.await("Game Master status changed: Alice = false");
                check(!db.identity("Alice").gameMaster(),"explicit short GM role form retained");
                String[] keyParts = Files.readString(directory.resolve("data/keys/public.txt")).trim().split("#");
                PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(new BigInteger(keyParts[0],16),new BigInteger(keyParts[1],16)));
                CompletableFuture<Void> ready = new CompletableFuture<>();
                Future<?> disconnected = worker.submit(() -> {
                    try (Socket socket = new Socket("127.0.0.1",port)) {
                        TestClient.authenticate(socket,key,"Alice","console-password",true,(peer,wire,oid) -> { ready.complete(null); check(peer.getInputStream().read() == -1,"terminal password reset revokes active session"); });
                    } catch (Exception failure) { ready.completeExceptionally(failure); throw new RuntimeException(failure); }
                });
                ready.get(10,TimeUnit.SECONDS);
                server.send("account password Alice\nchanged-console-password\nchanged-console-password\n"); server.await("Password changed: Alice"); disconnected.get(10,TimeUnit.SECONDS);
                try (Socket socket = new Socket("127.0.0.1",port)) { TestClient.authenticate(socket,key,"Alice","console-password",false,(a,b,c) -> {}); }
                server.send("account check Alice\nchanged-console-password\n"); server.await("Password matches.");
                server.send("knight delete Alice Console-Knight\nConsole-Knight\n");server.await("Knight deleted: Console-Knight");
                check(db.identity("Alice")!=null&&db.roster(1).isEmpty(),"terminal individual knight deletion preserves account");
                server.send("account delete Alice\nwrong-name\n"); server.await("Deletion confirmation did not match"); check(db.identity("Alice") != null,"incorrect deletion confirmation preserves account");
                server.send("account delete Alice\nAlice\n"); server.await("Account deleted: Alice"); check(db.identity("Alice") == null,"terminal deletion persisted");
                server.send("account create Bob\nbob-password\nbob-password\n"); server.await("Account created: Bob"); check(db.identity("Bob").id() == 2,"deleted account ID not reused");
                server.send("/status\n"); server.await("Server running. Accounts: 1");
                server.send("/stop\n"); check(server.process.waitFor(10,TimeUnit.SECONDS) && server.process.exitValue() == 0,"stop command exits cleanly without Ctrl+C");
            }
            String log = Files.readString(directory.resolve("data/logs/latest.log"));
            check(log.contains("Server stopped.") && !log.contains("console-password") && !log.contains("inline-secret") && !log.contains(TestClient.passwordHash("console-password")),"console logs lifecycle, never password arguments or prompts' values");
            try (var server = new ServerIntegrationTest.Running(jar,config,directory)) {
                server.ready(); server.process.getOutputStream().close(); server.await("Terminal input closed; server remains running");
                check(server.process.isAlive(),"redirected stdin EOF leaves service running");
            }
            System.out.println("PASS: running server terminal commands, password prompts, Game Master roles, reset revocation, delete confirmation, stop and closed stdin");
        } finally {
            worker.shutdownNow();
            try (var paths = Files.walk(directory)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
    }
}
