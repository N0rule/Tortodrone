package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.storage.Database;
import org.tortodrone.world.*;
import org.tortodrone.resources.Json;
import static org.tortodrone.support.TestWire.check;

/** Synthetic authored sensor uses real accounts, sessions, native response requests and scene services. */
public final class ScriptedConversationSessionTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-scripted-dialog-");
        try(var accounts=new AccountStore(home,false,ItemCatalog.starter())){
            accounts.create("Alice","alice-password".toCharArray());accounts.create("Bob","bob-password".toCharArray());
            var firstCharacters=new CharacterStore(new Database(home,false),1,"Alice");var secondCharacters=new CharacterStore(new Database(home,false),2,"Bob");
            var firstKnight=firstCharacters.create(knight("Dialog-One"));var secondKnight=secondCharacters.create(knight("Dialog-Two"));
            var original=SceneRegistry.system().entry().map();
            String id="@conversation/Fixture";
            var nodes=Map.of("first",new SceneConversation.Node("first","m.first",List.of(new SceneConversation.Response("r.next","last")),null,null),
                "last",new SceneConversation.Node("last","m.last",List.of(new SceneConversation.Response("r.finish","","","",new SceneActions.Signal("boss","activate"))),null,null));
            var definition=new SceneConversation(id,"n.fixture","conversation","first",null,nodes);
            String start=Json.stringify(Map.of("kind","delay","millis",100,"action",Map.of("kind","conversation","id",id,"activator",true)));
            var area=new SceneContent.Entry("500",original.spawnX(),original.spawnY(),0,new SceneContent.Spec(Map.ofEntries(Map.entry("config","Fixture/Area"),Map.entry("kind","SceneTrigger"),Map.entry("event","signal-start"),Map.entry("watch-tag","area:500"),Map.entry("signal","intersection"),Map.entry("scene-sensor","true"),Map.entry("sensor-mask","4"),Map.entry("width","2"),Map.entry("height","2"),Map.entry("action",start))));
            var boss=new SceneContent.Entry("501",0,0,0,new SceneContent.Spec(Map.of("config","Fixture/Signal","kind","SceneTrigger","event","signal","watch-tag","boss","signal","activate","action","{\"kind\":\"signal\",\"tag\":\"door\",\"name\":\"toggle\"}")));
            var door=new SceneContent.Entry("door",original.spawnX()+5,original.spawnY(),0,new SceneContent.Spec(Map.of("config","Fixture/Door","native-type","com.threerings.projectx.dungeon.data.actor.Door","kind","Door","tags","door","width","1","height","1","collision-flags","1","door-speed","0")));
            var content=new SceneContent(List.of(area,boss,door),original.content().scales(),Map.of(id,definition),original.content().stats(),Map.of(),Map.of(),original.content().statuses());
            var map=new SceneCatalog.MapScene(900001,16777217,"%town:m.haven","Fixture",original.image(),"town",original.version(),original.spawnX(),original.spawnY(),0,1,original.collision(),original.layout(),ScenePortals.EMPTY,content);
            var maps=new TreeMap<Integer,SceneCatalog.MapScene>();maps.put(map.id(),map);var registry=new SceneRegistry(SceneCatalog.of(maps,map.id()));
            var socket1=new DiscardSocket();var socket2=new DiscardSocket();
            try(var output1=new SessionOutput(socket1);var output2=new SessionOutput(socket2);
                    var first=new WorldSession(socket1,firstCharacters,output1,new SessionCodec(),1001,1,message->{},registry);
                    var second=new WorldSession(socket2,secondCharacters,output2,new SessionCodec(),1002,1,message->{},registry)){
                var receiver=Map.of(SceneRegistry.ZONE_RECEIVER,(short)0);first.select(firstKnight.name(),receiver);second.select(secondKnight.name(),receiver);
                int oid=registry.entry().oid();
                refuse(()->first.conversationInvocation(response(oid,0,10)));
                first.subscribe((short)1);first.entered();
                await(()->"first".equals(node(first)));
                check(node(second)==null,"unentered peer receives no scripted dialogue");
                check(!first.conversationInvocation(response(oid+1,0,9)),"wrong scene cannot submit conversation responses");
                first.conversationInvocation(response(oid,99,9));check("first".equals(node(first)),"invalid choice preserves the active node");
                first.conversationInvocation(response(oid,0,10));check("last".equals(node(first)),"native response advances the authored jump");
                first.conversationInvocation(response(oid,0,10));check("last".equals(node(first))&&!opened(registry),"duplicate request cannot answer the next node or start the encounter");
                first.conversationInvocation(response(oid,0,11));await(()->opened(registry));
                check(node(first)==null,"terminal action closes the answered native panel");
                first.conversationInvocation(response(oid,0,11));
                int afterDuplicate=registry.entry().simulation().snapshot().timestamp();
                await(()->registry.entry().simulation().snapshot().timestamp()>afterDuplicate);
                check(opened(registry),"duplicate terminal request cannot toggle progression twice");
                refuse(()->first.conversationInvocation(response(oid,0,12)));
                second.subscribe((short)2);second.entered();await(()->"first".equals(node(second)));
                check(node(first)==null,"delayed Activator conversation stays with the entering knight");
                check(registry.entry().simulation().snapshot().actors().stream().noneMatch(actor->actor.id()==0),"entry presentation never creates a phantom actor");
                first.unsubscribe();check(!first.conversationInvocation(response(oid+1,0,13)),"departed ownership cannot target another scene");
            }
            check(registry.entry().simulation().playerCount()==0,"disconnect cleans conversation ownership and scene occupants");
        }finally{try(var paths=Files.walk(home)){for(var file:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
        System.out.println("PASS: authored sensor -> delayed Activator dialogue -> native response -> scene action, ownership, invalid choice, duplicate response/terminal and cleanup");
    }
    private static Knight knight(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0);}
    private static SessionDecoder.Invocation response(int oid,int choice,int request){return new SessionDecoder.Invocation(oid,SceneConversation.SERVICE,1,List.of(choice,new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)request)));}
    private static String node(WorldSession world)throws Exception{synchronized(world){var field=WorldSession.class.getDeclaredField("conversationNode");field.setAccessible(true);return (String)field.get(world);}}
    private static boolean opened(SceneRegistry registry){return registry.entry().simulation().snapshot().actors().stream().filter(actor->actor.entity()!=null&&actor.entity().kind().equals("Door")).anyMatch(actor->(actor.flags()&2)!=0);}
    private static void await(Check condition)throws Exception{long until=System.nanoTime()+4_000_000_000L;while(System.nanoTime()<until){if(condition.ok())return;Thread.sleep(10);}throw new AssertionError("Timed out waiting for scripted conversation state");}
    private static void refuse(Operation operation)throws Exception{try{operation.run();throw new AssertionError("Expected conversation refusal");}catch(IOException expected){}}
    private interface Operation{void run()throws Exception;}private interface Check{boolean ok()throws Exception;}
    private static final class DiscardSocket extends Socket{@Override public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}}
}
