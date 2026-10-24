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
        var images=new ArrayList<Object>();
        if(Files.exists(root))try(var paths=Files.walk(root,8)) {
            for(Path image:paths.sorted().toList()) {
                if(Files.isDirectory(image)||image.toString().contains(".validate-"))continue;
                String lower=image.getFileName().toString().toLowerCase(Locale.ROOT);
                if(!lower.endsWith(".png")&&!lower.endsWith(".jpg")&&!lower.endsWith(".jpeg"))continue;
                ContentFiles.regular(image);if(images.size()>=128)throw new IOException("News image limit exceeded");
                try{int[] d=NewsYaml.dimensions(image);images.add(Map.of("image",root.relativize(image).toString().replace('\\','/'),"width",d[0],"height",d[1],"bytes",Files.size(image)));}
                catch(IOException invalid){/* Invalid files cannot be selected for a validated pack. */}
            }
        }
        return Map.of("catalog",catalog,"revision",revision(),"yaml",Files.exists(file)?Files.readString(file):"enabled: false\npacks: []\n","images",images,
            "dimensions",Map.of("featured",List.of(570,300),"spotlight",List.of(280,190)));
    }
    synchronized Object saveCatalog(String json,String expected)throws IOException {
        Object raw=Json.parse(json);
        if(!(raw instanceof Map<?,?> map)||!map.keySet().equals(Set.of("enabled","packs")))throw new IOException("Invalid News document");
        var data=new LinkedHashMap<String,Object>();for(var e:map.entrySet())data.put(e.getKey().toString(),e.getValue());
        ContentFiles.safe(root);Files.createDirectories(root);
        Path temp=Files.createTempDirectory(root,".visual-");
        try{Path file=temp.resolve("news.yml");Yaml.write(file,data);return save(Files.readString(file),expected);}
        finally{try(var paths=Files.walk(temp)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    synchronized Object upload(String name,String encoded)throws IOException {
        if(!name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}\\.(?i:png|jpe?g)"))throw new IOException("Use a PNG or JPEG filename");
        byte[] bytes;
        try{bytes=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException invalid){throw new IOException("Invalid image encoding");}
        if(bytes.length==0||bytes.length>1024*1024)throw new IOException("Uploaded images must be at most 1 MiB");
        ContentFiles.safe(root);Files.createDirectories(root);
        Path stage=Files.createTempDirectory(root,".validate-");
        try {
            Path file=stage.resolve(name);Files.write(file,bytes,StandardOpenOption.CREATE_NEW);
            int[] d=NewsYaml.dimensions(file);if(d[0]>570||d[1]>300)throw new IOException("Image exceeds the 570 by 300 featured slot");
            String ext=name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT);
            String targetName="upload-"+ContentFiles.digest(file)+ext;
            Path target=ContentFiles.relative(root,targetName);
            if(!Files.exists(target)) {
                int count=0;long total=bytes.length;
                try(var paths=Files.walk(root,8)) {
                    for(Path image:paths.toList()) {
                        if(image.startsWith(stage)||Files.isDirectory(image))continue;
                        String lower=image.getFileName().toString().toLowerCase(Locale.ROOT);
                        if(!lower.endsWith(".png")&&!lower.endsWith(".jpg")&&!lower.endsWith(".jpeg"))continue;
                        ContentFiles.regular(image);count++;total+=Files.size(image);
                    }
                }
                if(count>=128||total>32L*1024*1024)throw new IOException("News image library is full");
                Files.move(file,target,StandardCopyOption.ATOMIC_MOVE);
            }
            else {ContentFiles.regular(target);if(!ContentFiles.digest(target).equals(ContentFiles.digest(file)))throw new IOException("Image changed");}
            return Map.of("image",targetName,"width",d[0],"height",d[1],"bytes",bytes.length);
        }finally{try(var paths=Files.walk(stage)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
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
