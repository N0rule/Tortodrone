package org.tortodrone.server;

import java.io.IOException;
import java.net.Socket;
import java.security.*;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.UUID;
import org.tortodrone.protocol.HandshakeCodec;

/** Secure exchange, optionally authenticating only the fixed synthetic local account. */
final class SecureHandshake {
    static void run(Socket socket, PrivateKey key, Consumer<String> log) throws Exception {
        run(socket, key, log, false);
    }

    static void run(Socket socket, PrivateKey key, Consumer<String> log, boolean localAuth) throws Exception {
        HandshakeCodec codec = new HandshakeCodec();
        byte[] body = HandshakeCodec.readFrame(socket.getInputStream());
        if (body == null) return;
        HandshakeCodec.Request request = codec.request(body);
        log.accept("SecureRequest decoded; frame=" + (body.length + 4) + " bytes; messageId=" + request.messageId());
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
            log.accept("SecureResponse sent; frame=" + response.length + " bytes");
            body = HandshakeCodec.readFrame(socket.getInputStream());
            if (body == null) throw new IOException("Client disconnected before authentication request");
            if (!localAuth) {
                if (!codec.isAuthRequest(body)) throw new IOException("Unexpected next request type");
                log.accept("AESAuthRequest received; frame=" + (body.length + 4)
                    + " bytes; body not decoded; stopping before authentication");
                return;
            }
            HandshakeCodec.Authentication auth = codec.authentication(body);
            if (!auth.version().equals(request.version())) throw new IOException("Authentication build changed");
            log.accept("AESAuthRequest decoded; frame=" + (body.length + 4)
                + " bytes; bootstrap groups=" + auth.groupCount());
            Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
            // Verified legacy protocol IV; this is wire compatibility, not a new encryption design.
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"), new IvParameterSpec(
                new byte[] {1,2,3,4,5,6,7,8,1,2,3,4,5,6,7,8}));
            byte[] credentials = aes.doFinal(auth.encryptedCredentials());
            boolean accepted;
            try {
                accepted = HandshakeCodec.matchesSyntheticAccount(credentials);
            } finally {
                Arrays.fill(credentials, (byte) 0);
            }
            byte[] authResponse = HandshakeCodec.authenticationResponse(auth.messageId(), accepted,
                accepted ? UUID.randomUUID().toString() : null);
            socket.getOutputStream().write(authResponse);
            socket.getOutputStream().flush();
            log.accept("AuthResponse sent; result=" + (accepted ? "success (synthetic account only)" : "rejected")
                + "; frame=" + authResponse.length + " bytes");
            if (!accepted) return;
            // The client's writer starts only once it processes a successful AuthResponse.
            body = HandshakeCodec.readFrame(socket.getInputStream());
            if (body == null) throw new IOException("Client disconnected after authentication response");
            String next = codec.nextMessageType(body);
            if (!next.equals("com.threerings.presents.net.PingRequest")) {
                throw new IOException("Unexpected post-authentication message");
            }
            log.accept("Authentication accepted by client; PingRequest received; frame="
                + (body.length + 4) + " bytes; stopping before bootstrap");
        } finally {
            Arrays.fill(plain, (byte) 0);
            Arrays.fill(secret, (byte) 0);
        }
    }
}
