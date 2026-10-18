package org.tortodrone.resources;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.news.NewsCatalog;

/** Strict startup reader for content/shared/news/news.yml; an absent file leaves Uplink News disabled. */
public final class NewsYaml {
    private NewsYaml() { }
    public static NewsCatalog load(Path directory)throws IOException {
        Path file=directory.resolve("news.yml");
        if(Files.notExists(file,LinkOption.NOFOLLOW_LINKS))return NewsCatalog.disabled();
        var yaml=Yaml.read(file);
        Yaml.fields(yaml,file.toString(),"enabled","featured","spotlights");
        boolean enabled=yaml.containsKey("enabled")&&Yaml.flag(yaml.get("enabled"),file+": enabled");
        var featured=banner(yaml.get("featured"),directory,file,"featured");
        NewsCatalog.Banner left=null,right=null;
        if(yaml.containsKey("spotlights")) {
            var spotlights=Yaml.map(yaml.get("spotlights"),file+": spotlights");
            Yaml.fields(spotlights,file+": spotlights","left","right");
            left=banner(spotlights.get("left"),directory,file,"spotlights.left");
            right=banner(spotlights.get("right"),directory,file,"spotlights.right");
        }
        return new NewsCatalog(enabled,featured,left,right);
    }
    private static NewsCatalog.Banner banner(Object raw,Path directory,Path file,String where)throws IOException {
        if(raw==null)return null;
        var row=Yaml.map(raw,file+": "+where);
        Yaml.fields(row,file+": "+where,"image","tooltip","url");
        if(!row.containsKey("image"))throw new IOException(file+": "+where+" requires an image");
        String image=Yaml.text(row.get("image"),file+": "+where+" image");
        String lower=image.toLowerCase(Locale.ROOT);
        if(!(lower.endsWith(".png")||lower.endsWith(".jpg")||lower.endsWith(".jpeg")))throw new IOException(file+": "+where+" image must be a PNG or JPEG: "+image);
        Path path=ContentFiles.relative(directory,image);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException(file+": "+where+" image is missing: "+image);
        return new NewsCatalog.Banner(image,optional(row.get("tooltip"),file+": "+where+" tooltip"),optional(row.get("url"),file+": "+where+" url"));
    }
    private static String optional(Object value,String where)throws IOException {
        return value==null?"":Yaml.text(value,where);
    }
}
