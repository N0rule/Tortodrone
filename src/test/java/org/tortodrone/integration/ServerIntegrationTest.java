package org.tortodrone.integration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.tortodrone.config.ServerSettings;
import org.tortodrone.support.CharacterScenario;
import org.tortodrone.support.TestClient;

/** Starts the distributable JAR; needs only Java, with no launcher or game files. */
public final class ServerIntegrationTest {
    public static void main(String[] args) throws Exception {
        Path sourceJar = Path.of(args[0]).toAbsolutePath();
        Path directory = Files.createTempDirectory("tortodrone-server-test-");
        try {
            // Each deployment owns data beside its JAR, including custom-config runs.
            Path jar = directory.resolve("tortodrone.jar");
            Files.copy(sourceJar, jar);
            Process version = command(jar, null, directory).command(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar.toString(), "--version").start();
            try {
                check(version.waitFor(10, TimeUnit.SECONDS), "version command timeout");
                String output = new String(version.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
                String expected;
                try (var resource = ServerIntegrationTest.class.getResourceAsStream("/version.txt")) {
                    expected = new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
                }
                check(version.exitValue() == 0 && output.equals("Tortodrone " + expected), "packaged release version");
                check(!Files.exists(directory.resolve("data")), "version command does not create runtime state");
            } finally {
                version.destroyForcibly();
                version.waitFor();
            }
            // With no configuration, the JAR produces its documented defaults.
            Path generated = directory.resolve("generated/server.properties");
            check(ServerSettings.load(generated).port() == 47624, "default port");
            check(Files.exists(generated), "first-start configuration generation");
            defaultWorkflow(jar, directory.resolve("default-start"));
            rejected(jar, null, directory, "Use --help", "removed account startup option", "--list-accounts");
            managementWorkflow(jar, directory.resolve("management-start"));
            accountWorkflow(jar, directory.resolve("account-start"));
            multiSessionWorkflow(jar, directory.resolve("multi-session-start"));
            Path config = directory.resolve("external-settings/server.properties");
            Files.createDirectories(config.getParent());
            Files.writeString(config, "server-ip=0.0.0.0\nserver-port=0\nlog-mode=debug\n");
            Path state = directory.resolve("data");
            seed(jar,directory);
            byte[] identity;
            byte[] publicBytes;
            try (Running server = new Running(jar, config, directory)) {
                int port = server.ready();
                check(!Files.exists(state.resolve("client-connection.properties")), "no connection export is generated");
                String publicValue = Files.readString(state.resolve("keys/public.txt")).trim();
                PublicKey key = publicKey(publicValue);
                // Two independent host-network clients, including full secure/AES authentication.
                int firstClientOid;
                try (Socket valid = new Socket("127.0.0.1", port);
                     Socket invalid = new Socket("127.0.0.1", port)) {
                    firstClientOid = TestClient.authenticate(valid, key, true);
                    TestClient.authenticate(invalid, key, false);
                }
                try (Socket empty = new Socket("127.0.0.1", port)) { }
                try (Socket malformed = new Socket("127.0.0.1", port)) {
                    malformed.setSoTimeout(3000);
                    malformed.getOutputStream().write(new byte[] {0, 0, 0, 1});
                    check(malformed.getInputStream().read() == -1, "malformed frame closes connection");
                }
                // Client disconnects and invalid packets leave the listener running.
                try (Socket reconnect = new Socket("127.0.0.1", port)) {
                    check(TestClient.authenticate(reconnect, key, true) != firstClientOid,
                        "reconnecting clients have independent bootstrap identities");
                }
                check(server.process.isAlive(), "server survives clients");
                try (Socket creator = new Socket("127.0.0.1", port)) {
                    TestClient.authenticate(creator, key, true,
                        (socket, wire, oid) -> CharacterScenario.session(socket, wire, oid, true));
                }
                check(database(state).roster(1).size() == 1, "creation persisted before acknowledgement");
                check(!Files.exists(config.getParent().resolve("data")), "custom configuration cannot relocate runtime state");
                rejected(jar, config, directory, "Another server", "duplicate deployment");
                identity = Files.readAllBytes(state.resolve("keys/private.pk8"));
                publicBytes = Files.readAllBytes(state.resolve("keys/public.txt"));
                if (Files.getFileStore(state).supportsFileAttributeView("posix")) {
                    check(Files.getPosixFilePermissions(state.resolve("keys/private.pk8"))
                        .equals(PosixFilePermissions.fromString("rw-------")), "private key permissions");
                }
            }
            String log = Files.readString(state.resolve("logs/latest.log"));
            check(log.contains("Authentication accepted by client") && log.contains("result=rejected"), "saved authentication logs");
            check(log.contains("client bootstrap accepted") && log.contains("PongResponse sent"), "saved bootstrap and session logs");
            check(log.contains("/DEBUG]: SecureRequest") && log.contains("/WARN]: Normal login rejected"), "debug detail and warning levels");
            check(log.contains("[ServerMain/INFO]: Starting Tortodrone server version "), "versioned startup banner");
            checkLogFormat(log);
            check(log.contains("Server stopped."), "clean shutdown log");
            check(!log.contains("password=") && !log.contains(TestClient.passwordHash("tortodrone")) && !log.contains("private-test-ident")
                && !log.contains("BEGIN PRIVATE KEY"), "no sensitive payloads in logs");
            try (Running server = new Running(jar, config, directory)) {
                int port = server.ready();
                check(Arrays.equals(identity, Files.readAllBytes(state.resolve("keys/private.pk8"))), "stable private key");
                check(Arrays.equals(publicBytes, Files.readAllBytes(state.resolve("keys/public.txt"))), "stable public key");
                try (Socket returning = new Socket("127.0.0.1", port)) {
                    TestClient.authenticate(returning, publicKey(new String(publicBytes).trim()), true,
                        (socket, wire, oid) -> CharacterScenario.session(socket, wire, oid, false));
                }
            }
            // Reject mismatched and incomplete keys without replacing the server's identity.
            Files.writeString(state.resolve("keys/public.txt"), "mismatched");
            rejected(jar, config, directory, "does not match", "mismatched keypair");
            check(Arrays.equals(identity, Files.readAllBytes(state.resolve("keys/private.pk8"))), "mismatch preserves private key");
            Files.write(state.resolve("keys/public.txt"), publicBytes);
            Files.delete(state.resolve("keys/private.pk8"));
            rejected(jar, config, directory, "Incomplete server keys", "incomplete keypair");
            check(!Files.exists(state.resolve("keys/private.pk8")), "no silent key rotation");
            Files.writeString(config, "server-port=65536\n");
            rejected(jar, config, directory, "server-port", "invalid port");
            Files.writeString(config, "server-prt=47624\n");
            rejected(jar, config, directory, "Unknown server property", "configuration typo");
            Files.writeString(config, "log-mode=verbose\n");
            rejected(jar, config, directory, "log-mode must be normal or debug", "invalid log mode");
            Files.writeString(config, "data-directory=elsewhere\n");
            rejected(jar, config, directory, "Unknown server property: data-directory", "removed data-directory setting");
            check(!Files.exists(directory.resolve("elsewhere")), "removed setting cannot relocate state");
            String failures = Files.readString(state.resolve("logs/latest.log"));
            check(failures.contains("[ServerMain/ERROR]: Server startup failed:"), "startup failures use the log format");
            System.out.println("PASS: packaged JAR, normal accounts and password changes, isolated live rosters, concurrent clients, version/logs, restart, keys and invalid configuration");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void defaultWorkflow(Path jar, Path directory) throws Exception {
        Files.createDirectories(directory);
        Path deployment = directory.resolve("deployment with spaces");
        Files.createDirectories(deployment);
        Path deployedJar = deployment.resolve("tortodrone.jar");
        Files.copy(jar, deployedJar);
        seed(deployedJar,deployment);
        Path data = deployment.resolve("data");
        Path config = data.resolve("server.properties");
        ServerSettings defaults = ServerSettings.load(config);
        check(defaults.logMode() == ServerSettings.LogMode.NORMAL, "default logs use normal mode");
        check(!properties(config).containsKey("data-directory"), "generated settings omit data-directory");
        // Use an ephemeral port so this scenario can run alongside a real server.
        String settings = Files.readString(config).replace("server-port=47624", "server-port=0");
        Files.writeString(config, settings);
        byte[] identity;
        // Start from outside the deployment: state must follow the JAR, not the shell.
        try (Running server = new Running(directory.relativize(deployedJar), null, directory)) {
            int port = server.ready();
            for (String file : new String[] {"keys/private.pk8", "keys/public.txt",
                    "logs/latest.log", "server.lock"}) {
                check(Files.isRegularFile(data.resolve(file)), "default startup generates " + file);
            }
            try (Socket creator = new Socket("127.0.0.1", port)) {
                TestClient.authenticate(creator, publicKey(Files.readString(data.resolve("keys/public.txt")).trim()), true,
                    (socket, wire, oid) -> CharacterScenario.session(socket, wire, oid, true));
            }
            check(Files.isRegularFile(data.resolve("tortodrone.db")), "default character save stays under data");
            identity = Files.readAllBytes(data.resolve("keys/private.pk8"));
            check(!Files.exists(directory.resolve("data")), "startup does not create data in the working directory");
        }
        try (Running server = new Running(deployedJar, null, deployment)) {
            int port = server.ready();
            try (Socket returning = new Socket("127.0.0.1", port)) {
                TestClient.authenticate(returning, publicKey(Files.readString(data.resolve("keys/public.txt")).trim()), true,
                    (socket, wire, oid) -> CharacterScenario.session(socket, wire, oid, false));
            }
            check(Arrays.equals(identity, Files.readAllBytes(data.resolve("keys/private.pk8"))), "default restart keeps identity");
        }
        check(Files.readString(config).equals(settings), "default startup preserves edited settings");
        check(!Files.exists(data.resolve("client-connection.properties")), "startup and restart never export connection settings");
        String log = Files.readString(data.resolve("logs/latest.log"));
        checkLogFormat(log);
        check(log.contains("[ServerMain/INFO]: Starting Tortodrone server version ")
            && log.contains("/INFO]: Normal login accepted") && log.contains("/INFO]: Character saved"), "normal lifecycle and save logs");
        check(!log.contains("/DEBUG]") && !log.contains("SecureRequest") && !log.contains("PongResponse")
            && !log.contains("snapshot sent") && !log.contains("InvocationRequestEvent"), "normal mode suppresses verbose protocol logs");
        check(log.contains("Loading server keypair") && log.contains("Generating server keypair"), "accurate key creation/reuse messages");
        try (var entries = Files.list(deployment)) {
            check(entries.count() == 2, "deployment contains only the JAR and data directory");
        }
    }

    private static void managementWorkflow(Path jar, Path directory) throws Exception {
        Files.createDirectories(directory);
        Path deployed = directory.resolve("tortodrone.jar");
        Files.copy(jar, deployed);
        seed(deployed,directory);
        Path data = directory.resolve("data");
        Files.createDirectories(data);
        Path config = data.resolve("server.properties");
        Files.writeString(config, "server-ip=0.0.0.0\nserver-port=0\n");
        for (boolean create : new boolean[] {true, false}) {
            try (Running server = new Running(deployed, null, directory)) {
                int port = server.ready();
                try (Socket peer = new Socket("127.0.0.1", port)) {
                    TestClient.authenticate(peer, publicKey(Files.readString(data.resolve("keys/public.txt")).trim()), true,
                        (socket, wire, oid) -> CharacterScenario.management(socket, wire, oid, create));
                }
                var database = database(data);
                check(database.roster(1).size() == (create ? 3 : 0), "management callbacks follow persisted mutations");
                check(database.nextKnight(1) == 5, "editing/deleting does not reset character identities");
            }
        }
        String log = Files.readString(data.resolve("logs/latest.log"));
        check(log.contains("/INFO]: Character deleted"), "deletions appear in normal logs");
        checkLogFormat(log);
    }

    private static void accountWorkflow(Path sourceJar, Path directory) throws Exception {
        Files.createDirectories(directory);
        Path jar = directory.resolve("tortodrone.jar"); Files.copy(sourceJar, jar);
        seed(jar,directory);
        Path data = directory.resolve("data");
        String password = "alice-test-password";
        accountCommand(jar, directory, "--create-account", "Alice", password + "\n" + password + "\n", true, "Account created.");
        accountCommand(jar, directory, "--list-accounts", null, "", true, "2\tAlice");
        var before = database(data).list();
        accountCommand(jar, directory, "--create-account", "ALICE", password + "\n" + password + "\n", false, "already exists");
        accountCommand(jar, directory, "--set-password", "Alice", "mismatch-one\nmismatch-two\n", false, "Passwords do not match");
        accountCommand(jar, directory, "--set-password", "Alice", "", false, "Password input ended");
        check(before.equals(database(data).list()), "failed account commands preserve accounts");
        Path config = data.resolve("server.properties");
        Files.writeString(config, "server-ip=0.0.0.0\nserver-port=0\nlog-mode=debug\n");
        java.util.List<org.tortodrone.character.Knight> profile;
        byte[] keyBytes;
        try (Running server = new Running(jar, config, directory)) {
            int port = server.ready();
            PublicKey key = publicKey(Files.readString(data.resolve("keys/public.txt")).trim());
            try (Socket peer = new Socket("127.0.0.1", port)) {
                TestClient.authenticate(peer, key, "Alice", password, true,
                    (socket, wire, oid) -> CharacterScenario.session(socket, wire, oid, true));
            }
            profile = database(data).roster(2);
            keyBytes = Files.readAllBytes(data.resolve("keys/public.txt"));
            check(database(data).roster(1).isEmpty(), "new account does not save into default profile");
            try (Socket peer = new Socket("127.0.0.1", port)) {
                TestClient.authenticate(peer, key, "Alice", "incorrect-password", false, (a, b, c) -> {});
            }
        }
        String changed = "changed-test-password";
        accountCommand(jar, directory, "--set-password", "alice", changed + "\n" + changed + "\n", true, "Password changed.");
        check(profile.equals(database(data).roster(2)), "password reset preserves knights");
        try (Running server = new Running(jar, config, directory)) {
            int port = server.ready(); PublicKey key = publicKey(Files.readString(data.resolve("keys/public.txt")).trim());
            try (Socket old = new Socket("127.0.0.1", port)) {
                TestClient.authenticate(old, key, "Alice", password, false, (a, b, c) -> {});
            }
            try (Socket peer = new Socket("127.0.0.1", port)) {
                TestClient.authenticate(peer, key, "alice", changed, true, (socket, wire, oid) -> {
                    var writer = new org.tortodrone.support.TestWire.Writer(org.tortodrone.support.TestWire.NET + "SecureRequest",
                        org.tortodrone.support.TestWire.NET + "PublicKeyCredentials", "[Ljava.lang.String;", org.tortodrone.support.TestWire.NET + "AESAuthRequest");
                    writer.send(socket.getOutputStream(), out -> { writer.message(out, "SubscribeRequest", 40); out.writeInt(oid); });
                    var player = wire.snapshot(wire.frame(socket.getInputStream()), (short) 40, oid);
                    check(player.get("username").equals("Alice") && player.get("playerId").equals(2), "server-owned account identity in player snapshot");
                    check(((java.util.List<?>) player.get("availableKnights")).size() == 1, "account roster restored");
                    writer.send(socket.getOutputStream(), out -> writer.message(out, "LogoffRequest", 41));
                });
            }
        }
        check(Arrays.equals(keyBytes, Files.readAllBytes(data.resolve("keys/public.txt"))), "account administration preserves server identity");
        String log = Files.readString(data.resolve("logs/latest.log"));
        check(!log.contains(password) && !log.contains(changed) && !log.contains(TestClient.passwordHash(password)), "account secrets absent from logs");
    }

    private static void multiSessionWorkflow(Path sourceJar, Path directory) throws Exception {
        Files.createDirectories(directory); Path jar = directory.resolve("tortodrone.jar"); Files.copy(sourceJar, jar);
        seed(jar,directory);
        accountCommand(jar, directory, "--create-account", "Alice", "alice-password\nalice-password\n", true, "Account created.");
        accountCommand(jar, directory, "--create-account", "Bob", "bob-password\nbob-password\n", true, "Account created.");
        Path data = directory.resolve("data"), config = data.resolve("server.properties");
        Files.writeString(config, "server-ip=0.0.0.0\nserver-port=0\nlog-mode=normal\n");
        try (Running server = new Running(jar, config, directory)) {
            int port = server.ready(); PublicKey key = publicKey(Files.readString(data.resolve("keys/public.txt")).trim());
            org.tortodrone.support.MultiSessionScenario.run(port, key);
        }
        check(database(data).roster(2).size() == 1, "concurrent account roster persisted");
        check(database(data).roster(3).isEmpty(), "foreign-account operations never write its profile");
        check(database(data).roster(2).get(0).name().equals("Parallel-Two"), "final subscribed roster matches saved state");
    }

    private static void accountCommand(Path jar, Path directory, String command, String username,
            String input, boolean success, String expected) throws Exception {
        // Seed and inspect disposable deployments through the shared service, not removed server options.
        String result;
        boolean succeeded = true;
        try (var accounts = new org.tortodrone.account.AccountStore(directory.resolve("data"))) {
            if (command.equals("--list-accounts")) result = accounts.list().stream().map(a -> a.id()+"\t"+a.username()).collect(java.util.stream.Collectors.joining("\n"));
            else {
                String[] lines = input.split("\n", -1);
                if (lines.length < 3) throw new IOException("Password input ended");
                if (!lines[0].equals(lines[1])) throw new IOException("Passwords do not match");
                if (command.equals("--create-account")) accounts.create(username,lines[0].toCharArray());
                else accounts.setPassword(username,lines[0].toCharArray());
                result = command.equals("--create-account") ? "Account created." : "Password changed.";
            }
        } catch (IOException failure) { succeeded = false; result = failure.getMessage(); }
        check(succeeded == success && result.contains(expected), "account service result: " + result);
    }

    private static org.tortodrone.storage.Database database(Path data) throws IOException { return new org.tortodrone.storage.Database(data,false); }
    private static void seed(Path jar,Path directory) throws Exception {
        accountCommand(jar,directory,"--create-account","tortodrone","tortodrone\ntortodrone\n",true,"Account created.");
    }

    private static void checkLogFormat(String log) {
        check(log.lines().allMatch(line -> line.matches(
            "\\[\\d{2}:\\d{2}:\\d{2}\\] \\[[^/\\]]+/(INFO|DEBUG|WARN|ERROR)\\]: .+")), "time/thread/level log format");
    }

    private static ProcessBuilder command(Path jar, Path config, Path directory) {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder process = new ProcessBuilder(javaExecutable, "-jar", jar.toString());
        if (config != null) process.command().addAll(java.util.List.of("--config", config.toString()));
        return process.directory(directory.toFile()).redirectErrorStream(true);
    }

    private static void rejected(Path jar, Path config, Path directory, String reason, String label, String... options) throws Exception {
        ProcessBuilder builder = command(jar, config, directory); builder.command().addAll(java.util.List.of(options));
        Process process = builder.start();
        try {
            check(process.waitFor(10, TimeUnit.SECONDS), label + " timeout");
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            check(process.exitValue() != 0 && output.contains(reason), label + ": " + output);
        } finally {
            process.destroyForcibly();
            process.waitFor();
        }
    }

    private static Properties properties(Path file) throws IOException {
        Properties properties = new Properties();
        try (var source = Files.newInputStream(file)) { properties.load(source); }
        return properties;
    }

    private static PublicKey publicKey(String value) throws Exception {
        String[] parts = value.split("#");
        return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
            new BigInteger(parts[0], 16), new BigInteger(parts[1], 16)));
    }

    static final class Running implements AutoCloseable {
        final Process process;
        private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

        Running(Path jar, Path config, Path directory) throws IOException {
            process = command(jar, config, directory).start();
            Thread reader = new Thread(() -> {
                try (var input = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = input.readLine()) != null) lines.add(line);
                } catch (IOException e) { lines.add(e.toString()); }
                finally { lines.add("<process ended>"); }
            }, "test-server-output");
            reader.setDaemon(true);
            reader.start();
        }

        int ready() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            StringBuilder output = new StringBuilder();
            while (System.nanoTime() < deadline) {
                String line = lines.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (line == null || line.equals("<process ended>")) break;
                output.append(line).append('\n');
                if (line.contains("Listening on 0.0.0.0:")) {
                    return Integer.parseInt(line.split("Listening on 0.0.0.0:")[1].split(" ")[0]);
                }
            }
            throw new AssertionError("Server did not become ready: " + output);
        }

        void send(String input) throws IOException {
            process.getOutputStream().write(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            process.getOutputStream().flush();
        }

        void await(String expected) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            StringBuilder output = new StringBuilder();
            while (System.nanoTime() < deadline) {
                String line = lines.poll(Math.max(1, deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
                if (line == null || line.equals("<process ended>")) break;
                output.append(line).append('\n');
                if (line.contains(expected)) return;
            }
            throw new AssertionError("Missing console result " + expected + ": " + output);
        }

        @Override public void close() throws Exception {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor();
                throw new AssertionError("Server failed to shut down cleanly");
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
