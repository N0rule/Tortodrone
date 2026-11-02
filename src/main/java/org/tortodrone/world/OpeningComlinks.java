package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.OpeningMission;
import org.tortodrone.character.StarterCatalog;

/** Two captured opening comlinks. Only localized/configuration references, no client assets.
 * Edges/presentation: archive dialogue-branch-witnesses.json, live 2026-09-10.
 * Welcome's unobserved 5a/0 retains the native close action and cannot complete the card. */
public final class OpeningComlinks {
    private OpeningComlinks() { }
    private static final Map<OpeningMission,SceneConversation> DEFINITIONS=Map.of(
        OpeningMission.FIRST_CONTACT,definition("first_contact_b",new String[]{"","mission_1-1/rhen-raz-artifact.png","","mission_1-1/gremlin.png","mission_1-1/haven.png","mission_1-1/haven.png"}),
        OpeningMission.WELCOME_HAVEN,definition("welcome_to_haven",new String[]{"","mission_2-1/stranger.png","","mission_2-1/useenergy.png","mission_2-1/crsytalenergy_en.png",""}));
    public static SceneConversation get(OpeningMission mission){return DEFINITIONS.get(mission);}
    public static int scene(OpeningMission mission){return mission==OpeningMission.FIRST_CONTACT?218:mission==OpeningMission.WELCOME_HAVEN?1:0;}
    public static boolean owns(OpeningMission mission,SceneConversation definition){return mission!=null&&DEFINITIONS.get(mission)==definition;}
    public static boolean terminal(OpeningMission mission,SceneConversation definition,String node,int response){return owns(mission,definition)&&node.equals(mission==OpeningMission.FIRST_CONTACT?"node_end":"part 6a")&&response==0;}
    private static String node(String id,int index){return id.equals("first_contact_b")?(index==6?"node_end":"node_"+index):"part "+index+"a";}
    private static SceneConversation definition(String id,String[] images){
        var nodes=new LinkedHashMap<String,SceneConversation.Node>();
        for(int i=1;i<=6;i++){
            String node=node(id,i),key=id+"_"+i+"a",next=i==6?"":node(id,i+1);
            var responses=id.equals("welcome_to_haven")&&i==5?List.of(new SceneConversation.Response("r."+key,""),new SceneConversation.Response("r."+id+"_5b",next)):List.of(new SceneConversation.Response("r."+key,next));
            var args=new HashMap<String,Object>();args.put("Variant","Default");args.put("image",images[i-1].isEmpty()?FireCatalog.NullValue.INSTANCE:"ui/conversation/images/"+images[i-1]);
            nodes.put(node,new SceneConversation.Node(node,"m."+key,responses,null,null,new StarterCatalog.Model("ui/conversation/interface_comlink.dat",args)));
        }
        return new SceneConversation("@comlink/"+id,"n."+id,"mission",node(id,1),new StarterCatalog.Model("character/npc/crew/model_kora.dat",Map.of("Variant","comlink")),nodes,null,new StarterCatalog.Model("Custom/Mission/Comlink",Map.of()));
    }
}
