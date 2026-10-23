package org.tortodrone.web;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.resources.*;

/** Only news.yml can be replaced. Validate a disposable candidate before atomic publication. */
final class NewsEditor {
    private final Path root;
    NewsEditor(Path root){this.root=root;}
    private String revision()throws IOException{return Files.exists(root.resolve("news.yml"))?ContentFiles.digest(root.resolve("news.yml")):"absent";}
    synchronized Object read()throws IOException {
        var catalog=NewsYaml.load(root);Path file=root.resolve("news.yml");
        return Map.of("catalog",catalog,"revision",revision(),"yaml",Files.exists(file)?Files.readString(file):"enabled: false\npacks: []\n");
    }
    synchronized Object save(String yaml,String expected)throws IOException {
        if(!expected.equals(revision()))throw new IOException("News changed; refresh before saving");
        ContentFiles.safe(root);Files.createDirectories(root);Path stage=Files.createTempDirectory(root,".validate-");
        try {
            long size=0;int count=0;
            try(var walk=Files.walk(root,8)){
                for(var it=walk.iterator();it.hasNext();){Path p=it.next();if(p.startsWith(stage)||Files.isDirectory(p))continue;
                    String name=p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if(!name.endsWith(".png")&&!name.endsWith(".jpg")&&!name.endsWith(".jpeg"))continue;
                    ContentFiles.regular(p);if(++count>128||(size+=Files.size(p))>32L*1024*1024)throw new IOException("News validation limit exceeded");
                    Path target=stage.resolve(root.relativize(p));Files.createDirectories(target.getParent());Files.copy(p,target);
                }
            }
            Path candidate=stage.resolve("news.yml");Files.writeString(candidate,yaml);NewsYaml.load(stage);
            if(!expected.equals(revision()))throw new IOException("News changed; refresh before saving");
            Files.move(candidate,root.resolve("news.yml"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{try(var walk=Files.walk(stage)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
        return read();
    }
}
