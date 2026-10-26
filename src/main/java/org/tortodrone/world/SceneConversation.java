package org.tortodrone.world;

import java.io.*;
import java.util.*;
import org.tortodrone.character.StarterCatalog;

/** Authored conversation graph; bindings and node jumps are prepared data, not guessed nodes. */
public record SceneConversation(String actor,String name,String bundle,String initial,
        StarterCatalog.Model model,Map<String,Node> nodes,StarterCatalog.Model sourceCloseAnimation,StarterCatalog.Model music) {
    public SceneConversation(String actor,String name,String bundle,String initial,StarterCatalog.Model model,Map<String,Node> nodes){this(actor,name,bundle,initial,model,nodes,null,null);}
    public static final int SERVICE=21;
    public record Response(String text,String destination,String station,String command,SceneActions.Action action) {
        public Response(String text,String destination,String station,String command){this(text,destination,station,command,null);}
        public Response(String text,String destination,String station){this(text,destination,station,"");}
        public Response(String text,String destination){this(text,destination,"");}
    }
    public record Node(String id,String message,List<Response> responses,
            StarterCatalog.Model animation,StarterCatalog.Model transientModel,StarterCatalog.Model userInterface) {
        public Node(String id,String message,List<Response> responses,StarterCatalog.Model animation,StarterCatalog.Model transientModel){this(id,message,responses,animation,transientModel,null);}
        public Node { responses=List.copyOf(responses); }
    }
    public SceneConversation {
        // Legacy exports named the server's NPC namespace; the retail bundle is conversation.
        if(bundle.equals("npc"))bundle="conversation";
        nodes=Map.copyOf(nodes);
    }
    static Map<String,SceneConversation> load(Properties p)throws IOException {
        var result=new TreeMap<String,SceneConversation>();
        for(String id:p.getProperty("dialogues","").split(",")){
            if(id.isBlank())continue;String prefix="dialogue."+id+".";
            var nodes=new LinkedHashMap<String,Node>();
            for(String n:required(p,prefix+"nodes").split(",")){
                String np=prefix+"node."+n+".";String name=required(p,np+"name");var responses=new ArrayList<Response>();
                int count=Integer.parseInt(required(p,np+"responses"));if(count<0||count>32)throw new IOException("Invalid conversation responses");
                for(int i=0;i<count;i++)responses.add(new Response(required(p,np+"response."+i+".text"),p.getProperty(np+"response."+i+".destination",""),p.getProperty(np+"response."+i+".station",""),p.getProperty(np+"response."+i+".command",""),action(p,np+"response."+i+".action")));
                var node=new Node(name,required(p,np+"message"),responses,model(p,np+"animation"),model(p,np+"transient"),model(p,np+"interface"));
                if(nodes.putIfAbsent(name,node)!=null)throw new IOException("Duplicate conversation node");
            }
            String initial=required(p,prefix+"initial");if(!nodes.containsKey(initial))throw new IOException("Conversation initial jump is absent");
            for(var node:nodes.values())for(var response:node.responses())if(!response.destination().isEmpty()&&!nodes.containsKey(response.destination()))throw new IOException("Broken conversation jump");
            String actor=required(p,prefix+"actor");
            var conversation=new SceneConversation(actor,required(p,prefix+"name"),required(p,prefix+"bundle"),initial,model(p,prefix+"model"),nodes,model(p,prefix+"source-close-animation"),model(p,prefix+"music"));
            if(result.putIfAbsent(actor,conversation)!=null)throw new IOException("Duplicate NPC binding");
        }
        return Map.copyOf(result);
    }
    private static SceneActions.Action action(Properties properties,String key)throws IOException {
        String value=properties.getProperty(key);return value==null?null:SceneActions.parse(value);
    }
    private static String required(Properties p,String key)throws IOException{String s=p.getProperty(key);if(s==null||s.isBlank()||s.length()>1024)throw new IOException("Missing conversation field: "+key);return s;}
    private static StarterCatalog.Model model(Properties p,String key)throws IOException{String s=p.getProperty(key);return s==null?null:FireCatalog.modelJson(s);}
}
