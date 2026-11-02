import java.nio.file.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.tudey.data.TudeySceneModel;
import com.threerings.tudey.config.PlaceableConfig;
import org.tortodrone.resources.Json;

/** Read-only authored entry/handler inspection, not a scene-playability checker. */
public final class SceneEntryInspect {
    public static void main(String[] args)throws Exception {
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,args[0]+"/rsrc");
        var cm=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");cm.init();
        byte[] bytes=Files.readAllBytes(Path.of(args[1]));var scene=(TudeySceneModel)com.threerings.export.util.b.e(bytes);scene.init(cm);
        System.out.println("SCENE "+scene.sceneId+" version="+scene.version+" sha256="+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        var keys=new HashSet<>(Arrays.asList(args).subList(2,args.length));
        for(var raw:scene.adH())if(keys.contains(raw.getKey().toString())){
            System.out.println("ENTRY "+raw.getKey()+" position="+raw.au(cm));
            Object original;
            if(raw instanceof TudeySceneModel.PlaceableEntry entry){System.out.println("REFERENCE "+entry.placeable);original=entry.placeable.a(cm,PlaceableConfig.class).q(cm);}
            else if(raw instanceof TudeySceneModel.AreaEntry entry){System.out.println("REFERENCE "+entry.area);original=entry.area.a(cm,com.threerings.tudey.config.AreaConfig.class).aj(cm);}
            else{System.out.println("TYPE "+raw.getClass().getName());continue;}
            System.out.println(Json.stringify(OpeningMissionCompatibility.data(original,0)));
        }
        for(String key:keys)if(key.startsWith("actor:")){
            String name=key.substring(6);var config=cm.d(com.threerings.tudey.config.ActorConfig.class,name);
            if(config==null)throw new java.io.IOException("Missing native actor: "+name);
            System.out.println("ACTOR "+name);
            if(config.implementation instanceof com.threerings.tudey.config.ActorConfig.Derived derived){
                System.out.println("DERIVED "+Json.stringify(OpeningMissionCompatibility.data(derived.actor,0)));
                System.out.println("PARAMETERS "+Json.stringify(OpeningMissionCompatibility.data(config.parameters,0)));
            }
            var original=config.ai(cm);System.out.println("HANDLERS "+Json.stringify(OpeningMissionCompatibility.data(original.handlers,0)));
        }
    }
}
