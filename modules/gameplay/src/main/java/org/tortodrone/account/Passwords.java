package org.tortodrone.account;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Compatible normal-login transform, followed by a separately salted stored verifier. */
public final class Passwords {
    public static final int ITERATIONS = 600000;
    public record Protection(byte[] salt, byte[] verifier) { }
    private Passwords() { }
    public static Protection protect(char[] password) throws IOException {
        validate(password);
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
        char[] hash = transform(password);
        try { return new Protection(salt, derive(hash, salt)); }
        finally { Arrays.fill(hash, '\0'); }
    }
    public static boolean matches(char[] hash, byte[] salt, byte[] verifier) throws IOException {
        if (hash == null || hash.length != 32) return false;
        for (char c : hash) if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) return false;
        byte[] result = derive(hash, salt);
        try { return MessageDigest.isEqual(result, verifier); }
        finally { Arrays.fill(result, (byte) 0); }
    }
    private static byte[] derive(char[] hash, byte[] salt) throws IOException {
        PBEKeySpec spec = new PBEKeySpec(hash, salt, ITERATIONS, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (GeneralSecurityException e) { throw new IOException("Password verification unavailable", e); }
        finally { spec.clearPassword(); }
    }
    public static char[] transform(char[] password) throws IOException {
        var utf8 = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(password));
        byte[] bytes = new byte[utf8.remaining()]; utf8.get(bytes);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes)).toCharArray(); }
        catch (NoSuchAlgorithmException e) { throw new IOException("Client password transform unavailable", e); }
        finally { Arrays.fill(bytes, (byte) 0); if (utf8.hasArray()) Arrays.fill(utf8.array(), (byte) 0); }
    }
    public static void validate(char[] password) throws IOException {
        if (password == null || password.length < 8 || password.length > 128) throw new IOException("Passwords need 8-128 characters");
        for (char c : password) if (Character.isISOControl(c)) throw new IOException("Password contains control characters");
    }
}
