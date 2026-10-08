import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Names, sizes and hashes only; no file contents, asset extraction, or installation writes. */
public final class GameFileIndex {
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Usage: java scripts/compat/GameFileIndex.java installation fresh-output-directory");
        Path root=Path.of(args[0]).toRealPath(),out=Path.of(args[1]).toAbsolutePath().normalize();
        Path ancestor=out;while(!Files.exists(ancestor))ancestor=ancestor.getParent();
        if(ancestor.toRealPath().startsWith(root)||Files.exists(out))throw new IOException("Choose a fresh output directory outside the installation");
        Files.createDirectories(out);long[] counts={0,0};
        try(var files=Files.newBufferedWriter(out.resolve("files.tsv"),StandardOpenOption.CREATE_NEW);
                var entries=Files.newBufferedWriter(out.resolve("archive-entries.tsv"),StandardOpenOption.CREATE_NEW)) {
            files.write("path\tkind\tbytes\tsha256\tpurpose\n");entries.write("archive\tentry\tbytes\tcompressed-bytes\tcrc32\tpurpose\n");
            try(var paths=Files.walk(root)) {
                for(var path:paths.sorted().toList()) {
                    if(path.equals(root))continue;String relative=root.relativize(path).toString().replace(File.separatorChar,'/');
                    var attr=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                    if(attr.isDirectory())continue;
                    if(!attr.isRegularFile()) {files.write(clean(relative)+"\t"+(attr.isSymbolicLink()?"symlink":"special")+"\t0\t\tNot followed\n");continue;}
                    var digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(path)){byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;)digest.update(buffer,0,n);}
                    files.write(clean(relative)+"\tfile\t"+attr.size()+"\t"+HexFormat.of().formatHex(digest.digest())+"\t"+purpose(relative)+"\n");counts[0]++;
                    if(relative.endsWith(".jar")||relative.endsWith(".zip"))try(var zip=new ZipFile(path.toFile())) {
                        for(var entry:zip.stream().sorted(Comparator.comparing(ZipEntry::getName)).toList())if(!entry.isDirectory()) {
                            entries.write(clean(relative)+"\t"+clean(entry.getName())+"\t"+entry.getSize()+"\t"+entry.getCompressedSize()+"\t"+Long.toHexString(entry.getCrc())+"\t"+purpose(entry.getName())+"\n");counts[1]++;
                        }
                    }
                }
            }
        }
        System.out.println("PASS: indexed "+counts[0]+" files and "+counts[1]+" archive entries in "+out);
    }
    private static String clean(String text){return text.replace("\\","\\\\").replace("\t","\\t").replace("\n","\\n").replace("\r","\\r");}
    private static String purpose(String path) {
        String p=path.toLowerCase(Locale.ROOT);
        if(p.endsWith(".class"))return "Java class: protocol/data/client implementation";
        if(p.contains("config/"))return "Configuration: resolve with stock ConfigManager";
        if(p.contains("i18n/"))return "Localized message keys and display text";
        if(p.contains("item/"))return "Item model/icon/texture resource";
        if(p.contains("character/"))return "Character model/texture resource";
        if(p.contains("animation/"))return "Animation resource";
        if(p.endsWith(".jar")||p.endsWith(".zip"))return "Archive: see archive-entries.tsv";
        if(p.contains("ui/"))return "Interface/icon resource";
        if(p.contains("sound/")||p.endsWith(".ogg"))return "Audio resource";
        if(p.contains("world/"))return "World/scene resource";
        if(p.endsWith(".dat"))return "Binary configuration/resource: identify reader before decoding";
        if(p.endsWith(".properties"))return "Settings/message metadata";
        return "Unclassified: consult docs/game-data.md";
    }
}
