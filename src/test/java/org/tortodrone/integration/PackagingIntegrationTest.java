package org.tortodrone.integration;

import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.resources.Json;
import org.tortodrone.support.ContentFixture;
import static org.tortodrone.support.TestWire.check;

/** Inspect/extract the actual Gradle portable ZIP; no installed client or running server. */
public final class PackagingIntegrationTest {
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected portable ZIP path");
        Path root=Files.createTempDirectory("tortodrone-package-");
        try(var zip=new ZipFile(args[0])) {
            var names=new HashSet<String>();
            for(var entry:Collections.list(zip.entries())) {
                String name=entry.getName();
                check(names.add(name),"No duplicate ZIP entries: "+name);
                check(!name.startsWith("/")&&!name.contains("\\")&&!name.contains(":"),"Portable entry path");
                Path target=root.resolve(name).normalize();
                check(target.startsWith(root),"ZIP entry stays inside extraction root");
                check(!name.startsWith("data/")&&!name.startsWith("content-updates/")
                    &&!name.startsWith("webui/assets/game/icons/")&&!name.contains(".client-artwork-")
                    &&!name.contains(".artwork-publish-")&&!name.endsWith(".skipped")
                    &&!name.matches(".*\\.(?:db.*|sqlite.*|key|pk8|pem|log|pcap.*|hprof)$"),"No runtime/artwork/secret entry: "+name);
                if(entry.isDirectory()){Files.createDirectories(target);continue;}
                Files.createDirectories(target.getParent());
                try(var input=zip.getInputStream(entry)){Files.copy(input,target);}
            }
            for(String name:List.of("tortodrone.jar","VERSION","README.md","LICENSE","NOTICE","CONTRIBUTING.md",
                    "THIRD-PARTY.md","licenses/apache-2.0.txt","licenses/jline.txt","webui/index.html",
                    "webui/assets/game/manifest.json","config/server.yml","content/items/weapons.yml"))
                check(names.contains(name),"Required release file: "+name);
            for(String directory:List.of("config","content","plugins"))try(var files=Files.walk(Path.of(directory))) {
                for(var file:files.filter(Files::isRegularFile).toList())
                    check(Files.mismatch(file,root.resolve(file))==-1,"Packages source defaults, not deployment edits: "+file);
            }
            var manifest=(Map<?,?>)Json.parse(Files.readString(root.resolve("webui/assets/game/manifest.json")));
            check(((Map<?,?>)manifest.get("items")).isEmpty(),"Portable release has source-owned fallback artwork");
            check(Files.mismatch(Path.of("src/webui/assets/game/manifest.json"),root.resolve("webui/assets/game/manifest.json"))==-1,"Fallback manifest matches source");
            ContentManager.load(root);
            System.out.println("PASS: portable ZIP contains complete validated source defaults/licenses, no duplicate entries, runtime data or client artwork");
        } finally { ContentFixture.delete(root); }
    }
}
