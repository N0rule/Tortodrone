import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.tudey.config.PlaceableConfig;
import com.threerings.tudey.data.TudeySceneModel;
import com.threerings.projectx.item.config.ItemConfig;
import org.tortodrone.resources.Json;
import org.tortodrone.world.FireCatalog;

/** Read-only stock oracle for placed Town service handlers, shapes and minimap icons. */
public final class TownServicesExport {
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("game-directory maps-directory fresh-output-directory");
        Path game=Path.of(args[0]).toRealPath(),out=Path.of(args[2]).toAbsolutePath();
        Path ancestor=out;while(!Files.exists(ancestor))ancestor=ancestor.getParent();
        if(Files.exists(out)||ancestor.toRealPath().startsWith(game))throw new IOException("Choose fresh output outside the stock installation");
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,game.resolve("rsrc").toString());
        var manager=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");manager.init();
        var layouts=new TreeMap<String,Object>();
        try(var dirs=Files.list(Path.of(args[1]))){for(var dir:dirs.filter(Files::isDirectory).sorted().toList()){
            if(!Files.exists(dir.resolve("scene.dat")))continue;
            var scene=(TudeySceneModel)com.threerings.export.util.b.e(Files.readAllBytes(dir.resolve("scene.dat")));scene.init(manager);
            var entries=new ArrayList<Object>();
            for(Object raw:scene.adH())if(raw instanceof TudeySceneModel.PlaceableEntry entry){
                var config=entry.placeable.a(manager,PlaceableConfig.class);if(config==null)continue;
                var original=config.q(manager);boolean useful=false;
                for(var handler:original.handlers){String name=handler.getClass().getSimpleName();if(name.equals("Interaction")||name.equals("MinimapIcon")){useful=true;break;}}
                if(!useful)continue;
                var point=entry.au(manager);var q=entry.transform.tE();float rotation=(float)Math.IEEEremainder(Math.atan2(2*(q.w*q.z+q.x*q.y),1-2*(q.y*q.y+q.z*q.z))-Math.PI/2,2*Math.PI);
                entries.add(Map.of("key",String.valueOf(entry.getKey()),"config",dump(entry.placeable,0),"x",point.x,"y",point.y,"rotation",rotation,"original",dump(original,0),"shape-native",FireCatalog.modelJson(FireCatalogExport.model(new ConfigReference<>("shape","value",original.shape)))));
            }
            layouts.put(dir.getFileName().toString(),entries);
        }}
        var items=new ArrayList<Object>();
        for(var config:manager.bd(ItemConfig.class).kK()){
            var original=config.w(manager);
            if(original instanceof ItemConfig.Craft craft)items.add(Map.of("config",config.getName(),"spriteFoodRole",craft.spriteFoodRole.name()));
        }
        Files.createDirectory(out);Files.writeString(out.resolve("town-services.json"),Json.stringify(Map.of("layouts",layouts,"items",items)));
        System.out.println("Exported placed service references for "+layouts.size()+" supported maps");
    }
    private static Object dump(Object value,int depth)throws Exception {
        if(value==null||value instanceof String||value instanceof Boolean||value instanceof Integer||value instanceof Long||value instanceof Float)return value;
        if(value instanceof Enum<?> enumeration)return enumeration.name();
        if(depth>12)throw new IOException("Stock reference nesting limit");
        if(value instanceof ConfigReference<?> reference){var fields=new LinkedHashMap<String,Object>();fields.put("name",reference.getName());fields.put("arguments",dump(reference.kW(),depth+1));return fields;}
        if(value instanceof Map<?,?> map){var fields=new LinkedHashMap<String,Object>();for(var e:map.entrySet())fields.put(String.valueOf(e.getKey()),dump(e.getValue(),depth+1));return fields;}
        if(value.getClass().isArray()){var elements=new ArrayList<Object>();for(int i=0;i<Array.getLength(value);i++)elements.add(dump(Array.get(value,i),depth+1));return elements;}
        var fields=new LinkedHashMap<String,Object>();fields.put("class",value.getClass().getName());
        for(var field:value.getClass().getFields())if(!Modifier.isStatic(field.getModifiers()))fields.put(field.getName(),dump(field.get(value),depth+1));
        return fields;
    }
}
