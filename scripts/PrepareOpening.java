import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.resources.MapImport;
import org.tortodrone.world.TutorialRoute;

/** Prepare a separate opening deployment; never read/copy private runtime state. */
public final class PrepareOpening {
    public static void main(String[] args)throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("Usage: java -cp BASE/tortodrone.jar scripts/PrepareOpening.java GAME ARCHIVE BASE FRESH_OUTPUT");
        Path game=Path.of(args[0]).toRealPath(),archive=Path.of(args[1]).toRealPath(),base=Path.of(args[2]).toRealPath();
        Path requested=Path.of(args[3]).toAbsolutePath().normalize();
        Path output=requested.getParent().toRealPath().resolve(requested.getFileName());
        if(Files.exists(output,LinkOption.NOFOLLOW_LINKS))throw new IOException("Output already exists; nothing replaced");
        for(Path input:List.of(game,archive,base))if(output.startsWith(input)||input.startsWith(output))throw new IOException("Output must be separate from the client, archive and base deployment");
        if(!Files.isRegularFile(game.resolve("code/projectx-pcode.jar"))||!Files.isRegularFile(base.resolve("tortodrone.jar")))throw new IOException("Installed client and built base deployment required");
        var initial=fingerprint(base);var definitions=ContentManager.load(base);
        if(definitions.scenes().scenes().stream().anyMatch(s->s.id()==398||s.id()==217))throw new IOException("Base already contains opening scenes; use a fresh ordinary build");
        Path source=archive.resolve("reference/observations/scenes-cache-2026-10-22");
        TutorialRoute.sourceVersion(Files.readAllBytes(source.resolve("398")));
        TutorialRoute.campVersion(Files.readAllBytes(source.resolve("217")));
        Files.createDirectories(output.getParent());
        Path stage=Files.createTempDirectory(output.getParent(),".opening-candidate-");
        Path work=Files.createTempDirectory("tortodrone-opening-prepare-");
        try {
            Files.copy(base.resolve("tortodrone.jar"),stage.resolve("tortodrone.jar"));
            for(String name:List.of("content","config","webui","plugins"))if(Files.exists(base.resolve(name)))copy(base.resolve(name),stage.resolve(name));
            for(int scene:List.of(398,217)){
                Path raw=work.resolve(Integer.toString(scene));
                var command=List.of(java(),"-Dtortodrone.deployment="+base,Path.of("scripts/StockTools.java").toAbsolutePath().toString(),"scene",game.toString(),source.resolve(Integer.toString(scene)).toString(),raw.toString(),scene==398?"50332041":"16777221",scene==398?"%dungeon:m.tutorial_b":"%town:m.rescue_camp_state_1","--content");
                Path log=work.resolve(scene+".log");var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
                try {
                    if(!process.waitFor(240,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("Native export timed out for "+scene);
                    if(process.exitValue()!=0)throw new IOException("Native export failed for "+scene+":\n"+Files.readString(log));
                }finally{if(process.isAlive()){process.destroy();if(!process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);}}}
                MapImport.install(raw,stage,scene==398?"crash_site":"rescue_camp",game);
                System.out.println("Prepared original scene "+scene);
            }
            if(TutorialRoute.available(ContentManager.load(stage).scenes())==null)throw new IOException("Prepared opening route failed verification");
            if(!initial.equals(fingerprint(base)))throw new IOException("Base content changed while preparing; candidate not published");
            Files.move(stage,output);
            System.out.println("PASS: separate opening deployment prepared at "+output+"; no accounts, keys, preferences or runtime state copied");
            System.out.println("Camp-to-Haven missions are not completed by this preparation command.");
        }finally{delete(stage);delete(work);}
    }
    private static String java(){return Path.of(System.getProperty("java.home"),"bin/java").toString();}
    private static Map<String,String> fingerprint(Path base)throws Exception {
        var result=new TreeMap<String,String>();
        for(String name:List.of("tortodrone.jar","content","config","webui","plugins")){
            Path source=base.resolve(name);if(!Files.exists(source,LinkOption.NOFOLLOW_LINKS))continue;
            try(var paths=Files.walk(source)){for(var path:paths.sorted().toList()){
                if(Files.isSymbolicLink(path))throw new IOException("Symbolic input: "+path);
                if(Files.isDirectory(path))continue;if(!Files.isRegularFile(path)||result.size()>=100000)throw new IOException("Invalid or oversized deployment inputs");
                var digest=java.security.MessageDigest.getInstance("SHA-256");
                try(var in=new java.security.DigestInputStream(Files.newInputStream(path),digest)){in.transferTo(OutputStream.nullOutputStream());}
                result.put(base.relativize(path).toString(),HexFormat.of().formatHex(digest.digest()));
            }}
        }
        return result;
    }
    private static void copy(Path source,Path target)throws IOException {
        try(var paths=Files.walk(source)){for(var path:paths.sorted().toList()){
            if(Files.isSymbolicLink(path))throw new IOException("Symbolic links cannot enter the candidate: "+path);
            Path dest=target.resolve(source.relativize(path));
            if(Files.isDirectory(path))Files.createDirectories(dest);
            else if(Files.isRegularFile(path))Files.copy(path,dest);else throw new IOException("Non-regular input: "+path);
        }}
    }
    private static void delete(Path directory)throws IOException {
        if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return;
        try(var paths=Files.walk(directory)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
    }
}
