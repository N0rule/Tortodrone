import java.nio.file.*;
import java.util.*;
import java.io.*;
import com.threerings.config.ConfigManager;

/** Read-only native status definitions and gear resistance values; no client assets. */
public final class StatusRuntimeExport {
    public static void main(String[] args)throws Exception{
        Path game=Path.of(args[0]).toRealPath(),out=Path.of(args[1]).toAbsolutePath();
        if(Files.exists(out)||out.startsWith(game))throw new IOException("Choose a new output file outside the game installation");
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,game.resolve("rsrc").toString());
        var manager=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");manager.init();
        var values=new TreeMap<String,String>();SceneStatusExport.export(manager,values);
        var gear=new TreeMap<String,String>();ScenePlayerStatsExport.export(manager,gear);
        gear.forEach((key,value)->{if(key.contains(".status-resist."))values.put(key,value);});
        for(var config:manager.bd(com.threerings.projectx.item.config.VariantConfig.class).kK()){
            for(var variant:com.threerings.projectx.item.config.VariantConfig.a(manager,List.of(new com.threerings.config.ConfigReference<com.threerings.projectx.item.config.VariantConfig>(config.getName())))){
                if(variant instanceof com.threerings.projectx.item.config.VariantConfig.StatusResist resist){
                    String key="gear.variant."+config.getName()+".status-resist.";
                    values.put(key+"mask",Integer.toString(resist.conditionMask));values.put(key+"value",Integer.toString(resist.statusResist.resist(resist.resist,0)));
                    values.put(key+"relative",resist.statusResist.name().equals("CUSTOM")?"0":"1");
                }
            }
        }
        var props=new Properties();props.putAll(values);var buffer=new StringWriter();props.store(buffer,null);
        Files.writeString(out,buffer.toString().lines().filter(line->!line.startsWith("#")).sorted().collect(java.util.stream.Collectors.joining("\n","","\n")),StandardOpenOption.CREATE_NEW);
        System.out.println("PASS: exported "+values.size()+" native status/resistance properties");
    }
}
