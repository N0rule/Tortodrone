import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.tudey.data.*;

/** Read-only extraction of actors from the already selected layouts into a fresh staging directory. */
public final class TownSceneExport {
    public static void main(String[] args)throws Exception{
        if(args.length!=3)throw new IllegalArgumentException("game-directory maps-directory fresh-output-directory");
        Path output=Path.of(args[2]);if(Files.exists(output))throw new IOException("Choose a fresh output directory");Files.createDirectories(output);
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,args[0]+"/rsrc");
        var manager=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");manager.init();
        if(args[1].equals("--accessories")){
            var models=new ArrayList<Object>();for(var config:manager.bd(com.threerings.projectx.item.config.AccessoryConfig.class).kK()){
                var accessory=manager.d(com.threerings.projectx.item.config.AccessoryConfig.class,config.getName());
                if(accessory!=null&&accessory.model!=null)models.add(Map.of("config",config.getName(),"parameter",accessory.location.code(),"model",org.tortodrone.world.FireCatalog.modelJson(FireCatalogExport.model(accessory.model))));
            }
            Files.writeString(output.resolve("accessory-models.json"),org.tortodrone.resources.Json.stringify(models));System.out.println("Extracted accessory model references: "+models.size());return;
        }
        try(var dirs=Files.list(Path.of(args[1]))){for(Path dir:dirs.filter(Files::isDirectory).sorted().toList()){
            if(dir.getFileName().toString().equals("advanced_training"))continue;
            Path staged=output.resolve(dir.getFileName());Files.createDirectories(staged);byte[] bytes=Files.readAllBytes(dir.resolve("scene.dat"));Files.write(staged.resolve("scene.dat"),bytes);
            var scene=(TudeySceneModel)com.threerings.export.util.b.e(bytes);scene.init(manager);
            System.setProperty("tortodrone.townExport","true");
            var placements=new ArrayList<Object>();
            for(Object raw:scene.adH())if(raw instanceof TudeySceneModel.PlaceableEntry entry){
                var point=entry.au(manager);var q=entry.transform.tE();float rotation=(float)Math.IEEEremainder(Math.atan2(2*(q.w*q.z+q.x*q.y),1-2*(q.y*q.y+q.z*q.z))-Math.PI/2,2*Math.PI);
                placements.add(Map.of("kind","placeable","key",String.valueOf(entry.getKey()),"x",point.x,"y",point.y,"rotation",rotation,"config",org.tortodrone.world.FireCatalog.modelJson(FireCatalogExport.model(entry.placeable))));
            }
            for(Object raw:scene.adH())if(raw instanceof TudeySceneModel.AreaEntry entry){var point=entry.au(manager);var vertices=new ArrayList<Object>();for(var vertex:entry.vertices)vertices.add(Map.of("x",vertex.x,"y",vertex.y));placements.add(Map.of("kind","area","key",String.valueOf(entry.getKey()),"x",point.x,"y",point.y,"rotation",0,"vertices",vertices,"config",org.tortodrone.world.FireCatalog.modelJson(FireCatalogExport.model(entry.area))));}
            Files.writeString(staged.resolve("placements.json"),org.tortodrone.resources.Json.stringify(placements));
            SceneContentExport.export(Path.of(args[0]),manager,scene,staged);
            System.out.println("Extracted Town actors: "+dir.getFileName());
        }}
    }
}
