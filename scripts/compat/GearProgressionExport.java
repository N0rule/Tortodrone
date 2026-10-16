import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.projectx.item.config.*;
import org.tortodrone.resources.Json;

/** Read-only numeric forge/heat rules and resolved UV trees; never exports client assets. */
public final class GearProgressionExport {
    public static void main(String[] args)throws Exception{
        Path game=Path.of(args[0]).toRealPath(),out=Path.of(args[1]).toAbsolutePath().normalize();
        if(Files.exists(out)||out.startsWith(game))throw new IOException("Choose a fresh output directory outside the installation");
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");resource.getClass().getMethod("ga",String.class).invoke(resource,game.resolve("rsrc").toString());
        var manager=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");manager.init();
        var gear=new ArrayList<Object>();var forges=new TreeMap<String,Object>();var tables=new ArrayList<Object>();
        for(var config:manager.bd(ItemConfig.class).kK()){
            var original=config.w(manager);if(!(original instanceof ItemConfig.Level level))continue;
            if(!(level.L(manager) instanceof ItemPropertyConfig.LevelItemProps props))continue;
            gear.add(Map.of("config",config.getName(),"levels",level.K(manager),"baseHeat",props.baseHeat,"maxHeat",props.maxHeat,"forge",props.forgeProperty==null?"":props.forgeProperty.getName()));
            if(props.forgeProperty==null||forges.containsKey(props.forgeProperty.getName()))continue;
            var forge=manager.a(ForgePropertyConfig.class,props.forgeProperty);if(forge==null)continue;var rows=new ArrayList<Object>();
            for(var row:forge.props)rows.add(Map.of("crystals",Arrays.stream(row.crystalsNeeded).boxed().toList(),"success",floats(row.success)));
            forges.put(props.forgeProperty.getName(),Map.of("config",props.forgeProperty.getName(),"rows",rows));
        }
        for(var raw:manager.bd(VariantTableConfig.class).kK()){
            var config=manager.a(VariantTableConfig.class,new ConfigReference<VariantTableConfig>(raw.getName()));
            if(config!=null)tables.add(Map.of("config",raw.getName(),"root",table(manager,config,0)));
        }
        Files.createDirectories(out);Files.writeString(out.resolve("progression.json"),Json.stringify(Map.of("gearProgression",gear,"forgeTables",new ArrayList<>(forges.values()),"variantTables",tables)));
        System.out.println("PASS: exported "+gear.size()+" gear heat rules, "+forges.size()+" forge tables and "+tables.size()+" resolved UV trees");
    }
    private static List<Float> floats(float[] input){var values=new ArrayList<Float>();for(float value:input)values.add(value);return values;}
    private static Map<String,Object> table(ConfigManager cm,VariantTableConfig config,int depth)throws Exception{
        var result=base("Original");result.put("maxResults",config.maxResults);result.put("entries",List.of(Map.of("weight",1,"node",node(cm,config.root,depth+1))));return result;
    }
    private static Map<String,Object> base(String kind){var result=new TreeMap<String,Object>();result.put("kind",kind);result.put("entries",List.of());result.put("table","");result.put("variant","");result.put("group","SWORD");result.put("maxResults",3);result.put("selections",0);result.put("selectionWeights",List.of());result.put("probability",.001f);result.put("exclude",false);return result;}
    private static Map<String,Object> node(ConfigManager cm,VariantTableConfig.Entry entry,int depth)throws Exception{
        if(depth>32)throw new IOException("UV table recursion limit");var result=base(entry.getClass().getSimpleName());var children=new ArrayList<Object>();
        if(entry instanceof VariantTableConfig.VariantEntry v)result.put("variant",v.variant==null?"":v.variant.getName());
        else if(entry instanceof VariantTableConfig.TableEntry t){
            result.put("table",t.variantTable==null?"":t.variantTable.getName());var resolved=t.variantTable==null?null:cm.a(VariantTableConfig.class,t.variantTable);
            if(resolved!=null)children.add(Map.of("weight",1,"node",table(cm,resolved,depth+1)));
        }else if(entry instanceof VariantTableConfig.GroupEntry g){result.put("group",g.group.name());children.add(Map.of("weight",1,"node",node(cm,g.entry,depth+1)));}
        else if(entry instanceof VariantTableConfig.OptionalEntry o){result.put("probability",o.probability);children.add(Map.of("weight",1,"node",node(cm,o.entry,depth+1)));}
        else if(entry instanceof VariantTableConfig.CompoundEntry c){for(var child:c.entries)children.add(Map.of("weight",1,"node",node(cm,child,depth+1)));}
        else if(entry instanceof VariantTableConfig.MultiEntry m){
            result.put("exclude",m.exclude);for(var child:m.entries)children.add(Map.of("weight",child.weight,"node",node(cm,child.entry,depth+1)));
            if(m instanceof VariantTableConfig.SelectionEntry s)result.put("selections",s.selections);
            if(m instanceof VariantTableConfig.RandomSelectionEntry r)result.put("selectionWeights",floats(r.selectionWeights));
        }else throw new IOException("Unsupported UV node: "+entry.getClass().getName());
        result.put("entries",children);return result;
    }
}
