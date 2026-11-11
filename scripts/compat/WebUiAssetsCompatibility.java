import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import com.threerings.config.ConfigGroup;
import com.threerings.config.ConfigManager;
import com.threerings.config.ManagedConfig;
import com.threerings.projectx.item.config.ItemConfig;
import com.threerings.projectx.sprites.config.BattleSpriteConfig;

/** Optional stock-API regression: owned synthetic configs, no installed resources or preferences. */
public final class WebUiAssetsCompatibility {
    public static void main(String[] args)throws Exception {
        check(Class.forName("com.threerings.b.f").getMethod("gf",String.class).getReturnType()==void.class,"current resource-directory setter");
        var manager=new FixtureManager();
        var item=new ItemConfig();item.setName("Artwork fixture item");
        var sprite=new BattleSpriteConfig();sprite.setName("Artwork fixture sprite");
        manager.install(ItemConfig.class,item);
        manager.install(BattleSpriteConfig.class,sprite);
        check(collect(WebUiAssetsExport.configurations(manager,ItemConfig.class)).equals(List.of(item)),"exact typed item group");
        check(collect(WebUiAssetsExport.configurations(manager,BattleSpriteConfig.class)).equals(List.of(sprite)),"exact typed Sprite group");
        manager.install(ItemConfig.class,null);
        check(collect(WebUiAssetsExport.configurations(manager,ItemConfig.class)).isEmpty(),"empty group iteration");
        try {
            WebUiAssetsExport.configurations(new ConfigManager(),ItemConfig.class);
            throw new AssertionError("Missing group accepted");
        } catch(IOException expected) {
            check(expected.getMessage().contains(ItemConfig.class.getName()),"missing group diagnostic identifies its type");
        }
        System.out.println("PASS: artwork exporter compiles against stock API; resource setter, typed item/Sprite iteration, empty and missing groups");
    }
    private static final class FixtureManager extends ConfigManager {
        <T extends ManagedConfig> void install(Class<T> type,T config) {
            var group=ConfigGroup.ba(type);
            if(config!=null)group.a(config);
            _groups.put(type,group);
        }
    }
    private static <T> List<T> collect(Iterable<T> values) {
        var result=new ArrayList<T>();values.forEach(result::add);return result;
    }
    private static void check(boolean condition,String message) {
        if(!condition)throw new AssertionError(message);
    }
}
