package org.tortodrone.server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
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

/** Starts the distributable JAR; needs only Java, with no launcher or game files. */
public final class ServerIntegrationTest {
    public static void main(String[] args) throws Exception {
        Path jar = Path.of(args[0]).toAbsolutePath();
        Path directory = Files.createTempDirectory("tortodrone-server-test-");
        try {
            // With no configuration, the JAR produces its documented defaults.
            Path generated = directory.resolve("generated/server.properties");
            check(ServerSettings.load(generated).port() == 47624, "default port");
            check(Files.exists(generated), "first-start configuration generation");
            Path config = directory.resolve("server.properties");
            Files.writeString(config, "server-ip=0.0.0.0\nserver-port=0\nclient-host=192.0.2.25\ndata-directory=state\n");
            Path state = directory.resolve("state");
            byte[] identity;
            byte[] publicBytes;
            try (Running server = new Running(jar, config, directory)) {
                int port = server.ready();
                Properties connection = properties(state.resolve("client-connection.properties"));
                check(connection.getProperty("server_host").equals("192.0.2.25"), "advertised host");
                check(connection.getProperty("server_ports").equals(String.valueOf(port)), "bound port export");
                check(connection.getProperty("datagram_ports").equals(String.valueOf(port)), "datagram config export");
                check(connection.getProperty("anonymous_allowed").equals("false"), "guest disabled");
                String publicValue = Files.readString(state.resolve("keys/public.txt")).trim();
                check(connection.getProperty("key.public").equals(publicValue), "public key export");
                PublicKey key = publicKey(publicValue);
                // Two independent host-network clients, including full secure/AES authentication.
                int firstClientOid;
                try (Socket valid = new Socket("127.0.0.1", port);
                     Socket invalid = new Socket("127.0.0.1", port)) {
                    firstClientOid = LocalAuthenticationTest.authenticate(valid, key, true);
                    LocalAuthenticationTest.authenticate(invalid, key, false);
                }
                try (Socket empty = new Socket("127.0.0.1", port)) { }
                try (Socket malformed = new Socket("127.0.0.1", port)) {
                    malformed.setSoTimeout(3000);
                    malformed.getOutputStream().write(new byte[] {0, 0, 0, 1});
                    check(malformed.getInputStream().read() == -1, "malformed frame closes connection");
                }
                // Client disconnects and invalid packets leave the listener running.
                try (Socket reconnect = new Socket("127.0.0.1", port)) {
                    check(LocalAuthenticationTest.authenticate(reconnect, key, true) != firstClientOid,
                        "reconnecting clients have independent bootstrap identities");
                }
                check(server.process.isAlive(), "server survives clients");
                try (Socket creator = new Socket("127.0.0.1", port)) {
                    LocalAuthenticationTest.authenticate(creator, key, true,
                        (socket, wire, oid) -> CharacterCreationTest.session(socket, wire, oid, true));
                }
                check(properties(state.resolve("accounts/1.properties")).getProperty("knights").equals("1"), "creation persisted before acknowledgement");
                rejected(jar, config, directory, "Another server", "duplicate data-directory");
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
            check(log.contains("Server stopped."), "clean shutdown log");
            check(!log.contains("password=") && !log.contains(LocalAuthenticationTest.passwordHash("tortodrone")) && !log.contains("private-test-ident")
                && !log.contains("BEGIN PRIVATE KEY"), "no sensitive payloads in logs");
            try (Running server = new Running(jar, config, directory)) {
                int port = server.ready();
                check(Arrays.equals(identity, Files.readAllBytes(state.resolve("keys/private.pk8"))), "stable private key");
                check(Arrays.equals(publicBytes, Files.readAllBytes(state.resolve("keys/public.txt"))), "stable public key");
                try (Socket returning = new Socket("127.0.0.1", port)) {
                    LocalAuthenticationTest.authenticate(returning, publicKey(new String(publicBytes).trim()), true,
                        (socket, wire, oid) -> CharacterCreationTest.session(socket, wire, oid, false));
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
            Files.writeString(config, "client-host=0.0.0.0\n");
            rejected(jar, config, directory, "client-host", "unusable advertised host");
            System.out.println("PASS: runnable JAR, host-network clients, authentication/reconnect, logs, shutdown/restart, persistent keys, invalid configuration");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static ProcessBuilder command(Path jar, Path config, Path directory) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-jar", jar.toString(), "--config", config.toString())
            .directory(directory.toFile()).redirectErrorStream(true);
    }

    private static void rejected(Path jar, Path config, Path directory, String reason, String label) throws Exception {
        Process process = command(jar, config, directory).start();
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

    private static final class Running implements AutoCloseable {
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
