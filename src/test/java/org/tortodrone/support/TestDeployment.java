package org.tortodrone.support;

import java.io.IOException;
import java.nio.file.*;

/** Copies a packaged server, frontend and external content into a disposable deployment. */
public final class TestDeployment {
    private TestDeployment() { }
    public static void copyServer(Path source,Path target)throws IOException {
        Files.copy(source,target);
        Path maps=source.toAbsolutePath().getParent().resolve("content");
        Path destination=target.toAbsolutePath().getParent().resolve("content");
        if(!Files.isDirectory(maps))throw new IOException("Build maps before packaged deployment tests");
        try(var paths=Files.walk(maps)) {
            for(var path:paths.toList()) {
                Path copy=destination.resolve(maps.relativize(path));
                if(Files.isDirectory(path))Files.createDirectories(copy);
                else Files.copy(path,copy);
            }
        }
        Path webui=source.toAbsolutePath().getParent().resolve("webui");
        if(Files.isDirectory(webui))try(var paths=Files.walk(webui)) {
            for(var path:paths.toList()){
                Path copy=target.toAbsolutePath().getParent().resolve("webui").resolve(webui.relativize(path));
                if(Files.isDirectory(path))Files.createDirectories(copy);else Files.copy(path,copy);
            }
        }
        Path config=target.toAbsolutePath().getParent().resolve("config");Files.createDirectories(config);
        Files.copy(source.toAbsolutePath().getParent().resolve("config/combat.yml"),config.resolve("combat.yml"));
    }
}
