package org.tortodrone.resources;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.config.ServerSettings;

/** Reviewable migration candidate only. Never changes source content, config, keys or SQLite. */
public final class ContentMigration {
    private ContentMigration() { }
    public static void main(String[] args)throws IOException {
        if(args.length<2||args.length>3)throw new IllegalArgumentException("Usage: ContentMigration /old-deployment /fresh-candidate [/read-only-game]");
        migrate(Path.of(args[0]),Path.of(args[1]),args.length==3?Path.of(args[2]):null);
        System.out.println("Validated unified candidate. Review config/, content/ and migration-report.txt; install while stopped, retaining the original data/ directory.");
    }
    public static void migrate(Path deployment,Path candidate,Path game)throws IOException {
        Path root=deployment.toAbsolutePath().normalize(),target=candidate.toAbsolutePath().normalize();ContentFiles.safe(root);ContentFiles.safe(target);
        if(target.startsWith(root)||root.startsWith(target))throw new IOException("Migration candidate must be separate from the source deployment");
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("Migration destination already exists; nothing replaced");
        Path lockFile=root.resolve("data/server.lock");ContentFiles.safe(lockFile);
        try(var channel=Files.exists(lockFile,LinkOption.NOFOLLOW_LINKS)?FileChannel.open(lockFile,StandardOpenOption.WRITE):null;FileLock lock=acquire(channel)) {
            if(Files.exists(root.resolve("content/maps")))throw new IOException("Source already has unified content; mixed layouts require manual conflict review");
            var settings=ServerSettings.read(ServerSettings.defaultPath(root));var content=MigrationPackLoader.load(root,settings);
            // All enabled resources are converted, including Town and pet graphs. Disabled custom
            // packs are explicitly reported so the candidate cannot silently discard them.
            Files.createDirectories(target.getParent());Path staging=Files.createTempDirectory(target.getParent(),".content-migration-");
            Path prepared=staging.resolve("candidate");
            try {
                new UnifiedExport(game).export(content,prepared,settings);
                Path custom=root.resolve("content/custom/packs");
                if(Files.exists(custom))try(var dirs=Files.list(custom)) {for(Path path:dirs.sorted().toList())if(Files.isDirectory(path)&&content.packs().stream().noneMatch(p->p.id().equals(path.getFileName().toString())))throw new IOException("Unselected custom pack requires explicit migration/conflict review: "+path);}
                ContentManager.load(prepared);Files.move(prepared,target,StandardCopyOption.ATOMIC_MOVE);
            } finally {delete(staging);}
        }
    }
    private static FileLock acquire(FileChannel channel)throws IOException {
        if(channel==null)return null;try{var lock=channel.tryLock();if(lock!=null)return lock;}catch(OverlappingFileLockException held){ }
        throw new IOException("Server is running; stop it before content migration");
    }
    static void delete(Path root)throws IOException {if(Files.exists(root))try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
}
