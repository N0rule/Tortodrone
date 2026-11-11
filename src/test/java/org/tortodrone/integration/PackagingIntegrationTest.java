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
            var roots=new HashSet<String>();
            for(var entry:Collections.list(zip.entries())) {
                String name=entry.getName();
                check(names.add(name),"No duplicate ZIP entries: "+name);
                roots.add(name.split("/",2)[0]);
                check(!name.startsWith("/")&&!name.contains("\\")&&!name.contains(":"),"Portable entry path");
                Path target=root.resolve(name).normalize();
                check(target.startsWith(root),"ZIP entry stays inside extraction root");
                check(!name.startsWith("data/")&&!name.startsWith("content-updates/")
                    &&!name.startsWith("webui/assets/game/icons/")&&!name.contains(".client-artwork-")
                    &&!name.contains(".artwork-publish-")&&!name.endsWith(".skipped")
                    &&!name.contains("/keys/")&&!name.endsWith("private.txt")&&!name.endsWith("tokens.json")
                    &&!name.matches(".*\\.(?:db.*|sqlite.*|key|pk8|pem|log|pcap.*|hprof|bundle)$"),"No runtime/artwork/secret entry: "+name);
                if(entry.isDirectory()){Files.createDirectories(target);continue;}
                Files.createDirectories(target.getParent());
                try(var input=zip.getInputStream(entry)){Files.copy(input,target);}
            }
            check(roots.equals(Set.of("webui","plugins","docs","content","config","VERSION","tortodrone.jar","README.md")),"Exactly the eight requested release root entries: "+roots);
            for(String name:List.of("tortodrone.jar","VERSION","README.md","docs/LICENSE","docs/NOTICE","docs/CONTRIBUTING.md",
                    "docs/THIRD-PARTY.md","docs/licenses/apache-2.0.txt","docs/licenses/jline.txt","webui/index.html",
                    "webui/assets/game/manifest.json","config/server.yml","content/items/weapons.yml"))
                check(names.contains(name),"Required release file: "+name);
            for(String name:List.of("LICENSE","NOTICE","CONTRIBUTING.md"))
                check((name.equals("CONTRIBUTING.md")?Files.readString(Path.of(name)).replace("](docs/","]("):Files.readString(Path.of(name)))
                    .replace("\r\n","\n").equals(Files.readString(root.resolve("docs/"+name)).replace("\r\n","\n")),"Relocated license/attribution/contribution text is preserved: "+name);
            String readme=Files.readString(root.resolve("README.md"));
            check(readme.contains("](docs/LICENSE)")&&readme.contains("](docs/NOTICE)")&&readme.contains("](docs/THIRD-PARTY.md)"),"Release README links to relocated legal notices");
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
