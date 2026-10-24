import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import com.threerings.config.ConfigManager;
import com.threerings.projectx.item.config.ItemConfig;
import com.threerings.projectx.sprites.config.BattleSpriteConfig;
import com.threerings.opengl.gui.config.IconConfig;
import com.threerings.opengl.renderer.config.ColorizationConfig;
import org.tortodrone.resources.Json;

/** Optional offline preparation using the installed client's own resolver and colorizer.
 * Output is proprietary local material: never add it to Git or redistribute without rights. */
public final class WebUiAssetsExport {
    private static Path out;
    private static com.threerings.opengl.util.h images;
    private static com.threerings.media.image.ColorPository colors;
    private static final Map<String,String> items=new TreeMap<>(),ui=new TreeMap<>(),sprites=new TreeMap<>();
    public static void main(String[] args)throws Exception {
        Path game=Path.of(args[0]).toRealPath();out=Path.of(args[1]).toAbsolutePath().normalize();
        Path ancestor=out;while(!Files.exists(ancestor))ancestor=ancestor.getParent();
        if(Files.exists(out)||ancestor.toRealPath().startsWith(game))throw new IOException("Use a fresh output outside the client installation");
        Class<?> resourceType=Class.forName("com.threerings.b.f");
        var resource=resourceType.getConstructor(String.class).newInstance("rsrc/");resourceType.getMethod("ga",String.class).invoke(resource,game.resolve("rsrc").toString());
        var manager=(ConfigManager)ConfigManager.class.getConstructor(resourceType,com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");manager.init();
        colors=(com.threerings.media.image.ColorPository)com.threerings.media.image.ColorPository.class.getMethod("a",resourceType).invoke(null,resource);
        var ctx=(com.threerings.opengl.util.d)Proxy.newProxyInstance(WebUiAssetsExport.class.getClassLoader(),new Class<?>[]{com.threerings.opengl.util.d.class},(proxy,method,values)->switch(method.getName()){
            case "getResourceManager"->resource;case "getConfigManager"->manager;case "getColorPository"->colors;case "getImageCache"->images;
            case "getPixelScaleFactor","getWindowScaleFactor"->1f;
            default->throw new UnsupportedOperationException("Offline icon export cannot call "+method.getName());
        });
        images=new com.threerings.opengl.util.h(ctx,false);
        Files.createDirectories(out.resolve("icons"));
        int missing=0;
        for(var config:manager.bd(ItemConfig.class).kK()) {
            try {var item=config.w(manager);if(item!=null&&item.icon!=null&&!item.icon.file.isBlank())items.put(config.getName(),write(item.icon));else missing++;}
            catch(RuntimeException|IOException unavailable){missing++;}
        }
        for(var config:manager.bd(BattleSpriteConfig.class).kK()) {
            for(var entry:Map.of("1",config.icon1,"2",config.icon2,"3",config.icon3).entrySet()) {
                if(entry.getValue()!=null&&!entry.getValue().file.isBlank())try{sprites.put(config.getName()+":"+entry.getKey(),write(entry.getValue()));}catch(RuntimeException|IOException unavailable){missing++;}
            }
        }
        for(var entry:Map.of("crowns","ui/icon/crown_32.png","energy","ui/icon/energy_32.png","prestige","ui/icon/prestige_16.png",
                "heat","ui/window/geartip_levelbar.png","variants","ui/window/geartip_variant.png","equipment","ui/status/images/backing_slots.png",
                "button","ui/button/up.png","button-active","ui/button/up_yellow.png").entrySet()) {
            var icon=new IconConfig.ColorizedImage();icon.file=entry.getValue();ui.put(entry.getKey(),write(icon));
        }
        long bytes;try(var walk=Files.walk(out)){bytes=walk.filter(Files::isRegularFile).mapToLong(p->{try{return Files.size(p);}catch(IOException e){throw new UncheckedIOException(e);}}).sum();}
        var manifest=new TreeMap<String,Object>();manifest.put("items",items);manifest.put("ui",ui);manifest.put("sprites",sprites);manifest.put("bytes",bytes);manifest.put("source","Resolved installed client icons; PNG, maximum 96 pixels; content hash deduplicated");manifest.put("unavailable",missing);
        Files.writeString(out.resolve("manifest.json"),Json.stringify(manifest),StandardOpenOption.CREATE_NEW);
        System.out.println("Prepared "+items.size()+" exact item mappings, "+sprites.size()+" Sprite mappings and "+ui.size()+" UI assets; "+bytes+" image bytes; "+missing+" unavailable icons use fallback");
    }
    private static String write(IconConfig.ColorizedImage icon)throws IOException {
        if(icon.file==null||icon.file.isBlank())throw new IOException("Missing image path");
        var zations=new ArrayList<com.threerings.media.image.b>();
        if(icon.colorizations!=null)for(ColorizationConfig c:icon.colorizations)if(c!=null)zations.add(c.getColorization(colors));
        BufferedImage original=images.getBufferedImage(icon.file,zations.toArray(com.threerings.media.image.b[]::new));
        if(original==null)throw new IOException("Missing image");
        int width=original.getWidth(),height=original.getHeight();double scale=Math.min(1,96d/Math.max(width,height));
        var image=new BufferedImage(Math.max(1,(int)Math.round(width*scale)),Math.max(1,(int)Math.round(height*scale)),BufferedImage.TYPE_INT_ARGB);
        var graphics=image.createGraphics();try{graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);graphics.drawImage(original,0,0,image.getWidth(),image.getHeight(),null);}finally{graphics.dispose();}
        var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);byte[] encoded=bytes.toByteArray();
        String hash;try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        String path="icons/"+hash+".png";if(!Files.exists(out.resolve(path)))Files.write(out.resolve(path),encoded,StandardOpenOption.CREATE_NEW);return path;
    }
}
