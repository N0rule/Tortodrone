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
        HandshakeCodec codec = new HandshakeCodec();
        byte[] body = HandshakeCodec.readFrame(socket.getInputStream());
        if (body == null) return;
        HandshakeCodec.Request request = codec.request(body);
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
            // Verified legacy protocol IV; this is wire compatibility, not a new encryption design.
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"), new IvParameterSpec(
                new byte[] {1,2,3,4,5,6,7,8,1,2,3,4,5,6,7,8}));
            byte[] credentials = aes.doFinal(auth.encryptedCredentials());
            AccountStore.Account account;
            try (var decoded = HandshakeCodec.credentials(credentials)) {
                account = decoded.normalLogin() ? accounts.authenticate(decoded.username(), decoded.passwordHash()) : null;
            } finally {
                Arrays.fill(credentials, (byte) 0);
            }
            boolean accepted = account != null;
            byte[] authResponse = HandshakeCodec.authenticationResponse(auth.messageId(), accepted,
                accepted ? UUID.randomUUID().toString() : null, accepted ? account.username() : null);
            socket.getOutputStream().write(authResponse);
            socket.getOutputStream().flush();
            log.debug("AuthResponse sent; result=" + (accepted ? "success"
                : "rejected")
                + "; frame=" + authResponse.length + " bytes");
            if (!accepted) {
                log.warn("Normal login rejected");
                return;
            }
            log.info("Normal login accepted; accountId=" + account.id());
            // Do not retain negotiated secrets for the unencrypted distributed-object session.
            Arrays.fill(plain, (byte) 0);
            Arrays.fill(secret, (byte) 0);
            ClientSession.run(socket, codec, connectionId, host, port, log, account.characters());
        } finally {
            Arrays.fill(plain, (byte) 0);
            Arrays.fill(secret, (byte) 0);
        }
    }
}
