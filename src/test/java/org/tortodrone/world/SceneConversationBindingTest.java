package org.tortodrone.world;

import java.io.IOException;
import java.util.*;
import org.tortodrone.resources.Json;
import static org.tortodrone.support.TestWire.check;

/** Content ownership distinguishes real placed NPCs from reachable scene-started conversations. */
public final class SceneConversationBindingTest {
    public static void main(String[] args)throws Exception {
        String first="@conversation/First",last="@conversation/Last";
        var firstDefinition=definition(first,new SceneActions.Conversation(last));var lastDefinition=definition(last,null);
        String graph=Json.stringify(Map.of("kind","delay","millis",10,"action",Map.of("kind","limited","limit",1,"action",Map.of("kind","conversation","id",first,"activator",true),"otherwise",Map.of("kind","sequence","stopOnFailure",false,"actions",List.of()))));
        var trigger=new SceneContent.Entry("500",0,0,0,new SceneContent.Spec(Map.of("kind","SceneTrigger","config","Fixture/Trigger","event","signal","watch-tag","start","signal","activate","action",graph)));
        var good=content(List.of(trigger),Map.of(first,firstDefinition,last,lastDefinition));good.validateConversations();
        var initialDefinition=new SceneConversation(first,"n.fixture","conversation","first",null,definition(first,null).nodes(),null,null,new SceneActions.Conversation(last));
        content(List.of(trigger),Map.of(first,initialDefinition,last,lastDefinition)).validateConversations();
        reject(()->content(List.of(trigger),Map.of(first,initialDefinition)).validateConversations(),"Missing scripted conversation");
        reject(()->content(List.of(trigger),Map.of(first,firstDefinition)).validateConversations(),"Missing scripted conversation");
        reject(()->content(List.of(trigger),Map.of(first,definition(first,null),last,lastDefinition)).validateConversations(),"Unreferenced scripted conversation");
        reject(()->content(List.of(),Map.of(first,firstDefinition,last,definition(last,new SceneActions.Conversation(first)))).validateConversations(),"Unreferenced scripted conversation");
        var npc=definition("Fixture/NPC",null);
        var actor=new SceneContent.Entry("600",0,0,0,new SceneContent.Spec(Map.of("kind","Stranger","config",npc.actor())));
        var marker=new SceneContent.Entry("600",0,0,0,new SceneContent.Spec(Map.of("kind","SceneAnchor","config",npc.actor(),"source-tags","location")));
        content(List.of(actor,marker),Map.of(npc.actor(),npc)).validateConversations();
        reject(()->content(List.of(marker),Map.of(npc.actor(),npc)).validateConversations(),"Missing or ambiguous NPC actor binding");
        reject(()->content(List.of(actor,actor),Map.of(npc.actor(),npc)).validateConversations(),"Missing or ambiguous NPC actor binding");
        check(SceneActions.conversationIds(SceneActions.parse(graph)).equals(Set.of(first)),"nested authored conversation references are retained");
        System.out.println("PASS: scene-started dialogue reachability, response follow-ups, missing/unreachable graph refusal, placed NPC ownership and passive marker exclusion");
    }
    private static SceneConversation definition(String actor,SceneActions.Action action){return new SceneConversation(actor,"n.fixture","conversation","first",null,Map.of("first",new SceneConversation.Node("first","m.fixture",List.of(new SceneConversation.Response("r.fixture","","","",action)),null,null)));}
    private static SceneContent content(List<SceneContent.Entry> entries,Map<String,SceneConversation> conversations){return new SceneContent(entries,Map.of(),conversations,Map.of(),Map.of(),Map.of(),Map.of());}
    private interface Action{void run()throws Exception;}
    private static void reject(Action action,String message)throws Exception{try{action.run();throw new AssertionError("Invalid conversation binding accepted");}catch(IOException expected){check(expected.getMessage().contains(message),"precise conversation binding error");}}
}
