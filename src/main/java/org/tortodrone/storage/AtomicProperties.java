package org.tortodrone.storage;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Properties;

/** Publish a complete properties file only after its contents have been flushed. */
public final class AtomicProperties {
    private AtomicProperties() { }

    public static void save(Path file, Properties properties, String comment) throws IOException {
        Files.createDirectories(file.getParent());
        StringWriter text = new StringWriter();
        properties.store(text, comment);
        byte[] encoded = text.toString().getBytes(StandardCharsets.UTF_8);
        Path temporary = Files.createTempFile(file.getParent(), ".save-", ".tmp");
        try {
            if (Files.getFileStore(temporary).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(encoded);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            java.util.Arrays.fill(encoded, (byte) 0);
            Files.deleteIfExists(temporary);
        }
    }
}
