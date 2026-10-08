package org.tortodrone.support;

import java.io.IOException;
import java.nio.file.*;

/** Copies a packaged server and its external maps into a disposable deployment. */
public final class TestDeployment {
    private TestDeployment() { }
    public static void copyServer(Path source,Path target)throws IOException {
        Files.copy(source,target);
        Path maps=source.toAbsolutePath().getParent().resolve("maps");
        Path destination=target.toAbsolutePath().getParent().resolve("maps");
        if(!Files.isDirectory(maps))throw new IOException("Build maps before packaged deployment tests");
        try(var paths=Files.walk(maps)) {
            for(var path:paths.toList()) {
                Path copy=destination.resolve(maps.relativize(path));
                if(Files.isDirectory(path))Files.createDirectories(copy);
                else Files.copy(path,copy);
            }
        }
    }
}
