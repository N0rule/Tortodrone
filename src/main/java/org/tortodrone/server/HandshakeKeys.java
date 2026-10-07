package org.tortodrone.server;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;

/** Persistent server identity. Never replace an existing keypair on startup. */
public final class HandshakeKeys {
    public static PrivateKey loadOrCreate(Path directory) throws Exception {
        Path privateFile = directory.resolve("private.pk8");
        Path publicFile = directory.resolve("public.txt");
        if (!Files.exists(directory)) Files.createDirectory(directory);
        restrict(directory, "rwx------");
        if (!Files.exists(privateFile)) {
            // A partial identity must not silently become a new server identity.
            try (var entries = Files.list(directory)) {
                if (entries.findAny().isPresent()) {
                    throw new java.io.IOException("Incomplete server keys; restore keys/private.pk8 from backup");
                }
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            write(privateFile, generator.generateKeyPair().getPrivate().getEncoded());
        }
        restrict(privateFile, "rw-------");
        PrivateKey key = load(privateFile);
        if (!(key instanceof RSAPrivateCrtKey rsa) || rsa.getModulus().bitLength() != 2048) {
            throw new java.io.IOException("Server requires a 2048-bit RSA private key");
        }
        String publicValue = rsa.getModulus().toString(16) + "#" + rsa.getPublicExponent().toString(16);
        if (Files.exists(publicFile)) {
            if (Files.size(publicFile) > 4096 || !Files.readString(publicFile).trim().equals(publicValue)) {
                throw new java.io.IOException("keys/public.txt does not match keys/private.pk8; restore the matching pair");
            }
            restrict(publicFile, "rw-------");
        } else {
            write(publicFile, publicValue.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        return key;
    }

    private static void write(Path path, byte[] value) throws Exception {
        if (Files.getFileStore(path.getParent()).supportsFileAttributeView("posix")) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(path);
        }
        Files.write(path, value);
    }

    private static void restrict(Path path, String permissions) throws java.io.IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        }
    }

    public static PrivateKey load(Path path) throws Exception {
        if (Files.size(path) > 4096) throw new IllegalArgumentException("Private key file too large");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(path)));
    }
}
