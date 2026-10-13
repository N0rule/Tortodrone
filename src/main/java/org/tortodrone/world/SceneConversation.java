package org.tortodrone.world;

import java.io.*;
import java.util.*;
import org.tortodrone.character.StarterCatalog;

/** Authored conversation graph; bindings and node jumps are prepared data, not guessed nodes. */
public record SceneConversation(String actor,String name,String bundle,String initial,
        StarterCatalog.Model model,Map<String,Node> nodes) {
    public static final int SERVICE=21;
    public record Response(String text,String destination) { }
    public record Node(String id,String message,List<Response> responses,
            StarterCatalog.Model animation,StarterCatalog.Model transientModel) {
        public Node { responses=List.copyOf(responses); }
    }
    public SceneConversation { nodes=Map.copyOf(nodes); }
    static Map<String,SceneConversation> load(Properties p)throws IOException {
        var result=new TreeMap<String,SceneConversation>();
        for(String id:p.getProperty("dialogues","").split(",")){
            if(id.isBlank())continue;String prefix="dialogue."+id+".";
            var nodes=new LinkedHashMap<String,Node>();
            for(String n:required(p,prefix+"nodes").split(",")){
                String np=prefix+"node."+n+".";String name=required(p,np+"name");var responses=new ArrayList<Response>();
                int count=Integer.parseInt(required(p,np+"responses"));if(count<0||count>32)throw new IOException("Invalid conversation responses");
                for(int i=0;i<count;i++)responses.add(new Response(required(p,np+"response."+i+".text"),p.getProperty(np+"response."+i+".destination","")));
                var node=new Node(name,required(p,np+"message"),responses,model(p,np+"animation"),model(p,np+"transient"));
                if(nodes.putIfAbsent(name,node)!=null)throw new IOException("Duplicate conversation node");
            }
            String initial=required(p,prefix+"initial");if(!nodes.containsKey(initial))throw new IOException("Conversation initial jump is absent");
            for(var node:nodes.values())for(var response:node.responses())if(!response.destination().isEmpty()&&!nodes.containsKey(response.destination()))throw new IOException("Broken conversation jump");
            String actor=required(p,prefix+"actor");
            var conversation=new SceneConversation(actor,required(p,prefix+"name"),required(p,prefix+"bundle"),initial,model(p,prefix+"model"),nodes);
            if(result.putIfAbsent(actor,conversation)!=null)throw new IOException("Duplicate NPC binding");
        }
        return Map.copyOf(result);
    }
    private static String required(Properties p,String key)throws IOException{String s=p.getProperty(key);if(s==null||s.isBlank()||s.length()>1024)throw new IOException("Missing conversation field: "+key);return s;}
    private static StarterCatalog.Model model(Properties p,String key)throws IOException{String s=p.getProperty(key);return s==null?null:FireCatalog.modelJson(s);}
}
