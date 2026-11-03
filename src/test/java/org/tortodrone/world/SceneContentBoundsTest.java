package org.tortodrone.world;

import java.nio.file.*;
import java.util.*;
import static org.tortodrone.support.TestWire.check;

/** File boundary regression for original Chasm's content size, using disposable metadata. */
public final class SceneContentBoundsTest {
    public static void main(String[] args)throws Exception {
        Path folder=Files.createTempDirectory("tortodrone-scene-bounds-");
        try{
            Path file=folder.resolve("content.properties");
            Files.writeString(file,"format=1\nlayout-sha256=fixture\n#"+"x".repeat(4_900_000)+"\n");
            check(SceneContent.load(folder,"fixture").entries().isEmpty(),"valid metadata of original Chasm size fits the bounded reader");
            refuse(()->SceneContent.load(folder,"wrong-layout"));
            Files.write(file,new byte[8_000_001]);refuse(()->SceneContent.load(folder,"fixture"));
            Files.delete(file);Path target=folder.resolve("elsewhere.properties");Files.writeString(target,"format=1\nlayout-sha256=fixture\n");Files.createSymbolicLink(file,target);
            refuse(()->SceneContent.load(folder,"fixture"));
            var p=new Properties();p.setProperty("format","1");p.setProperty("layout-sha256","fixture");p.setProperty("actor-templates",String.join(",",java.util.stream.IntStream.range(0,129).mapToObj(i->"spawn_"+i).toList()));
            refuse(()->SceneContent.load(p,"fixture"));
        }finally{try(var paths=Files.walk(folder)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
        System.out.println("PASS: original-sized scene metadata, hard 8 MB cap, layout identity, symlink refusal and unchanged template-count guard (disposable metadata)");
    }
    private static void refuse(Operation work)throws Exception{try{work.run();throw new AssertionError("Unsafe scene metadata accepted");}catch(java.io.IOException expected){}}
    private interface Operation{void run()throws Exception;}
}
