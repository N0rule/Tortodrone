import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Source-checkout native importer; the installed game is read-only through StockTools. */
public final class MapImport {
    public static void main(String[] args)throws Exception {
        boolean replace=false;var rest=new ArrayList<String>();
        for(String arg:args)if(arg.equals("--replace"))replace=true;else rest.add(arg);
        if(rest.size()!=6&&rest.size()!=8)throw new IllegalArgumentException("Usage: java scripts/MapImport.java game-directory scene.dat deployment map_name zone-id native-zone-name [spawn-x spawn-y] [--replace]");
        Path scratch=Files.createTempDirectory("tortodrone-native-import-");
        try {
            Path prepared=scratch.resolve("prepared");String java=Path.of(System.getProperty("java.home"),"bin/java").toString();
            var scene = new ArrayList<>(List.of(java,"-cp","build/tortodrone.jar","scripts/StockTools.java","scene",rest.get(0),rest.get(1),prepared.toString(),rest.get(4),rest.get(5)));
            if(rest.size()==8){scene.add(rest.get(6));scene.add(rest.get(7));}
            scene.add("--content");run(scene);
            var install = new ArrayList<>(List.of(java,"-cp","build/tortodrone.jar","org.tortodrone.resources.MapImport",prepared.toString(),rest.get(2),rest.get(3),rest.get(0)));
            if(replace)install.add("--replace");run(install);
        }finally{try(var paths=Files.walk(scratch)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    private static void run(List<String> command)throws Exception {if(new ProcessBuilder(command).inheritIO().start().waitFor()!=0)throw new IOException("Native map preparation failed; no map published");}
}
