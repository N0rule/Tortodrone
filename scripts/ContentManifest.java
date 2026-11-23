import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Maintainer tool for {@code content/_internal/default-source.properties}.
 *
 * <p>{@code source.*} pins the reviewed content snapshot and is verified on every
 * preparation. {@code owned.*} lists every digest this project has published for a path,
 * so a deployment can replace or retire its own unmodified copy while a local edit still
 * surfaces as a conflict. Refresh therefore updates {@code source.*}, appends new
 * {@code owned.*} history and never drops a removal pin.
 */
public final class ContentManifest {
    private static final String MANIFEST="content/_internal/default-source.properties";

    public static void main(String[] args)throws Exception {
        if(args.length>0)throw new IllegalArgumentException("Usage: ContentManifest");
        Path project=Path.of("").toAbsolutePath().normalize();
        Path manifest=project.resolve(MANIFEST);
        Properties existing=BuildTools.manifest(manifest);
        if(existing==null)throw new IOException("Missing source manifest; create one with format=1 first: "+manifest);
        TreeMap<String,String> owned=entries(existing,"owned."),source=entries(existing,"source.");
        Path content=project.resolve("content");
        var verified=new TreeSet<String>();
        int addedSource=0,changedSource=0,addedHistory=0,addedOwnedOnly=0;
        try(var paths=Files.walk(content,32)) {
            for(Path file:paths.sorted().toList()) {
                if(Files.isSymbolicLink(file))throw new IOException("Content contains a symlink: "+file);
                if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))continue;
                String relative=content.relativize(file).toString().replace('\\','/');
                if(relative.equals("_internal/default-source.properties"))continue;
                String digest=BuildTools.fileDigest(file);
                if(source.containsKey(relative)) {
                    if(!digest.equals(source.put(relative,digest)))changedSource++;
                    if(appendHistory(owned,relative,digest))addedHistory++;
                    verified.add(relative);
                } else if(owned.containsKey(relative)) {
                    // This path was published under owned.* only; keep that reviewed policy.
                    addedOwnedOnly++;
                } else {
                    // A content file this manifest never described: register it now.
                    source.put(relative,digest);
                    appendHistory(owned,relative,digest);
                    verified.add(relative);
                    addedSource++;
                }
            }
        }
        int retired=0;
        // A path that left the tree is no longer verified, but its owned.* pins stay so an
        // existing deployment can still retire the stale copy during preparation.
        for(var iterator=source.keySet().iterator();iterator.hasNext();) {
            if(!verified.contains(iterator.next())) {iterator.remove();retired++;}
        }
        var lines=new ArrayList<String>(1+owned.size()+source.size());
        lines.add("format=1");
        for(Map.Entry<String,String> entry:owned.entrySet())lines.add("owned."+entry.getKey()+"="+entry.getValue());
        for(Map.Entry<String,String> entry:source.entrySet())lines.add("source."+entry.getKey()+"="+entry.getValue());
        Files.write(manifest,lines,StandardCharsets.UTF_8);
        System.out.printf("Refreshed %s: %d source entries (%d added, %d changed, %d retired), %d owned paths, "
            + "%d new digests, %d owned-only paths left unchanged, %d bytes%n",
            manifest,source.size(),addedSource,changedSource,retired,owned.size(),addedHistory,addedOwnedOnly,
            Files.size(manifest));
    }

    private static TreeMap<String,String> entries(Properties values,String prefix) {
        var result=new TreeMap<String,String>();
        for(String key:values.stringPropertyNames())
            if(key.startsWith(prefix))result.put(key.substring(prefix.length()),values.getProperty(key));
        return result;
    }

    /** Appends a digest once; returns true when the published history grew. */
    private static boolean appendHistory(TreeMap<String,String> owned,String path,String digest) {
        String known=owned.get(path);
        if(known==null) {owned.put(path,digest);return true;}
        for(String published:known.split(","))if(published.equals(digest))return false;
        owned.put(path,known+","+digest);
        return true;
    }
}
