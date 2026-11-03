package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.resources.Json;
import static org.tortodrone.support.TestWire.check;

public final class WeightedTargetingTest {
    public static void main(String[] args)throws Exception {
        var a=new SceneTargeting.Mode(0,0,10,1,1,true,.1f);var b=new SceneTargeting.Mode(1,0,10,4,3,false,0);
        var policy=new SceneTargeting(8,1000,1999,List.of(a,b));
        check(policy.choose(1,draw(.1))==a&&policy.choose(1,draw(.9))==b,"authored positive weights select distinct path/facing operations");
        check(policy.choose(11,draw(.5))==null,"no eligible conditional mode yields no attack");
        var fields=new HashMap<String,String>(Map.of("native-type","com.threerings.projectx.dungeon.data.actor.Monster","kind","Monster","config","Fixture/Weighted","health","100","radius",".4","speed","0","turn-rate","0"));
        fields.putAll(Map.of("targeting.kind","weighted-path-attacks","targeting.expansion","8","targeting.evaluation","100","targeting.interval","1999"));
        fields.put("targeting.modes",Json.stringify(List.of(row(0,0,2,true),row(1,3,1000,false))));
        for(int slot=0;slot<2;slot++){
            String p="attack."+slot+".";fields.put(p+"clear","1000");fields.put(p+"land","0");fields.put(p+"fires","0");fields.put(p+"fire.0.delay","800");
            fields.put(p+"fire.0.action",FireCatalog.actionJson(new FireCatalog.Action(FireCatalog.Kind.FIRE_EFFECT,new StarterCatalog.Model("Fixture/Head"+slot,Map.of()),null,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(),"")));
        }
        // Head 1 is allowed to attack without facing; head 0 initially needs to face.
        fields.put("turn-rate","174.5");var content=new SceneContent(List.of(new SceneContent.Entry("enemy",0,0,0,new SceneContent.Spec(fields))),Map.of());
        var world=new SceneEntities(content,SceneWeaponsTest.openTerrain(),SceneCollision.empty(),new Random(7));
        var fires=new ArrayList<String>();world.attacks((actor,action)->fires.add(action.config().name()));
        for(int stamp=1000;stamp<5000;stamp+=50)world.advance(stamp,List.of(player(stamp<1200?1:4)));
        check(fires.size()>=2&&fires.get(0).equals("Fixture/Head0")&&fires.get(1).equals("Fixture/Head1"),"evaluation defers switching until the active head and its minimum interval finish");
        fields.put("turn-rate","0");fields.put("targeting.modes",Json.stringify(List.of(row(1,0,1000,false))));
        var unfaced=new SceneEntities(new SceneContent(List.of(new SceneContent.Entry("enemy",0,0,(float)Math.PI,new SceneContent.Spec(fields))),Map.of()),SceneWeaponsTest.openTerrain());
        var angles=new ArrayList<Float>();unfaced.attacks((actor,action)->angles.add(actor.rotation()));
        for(int stamp=1000;stamp<2500;stamp+=50)unfaced.advance(stamp,List.of(player(1)));
        check(!angles.isEmpty()&&Math.abs(Math.IEEEremainder(angles.getFirst()-Math.PI,2*Math.PI))<.001,"attackFacesTarget=false does not invent an instant facing snap");
        fields.put("turn-rate","1");
        var locked=new SceneEntities(new SceneContent(List.of(new SceneContent.Entry("enemy",0,0,0,new SceneContent.Spec(fields))),Map.of()),SceneWeaponsTest.openTerrain());
        locked.advance(1000,List.of(player(1)));locked.advance(1050,List.of(player(0,1)));
        check(locked.actors().getFirst().activity()==5&&locked.actors().getFirst().rotation()==0,"a non-blended attack locks rotation while its native activity runs");
        fields.put("attack.1.blended","true");
        var blended=new SceneEntities(new SceneContent(List.of(new SceneContent.Entry("enemy",0,0,0,new SceneContent.Spec(fields))),Map.of()),SceneWeaponsTest.openTerrain());
        blended.advance(1000,List.of(player(1)));blended.advance(1050,List.of(player(0,1)));
        check(Math.abs(blended.actors().getFirst().rotation()-.05f)<.001,"a blended attack follows the current target using one native turn-rate step");
        for(int cooldown:List.of(1499,2999)){
            var timed=new LinkedHashMap<>(row(1,0,1000,false));timed.put("interval",cooldown);fields.put("targeting.modes",Json.stringify(List.of(timed)));fields.put("targeting.interval","99999");
            var scoped=new SceneEntities(new SceneContent(List.of(new SceneContent.Entry("enemy",0,0,0,new SceneContent.Spec(fields))),Map.of()),SceneWeaponsTest.openTerrain());
            var starts=new ArrayList<Integer>();int previous=-1;
            for(int stamp=1000;stamp<9000;stamp+=50){scoped.advance(stamp,List.of(player(1)));var state=scoped.actors().getFirst();if(state.activity()>=4&&state.activityStarted()!=previous){starts.add(state.activityStarted());previous=state.activityStarted();}}
            check(starts.size()>1&&starts.get(1)-starts.get(0)==cooldown+1,"each chosen mode owns its actual minimum interval, overriding the policy fallback");
        }
        for(Object bad:List.of(-1,1.5,3_600_001,"1000")){
            var timed=new LinkedHashMap<>(row(1,0,1000,false));timed.put("interval",bad);fields.put("targeting.modes",Json.stringify(List.of(timed)));
            try{SceneTargeting.read(new SceneContent.Spec(fields));throw new AssertionError("Invalid mode cooldown accepted");}catch(IllegalArgumentException expected){}
        }
        var invalid=new LinkedHashMap<>(row(255,0,1000,false));invalid.put("weight",0);
        fields.put("targeting.modes",Json.stringify(List.of(row(0,0,1000,true),invalid)));
        try{SceneTargeting.read(new SceneContent.Spec(fields));throw new AssertionError("Unsupported zero-weight sibling ignored");}catch(IllegalArgumentException expected){}
        System.out.println("PASS: conditional weighted heads, active-head/cooldown preservation, non-facing attacks, no eligible mode and whole-policy validation");
    }
    private static Map<String,Object> row(int attack,float minimum,float maximum,boolean faces){return Map.of("attack",attack,"minimum",minimum,"maximum",maximum,"range",4,"weight",1,"faces",faces,"turn-snap",.1f);}
    private static ActorState player(float x){return player(x,0);}
    private static ActorState player(float x,float y){return new ActorState(219,1001,"Fixture",0,1,x,y,0,0,0,new StarterCatalog.Model("Player",Map.of()));}
    private static Random draw(double value){return new Random(0){@Override public double nextDouble(){return value;}};}
}
