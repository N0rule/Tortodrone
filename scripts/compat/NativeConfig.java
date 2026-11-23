import java.nio.file.Path;
import com.threerings.config.ConfigManager;

/** Reflective bootstrap shared by probes that read the installed stock client's config/ tree.
 * Keep this file dependency-light: only the client classes every such probe already needs. */
public final class NativeConfig {
    private NativeConfig() { }
    /** Select the native ordinal enum wire policy before any client class initializes. */
    static void ordinal(){System.setProperty("com.threerings.io.enumPolicy","ORDINAL");}
    /** Load and initialize the installed client's config manager from {@code game/rsrc}. */
    static ConfigManager load(Path game)throws Exception{
        Object resources=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resources.getClass().getMethod("gf",String.class).invoke(resources,game.resolve("rsrc").toString());
        var manager=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resources,null,"config/");
        manager.init();
        return manager;
    }
}
