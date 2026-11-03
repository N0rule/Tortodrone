package org.tortodrone.world;

import java.io.IOException;
import java.util.*;

/** Scene-local entity definitions; cycles are references, never recursive projectile payloads. */
final class SceneActorTemplates {
    private SceneActorTemplates() { }
    static void validate(List<SceneContent.Entry> entries,Map<String,SceneContent.Spec> templates) {
        if(templates.size()>128)throw new IllegalArgumentException("Too many scene actor templates");
        for(var entry:templates.entrySet()){
            if(!entry.getKey().matches("spawn_[0-9]{1,3}")||!entry.getValue().text("native-type","").equals("com.threerings.projectx.dungeon.data.actor.Monster"))
                throw new IllegalArgumentException("Unsupported scene actor template");
            if(entry.getValue().fields().containsKey("startup-action"))throw new IllegalArgumentException("Unimplemented scene actor startup graph");
            new SceneActions.Spawn(entry.getValue().fields());
        }
        for(var entry:entries)validate(entry.spec(),templates,0,false);
        for(var spec:templates.values())validate(spec,templates,0,true);
    }
    private static void validate(SceneContent.Spec spec,Map<String,SceneContent.Spec> templates,int depth,boolean strict) {
        if(depth>16)throw new IllegalArgumentException("Nested scene actor lifecycle exceeds limit");
        for(String key:List.of("death-action")){
            String graph=spec.text(key,"");if(graph.isEmpty())continue;
            try{validate(FireCatalog.actionJson(graph),templates,0,new int[]{0},strict);}
            catch(IOException invalid){throw new IllegalArgumentException("Invalid scene actor lifecycle",invalid);}
        }
        if(!spec.child().fields().isEmpty())validate(spec.child(),templates,depth+1,strict);
    }
    private static void validate(FireCatalog.Action action,Map<String,SceneContent.Spec> templates,int depth,int[] count,boolean strict) {
        if(action==null)return;
        if(depth>64||++count[0]>10000)throw new IllegalArgumentException("Oversized scene actor lifecycle");
        if(action.kind()==FireCatalog.Kind.SPAWN_SCENE_ACTOR&&!templates.containsKey(action.config().arguments().get("template")))
            throw new IllegalArgumentException("Unresolved scene actor template");
        if(strict&&Set.of(FireCatalog.Kind.UNSUPPORTED,FireCatalog.Kind.SPAWN_ACTOR,FireCatalog.Kind.SPAWN_FACING,FireCatalog.Kind.RANDOM_TRANSLATED).contains(action.kind()))
            throw new IllegalArgumentException("Unimplemented scene actor lifecycle action: "+action.kind()+" "+action.unsupportedType());
        for(var child:action.children())validate(child,templates,depth+1,count,strict);
        validate(action.actor(),templates,depth+1,count,strict);
    }
    private static void validate(FireCatalog.ActorSpec actor,Map<String,SceneContent.Spec> templates,int depth,int[] count,boolean strict) {
        if(actor==null)return;
        if(depth>64||++count[0]>10000)throw new IllegalArgumentException("Oversized scene actor lifecycle");
        validate(actor.startup(),templates,depth+1,count,strict);validate(actor.detonation(),templates,depth+1,count,strict);
        validate(actor.shutdown(),templates,depth+1,count,strict);validate(actor.activation(),templates,depth+1,count,strict);
        validate(actor.attachment(),templates,depth+1,count,strict);
        for(var timer:actor.timers()){validate(timer.action(),templates,depth+1,count,strict);validate(timer.endAction(),templates,depth+1,count,strict);}
    }
}
