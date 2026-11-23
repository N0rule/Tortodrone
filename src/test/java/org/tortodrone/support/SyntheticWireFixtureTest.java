package org.tortodrone.support;

import static org.tortodrone.support.TestWire.check;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** The synthetic fixture container round-trips, rejects unsafe input and detects drift. */
public final class SyntheticWireFixtureTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("tortodrone-wire-fixture-");
        try {
            var frames = List.of(new byte[] {0, 0, 0, 4, 1, 2, 3, 4}, new byte[] {(byte) 0xff, 0, 0, 0, 1});
            var fixture = new WireFixture.Fixture("synthetic.roundtrip", frames);
            Path file = root.resolve("nested/synthetic.roundtrip.wire");
            WireFixture.write(file, fixture);
            var loaded = WireFixture.read(file);
            check(loaded.name().equals(fixture.name()), "fixture name survives the container");
            check(loaded.frames().size() == 2, "both frames survive the container");
            check(
                    java.util.Arrays.equals(loaded.stream(), fixture.stream()),
                    "concatenated frames are the observable stream");
            check(
                    java.util.Arrays.equals(loaded.frames().get(1), frames.get(1)),
                    "unsigned frame bytes survive base64");
            WireFixture.requireSame("round trip", fixture, loaded);

            var sameStream = new WireFixture.Fixture("synthetic.split", List.of(loaded.stream()));
            check(
                    java.util.Arrays.equals(sameStream.stream(), fixture.stream()),
                    "stream equality is independent of frame split");
            try {
                WireFixture.requireSame("split", fixture, sameStream);
                throw new AssertionError("Different frame boundaries accepted");
            } catch (AssertionError expected) {
                check(expected.getMessage().contains("frame count"), "frame-count drift is reported");
            }

            var changed = new WireFixture.Fixture(
                    "synthetic.roundtrip", List.of(frames.get(0), new byte[] {(byte) 0xff, 0, 0, 0, 2}));
            try {
                WireFixture.requireSame("drift", fixture, changed);
                throw new AssertionError("Changed frame accepted");
            } catch (AssertionError expected) {
                check(
                        expected.getMessage().contains("frame 1 differs at byte 4"),
                        "frame drift names the first differing byte: " + expected.getMessage());
            }

            expectRefused(
                    root.resolve("bad-header.wire"),
                    "wire-fixture 1\nname synthetic.x\nsource synthetic\nframe AA==\n",
                    "expected header");
            expectRefused(
                    root.resolve("capture.wire"),
                    WireFixture.HEADER + "\nname synthetic.x\nsource capture\nframe AA==\n",
                    "source synthetic");
            expectRefused(
                    root.resolve("no-frames.wire"),
                    WireFixture.HEADER + "\nname synthetic.x\nsource synthetic\n",
                    "missing frames");
            expectRefused(
                    root.resolve("unknown.wire"),
                    WireFixture.HEADER + "\nname synthetic.x\nsource synthetic\npayload AA==\n",
                    "unknown fixture directive");
            expectRefused(
                    root.resolve("bad-base64.wire"),
                    WireFixture.HEADER + "\nname synthetic.x\nsource synthetic\nframe !!!\n",
                    "invalid frame base64");
            expectRefused(
                    root.resolve("bad-name.wire"),
                    WireFixture.HEADER + "\nname Synthetic X\nsource synthetic\nframe AA==\n",
                    "Fixture name");
            expectRefused(
                    root.resolve("empty-frame.wire"),
                    WireFixture.HEADER + "\nname synthetic.x\nsource synthetic\nframe \n",
                    "non-empty");
            System.out.println(
                    "PASS: synthetic wire fixtures round-trip arbitrary bytes, keep stream equality, and refuse captures, unknown directives and frame drift");
        } finally {
            try (var paths = Files.walk(root)) {
                for (var path :
                        paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void expectRefused(Path file, String text, String message) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        try {
            WireFixture.read(file);
            throw new AssertionError("Invalid fixture accepted: " + file.getFileName());
        } catch (IOException expected) {
            check(
                    expected.getMessage().contains(message),
                    "refusal mentions '" + message + "': " + expected.getMessage());
        }
    }
}
