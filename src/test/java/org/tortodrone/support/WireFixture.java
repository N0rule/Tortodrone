package org.tortodrone.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Container for project-authored synthetic wire streams.
 *
 * <p>A fixture is a small UTF-8 text file with LF line endings:
 *
 * <pre>
 * tortodrone-wire-fixture 1
 * name synthetic.example
 * source synthetic
 * frame AAEC
 * frame CQg=
 * </pre>
 *
 * Each {@code frame} line is unwrapped base64 of one complete framed body, length prefix
 * included. Concatenating the decoded frames is the byte stream a reader observes. Fixture
 * bytes come from the server codec or an independent synthetic encoder; they are never client
 * captures, extracted assets or decompiled material.
 *
 * <p>This is deliberately only a container and comparison helper. Phase 2 and Phase 7 add the
 * actual golden streams once the native layouts have evidence; this class does not assert any
 * wire layout by itself.
 */
public final class WireFixture {
    public static final String HEADER = "tortodrone-wire-fixture 1";
    private static final String NAME_PATTERN = "[a-z][a-z0-9._-]{0,127}";

    private WireFixture() {}

    /** One named fixture with complete frames in delivery order. */
    public record Fixture(String name, List<byte[]> frames) {
        public Fixture {
            if (name == null || !name.matches(NAME_PATTERN))
                throw new IllegalArgumentException("Fixture name must match " + NAME_PATTERN);
            if (frames.isEmpty()) throw new IllegalArgumentException("A fixture needs at least one frame");
            var copy = new ArrayList<byte[]>(frames.size());
            for (byte[] frame : frames) {
                if (frame == null || frame.length == 0) throw new IllegalArgumentException("Frames must be non-empty");
                copy.add(frame.clone());
            }
            frames = List.copyOf(copy);
        }

        @Override
        public List<byte[]> frames() {
            var copy = new ArrayList<byte[]>(frames.size());
            for (byte[] frame : frames) copy.add(frame.clone());
            return List.copyOf(copy);
        }

        public byte[] stream() {
            int size = 0;
            for (byte[] frame : frames) size += frame.length;
            var stream = new byte[size];
            int at = 0;
            for (byte[] frame : frames) {
                System.arraycopy(frame, 0, stream, at, frame.length);
                at += frame.length;
            }
            return stream;
        }
    }

    public static Fixture read(Path path) throws IOException {
        var lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.getFirst().equals(HEADER))
            throw new IOException(path + ": expected header '" + HEADER + "'");
        String name = null;
        boolean synthetic = false;
        var frames = new ArrayList<byte[]>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) continue;
            int space = line.indexOf(' ');
            String directive = space < 0 ? line : line.substring(0, space),
                    value = space < 0 ? "" : line.substring(space + 1);
            switch (directive) {
                case "name" -> {
                    if (name != null) throw new IOException(path + ": duplicate name");
                    name = value;
                }
                case "source" -> {
                    if (!value.equals("synthetic"))
                        throw new IOException(path + ": fixtures must declare source synthetic");
                    synthetic = true;
                }
                case "frame" -> {
                    try {
                        frames.add(Base64.getDecoder().decode(value));
                    } catch (IllegalArgumentException invalid) {
                        throw new IOException(path + ": invalid frame base64 on line " + (i + 1), invalid);
                    }
                }
                default ->
                    throw new IOException(path + ": unknown fixture directive '" + directive + "' on line " + (i + 1));
            }
        }
        if (name == null) throw new IOException(path + ": missing name");
        if (!synthetic) throw new IOException(path + ": missing source synthetic");
        if (frames.isEmpty()) throw new IOException(path + ": missing frames");
        try {
            return new Fixture(name, frames);
        } catch (IllegalArgumentException invalid) {
            throw new IOException(path + ": " + invalid.getMessage(), invalid);
        }
    }

    public static void write(Path path, Fixture fixture) throws IOException {
        var text = new StringBuilder(HEADER)
                .append('\n')
                .append("name ")
                .append(fixture.name())
                .append('\n')
                .append("source synthetic\n");
        for (byte[] frame : fixture.frames())
            text.append("frame ")
                    .append(Base64.getEncoder().encodeToString(frame))
                    .append('\n');
        var parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(path, text.toString(), StandardCharsets.UTF_8);
    }

    /** Compare frame-by-frame; the first difference names the frame and byte offset. */
    public static void requireSame(String context, Fixture expected, Fixture actual) {
        if (expected.frames().size() != actual.frames().size())
            throw new AssertionError(
                    context + ": frame count " + actual.frames().size() + " != expected "
                            + expected.frames().size());
        for (int i = 0; i < expected.frames().size(); i++) {
            byte[] want = expected.frames().get(i), got = actual.frames().get(i);
            if (Arrays.equals(want, got)) continue;
            int at = 0;
            while (at < Math.min(want.length, got.length) && want[at] == got[at]) at++;
            throw new AssertionError(context + ": frame " + i + " differs at byte " + at + " (length " + got.length
                    + " != expected " + want.length + ")");
        }
    }
}
