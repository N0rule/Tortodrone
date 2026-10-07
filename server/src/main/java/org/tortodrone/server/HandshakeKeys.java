package org.tortodrone.server;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;

/** Ephemeral local experiment keys; no production keys or account data. */
public final class HandshakeKeys {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: HandshakeKeys new-local-directory");
        Path directory = Path.of(args[0]);
        Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rwx------")));
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        write(directory.resolve("private.pk8"), pair.getPrivate().getEncoded());
        RSAPublicKey key = (RSAPublicKey) pair.getPublic();
        write(directory.resolve("public.txt"), (key.getModulus().toString(16) + "#"
            + key.getPublicExponent().toString(16)).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static void write(Path path, byte[] value) throws Exception {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rw-------")));
        Files.write(path, value);
    }

    public static PrivateKey load(Path path) throws Exception {
        if (Files.size(path) > 4096) throw new IllegalArgumentException("Private key file too large");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(path)));
    }
}
