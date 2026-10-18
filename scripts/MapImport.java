import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Source-checkout native importer; the installed game is read-only through StockTools. */
public final class MapImport {
    public static void main(String[] args)throws Exception {
        if(args.length!=6)throw new IllegalArgumentException("Usage: java scripts/MapImport.java game-directory scene.dat deployment map_name zone-id native-zone-name");
        Path scratch=Files.createTempDirectory("tortodrone-native-import-");
        try {
            Path prepared=scratch.resolve("prepared");String java=Path.of(System.getProperty("java.home"),"bin/java").toString();
            run(List.of(java,"scripts/StockTools.java","scene",args[0],args[1],prepared.toString(),args[4],args[5],"--content"));
            run(List.of(java,"-cp","build/tortodrone.jar","org.tortodrone.resources.MapImport",prepared.toString(),args[2],args[3],args[0]));
        }finally{try(var paths=Files.walk(scratch)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    private static void run(List<String> command)throws Exception {if(new ProcessBuilder(command).inheritIO().start().waitFor()!=0)throw new IOException("Native map preparation failed; no map published");}
}
