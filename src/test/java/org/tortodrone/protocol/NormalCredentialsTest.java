package org.tortodrone.protocol;

import java.io.*;
import java.util.Arrays;
import static org.tortodrone.support.TestWire.check;

public final class NormalCredentialsTest {
    public static void main(String[] args) throws Exception {
        var decoded = HandshakeCodec.credentials(payload(null, false, false, 0, null));
        check(decoded.normalLogin() && decoded.username().equals("Alice"), "normal username/password envelope");
        check(decoded.language().equals("en")&&decoded.region().equals("fixture-region"),"verified non-secret locale fields retained for debug diagnostics");
        char[] secret = decoded.passwordHash();
        check(!decoded.toString().contains("Alice") && !decoded.toString().contains("012345"), "credential diagnostics are redacted");
        decoded.close(); check(Arrays.equals(secret, new char[32]), "decoded transform cleared");
        for (byte[] unsupported : new byte[][] {payload("session", false, false, 0, null), payload(null, true, false, 0, null),
                payload(null, false, false, 1, null), payload(null, false, false, 0, "invite")}) {
            try (var credentials = HandshakeCodec.credentials(unsupported)) { check(!credentials.normalLogin(), "session/platform credential not authorized"); }
        }
        try (var steamCreds = HandshakeCodec.credentials(payload(null, false, true, 0, null))) {
            check(steamCreds.normalLogin(), "steam client with username/password is accepted");
        }
        byte[] normal = payload(null, false, false, 0, null);
        rejected(Arrays.copyOf(normal, normal.length + 1));
        rejected(new byte[4097]);
        ByteArrayOutputStream guest = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(guest); out.writeShort(-1); out.writeUTF("com.threerings.projectx.data.GuestCredentials");
        rejected(guest.toByteArray());
        System.out.println("PASS: normal credential policy, platform/session rejection, bounds and secret clearing");
    }
    private static byte[] payload(String session, boolean sega, boolean steam, int site, String invite) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(-1); out.writeUTF("com.threerings.projectx.data.ProjectXCredentials");
        out.writeShort(-2); out.writeUTF("com.threerings.util.Name"); string(out, "Alice");
        string(out, "0123456789abcdef0123456789abcdef"); string(out, null); string(out, "en"); string(out, session);
        out.writeBoolean(false); out.writeBoolean(sega); out.writeBoolean(steam); out.writeInt(site);
        string(out, invite); string(out, "fixture-region"); return bytes.toByteArray();
    }
    private static void string(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null); if (value != null) out.writeUTF(value);
    }
    private static void rejected(byte[] payload) throws Exception {
        try { HandshakeCodec.credentials(payload); throw new AssertionError("Unsupported credential accepted"); }
        catch (IOException expected) { }
    }
}
