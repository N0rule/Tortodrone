import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.tortodrone.core.Yaml;

/**
 * Read-only duplication audit for {@code content/}.
 *
 * <p>{@code check} is the fast build guard: it fails when an exact duplicate text file is
 * not listed in {@code scripts/content/duplicate-allowlist.txt}. {@code report} adds the
 * advisory detail used to plan de-duplication: repeated lines per area, large identical
 * canonical YAML subtrees, and ignored small or binary duplicate groups. Nothing is rewritten.
 */
public final class ContentAudit {
    /** Smaller identical files are placeholders such as an empty mapping; only report them. */
    private static final long MIN_EXACT_BYTES=512;
    /** A YAML subtree must carry at least this many nodes before it is worth sharing. */
    private static final int MIN_STRUCTURAL_NODES=40;
    private static final int MAX_STRUCTURAL_GROUPS=40;
    private static final Set<String> TEXT_EXTENSIONS=
        Set.of("yml","yaml","json","txt","properties","tsv","csv","md");
    private static final Path ALLOWLIST=Path.of("scripts/content/duplicate-allowlist.txt");

    public static void main(String[] args)throws Exception {
        String mode=args.length==0?"check":args[0];
        if(!mode.equals("check")&&!mode.equals("report"))
            throw new IllegalArgumentException("Usage: ContentAudit [check|report]");
        Path project=Path.of("").toAbsolutePath().normalize();
        Path content=project.resolve("content");
        List<Path> files=files(content);
        boolean failed=exactDuplicates(content,files,mode.equals("report"));
        if(mode.equals("report")) {
            lineMetrics(content,files);
            structuralDuplicates(content,files);
        }
        if(failed)System.exit(1);
    }

