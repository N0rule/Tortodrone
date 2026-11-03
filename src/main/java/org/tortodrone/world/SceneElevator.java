package org.tortodrone.world;

/** Native standard elevator configuration; authority and travel belong to the party/session. */
public record SceneElevator(int advance,boolean completesGate) {
    public static final int ACTIVATE_MILLIS=1050,DROP_MILLIS=2400;
    public SceneElevator {if(advance!=1&&advance!=-1)throw new IllegalArgumentException("Invalid elevator direction");}
    public static SceneElevator read(SceneContent.Spec spec){
        if(!spec.fields().containsKey("elevator-mode"))return null;
        if(!spec.text("elevator-mode","").equals("standard")||!spec.text("kind","").equals("Elevator")
            ||!spec.text("native-type","").equals("com.threerings.tudey.data.actor.EntryState")||!spec.text("shape","").equals("Rectangle")
            ||spec.integer("collision-flags",-1)!=192||spec.integer("collision-mask",-1)!=0
            ||!Float.isFinite(spec.number("width",Float.NaN))||!Float.isFinite(spec.number("height",Float.NaN))
            ||spec.number("width",0)<=0||spec.number("height",0)<=0||spec.number("width",0)>100||spec.number("height",0)>100)throw new IllegalArgumentException("Unsupported native elevator");
        String complete=spec.text("elevator-completes","");if(!complete.equals("true")&&!complete.equals("false"))throw new IllegalArgumentException("Missing elevator completion policy");
        return new SceneElevator(spec.integer("elevator-advance",0),Boolean.parseBoolean(complete));
    }
}
