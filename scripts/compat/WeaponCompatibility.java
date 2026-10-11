import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.world.*;
import com.threerings.presents.net.EventNotification;
import com.threerings.tudey.dobj.SceneDeltaEvent;
import com.threerings.projectx.dungeon.data.actor.Dungeoneer;

/** Small unchanged-stock-reader check for native weapon state and its delta mask. */
public final class WeaponCompatibility {
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Usage: WeaponCompatibility item-catalog weapon-catalog");
        var items=ItemCatalog.load(Path.of(args[0]));var weapons=WeaponCatalog.load(Path.of(args[1]));
        int checked=0;
        for(var slot:List.of(WorldProfile.Slot.SWORD,WorldProfile.Slot.GUN,WorldProfile.Slot.BOMB)) {
            var entry=items.entries().stream().filter(e->e.slot()==slot&&e.grantable()&&weapons.find(e.config())!=null).findFirst().orElseThrow();
            var shield=weapons.shields().stream().filter(s->s.health()>0).findFirst().orElseThrow();
            var item=new WorldProfile.Item(45,slot,entry.config(),null,0,6,true);
            var first=new KnightCombat.State(item,shield.config(),null,shield.health(),1,700,0,null);
            var changed=new KnightCombat.State(null,null,null,0,2,800,1000,new KnightCombat.Impulse(150,625,5,0));
            var model=new StarterCatalog.Model("Character/PC/Level",Map.of());
            var a=new ActorState(51,1001,"Fixture",257,100,0,0,0,0,0,292,100,model,false,first);
            var b=new ActorState(51,1001,"Fixture",257,100,1,0,1,0,0,286,150,model,false,changed);
            var codec=new SessionCodec(items);var bytes=new ByteArrayOutputStream();
            for(byte[] frame:List.of(HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"),
                HandshakeCodec.authenticationResponse((short)2,true,"synthetic","Fixture"),
                codec.sceneDelta(1001,140,0,(short)0,100,100,(short)50,List.of(a),List.of(),new int[0],Map.of(),true),
                codec.sceneDelta(1001,140,100,(short)0,100,150,(short)50,List.of(),List.of(b),new int[0],Map.of(51,a),true)))bytes.write(frame,4,frame.length-4);
            var in=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));in.qe();in.qe();
            var add=(SceneDeltaEvent)((EventNotification)in.qe()).yj();
            var nativeActor=(Dungeoneer)add.aej()[0];
            var nativeItem=(com.threerings.projectx.item.data.LevelItem)field(nativeActor,"_weapon");
            check(nativeItem!=null&&nativeItem.Vx().getName().equals(entry.config()),"native equipped weapon");
            check(field(nativeActor,"_shield").equals(shield.config())&&(int)field(nativeActor,"_shieldMaxHealth")==shield.health(),"native shield fields");
            var update=(SceneDeltaEvent)((EventNotification)in.qe()).yj();
            var applied=(Dungeoneer)update.aek()[0].apply(nativeActor);
            check(field(applied,"_weapon")==null&&field(applied,"_shield")==null,"delta clears old weapon and shield");
            check((int)field(applied,"_chainCount")==2&&(int)field(applied,"_chainResetTime")==800&&(int)field(applied,"_nextDodge")==1000,"delta updates chains and dodge");
            Object impulse=field(applied,"_impulse");
            check(impulse!=null&&(int)field(impulse,"_started")==150&&(int)field(impulse,"_duration")==625,"native dodge impulse");
            check((int)field(applied,"_activity")==286&&in.available()==0,"exact stock stream framing");checked++;
        }
        projectiles(items);
        System.out.println("PASS: stock reader accepts sword, gun and bomb actor state, shield, impulse and combat deltas ("+checked+" cases)");
    }
    private static void projectiles(ItemCatalog items)throws Exception {
        var codec=new SessionCodec(items);var config=new StarterCatalog.Model("Fixtures/Projectile",Map.of());
        var bulletState=new ProjectileState("BULLET",config,1001,100,Integer.MAX_VALUE,0,0,1,10,.1f,51,false);
        var bombState=new ProjectileState("BOMB",config,1001,100,Integer.MAX_VALUE,0,0,0,0,0,51,false);
        var bullet=new ActorState(71,1001,"",0,100,1,2,.5f,.5f,2,0,100,config,false,null,bulletState);
        var bomb=new ActorState(72,1001,"",0,100,2,3,1,1,0,0,100,config,false,null,bombState);
        var endedBullet=new ActorState(71,1001,"",0,100,3,4,.5f,.5f,6,0,100,config,false,null,
            new ProjectileState("BULLET",config,1001,100,200,0,0,1,10,.1f,51,true));
        var endedBomb=new ActorState(72,1001,"",0,100,2,3,1,1,0,0,100,config,false,null,
            new ProjectileState("BOMB",config,1001,100,200,0,0,0,0,0,51,false));
        var bytes=new ByteArrayOutputStream();
        for(byte[] frame:List.of(HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"),
            HandshakeCodec.authenticationResponse((short)2,true,"synthetic","Fixture"),
            codec.sceneDelta(1001,140,0,(short)0,100,100,(short)50,List.of(bullet,bomb),List.of(),new int[0],Map.of(),true,
                List.of(new SceneEffect(1,config,100,1,2,.5f,1001))),
            codec.sceneDelta(1001,140,100,(short)0,100,200,(short)50,List.of(),List.of(endedBullet,endedBomb),new int[0],Map.of(71,bullet,72,bomb),true)))bytes.write(frame,4,frame.length-4);
        var in=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));in.qe();in.qe();
        var add=(SceneDeltaEvent)((EventNotification)in.qe()).yj();
        var nativeBullet=add.aej()[0];var nativeBomb=add.aej()[1];
        check(nativeBullet.getClass().getSimpleName().equals("Bullet")&&nativeBomb.getClass().getSimpleName().equals("Bomb"),"native projectile classes");
        check((int)field(nativeBullet,"_faction")==51&&(int)field(nativeBullet,"_clientOid")==1001&&Math.abs((float)field(nativeBullet,"_z")-1)<.001,"bullet source/height");
        var update=(SceneDeltaEvent)((EventNotification)in.qe()).yj();
        var appliedBullet=update.aek()[0].apply(nativeBullet);var appliedBomb=update.aek()[1].apply(nativeBomb);
        check((int)field(appliedBullet,"_destroyed")==200&&(int)field(appliedBullet,"_flags")==6,"bullet destroyed/fizzle delta");
        check((int)field(appliedBomb,"_destroyed")==200&&in.available()==0,"bomb destroyed delta and effect stream framing");
        System.out.println("PASS: stock reader accepts native bullet, bomb, destruction deltas and scene effect");
    }
    private static Object field(Object object,String name)throws Exception {
        for(Class<?> c=object.getClass();c!=null;c=c.getSuperclass())try{var f=c.getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(name);
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