    private static List<Path> files(Path content)throws IOException {
        var files=new ArrayList<Path>();
        try(var paths=Files.walk(content,32)) {
            for(Path file:paths.sorted().toList()) {
                if(Files.isSymbolicLink(file))throw new IOException("Content contains a symlink: "+file);
                if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))files.add(file);
            }
        }
        return files;
    }

    // ---------------------------------------------------------------- exact duplicates

    private record Group(String digest,List<Path> files,long bytes,long lines) { }

    private static boolean exactDuplicates(Path content,List<Path> files,boolean verbose)throws IOException {
        Map<String,List<Path>> byDigest=new LinkedHashMap<>();
        for(Path file:files)byDigest.computeIfAbsent(BuildTools.fileDigest(file),key->new ArrayList<>()).add(file);
        var groups=new ArrayList<Group>();
        for(Map.Entry<String,List<Path>> entry:byDigest.entrySet()) {
            if(entry.getValue().size()<2)continue;
            Path first=entry.getValue().get(0);
            groups.add(new Group(entry.getKey(),List.copyOf(entry.getValue()),Files.size(first),
                isText(first)?countLines(first):0));
        }
        groups.sort(Comparator.comparing(group->relative(content,group.files().get(0))));
        Set<String> allowed=allowlist();
        int errors=0,accepted=0,ignored=0;
        for(Group group:groups) {
            boolean text=group.files().stream().allMatch(ContentAudit::isText);
            var paths=group.files().stream().map(file->relative(content,file)).toList();
            boolean candidate=text&&group.bytes()>=MIN_EXACT_BYTES;
            boolean acceptedHere=candidate&&paths.stream().allMatch(allowed::contains);
            if(candidate&&!acceptedHere) {
                errors++;
                System.out.printf(Locale.ROOT,"ERROR exact duplicate: %d files, %,d bytes, %,d lines each%n",
                    group.files().size(),group.bytes(),group.lines());
                for(String path:paths)System.out.println("  "+path+(allowed.contains(path)?"  [allowlisted]":""));
            } else if(candidate) {
                accepted++;
                if(verbose)System.out.printf(Locale.ROOT,"ALLOWED exact duplicate: %d files, %,d bytes, %,d lines each%n  %s%n",
                    group.files().size(),group.bytes(),group.lines(),String.join("\n  ",paths));
            } else {
                ignored++;
                if(verbose)System.out.printf(Locale.ROOT,"ignored duplicate: %d files, %,d bytes, %,d lines each (small or binary)%n  %s%n",
                    group.files().size(),group.bytes(),group.lines(),String.join("\n  ",paths));
            }
        }
        var stale=new TreeSet<>(allowed);
        for(Group group:groups)for(Path file:group.files())stale.remove(relative(content,file));
        if(!stale.isEmpty()) {
            System.out.println("WARNING stale allowlist entries (no longer duplicated):");
            for(String path:stale)System.out.println("  "+path);
        }
        if(errors==0)
            System.out.printf(Locale.ROOT,"Content duplication check passed: %d accepted, %d ignored, %d exact duplicate groups%n",
                accepted,ignored,groups.size());
        else
            System.out.printf(Locale.ROOT,"Content duplication check failed: %d unlisted exact duplicate groups; %d accepted, %d ignored%n",
                errors,accepted,ignored);
        return errors>0;
    }

    private static Set<String> allowlist()throws IOException {
        if(Files.notExists(ALLOWLIST,LinkOption.NOFOLLOW_LINKS))return Set.of();
        var paths=new TreeSet<String>();
        for(String line:Files.readAllLines(ALLOWLIST,StandardCharsets.UTF_8)) {
            int comment=line.indexOf('#');
            String path=(comment>=0?line.substring(0,comment):line).trim().replace('\\','/');
            if(!path.isEmpty())paths.add(path);
        }
        return paths;
    }

    // ------------------------------------------------------------------ advisory report

    private static void lineMetrics(Path content,List<Path> files)throws IOException {
        var total=new TreeMap<String,long[]>();
        var frequency=new LinkedHashMap<String,Integer>();
        long allLines=0,allNonBlank=0;
        for(Path file:files) {
            if(!isText(file))continue;
            String area=relative(content,file).split("/",2)[0];
            long[] counts=total.computeIfAbsent(area,key->new long[3]);
            try(var lines=Files.lines(file,StandardCharsets.UTF_8)) {
                for(String line:(Iterable<String>)lines::iterator) {
                    counts[0]++;
                    allLines++;
                    String trimmed=line.trim();
                    if(trimmed.isEmpty())continue;
                    counts[1]++;
                    allNonBlank++;
                    Integer seen=frequency.get(trimmed);
                    if(seen==null){frequency.put(trimmed,1);counts[2]++;}
                    else frequency.put(trimmed,seen+1);
                }
            }
        }
        System.out.println();
        System.out.println("REPEATED LINES BY AREA (unique = distinct trimmed non-blank lines)");
        System.out.printf(Locale.ROOT,"  %-20s %10s %10s %8s%n","area","lines","unique","repeated");
        long totalUnique=0;
        for(Map.Entry<String,long[]> entry:total.entrySet()) {
            long[] counts=entry.getValue();
            long repeated=counts[1]-counts[2];
            totalUnique+=counts[2];
            System.out.printf(Locale.ROOT,"  %-20s %,10d %,10d %7.1f%%%n",entry.getKey(),counts[1],counts[2],
                counts[1]==0?0:repeated*100.0/counts[1]);
        }
        System.out.printf(Locale.ROOT,"  %-20s %,10d %,10d %7.1f%%%n","all content",allLines,totalUnique,
            allNonBlank==0?0:(allNonBlank-totalUnique)*100.0/allNonBlank);
        var repeated=frequency.entrySet().stream().filter(entry->entry.getValue()>=20&&entry.getKey().length()>=8)
            .sorted(Map.Entry.<String,Integer>comparingByValue().reversed()).limit(10).toList();
        if(!repeated.isEmpty()) {
            System.out.println("  most repeated lines:");
            for(Map.Entry<String,Integer> entry:repeated)
                System.out.printf(Locale.ROOT,"    %,6d  %s%n",entry.getValue(),entry.getKey());
        }
    }

    private record Occ(String file,List<String> path,int nodes) { }
    private record Node(String digest,int nodes) { }

    private static void structuralDuplicates(Path content,List<Path> files)throws IOException {
        Map<String,List<Occ>> groups=new LinkedHashMap<>();
        int parsed=0,skipped=0;
        for(Path file:files) {
            String path=relative(content,file);
            if(!isYaml(file)||Files.size(file)>8_000_000)continue;
            Map<String,Object> document;
            try {document=Yaml.read(file);}
            catch(IOException invalid) {skipped++;continue;}
            collect(document,path,new ArrayDeque<>(),groups);
            parsed++;
        }
        var reported=new ArrayList<Map.Entry<String,List<Occ>>>();
        for(Map.Entry<String,List<Occ>> entry:groups.entrySet()) {
            int distinct=(int)entry.getValue().stream().map(Occ::file).distinct().count();
            if(distinct>=2)reported.add(entry);
        }
        reported.sort(Comparator.comparingLong((Map.Entry<String,List<Occ>> entry)->{
            var entries=entry.getValue();
            int distinct=(int)entries.stream().map(Occ::file).distinct().count();
            return -(long)entries.get(0).nodes()*(distinct-1);
        }));
        var kept=new ArrayList<Occ>();
        var surviving=new ArrayList<Map.Entry<String,List<Occ>>>();
        for(Map.Entry<String,List<Occ>> entry:reported) {
            boolean covered=entry.getValue().stream().allMatch(occ->kept.stream()
                .anyMatch(parent->parent.file().equals(occ.file())&&isAncestor(parent.path(),occ.path())));
            if(covered)continue;
            surviving.add(entry);
            kept.addAll(entry.getValue());
        }
        System.out.println();
        System.out.printf(Locale.ROOT,"STRUCTURAL DUPLICATES (canonical YAML subtree, >= %d nodes, 2+ files; %d patterns)%n",
            MIN_STRUCTURAL_NODES,surviving.size());
        if(parsed==0)System.out.println("  no YAML documents parsed");
        for(int index=0;index<Math.min(surviving.size(),MAX_STRUCTURAL_GROUPS);index++) {
            var entry=surviving.get(index);
            var entries=entry.getValue();
            int distinct=(int)entries.stream().map(Occ::file).distinct().count();
            int nodes=entries.get(0).nodes();
            System.out.printf(Locale.ROOT,"  %2d. %s (%,d nodes, %d files, ~%,d redundant nodes)%n",
                index+1,String.join(".",entries.get(0).path()),nodes,distinct,(long)nodes*(distinct-1));
            var shown=new TreeSet<String>();
            for(Occ occ:entries)shown.add(occ.file());
            var listed=shown.stream().limit(8).toList();
            for(String file:listed)System.out.println("      "+file);
            if(shown.size()>listed.size())System.out.println("      ... and "+(shown.size()-listed.size())+" more files");
        }
        if(surviving.size()>MAX_STRUCTURAL_GROUPS)
            System.out.println("  ... "+(surviving.size()-MAX_STRUCTURAL_GROUPS)+" more patterns; raise the cap to inspect them");
        if(skipped>0)System.out.println("  skipped "+skipped+" unparseable YAML documents");
        long largeJson=0;
        for(Path file:files)if(isJson(file))largeJson+=Files.size(file);
        System.out.printf(Locale.ROOT,"  JSON inputs are excluded from structural analysis: %,d bytes%n",largeJson);
    }

    private static Node collect(Object value,String file,Deque<String> path,Map<String,List<Occ>> groups) {
        var canonical=new StringBuilder();
        int nodes=1;
        if(value instanceof Map<?,?> map) {
            var keys=new TreeSet<String>();
            for(Object key:map.keySet())keys.add((String)key);
            for(String key:keys) {
                path.addLast(key);
                Node child=collect(map.get(key),file,path,groups);
                path.removeLast();
                canonical.append(key.length()).append(':').append(key).append('=').append(child.digest()).append(';');
                nodes+=child.nodes();
            }
        } else if(value instanceof List<?> list) {
            for(Object entry:list) {
                Node child=collect(entry,file,path,groups);
                canonical.append(child.digest()).append(',');
                nodes+=child.nodes();
            }
        } else {
            scalar(canonical,value);
        }
        String digest=digest(canonical.toString());
        if(!path.isEmpty()&&nodes>=MIN_STRUCTURAL_NODES)
            groups.computeIfAbsent(digest,key->new ArrayList<>()).add(new Occ(file,List.copyOf(path),nodes));
        return new Node(digest,nodes);
    }

    private static void scalar(StringBuilder out,Object value) {
        if(value instanceof Number number)
            out.append('n').append(new BigDecimal(number.toString()).stripTrailingZeros().toPlainString());
        else if(value instanceof Boolean flag)
            out.append(flag?'T':'F');
        else if(value==null)
            out.append('z');
        else
            out.append('s').append(value.toString().length()).append(':').append(value);
    }

    private static boolean isAncestor(List<String> parent,List<String> child) {
        if(parent.size()>=child.size())return false;
        for(int index=0;index<parent.size();index++)
            if(!parent.get(index).equals(child.get(index)))return false;
        return true;
    }

    // --------------------------------------------------------------------------- helpers

    private static String relative(Path content,Path file) {
        return content.relativize(file).toString().replace('\\','/');
    }

    private static String extension(Path file) {
        String name=file.getFileName().toString();
        int dot=name.lastIndexOf('.');
        return dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);
    }

    private static boolean isText(Path file){return TEXT_EXTENSIONS.contains(extension(file));}
    private static boolean isYaml(Path file){String extension=extension(file);return extension.equals("yml")||extension.equals("yaml");}
    private static boolean isJson(Path file){return extension(file).equals("json");}

    private static long countLines(Path file)throws IOException {
        try(var lines=Files.lines(file,StandardCharsets.UTF_8)) {return lines.count();}
    }

    private static String digest(String canonical) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException impossible) {throw new AssertionError(impossible);}
    }
}
