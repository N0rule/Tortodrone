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
        Yaml.fields(yaml,file.toString(),"enabled","packs");
        boolean enabled=yaml.containsKey("enabled")&&Yaml.flag(yaml.get("enabled"),file+": enabled");
        var packs=new ArrayList<NewsCatalog.Pack>();
        if(yaml.containsKey("packs")) {
            var rows=Yaml.list(yaml.get("packs"),file+": packs");
            for(int index=0;index<rows.size();index++)packs.add(pack(rows.get(index),directory,file,"packs["+index+"]"));
        }
        // An enabled file with no pack would silently show nothing; refuse it instead of failing silently.
        if(enabled&&packs.isEmpty())throw new IOException(file+": enabled Uplink News requires at least one pack");
        return new NewsCatalog(enabled,packs);
    }
    private static NewsCatalog.Pack pack(Object raw,Path directory,Path file,String where)throws IOException {
        var pack=Yaml.map(raw,file+": "+where);
        Yaml.fields(pack,file+": "+where,"featured","spotlights");
        if(pack.get("featured")==null)throw new IOException(file+": "+where+" requires a featured banner");
        var featured=banner(pack.get("featured"),directory,file,where+".featured",
            NewsCatalog.FEATURED_WIDTH,NewsCatalog.FEATURED_HEIGHT);
        NewsCatalog.Banner left=null,right=null;
        if(pack.containsKey("spotlights")) {
            var spotlights=Yaml.map(pack.get("spotlights"),file+": "+where+".spotlights");
            Yaml.fields(spotlights,file+": "+where+".spotlights","left","right");
            left=banner(spotlights.get("left"),directory,file,where+".spotlights.left",
                NewsCatalog.SPOTLIGHT_WIDTH,NewsCatalog.SPOTLIGHT_HEIGHT);
            right=banner(spotlights.get("right"),directory,file,where+".spotlights.right",
                NewsCatalog.SPOTLIGHT_WIDTH,NewsCatalog.SPOTLIGHT_HEIGHT);
        }
        return new NewsCatalog.Pack(featured,left,right);
    }
    private static NewsCatalog.Banner banner(Object raw,Path directory,Path file,String where,int maxWidth,int maxHeight)throws IOException {
        if(raw==null)return null;
        var row=Yaml.map(raw,file+": "+where);
        Yaml.fields(row,file+": "+where,"image","tooltip","url","action","argument");
        if(!row.containsKey("image"))throw new IOException(file+": "+where+" requires an image");
        String image=Yaml.text(row.get("image"),file+": "+where+" image");
        String lower=image.toLowerCase(Locale.ROOT);
        if(!(lower.endsWith(".png")||lower.endsWith(".jpg")||lower.endsWith(".jpeg")))throw new IOException(file+": "+where+" image must be a PNG or JPEG: "+image);
        Path path=ContentFiles.relative(directory,image);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException(file+": "+where+" image is missing: "+image);
        String action=optional(row.get("action"),file+": "+where+" action");
        String argument=optional(row.get("argument"),file+": "+where+" argument");
        if(row.containsKey("action")&&!action.equals("depot"))throw new IOException(file+": "+where+" supports only the depot action");
        if(!argument.isEmpty())throw new IOException(file+": "+where+" depot action takes no argument");
        image(path,file+": "+where,image,maxWidth,maxHeight);
        return new NewsCatalog.Banner(image,optional(row.get("tooltip"),file+": "+where+" tooltip"),
            optional(row.get("url"),file+": "+where+" url"),action,argument);
    }
    /** Banners must fit their native promo button; the stock window draws them at pixel size. */
    private static void image(Path path,String where,String name,int maxWidth,int maxHeight)throws IOException {
        long size=Files.size(path);
        if(size>NewsCatalog.MAX_IMAGE_BYTES)throw new IOException(where+" image exceeds the 8 MiB limit: "+name);
        int[] dimensions;
        try {dimensions=dimensions(path);}
        catch(IOException invalid) {throw new IOException(where+" image is not a valid PNG or JPEG: "+name,invalid);}
        if(dimensions[0]>maxWidth||dimensions[1]>maxHeight)
            throw new IOException(where+" image is "+dimensions[0]+"x"+dimensions[1]+", larger than the "+maxWidth+"x"+maxHeight+" slot: "+name);
    }
    private static int[] dimensions(Path path)throws IOException {
        // ImageIO initializes AWT even in a nogui server. Read only bounded format metadata.
        try(var input=new java.io.DataInputStream(Files.newInputStream(path))) {
            if(path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) {
                if(input.readLong()!=0x89504e470d0a1a0aL||input.readInt()!=13||input.readInt()!=0x49484452)
                    throw new IOException("Invalid PNG header");
                return dimensions(input.readInt(),input.readInt());
            }
            if(input.readUnsignedShort()!=0xffd8)throw new IOException("Invalid JPEG signature");
            for(;;) {
                if(input.readUnsignedByte()!=0xff)throw new IOException("Invalid JPEG marker");
                int marker;do{marker=input.readUnsignedByte();}while(marker==0xff);
                if(marker==0xd9||marker==0xda)throw new IOException("Missing JPEG frame header");
                if(marker==1||marker>=0xd0&&marker<=0xd7)continue;
                int length=input.readUnsignedShort();if(length<2)throw new IOException("Invalid JPEG segment");
                if(marker>=0xc0&&marker<=0xcf&&marker!=0xc4&&marker!=0xc8&&marker!=0xcc) {
                    if(length<8)throw new IOException("Invalid JPEG frame");
                    input.readUnsignedByte();int height=input.readUnsignedShort(),width=input.readUnsignedShort();
                    input.skipNBytes(length-7L);
                    return dimensions(width,height);
                }
                input.skipNBytes(length-2L);
            }
        }
    }
    private static int[] dimensions(int width,int height)throws IOException {
        if(width<1||height<1)throw new IOException("Empty image");return new int[]{width,height};
    }
    private static String optional(Object value,String where)throws IOException {
        return value==null?"":Yaml.text(value,where);
    }
}
