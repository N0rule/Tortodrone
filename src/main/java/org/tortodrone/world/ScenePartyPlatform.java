package org.tortodrone.world;

import java.io.IOException;

/** Prepared native PartyIntersection switch. Counts and action receipts belong to its entity. */
public record ScenePartyPlatform(SceneActions.Action intersection,SceneActions.Action pressed) {
    public static ScenePartyPlatform read(SceneContent.Spec spec){
        if(!spec.text("switch-mode","").equals("party"))return null;
        if(!spec.text("kind","").equals("Switch")||!spec.text("native-type","").equals("com.threerings.projectx.dungeon.data.actor.Switch")
            ||spec.integer("collision-flags",-1)!=0||spec.integer("collision-mask",-1)!=0||!spec.text("shape","").equals("Rectangle")
            ||!Float.isFinite(spec.number("width",Float.NaN))||!Float.isFinite(spec.number("height",Float.NaN))||spec.number("width",0)<=0||spec.number("height",0)<=0
            ||spec.number("width",0)>100||spec.number("height",0)>100)throw new IllegalArgumentException("Invalid native party platform");
        try{return new ScenePartyPlatform(SceneActions.parse(spec.text("party-intersection-action","")),SceneActions.parse(spec.text("state.1.scene-action","")));}
        catch(IOException invalid){throw new IllegalArgumentException("Invalid party platform action",invalid);}
    }
}
