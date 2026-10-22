import java.util.*;
import com.threerings.tudey.config.ActionConfig;
import org.tortodrone.world.FireCatalog;

/** Native Random graphs retain their weights, null alternatives and failure semantics. */
public final class ActionGraphCompatibility {
    public static void main(String[] args)throws Exception {
        var random=new ActionConfig.Random();random.actions=new ActionConfig.WeightedAction[3];
        for(int i=0;i<3;i++){random.actions[i]=new ActionConfig.WeightedAction();random.actions[i].weight=i;random.actions[i].action=null;}
        var converted=FireCatalogExport.readAction(null,random);
        require(converted.kind()==FireCatalog.Kind.RANDOM_WEIGHTED,"unequal native weights preserved");
        var weights=(FireCatalog.ListValue)converted.config().arguments().get("weights");
        require(weights.values().equals(List.of(0f,1f,2f)),"exact original weights, including zero");
        require(converted.children().size()==3&&converted.children().stream().allMatch(a->a.kind()==FireCatalog.Kind.COMPOUND),"null alternatives remain successful no-ops");
        for(var choice:random.actions)choice.weight=1;
        require(FireCatalogExport.readAction(null,random).kind()==FireCatalog.Kind.RANDOM_EQUAL,"equal positive weights keep uniform selection");
        for(var choice:random.actions)choice.weight=0;
        require(FireCatalogExport.readAction(null,random).kind()==FireCatalog.Kind.UNSUPPORTED,"all-zero table refused");
        random.actions[0].weight=Float.NaN;
        require(FireCatalogExport.readAction(null,random).kind()==FireCatalog.Kind.UNSUPPORTED,"non-finite weights refused");
        System.out.println("PASS: native weighted/uniform random export, null alternatives, zero weights and invalid-table refusal");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
