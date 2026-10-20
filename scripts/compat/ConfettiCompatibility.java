import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.projectx.item.config.ItemConfig;
import com.threerings.presents.dobj.*;
import com.threerings.presents.net.*;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.resources.ItemYaml;
import org.tortodrone.world.*;

/** Lightweight, read-only stock config/reader oracle; no server or graphical app. */
public final class ConfettiCompatibility {
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Usage: ConfettiCompatibility game-directory");
        var game=Path.of(args[0]).toRealPath();
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,game.resolve("rsrc").toString());
        var manager=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");manager.init();
        var items=ItemYaml.load(Path.of("content/items"));int variants=0;
        for(var config:manager.bd(ItemConfig.class).kK())if(config.getName().startsWith("Usable/Confetti")){
            check(Confetti.supports(config.getName())&&items.require(config.getName()).type().equals("AnimatedAction"),"all native confetti variants included");
            var action=(ItemConfig.AnimatedAction)config.w(manager);
            check(action.land==Confetti.LAND_MILLIS&&action.action instanceof com.threerings.tudey.config.ActionConfig.FireEffect f&&f.effect==null,"native land time and no extra server-fired effect");
            for(int stamp:new int[]{0,1,1000,13220154,13220155,987654321,Integer.MAX_VALUE,-1000})
                check(Confetti.duration(config.getName(),stamp)==action.land+action.fn(stamp).clear,"timestamp-seeded animation duration matches unchanged client: "+config.getName());
            variants++;
        }
        check(variants==14,"fourteen native confetti configs verified");
        var map=SceneCatalog.load(Path.of("content/maps")).scene(1);
        var area=map.content().entries().stream().filter(e->e.key().equals("3733")&&e.spec().text("town-kind","").equals("OpenAuction")).findFirst().orElseThrow();
        var building=manager.d(com.threerings.tudey.config.PlaceableConfig.class,"Prop/Haven/Auction House/Setpiece, building interior").q(manager);
        var nativeShape=building.shape.acP().d(new com.threerings.math.Transform2D(new com.threerings.math.Vector2f(1,18.5f),0));
        var disc=new com.threerings.tudey.shape.config.ShapeConfig.Circle();disc.radius=.4f;
        for(float[] point:new float[][]{{1,14},{6,14},{7,14},{1,18},{1,10},{-4,14},{0.6f,15.3f}}){
            boolean expected=nativeShape.b(disc.acP().d(new com.threerings.math.Transform2D(new com.threerings.math.Vector2f(point[0],point[1]),0)));
            check(area.spec().contains(area.x(),area.y(),area.rotation(),point[0],point[1],.4f)==expected,"auction interaction capsule matches native shape at "+Arrays.toString(point));
        }
        var codec=new SessionCodec(items);var bytes=new ByteArrayOutputStream();
        append(bytes,HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"));
        append(bytes,HandshakeCodec.authenticationResponse((short)2,true,"synthetic","Alice"));
        var data=new SceneKnightState(new StarterCatalog.Model("Usable/Confetti, Dark Harvest",Map.of()),0);
        var actor=new ActorState(42,1001,"Test-Knight",256,1,0,0,0,0,0,267,1000,new StarterCatalog.Model("Character/PC/Default",Map.of()),false,null,null,null,null,data);
        append(bytes,codec.sceneDelta(1001,10,0,(short)0,0,1000,(short)50,List.of(actor),List.of(),new int[0],Map.of(),false));
        append(bytes,codec.sceneDelta(1001,10,0,(short)0,1000,1050,(short)50,List.of(),List.of(actor),new int[0],Map.of(42,actor),false));
        append(bytes,codec.sceneDelta(1001,11,0,(short)0,0,1000,(short)50,List.of(actor),List.of(),new int[0],Map.of(),true));
        var source=new ActorState(1000000000,0,"",0,1,1,18.5f,0,0,0,0,1,new StarterCatalog.Model("Dynamic/Misc/Interact Node/Base",Map.of()),false,null,null,new SceneEntityState("Actor",Integer.MAX_VALUE,0,0,-1,0,0,0,0,"3733"));
        append(bytes,codec.controllerAction(1001,"auction",source));
        append(bytes,codec.invocationFailure(1001,(short)8,"e.not_use_busy"));
        var in=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));in.qe();in.qe();
        var added=(com.threerings.tudey.dobj.SceneDeltaEvent)((EventNotification)in.qe()).yj();
        var knight=(com.threerings.projectx.data.actor.Knight)added.aej()[0];
        var changed=(com.threerings.tudey.dobj.SceneDeltaEvent)((EventNotification)in.qe()).yj();
        knight=(com.threerings.projectx.data.actor.Knight)changed.aek()[0].apply(knight);
        check(knight.adU()==267&&knight.bZ(ItemConfig.class).getName().equals("Usable/Confetti, Dark Harvest"),"native confetti activity and config reference survive actor/delta");
        var interior=(com.threerings.tudey.dobj.SceneDeltaEvent)((EventNotification)in.qe()).yj();
        var dungeon=(com.threerings.projectx.dungeon.data.actor.Dungeoneer)interior.aej()[0];
        check(dungeon.adU()==267&&dungeon.bZ(ItemConfig.class).getName().equals("Usable/Confetti, Dark Harvest"),"same native activity in levels");
        var message=(MessageEvent)((EventNotification)in.qe()).yj();
        check(message.getName().equals("forceClientAction"),"captured controller action message");
        var arguments=message.yd();
        check(arguments.length==2&&arguments[0] instanceof com.threerings.tudey.config.ClientActionConfig.ControllerAction a&&a.command.equals("auction")&&a.argument.equals(""),"native auction command and empty argument");
        check(arguments[1] instanceof com.threerings.tudey.data.EntityKey.Entry e&&e.getKey().equals(3733),"auction source is the captured placed entry key");
        var failure=(InvocationResponseEvent)((EventNotification)in.qe()).yj();
        check(failure.yc()==0&&failure.yd()[0].equals("e.not_use_busy"),"busy refusal stays a native localized item key");
        check(in.available()==0,"no trailing bytes");
        System.out.println("PASS: 14 stock confetti configs, timestamp-seeded durations, Knight/Dungeoneer activity layouts, native auction shape/command/entry source and localized busy callback");
    }
    private static void append(ByteArrayOutputStream out,byte[] frame){out.write(frame,4,frame.length-4);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
