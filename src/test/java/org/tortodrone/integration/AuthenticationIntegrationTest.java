package org.tortodrone.integration;

import java.math.BigInteger;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.List;
import org.tortodrone.account.AccountStore;
import org.tortodrone.support.ContentFixture;
import org.tortodrone.support.TestClient;
import org.tortodrone.support.TestClient.LoginFlags;
import org.tortodrone.support.TestDeployment;
import org.tortodrone.support.TestWire;
import static org.tortodrone.support.TestWire.check;

/** Disposable packaged-server authentication, including Steam environment flags and hostile diagnostics. */
public final class AuthenticationIntegrationTest {
    private static final String USERNAME = "SteamFixture";
    private static final String PASSWORD = "fixture-auth-password";
    private static final String WRONG_PASSWORD = "fixture-wrong-password";
    private static final LoginFlags STEAM = new LoginFlags(true, null, false, 0, null);

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("tortodrone-authentication-");
        try {
            Path jar = directory.resolve("tortodrone.jar");
            TestDeployment.copyServer(Path.of(args[0]).toAbsolutePath(), jar);
            Path data = directory.resolve("data");
            int accountId;
            List<AccountStore.Summary> original;
            try (var accounts = new AccountStore(data)) {
                accounts.create(USERNAME, PASSWORD.toCharArray());
                accounts.create("OtherOwner", "other-owner-password".toCharArray());
                original = accounts.list();
                accountId = original.getFirst().id();
            }
            String hostile = "Injected\"\\\r\n\t\u001b\u0085\u202e\u2028\u2029" + "x".repeat(600) + "PRIVATE-TAIL";
            Path config = directory.resolve("auth.properties");
            for (String mode : List.of("normal", "debug")) {
                Files.writeString(config, "server-ip=0.0.0.0\nserver-port=0\nwebui-enabled=false\n"
                    + "webui/content_url-port=0\nlog-mode=" + mode + "\n");
                // Each mode gets its own log, without deleting runtime identity or database state.
                Path logPath = data.resolve("logs/latest.log");
                long logOffset = Files.exists(logPath) ? Files.size(logPath) : 0;
                try (var server = new ServerIntegrationTest.Running(jar, config, directory)) {
                    int port = server.ready();
                    PublicKey key = publicKey(Files.readString(data.resolve("keys/public.txt")).trim());
                    login(port, key, USERNAME.toLowerCase(java.util.Locale.ROOT), PASSWORD, "success", STEAM, accountId);
                    login(port, key, USERNAME, WRONG_PASSWORD, "m.invalid_password", STEAM, accountId);
                    login(port, key, "MissingSteamUser", PASSWORD, "m.no_such_user", STEAM, accountId);
                    login(port, key, hostile, WRONG_PASSWORD, "m.no_such_user", STEAM, accountId);
                    // Correct passwords must not authorize alternate login modes, even with steamClient=true.
                    for (LoginFlags flags : List.of(new LoginFlags(true, "private-test-session", false, 0, null),
                            new LoginFlags(true, null, true, 0, null), new LoginFlags(true, null, false, 1, null),
                            new LoginFlags(true, null, false, 0, "private-test-invite"))) {
                        login(port, key, USERNAME, PASSWORD, "m.invalid_password", flags, accountId);
                    }
                    login(port, key, USERNAME, PASSWORD, "success", new LoginFlags(false, null, false, 0, null), accountId);
                    check(server.process.isAlive(), "listener survives rejected Steam/hostile logins");
                }
                byte[] logBytes = Files.readAllBytes(logPath);
                String log = new String(logBytes, Math.toIntExact(logOffset), logBytes.length - Math.toIntExact(logOffset),
                    java.nio.charset.StandardCharsets.UTF_8);
                verifyLogs(log, mode);
                try (var accounts = new AccountStore(data)) {
                    check(accounts.list().equals(original), "successful and rejected logins preserve accounts, ownership and rosters");
                }
            }
            System.out.println("PASS: packaged Steam/non-Steam authentication, password and mode rejection, owned identity, bounded secret-free logs in normal/debug modes");
        } finally {
            ContentFixture.delete(directory);
        }
    }

    private static void login(int port, PublicKey key, String username, String password, String expected,
            LoginFlags flags, int accountId) throws Exception {
        try (var peer = new Socket("127.0.0.1", port)) {
            int oid = TestClient.authenticate(peer, key, username, password, expected, 30000, flags, (socket, wire, clientOid) -> {
                var writer = new TestWire.Writer(TestWire.NET + "SecureRequest", TestWire.NET + "PublicKeyCredentials",
                    "[Ljava.lang.String;", TestWire.NET + "AESAuthRequest");
                writer.send(socket.getOutputStream(), out -> { writer.message(out, "SubscribeRequest", 29); out.writeInt(clientOid); });
                var player = wire.snapshot(wire.frame(socket.getInputStream()), (short) 29, clientOid);
                check(player.get("username").equals(USERNAME) && player.get("playerId").equals(accountId),
                    "Steam flag cannot change the server-owned account identity");
                TestClient.logoff(socket);
            });
            check(expected.equals("success") ? oid > 0 : oid == 0, "only accepted logins receive a bootstrap identity");
        }
    }

    private static void verifyLogs(String log, String mode) {
        var warnings = log.lines().filter(line -> line.contains("/WARN]: Normal login rejected;")).toList();
        check(warnings.size() == 7, "each failed login has one warning in " + mode);
        check(warnings.stream().anyMatch(line -> line.contains("account=\"" + USERNAME + "\"; reason=m.invalid_password; normalLogin=true")),
            "wrong-password diagnostic stays useful");
        check(warnings.stream().anyMatch(line -> line.contains("account=\"MissingSteamUser\"; reason=m.no_such_user; normalLogin=true")),
            "missing-account diagnostic stays useful");
        check(warnings.stream().filter(line -> line.contains("reason=unsupported_login; normalLogin=false")).count() == 4,
            "unsupported modes are distinguished without logging their payloads");
        String hostile = warnings.stream().filter(line -> line.contains("account=\"Injected")).findFirst().orElseThrow();
        check(hostile.contains("Injected\\\"\\\\") && hostile.contains("...\"; reason=") && hostile.length() < 700,
            "untrusted account is escaped, quoted and bounded");
        check(hostile.chars().noneMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
            || Character.getType(c) == Character.LINE_SEPARATOR || Character.getType(c) == Character.PARAGRAPH_SEPARATOR),
            "hostile account cannot inject terminal controls or Unicode line breaks");
        for (String secret : List.of(PASSWORD, WRONG_PASSWORD, TestClient.passwordHash(PASSWORD), TestClient.passwordHash(WRONG_PASSWORD),
                "private-test-ident", "private-test-session", "private-test-invite", "PRIVATE-TAIL")) {
            check(!log.contains(secret), "authentication payloads and truncated text never reach logs");
        }
        check(log.contains("/DEBUG]: AESAuthRequest decoded") == mode.equals("debug"), "debug detail follows log mode");
        check(log.lines().allMatch(line -> line.matches("\\[\\d{2}:\\d{2}:\\d{2}\\] \\[[^/\\]]+/(INFO|DEBUG|WARN|ERROR)\\]: .+")),
            "saved log retains one formatted event per line");
    }

    private static PublicKey publicKey(String value) throws Exception {
        String[] parts = value.split("#");
        return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
            new BigInteger(parts[0], 16), new BigInteger(parts[1], 16)));
    }
}
