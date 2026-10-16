import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.projectx.craft.config.*;

/** Read-only stock oracle: exports craft recipes with native ingredient layouts to a fresh directory. */
public final class RecipeCatalogExport {
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Usage: RecipeCatalogExport game-directory fresh-output-directory");
        Path game=Path.of(args[0]).toRealPath(),out=Path.of(args[1]).toAbsolutePath().normalize();
        Path ancestor=out;while(!Files.exists(ancestor))ancestor=ancestor.getParent();
        if(ancestor.toRealPath().startsWith(game)||Files.exists(out))throw new IOException("Choose a fresh output directory outside the installation");
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,game.resolve("rsrc").toString());
        var manager=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");manager.init();
        var recipes=new ArrayList<Map<String,Object>>();
        @SuppressWarnings("unchecked") var group=(ConfigGroup<RecipeConfig>)manager.bd(RecipeConfig.class);
        for(var config:group.kK()){
            var record=new LinkedHashMap<String,Object>();
            record.put("config",config.getName());
            record.put("item",config.item==null?"":String.valueOf(config.item.getName()));
            record.put("count",config.count);
            record.put("locked",config.lockItem);
            record.put("variantTable",config.variantTable==null?"":String.valueOf(config.variantTable.getName()));
            var rows=new ArrayList<Map<String,Object>>();
            if(config.recipeProp!=null){var props=config.recipeProp.a(manager,RecipePropertyConfig.class);
                if(props!=null&&props.recipeProps!=null)for(var row:props.recipeProps){
                    var entry=new LinkedHashMap<String,Object>();entry.put("crowns",row.crowns);entry.put("useOrbs",row.useOrbs);rows.add(entry);}
            }
            record.put("props",rows);
            var ingredients=new ArrayList<Map<String,Object>>();
            if(config.ingredients!=null)for(var ingredient:config.ingredients)ingredients.add(ingredient(ingredient));
            record.put("ingredients",ingredients);
            recipes.add(record);
        }
        recipes.sort(Comparator.comparing(r->String.valueOf(r.get("config"))));
        Files.createDirectories(out);
        Files.writeString(out.resolve("recipes.json"),json(recipes));
        System.out.println("Exported "+recipes.size()+" recipes to "+out.resolve("recipes.json"));
    }
    private static String json(Object value){
        if(value==null)return "null";
        if(value instanceof Boolean||value instanceof Integer||value instanceof Float)return String.valueOf(value);
        if(value instanceof String text)return '"' + text.replace("\\","\\\\").replace("\"","\\\"") + '"';
        if(value instanceof Map<?,?> map){var parts=new ArrayList<String>();for(var entry:map.entrySet())parts.add(json(entry.getKey())+":"+json(entry.getValue()));return "{"+String.join(",",parts)+"}";}
        if(value instanceof Iterable<?> list){var parts=new ArrayList<String>();for(var element:list)parts.add(json(element));return "["+String.join(",",parts)+"]";}
        throw new IllegalArgumentException("Unsupported JSON value: "+value.getClass());
    }
    private static Map<String,Object> ingredient(IngredientConfig ingredient){
        var entry=new LinkedHashMap<String,Object>();
        entry.put("kind",ingredient.getClass().getSimpleName());
        entry.put("count",ingredient.count);
        if(ingredient instanceof IngredientConfig.Item item)entry.put("item",item.item==null?"":String.valueOf(item.item.getName()));
        if(ingredient instanceof IngredientConfig.GroupBase base)entry.put("rarity",base.rarity);
        if(ingredient instanceof IngredientConfig.ItemGroup group)entry.put("group",group.group==null?"":String.valueOf(group.group));
        if(ingredient instanceof IngredientConfig.SpriteFoodMaterial)entry.put("group","MATERIAL_AND_FOOD");
        if(ingredient instanceof IngredientConfig.LevelItem level){
            entry.put("level",level.level);
            entry.put("upgrade",level.upgrade==null?"CHOICE":String.valueOf(level.upgrade));
            entry.put("keepHeat",level.keepHeat);
        }
        if(ingredient instanceof IngredientConfig.ChoiceLevelItem choice){
            var options=new ArrayList<String>();
            if(choice.options!=null)for(var option:choice.options)if(option!=null)options.add(option.ref==null?"":String.valueOf(option.ref.getName()));
            entry.put("options",options);
        }
        return entry;
    }
}
