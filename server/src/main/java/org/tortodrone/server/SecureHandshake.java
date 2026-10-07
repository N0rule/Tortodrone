package org.tortodrone.server;

import java.io.IOException;
import java.net.Socket;
import java.security.*;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import org.tortodrone.protocol.HandshakeCodec;

/** One secure response, then identify the next request and stop before authentication. */
final class SecureHandshake {
    static void run(Socket socket, PrivateKey key, Consumer<String> log) throws Exception {
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
            if (!codec.isAuthRequest(body)) throw new IOException("Unexpected next request type");
            log.accept("AESAuthRequest received; frame=" + (body.length + 4)
                + " bytes; body not decoded; stopping before authentication");
        } finally {
            Arrays.fill(plain, (byte) 0);
            Arrays.fill(secret, (byte) 0);
        }
    }
}
