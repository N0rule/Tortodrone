package org.tortodrone.resources;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Bounded, symlink-free content inputs and deterministic file/tree fingerprints. */
public final class ContentFiles {
    private static final long MAX_BYTES=128L*1024*1024;
    private ContentFiles() { }
    /** Maintainer tool: fingerprint one file or a complete scene directory. */
    public static void main(String[] args) throws IOException {
        if(args.length!=1)throw new IllegalArgumentException("Usage: java -cp tortodrone.jar org.tortodrone.resources.ContentFiles /resource-path");
        System.out.println(digest(Path.of(args[0])));
    }
    public static Properties properties(Path file) throws IOException {
        regular(file);
        if(Files.size(file)>65536)throw new IOException("Content descriptor exceeds size limit: "+file);
        var p=new Properties() {
            @Override public synchronized Object put(Object key,Object value) {
                if(containsKey(key))throw new IllegalArgumentException("Duplicate content property: "+key);
                return super.put(key,value);
            }
        };
        try(var input=Files.newInputStream(file)){p.load(input);}
        catch(IllegalArgumentException invalid){throw new IOException("Invalid content descriptor: "+file+": "+invalid.getMessage(),invalid);}
        return p;
    }
    public static Path relative(Path root,String name) throws IOException {
        if(name.isBlank()||name.contains("\\")||name.contains(":"))throw new IOException("Invalid content path: "+name);
        Path relative;
        try {relative=Path.of(name);}catch(InvalidPathException invalid){throw new IOException("Invalid content path: "+name,invalid);}
        if(relative.isAbsolute()||!relative.normalize().equals(relative)||relative.startsWith(".."))
            throw new IOException("Content path escapes pack: "+name);
        Path file=root.resolve(relative);
        safe(file);
        return file;
    }
    public static void safe(Path path) throws IOException {
        Path absolute=path.toAbsolutePath().normalize(),current=absolute.getRoot();
        for(Path part:absolute){current=current.resolve(part);if(Files.isSymbolicLink(current))throw new IOException("Symlink in content path: "+current);}
    }
    public static void regular(Path file) throws IOException {
        safe(file);
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Missing content file: "+file);
    }
    public static String digest(Path path) throws IOException {
        safe(path);
        try {
            var hash=MessageDigest.getInstance("SHA-256");
            if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)) {
                long total=0;int entries=0;
                var files=new ArrayList<Path>();
                try(var walk=Files.walk(path,32)) {
                    var iterator=walk.iterator();
                    while(iterator.hasNext()) {
                        Path file=iterator.next();safe(file);
                        if(++entries>1024)throw new IOException("Content tree exceeds entry limit: "+path);
                        if(Files.isDirectory(file,LinkOption.NOFOLLOW_LINKS)) {
                            if(path.relativize(file).getNameCount()>=32)throw new IOException("Content tree exceeds depth limit: "+path);
                            continue;
                        }
                        regular(file);
                        if(files.size()>=512||(total+=Files.size(file))>MAX_BYTES)throw new IOException("Content tree exceeds limits: "+path);
                        files.add(file);
                    }
                }
                files.sort(Comparator.comparing(file->path.relativize(file).toString().replace('\\','/')));
                for(Path file:files) {
                    hash.update(path.relativize(file).toString().replace('\\','/').getBytes(StandardCharsets.UTF_8));
                    hash.update((byte)0);
                    hash.update(HexFormat.of().parseHex(digest(file)));
                }
            } else {
                regular(path);
                if(Files.size(path)>MAX_BYTES)throw new IOException("Content file exceeds size limit: "+path);
                try(var in=Files.newInputStream(path)){byte[] bytes=new byte[65536];for(int n;(n=in.read(bytes))!=-1;)hash.update(bytes,0,n);}
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
