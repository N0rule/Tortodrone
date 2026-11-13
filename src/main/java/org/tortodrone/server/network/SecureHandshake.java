package org.tortodrone.server.network;

import java.io.IOException;
import java.net.Socket;
import java.security.*;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.account.AccountStore;
import org.tortodrone.protocol.HandshakeCodec;

/** Secure exchange and normal authentication against server-owned accounts. */
public final class SecureHandshake {
    /** Displayed verbatim by the stock logon panel (unknown codes fall back to their own text). */
    static final String STEAM_LOGIN_MESSAGE =
        "Steam accounts are not supported. Log in using your regular account name and password.";
    static void run(Socket socket, PrivateKey key, SessionLog log) throws Exception {
        run(socket, key, log, false);
    }

    static void run(Socket socket, PrivateKey key, SessionLog log, boolean localAuth) throws Exception {
        run(socket, key, log, localAuth, 1, socket.getLocalAddress().getHostAddress(), socket.getLocalPort());
    }

    static void run(Socket socket, PrivateKey key, SessionLog log, boolean localAuth,
            int connectionId, String host, int port) throws Exception {
        run(socket, key, log, localAuth, connectionId, host, port, new CharacterStore());
    }

    public static void run(Socket socket, PrivateKey key, SessionLog log, boolean localAuth,
            int connectionId, String host, int port, CharacterStore characters) throws Exception {
        run(socket, key, log, localAuth, connectionId, host, port, new AccountStore(characters));
    }

    public static void run(Socket socket, PrivateKey key, SessionLog log, boolean localAuth,
            int connectionId, String host, int port, AccountStore accounts) throws Exception {
        run(socket,key,log,localAuth,connectionId,host,port,accounts,"");
    }

    public static void run(Socket socket, PrivateKey key, SessionLog log, boolean localAuth,
            int connectionId, String host, int port, AccountStore accounts, String welcomeMessage) throws Exception {
        long started=System.nanoTime();
        HandshakeCodec codec = new HandshakeCodec();
        byte[] body = HandshakeCodec.readFrame(socket.getInputStream());
        if (body == null) return;
        HandshakeCodec.Request request = codec.request(body);
        log.authenticating(request.version());
        log.debug("SecureRequest decoded; frame=" + (body.length + 4) + " bytes; messageId=" + request.messageId());
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.DECRYPT_MODE, key);
        byte[] plain = rsa.doFinal(request.encryptedSecret());
        byte[] secret = new byte[16];
        try {
            if (plain.length != 20 || !MessageDigest.isEqual(Arrays.copyOfRange(plain, 16, 20), request.salt())) {
                throw new IOException("Invalid secret/salt");
            }
            new SecureRandom().nextBytes(secret);
            byte[] masked = new byte[16];
            for (int i = 0; i < 16; i++) masked[i] = (byte) (secret[i] ^ plain[i]);
            byte[] response = HandshakeCodec.response(request.messageId(), HexFormat.of().formatHex(masked));
            socket.getOutputStream().write(response);
            socket.getOutputStream().flush();
            log.debug("SecureResponse sent; frame=" + response.length + " bytes");
            body = HandshakeCodec.readFrame(socket.getInputStream());
            if (body == null) throw new IOException("Client disconnected before authentication request");
            if (!localAuth) {
                if (!codec.isAuthRequest(body)) throw new IOException("Unexpected next request type");
                log.debug("AESAuthRequest received; frame=" + (body.length + 4)
                    + " bytes; body not decoded; stopping before authentication");
                return;
            }
            HandshakeCodec.Authentication auth = codec.authentication(body);
            if (!auth.version().equals(request.version())) throw new IOException("Authentication build changed");
            log.debug("AESAuthRequest decoded; frame=" + (body.length + 4)
                + " bytes; bootstrap groups=" + auth.groupCount());
            Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
            // Verified stock protocol IV; this is wire compatibility, not a new encryption design.
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"), new IvParameterSpec(
                new byte[] {1,2,3,4,5,6,7,8,1,2,3,4,5,6,7,8}));
            byte[] credentials = aes.doFinal(auth.encryptedCredentials());
            AccountStore.Account account = null;
            String language = null, region = null;
            String loginResult = "m.invalid_password";
            boolean normalLogin = false;
            boolean steamClient = false;
            boolean ticketLogin = false;
            String attemptedUser = null;
            try (var decoded = HandshakeCodec.credentials(credentials)) {
                normalLogin = decoded.normalLogin();
                steamClient = decoded.steamClient();
                attemptedUser = decoded.username();
                if (normalLogin) {
                    language = decoded.language();
                    region = decoded.region();
                    var result = accounts.login(decoded.username(), decoded.passwordHash());
                    account = result.account();
                    loginResult = account != null ? "success" : result.failure() == AccountStore.LoginFailure.NO_SUCH_USER ? "m.no_such_user" : "m.invalid_password";
                }
            } catch (HandshakeCodec.TicketLogin ticket) {
                // Steam account/ticket authentication stays unsupported, but the attempt is a valid
                // stock mode: answer so the client can show why instead of reporting a network error.
                ticketLogin = true;
                loginResult = STEAM_LOGIN_MESSAGE;
            } finally {
                Arrays.fill(credentials, (byte) 0);
            }
            boolean accepted = account != null;
            // The stock client adopts this name into its credentials, and SteamDirector offers
            // account conversion to Steam-initialized clients whose session name is not a steam
            // alias. Tortodrone never converts accounts, so a Steam client is reported under the
            // server-chosen alias of its authenticated account; the account itself stays normal.
            String authName = accepted ? (steamClient ? "steam:" + account.id() : account.username()) : null;
            byte[] authResponse = HandshakeCodec.authenticationResponse(auth.messageId(), loginResult,
                accepted ? UUID.randomUUID().toString() : null, authName);
            socket.getOutputStream().write(authResponse);
            socket.getOutputStream().flush();
            log.debug("AuthResponse sent; result=" + (accepted ? "success" : "rejected")
                + "; frame=" + authResponse.length + " bytes");
            if (accepted && steamClient) {
                log.debug("Steam client alias assigned; accountId=" + account.id()
                    + "; the authenticated account and its password login are unchanged");
            }
            if (!accepted) {
                if (ticketLogin) {
                    log.warn("Steam platform-ticket login rejected; reason=steam_unsupported");
                } else {
                    log.warn("Normal login rejected; account=" + ClientDiagnostics.text(attemptedUser)
                        + "; reason=" + (normalLogin ? loginResult : "unsupported_login") + "; normalLogin=" + normalLogin);
                }
                return;
            }
            log.authenticated(account.id(), account.username());
            log.info("Normal login accepted; account=" + account.username() + " (#" + account.id() + "); accountId=" + account.id());
            // Do not retain negotiated secrets for the unencrypted distributed-object session.
            Arrays.fill(plain, (byte) 0);
            Arrays.fill(secret, (byte) 0);
            try (var session = accounts.attach(account,socket)) {
                ClientDiagnostics.joined(log,socket,connectionId,account,request,language,region,(System.nanoTime()-started)/1_000_000);
                ClientSession.run(socket, codec, connectionId, host, port, log, account.characters(),account.authRevision(),session,welcomeMessage);
            }
        } finally {
            Arrays.fill(plain, (byte) 0);
            Arrays.fill(secret, (byte) 0);
        }
    }
}
