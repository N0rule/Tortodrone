import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import com.threerings.config.ConfigManager;
import com.threerings.config.ConfigGroup;
import com.threerings.config.ManagedConfig;
import com.threerings.projectx.item.config.ItemConfig;
import com.threerings.projectx.sprites.config.BattleSpriteConfig;
import com.threerings.opengl.gui.config.IconConfig;
import com.threerings.opengl.renderer.config.ColorizationConfig;
import org.tortodrone.resources.Json;
import org.tortodrone.server.ClientArtworkApi;

/** Optional offline preparation using the installed client's own resolver and colorizer.
 * Output is proprietary local material: never add it to Git or redistribute without rights. */
public final class WebUiAssetsExport {
    private static Path out;
    private static com.threerings.opengl.util.h images;
    private static com.threerings.media.image.ColorPository colors;
    private static final Map<String,String> items=new TreeMap<>(),ui=new TreeMap<>(),sprites=new TreeMap<>(),symbols=new TreeMap<>();
    public static void main(String[] args)throws Exception {
        Path game=Path.of(args[0]).toRealPath();out=Path.of(args[1]).toAbsolutePath().normalize();
        Path ancestor=out;while(!Files.exists(ancestor))ancestor=ancestor.getParent();
        if(Files.exists(out)||ancestor.toRealPath().startsWith(game))throw new IOException("Use a fresh output outside the client installation");
        // Reflection avoids the stock JAR's com.threerings.b class/package name collision.
        Class<?> resourceType=Class.forName("com.threerings.b.f");
        var resource=resourceType.getConstructor(String.class).newInstance("rsrc/");
        ClientArtworkApi.invoke(ClientArtworkApi.method(resourceType,"resource directory",false,void.class,String.class),resource,game.resolve("rsrc").toString());
        var manager=(ConfigManager)ConfigManager.class.getConstructor(resourceType,com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");manager.init();
        colors=(com.threerings.media.image.ColorPository)ClientArtworkApi.invoke(ClientArtworkApi.method(com.threerings.media.image.ColorPository.class,"color repository factory",true,com.threerings.media.image.ColorPository.class,resourceType),null,resource);
        var ctx=(com.threerings.opengl.util.d)Proxy.newProxyInstance(WebUiAssetsExport.class.getClassLoader(),new Class<?>[]{com.threerings.opengl.util.d.class},(proxy,method,values)->switch(method.getName()){
            case "getResourceManager"->resource;case "getConfigManager"->manager;case "getColorPository"->colors;case "getImageCache"->images;
            case "getPixelScaleFactor","getWindowScaleFactor"->1f;
            default->throw new UnsupportedOperationException("Offline icon export cannot call "+method.getName());
        });
        images=new com.threerings.opengl.util.h(ctx,false);
        // ItemConfig.Level.d uses this stock style to color category masks for empty sockets.
        var style=(com.threerings.opengl.gui.config.StyleConfig)ClientArtworkApi.invoke(ClientArtworkApi.namedConfig(ConfigManager.class,ManagedConfig.class),manager,com.threerings.opengl.gui.config.StyleConfig.class,"Status/Equip Socket/Backing, Icon");
        if(style==null)throw new IOException("Client artwork socket style is missing");
        var socketStyle=(com.threerings.opengl.gui.config.StyleConfig.Original)ClientArtworkApi.invoke(ClientArtworkApi.method(com.threerings.opengl.gui.config.StyleConfig.class,"original style",false,com.threerings.opengl.gui.config.StyleConfig.Original.class,com.threerings.opengl.util.d.class),style,ctx);
        var socketColors=((com.threerings.opengl.gui.config.BackgroundConfig.ColorizedImage)socketStyle.background).colorizations;
        Files.createDirectories(out.resolve("icons"));
        int missing=0;
        // Resolve once, outside per-icon fallback handling: an incompatible API must stop extraction.
        var originalItem=ClientArtworkApi.method(ItemConfig.class,"original item",false,ItemConfig.Original.class,ConfigManager.class);
        for(var config:configurations(manager,ItemConfig.class)) {
            try {var item=(ItemConfig.Original)ClientArtworkApi.invoke(originalItem,config,manager);if(item!=null&&item.icon!=null&&!item.icon.file.isBlank())items.put(config.getName(),write(item.icon));else missing++;}
            catch(RuntimeException|IOException unavailable){missing++;}
        }
        for(var config:configurations(manager,BattleSpriteConfig.class)) {
            for(var entry:Map.of("1",config.icon1,"2",config.icon2,"3",config.icon3).entrySet()) {
                if(entry.getValue()!=null&&!entry.getValue().file.isBlank())try{sprites.put(config.getName()+":"+entry.getKey(),write(entry.getValue()));}catch(RuntimeException|IOException unavailable){missing++;}
            }
        }
        for(var config:configurations(manager,BattleSpriteConfig.class))for(int i=0;i<config.skills.length;i++) {
            var icon=config.skills[i].name.icon;
            if(icon!=null&&!icon.file.isBlank())sprites.put(config.getName()+":skill:"+i,write(icon));
        }
        var navigation=new TreeMap<String,String>();
        for(var entry:Map.ofEntries(Map.entry("overview","readyroom"),Map.entry("players","social"),Map.entry("accounts","guildhall"),Map.entry("knights","equipment"),Map.entry("equipment","loadout"),Map.entry("inventory","arsenal"),Map.entry("progression","missions"),Map.entry("depot","supplydepot"),Map.entry("news","feed"),Map.entry("content","arsenal"),Map.entry("console","settings"),Map.entry("logs","mail"),Map.entry("webui-users","settings"),Map.entry("details","help"),Map.entry("edit","forge")).entrySet())navigation.put(entry.getKey(),"ui/hud_v2/images/icons/system/"+entry.getValue()+".png");
        navigation.put("auctions","ui/icon/market_32.png");navigation.put("sprites","ui/icon/inventory/categories/icon_battlesprite.png");
        navigation.put("audit","ui/icon/check.png");navigation.put("save","ui/icon/check.png");navigation.put("add","ui/icon/icon_plus.png");navigation.put("remove","ui/button/dismiss-sml.png");
        for(var entry:navigation.entrySet()){var icon=new IconConfig.ColorizedImage();icon.file=entry.getValue();if(icon.file.contains("/categories/"))icon.colorizations=socketColors;ui.put("nav-"+entry.getKey(),write(icon));symbols.put("nav-"+entry.getKey(),writeSymbol(icon.file,icon.file.contains("/categories/")));}
        for(var entry:Map.ofEntries(Map.entry("helmet","helmet"),Map.entry("armor","armor"),Map.entry("shield","shield"),Map.entry("weapon","sword"),Map.entry("trinket","amulet"),Map.entry("costume","costume")).entrySet()){var icon=new IconConfig.ColorizedImage();icon.file="ui/icon/inventory/categories/icon_"+entry.getValue()+".png";icon.colorizations=socketColors;ui.put("slot-"+entry.getKey(),write(icon));symbols.put("slot-"+entry.getKey(),writeSymbol(icon.file,true));}
        for(var entry:Map.of("crowns","ui/icon/crown_32.png","energy","ui/icon/energy_32.png","prestige","ui/icon/prestige_16.png",
                "heat","ui/window/geartip_levelbar.png","variants","ui/window/tooltip_parts/icon_variant.png","equipment","ui/status/images/backing_slots.png",
                "button","ui/button/up.png","button-active","ui/button/up_yellow.png").entrySet()) {
            var icon=new IconConfig.ColorizedImage();icon.file=entry.getValue();ui.put(entry.getKey(),write(icon));
        }
        symbols.put("brand",writeSymbol("ui/icon/spiralseal.png",false));
        long bytes;try(var walk=Files.walk(out)){bytes=walk.filter(Files::isRegularFile).mapToLong(p->{try{return Files.size(p);}catch(IOException e){throw new UncheckedIOException(e);}}).sum();}
        var manifest=new TreeMap<String,Object>();manifest.put("items",items);manifest.put("ui",ui);manifest.put("sprites",sprites);manifest.put("symbols",symbols);manifest.put("bytes",bytes);manifest.put("source","Resolved installed client icons; PNG, maximum 96 pixels; content hash deduplicated");manifest.put("unavailable",missing);
        Files.writeString(out.resolve("manifest.json"),Json.stringify(manifest),StandardOpenOption.CREATE_NEW);
        System.out.println("Prepared "+items.size()+" exact item mappings, "+sprites.size()+" Sprite mappings and "+ui.size()+" UI assets; "+bytes+" image bytes; "+missing+" unavailable icons use fallback");
    }
    static <T extends ManagedConfig> Iterable<T> configurations(ConfigManager manager,Class<T> type)throws IOException {
        var group=ClientArtworkApi.invoke(ClientArtworkApi.groupAccessor(ConfigManager.class,ConfigGroup.class,ManagedConfig.class),manager,type);
        if(group==null)throw new IOException("Client artwork configuration group is missing: "+type.getName());
        var values=ClientArtworkApi.invoke(ClientArtworkApi.typedValues(ConfigGroup.class,ManagedConfig.class),group);
        if(!(values instanceof Iterable<?> iterable))throw new IOException("Client artwork configuration iteration is missing: "+type.getName());
        // Check every value before using it, including an empty group's valid iterable.
        var checked=new ArrayList<T>();
        for(var value:iterable){if(!type.isInstance(value))throw new IOException("Wrong client artwork configuration type: "+type.getName());checked.add(type.cast(value));}
        return checked;
    }
    private static String write(IconConfig.ColorizedImage icon)throws IOException {
        if(icon.file==null||icon.file.isBlank())throw new IOException("Missing image path");
        var zations=new ArrayList<com.threerings.media.image.b>();
        if(icon.colorizations!=null)for(ColorizationConfig c:icon.colorizations)if(c!=null)zations.add(c.getColorization(colors));
        BufferedImage original=images.getBufferedImage(icon.file,zations.toArray(com.threerings.media.image.b[]::new));
        if(original==null)throw new IOException("Missing image");
        return writeImage(original);
    }
    /** Inventory category art is an RGB material mask: blue is the main glyph, red its secondary ink,
     * magenta the socket backing. Remove the backing before applying the WebUI theme. */
    private static String writeSymbol(String file,boolean category)throws IOException {
        BufferedImage original=images.getBufferedImage(file,new com.threerings.media.image.b[0]);
        if(original==null)throw new IOException("Missing symbol: "+file);
        var mask=new BufferedImage(original.getWidth(),original.getHeight(),BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<original.getHeight();y++)for(int x=0;x<original.getWidth();x++) {
            int pixel=original.getRGB(x,y),alpha=pixel>>>24,r=(pixel>>>16)&255,g=(pixel>>>8)&255,b=pixel&255;
            int strength=category?(int)(Math.max(0,b-r)+Math.max(0,r-b)*0.55):
                file.equals("ui/button/dismiss-sml.png")?Math.clamp((r-Math.max(g,b)-20)*255/110,0,255):
                Math.clamp((int)Math.round((0.2126*r+0.7152*g+0.0722*b-64)*255/160),0,255);
            mask.setRGB(x,y,((alpha*strength/255)<<24)|0xffffff);
        }
        return writeImage(mask);
    }
    private static String writeImage(BufferedImage original)throws IOException {
        int width=original.getWidth(),height=original.getHeight();double scale=Math.min(1,96d/Math.max(width,height));
        var image=new BufferedImage(Math.max(1,(int)Math.round(width*scale)),Math.max(1,(int)Math.round(height*scale)),BufferedImage.TYPE_INT_ARGB);
        var graphics=image.createGraphics();try{graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);graphics.drawImage(original,0,0,image.getWidth(),image.getHeight(),null);}finally{graphics.dispose();}
        var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);byte[] encoded=bytes.toByteArray();
        String hash;try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        String path="icons/"+hash+".png";if(!Files.exists(out.resolve(path)))Files.write(out.resolve(path),encoded,StandardOpenOption.CREATE_NEW);return path;
    }
}
