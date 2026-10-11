import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.world.*;
import com.threerings.config.*;
import com.threerings.presents.net.EventNotification;
import com.threerings.tudey.dobj.SceneDeltaEvent;

/** Small stock-reader oracle for the actual emitted reference argument shapes. */
public final class FireCompatibility {
    public static void main(String[] args)throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        var catalog=FireCatalog.load(Path.of(args[0]));var selected=new LinkedHashMap<String,FireCatalog.ActorSpec>();
        var effects=new LinkedHashMap<String,StarterCatalog.Model>();
        for(var attack:catalog.entries())for(var ref:attack.fireRefs())collect(ref.action(),selected,effects);
        int checked=0;
        for(var spec:selected.values()) {
            var orbit=spec.orbit()==null?null:new ProjectileState.Orbit(spec.orbit().radius(),0,.25f,.5f,0,.75f,51);
            var model=spec.config();var p=new ProjectileState(spec.nativeType(),model,1001,100,Integer.MAX_VALUE,0,0,ProjectileState.UNSPECIFIED_Z,spec.speed(),spec.maxStep(),51,false,orbit);
            var actor=new ActorState(71,1001,"",0,100,0,0,0,0,spec.flags(),0,100,model,false,null,p);
            var event=read(new SessionCodec().sceneDelta(1001,140,0,(short)0,100,100,(short)50,List.of(actor),List.of(),new int[0],Map.of(),true));
            check(field(event.aej()[0],"_config").equals(reference(model)),"exact projectile config arguments: "+model.name());checked++;
            if(orbit==null){
                Object nativeActor=event.aej()[0];
                check((float)field(nativeActor,"_z")==Float.NEGATIVE_INFINITY
                    &&!(boolean)nativeActor.getClass().getMethod("Rf").invoke(nativeActor),"native floor Z is unspecified: "+model.name());
            }
            if(orbit!=null){var nativeActor=event.aej()[0];
                check(nativeActor.getClass().getSimpleName().equals(p.nativeClass()),"native orbital class");
                check((int)field(nativeActor,"_homeTargetId")==51&&(int)field(nativeActor,"_clientOid")==1001&&(float)field(nativeActor,"_oRot")==.75f,"orbital ownership and original rotation");
                var ended=new ProjectileState(spec.nativeType(),model,1001,100,200,0,0,ProjectileState.UNSPECIFIED_Z,spec.speed(),spec.maxStep(),51,true,
                    new ProjectileState.Orbit(spec.orbit().radius(),0,1,2,175,.75f,51));
                var updated=new ActorState(71,1001,"",0,100,3,4,0,0,12,0,100,model,false,null,ended);
                var update=read(new SessionCodec().sceneDelta(1001,140,100,(short)0,100,200,(short)50,List.of(),List.of(updated),new int[0],Map.of(71,actor),true));
                var applied=update.aek()[0].apply(nativeActor);
                check((int)field(applied,"_collided")==175&&(int)field(applied,"_destroyed")==200&&(int)field(applied,"_flags")==12,"orbital collision/return/fizzle delta");
                check(field(applied,"_relativeTranslation").equals(new com.threerings.math.Vector2f(1,2)),"orbital relative position delta");
            }
        }
        for(var model:effects.values()) {
            var event=read(new SessionCodec().sceneDelta(1001,140,0,(short)0,100,100,(short)50,List.of(),List.of(),new int[0],Map.of(),true,List.of(new SceneEffect(1,model,100,1,2,0,1001))));
            Object array=null;for(var f:SceneDeltaEvent.class.getDeclaredFields())if(f.getType().isArray()&&f.getType().getComponentType().getName().endsWith(".Effect")){f.setAccessible(true);array=f.get(event);}
            check(array!=null&&field(Array.get(array,0),"_config").equals(reference(model)),"exact native effect arguments");checked++;
        }
        System.out.println("PASS: stock reader preserves "+checked+" representative real projectile/effect reference shapes");
    }
    static void collect(FireCatalog.Action action,Map<String,FireCatalog.ActorSpec> actors,Map<String,StarterCatalog.Model> effects){
        if(action==null)return;
        if(action.actor()!=null){var spec=action.actor();if(spec.nativeType().equals("BULLET")||spec.nativeType().equals("BOMB")||spec.orbit()!=null)actors.putIfAbsent(spec.nativeType()+shape(spec.config()),spec);collect(spec.detonation(),actors,effects);}
        if(action.kind()==FireCatalog.Kind.FIRE_EFFECT&&action.config()!=null)effects.putIfAbsent(shape(action.config()),action.config());
        for(var child:action.children())collect(child,actors,effects);
    }
    static String shape(Object value){
        if(value instanceof StarterCatalog.Model m)return shape(m.arguments());
        if(value instanceof FireCatalog.DataValue d)return d.type()+d.fields().stream().map(f->shape(f.value())).distinct().sorted().toList();
        if(value instanceof FireCatalog.EnumValue e)return e.type();
        if(value instanceof FireCatalog.ListValue l)return l.componentType()+l.values().stream().map(FireCompatibility::shape).distinct().sorted().toList();
        if(value instanceof Map<?,?> m)return m.values().stream().map(FireCompatibility::shape).distinct().sorted().toList().toString();
        return value.getClass().getName();
    }
    static SceneDeltaEvent read(byte[] frame)throws Exception{
        var bytes=new ByteArrayOutputStream();for(byte[] f:List.of(HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"),HandshakeCodec.authenticationResponse((short)2,true,"synthetic","Fixture"),frame))bytes.write(f,4,f.length-4);
        var in=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));in.qe();in.qe();var result=(SceneDeltaEvent)((EventNotification)in.qe()).yj();check(in.available()==0,"exact stream framing");return result;
    }
    static ConfigReference<?> reference(StarterCatalog.Model model)throws Exception {var result=new ConfigReference<>(model.name());for(var e:model.arguments().entrySet())result.kW().put(e.getKey(),stock(e.getValue()));return result;}
    @SuppressWarnings({"unchecked","rawtypes"}) static Object stock(Object value)throws Exception {
        if(value==FireCatalog.NullValue.INSTANCE)return null;
        if(value instanceof StarterCatalog.Model m)return reference(m);
        if(value instanceof FireCatalog.EnumValue e)return Enum.valueOf((Class)Class.forName(e.type()),e.name());
        if(value instanceof FireCatalog.ListValue l){Object array=Array.newInstance(Class.forName(l.componentType()),l.values().size());for(int i=0;i<l.values().size();i++)Array.set(array,i,stock(l.values().get(i)));return array;}
        if(value instanceof FireCatalog.DataValue d){var object=Class.forName(d.type()).getConstructor().newInstance();for(var f:d.fields()){Field field=find(object.getClass(),f.name());field.set(object,stock(f.value()));}return object;}
        return value;
    }
    static Field find(Class<?> c,String name)throws Exception{for(;c!=null;c=c.getSuperclass())try{var f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
    static Object field(Object object,String name)throws Exception{return find(object.getClass(),name).get(object);}
    static void check(boolean condition,String description){if(!condition)throw new AssertionError(description);}
}
