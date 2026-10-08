import java.io.*;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.world.*;
import com.threerings.presents.net.*;
import com.threerings.presents.dobj.*;
import com.threerings.projectx.data.*;
import com.threerings.projectx.town.data.TownSceneObject;
import com.threerings.tudey.dobj.*;

/** Optional independent reader fixture using the read-only installed client. */
public final class WorldCompatibility {
    private static void append(ByteArrayOutputStream stream, byte[] frame) {
        stream.write(frame,4,frame.length-4);
    }
    public static void main(String[] args) throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        var knight=new org.tortodrone.character.Knight(1,"World-Knight","Gear/Helm/Spiral Sallet (Blue)",
            "Gear/Armor/Spiral Culet (Blue)","Helm/Top/Headband/Headband",null,"Blue",1,2,0,1);
        var items=List.of(
            new WorldProfile.Item(1,WorldProfile.Slot.HELM,knight.helm(),knight.helmAccessory(),0,0),
            new WorldProfile.Item(2,WorldProfile.Slot.ARMOR,knight.armor(),null,0,1),
            new WorldProfile.Item(3,WorldProfile.Slot.SWORD,"Weapon/Sword/Proto Sword",null,0,6),
            new WorldProfile.Item(4,WorldProfile.Slot.GUN,"Weapon/Handgun/Proto Gun",null,0,7),
            new WorldProfile.Item(5,WorldProfile.Slot.SHIELD,"Gear/Shield/Proto Shield",null,0,2));
        var profile=new WorldProfile(10,1,1,120,34,1,items);
        var model=StarterCatalog.equipped(knight,profile);
        var state=new ActorState(10,1001,knight.name(),257,100,0,-8,0,0,0,
            new StarterCatalog.Model("Character/PC/Default",Map.of("Model",model,"Height",model.arguments().get("Height"))));
        var codec=new SessionCodec();var bytes=new ByteArrayOutputStream();
        append(bytes,HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"));
        append(bytes,HandshakeCodec.authenticationResponse((short)2,true,"synthetic-session","Alice"));
        append(bytes,codec.player((short)3,1001,1,"Alice"));
        append(bytes,codec.selected(1001,knight,profile));
        append(bytes,codec.forceTown(1001,(short)3));
        append(bytes,codec.sceneMove(1001,(short)4,true));
        append(bytes,codec.townLocation(1001));
        append(bytes,codec.scene((short)5,List.of(state)));
        append(bytes,codec.sceneDelta(1001,0,0,101,List.of(state),List.of(),new int[0]));
        var moved=new ActorState(10,1001,knight.name(),257,100,.25f,-8,1.5f,0,2,state.config());
        append(bytes,codec.sceneDelta(1001,101,(short)20,101,151,(short)50,List.of(),List.of(moved),new int[0],Map.of(10,state)));
        append(bytes,codec.occupants(List.of(state)));
        var input=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));
        input.qe();input.qe();
        var player=(PlayerObject)((ObjectResponse)input.qe()).yk();
        TownSceneObject town=null;
        com.threerings.projectx.data.actor.Knight actor=null;
        for(int i=0;i<8;i++) {
            var message=input.qe();
            var event=message instanceof EventNotification notification ? notification.yj() : message;
            if(event instanceof ObjectResponse object) town=(TownSceneObject)object.yk();
            else if(event instanceof CompoundEvent compound) {
                for(var child:compound.xE()) child.b(i==7 ? town : player);
            } else if(event instanceof SceneDeltaEvent delta) {
                if(delta.aej()!=null) {
                    actor=(com.threerings.projectx.data.actor.Knight)delta.aej()[0];
                    // controlledActorAdded predicts directly on this first record. Emulate the
                    // inventory panel opened during entry, before its next authoritative delta.
                    actor.aU(3,101);
                }
                else actor=(com.threerings.projectx.data.actor.Knight)delta.aek()[0].apply(actor);
            }
        }
        if(input.available()!=0 || player.items.size()!=5 || player.crowns!=120 || player.energy!=34
                || player.loadouts.size()!=1 || town.occupantInfo.size()!=1 || actor==null
                || !actor.Np() || actor.adU()!=0 || actor.adV()!=100
                || Math.abs(actor.aeb()-TownMovement.MAX_STEP)>.00001 || actor.tz().x!=.25f || actor.tA()!=1.5f) throw new AssertionError("World schema mismatch or predicted activity leaked into delta baseline");
        var occupant=(ProjectXOccupantInfo)town.occupantInfo.iterator().next();
        for (var item : player.items) {
            if (!item.Cs() || item.VC()) throw new AssertionError("Starter binding flags differ from stock mutable binding");
        }
        if(occupant.pr==null) throw new AssertionError("Missing prestige rank for stock renderer");
        occupant.getIcon();
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,args[0]+"/rsrc");
        var manager=(com.threerings.config.ConfigManager)com.threerings.config.ConfigManager.class
            .getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class)
            .newInstance(resource,null,"config/");
        manager.init();
        // The server's equipped model must match the stock player's builder, including weapon/shield.
        var stock=player.n(manager);
        if(!stock.equals(reference(model)))throw new AssertionError("Equipped model differs: "+stock+" vs "+model);
        // Exercise the actual client predictor, not just the reader: a zero maxStep used to
        // clamp every local substep, forcing movement to depend on server correction packets.
        actor.init(manager);
        var sceneModel = new com.threerings.tudey.data.TudeySceneModel();
        sceneModel.init(manager);
        Object environment = java.lang.reflect.Proxy.newProxyInstance(
            com.threerings.projectx.util.j.b.class.getClassLoader(),
            new Class<?>[]{com.threerings.projectx.util.j.b.class}, (proxy, method, values) -> {
                return switch(method.getName()) {
                    case "Sn" -> sceneModel;
                    case "getConfigManager" -> manager;
                    case "CT" -> player;
                    default -> method.getReturnType()==boolean.class ? false
                        : method.getReturnType()==int.class ? 0 : null;
                };
            });
        var predicted = (com.threerings.projectx.data.actor.Knight)actor.clone();
        var advancer = (com.threerings.tudey.util.k)predicted.a(
            (com.threerings.tudey.util.b.a)environment,151);
        float start = predicted.tz().x;
        advancer.a(new ProjectXInputFrame(201,1.5f,0,1,0));
        if(Math.abs(predicted.tz().x-start-.25f)>.0001 || !predicted.bR(2)) {
            throw new AssertionError("Stock predictor cannot walk smoothly: "+predicted);
        }
        System.out.println("PASS: stock predictor advances the knight locally with movement enabled");
        System.out.println("PASS: stock reader accepts player/loadout, original town, occupant metadata and usable actor deltas");
    }
    private static com.threerings.config.ConfigReference<?> reference(StarterCatalog.Model model) {
        var result=new com.threerings.config.ConfigReference<>(model.name());
        model.arguments().forEach((key,value) -> result.kW().put(key,
            value instanceof StarterCatalog.Model nested ? reference(nested)
                : value instanceof StarterCatalog.Colorization color
                    ? com.threerings.opengl.renderer.config.ColorizationConfig.createConfig(color.value()) : value));
        return result;
    }
}
