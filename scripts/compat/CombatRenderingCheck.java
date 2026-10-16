import com.threerings.projectx.client.ProjectXApp;
import com.threerings.projectx.dungeon.data.actor.Dungeoneer;
import com.threerings.projectx.item.data.Item;
import com.threerings.tudey.data.actor.Actor;
import com.threerings.tudey.dobj.SceneDeltaEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.tortodrone.account.AccountStore;
import org.tortodrone.character.Knight;
import org.tortodrone.world.CollisionMap;
import org.tortodrone.world.SceneCatalog;
import org.tortodrone.world.FireCatalog;

/** Unchanged native controller/sprite/framebuffer probe; launched only by CheckCombatRendering. */
public final class CombatRenderingCheck {
    private record Action(String name,String weapon,int flags,int held,int required,boolean projectile) { }
    private record Heading(float rotation,float variance) { }
    private final Map<Integer,List<Heading>> needleHeadings=new HashMap<>();
    private static final String SWORD="Weapon/Sword/Calibur", GUN="Weapon/Handgun/Proto Gun", BOMB="Weapon/Bomb/Blast Bomb";
    private static final String NEEDLE="Weapon/Handgun/Blitz Needle";
    private static final List<Action> ACTIONS=List.of(
        new Action("sword",SWORD,4,120,292,false),
        new Action("sword-charge",SWORD,4,4000,290,false),
        new Action("gun",GUN,4,120,292,true),
        new Action("gun-charge",GUN,4,4000,290,true),
        // An early release emits the stock one-millisecond, invisible Dud.
        new Action("bomb",BOMB,4,120,289,false),
        new Action("bomb-charge",BOMB,4,4000,290,true),
        new Action("defense",SWORD,64,700,284,false),
        new Action("bash",SWORD,2048,120,287,false),
        new Action("dodge",SWORD,1024,120,286,false),
        new Action("blitz-needle",NEEDLE,4,120,292,true),
        new Action("blitz-needle-charge",NEEDLE,4,4000,290,true),
        new Action("amputator","Weapon/Sword/Amputator",4,120,292,false),
        new Action("amputator-charge","Weapon/Sword/Amputator",4,4000,290,true),
        new Action("combuster-charge","Weapon/Sword/Combuster",4,4000,290,true),
        new Action("pulsar","Weapon/Handgun/Pulsar",4,120,292,true),
        new Action("pulsar-charge","Weapon/Handgun/Pulsar",4,4000,290,true),
        new Action("alchemer-charge","Weapon/Handgun/Voltech Alchemer Mk II",4,4000,290,true),
        new Action("tortofist-charge","Weapon/Handgun/Tortafist/Grand Tortofist",4,4000,290,true));
    private final ProjectXApp app;
    private final Path output;
    private final boolean peer;
    private final boolean trainingOnly;
    private boolean healthOnly;
    private boolean movementOnly;
    private List<Action> actions=ACTIONS;
    private int healthMaximum;
    private float hazardX,hazardY;
    private float peerStartX,peerStartY;
    private float peerRenderedX,peerRenderedY;
    private boolean peerBaseline,peerMotion;
    private final Map<Integer,Actor> authoritative=new HashMap<>();
    private final Set<Integer> activities=new TreeSet<>(),renderedActivities=new TreeSet<>();
    private final Set<String> animations=new TreeSet<>();
    private final Set<Integer> capturedProjectiles=new HashSet<>();
    private final Set<Integer> previousActors=new HashSet<>();
    private final ExecutorService images=Executors.newSingleThreadExecutor();
    private final List<Future<?>> writes=new ArrayList<>();
    private static final int[] TOUR={2,3,445,30893,318};
    private int tour;
    private int stage,index,frames,lastStamp,actionStamp,requiredStarted,burstFrames;
    private float moveX,moveY;
    private long deadline,started,nextFrame,lastStatus;
    private boolean listening,released,projectile,aftermath,done,passed;
    private boolean needleFacingReady,needleNormalSeen,needleChargedSeen;
    private float needleNormalFacing;
    private long trainingSince;
    private int konwayActor,konwayRouteIndex;
    private List<float[]> konwayRoute=List.of();
    private long konwayStarted;
    private Confirm conversationResponse;
    private String initialConversationMessage;
    private SceneCatalog.MapScene trainingMap;
    private int vialPickupActor;
    private long vialItemId,vialSearchSince,vialLiftActor,vialQuickbarAwaitSince,vialQuickbarSettled;
    private String vialItemConfig;
    private int vialCountBefore;
    private boolean vialThrownCaptured;
    private Confirm vialQuickbarReply,vialUseReply;
    private Confirm equip;
    private String lastAnimation="";
    private Throwable failure;

    private CombatRenderingCheck(ProjectXApp app,Path output,boolean peer,boolean trainingOnly){this.app=app;this.output=output;this.peer=peer;this.trainingOnly=trainingOnly;deadline=System.nanoTime()+(peer?480:trainingOnly?420:380)*1_000_000_000L;}
    public static void main(String[] args)throws Exception {
        if(args.length==2&&args[0].equals("--seed")){seed(Path.of(args[1]));return;}
        boolean peer=args.length>=4&&args[0].equals("--peer");
        if(peer)args=Arrays.copyOfRange(args,1,args.length);
        boolean trainingOnly=!peer&&args.length==4&&(args[3].equals("--training-only")||args[3].equals("--health-only")||args[3].equals("--movement-only"));
        if(args.length!=(trainingOnly?4:3))throw new IllegalArgumentException("Use CheckCombatRendering.java to launch this fixture");
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        // Override only this disposable process, before the native application is constructed.
        var configField=Class.forName("com.threerings.projectx.util.a").getDeclaredField("bMw");configField.setAccessible(true);
        Object config=configField.get(null);Field properties=field(config,"Jf");
        var copy=new Properties();copy.putAll((Properties)properties.get(config));
        copy.setProperty("server_host","127.0.0.1");copy.setProperty("server_ports",args[0]);
        copy.setProperty("key.public",Files.readString(Path.of(args[1])).trim());properties.set(config,copy);
        // Native startup re-applies this preference after construction. This is
        // the launcher's disposable preferences root, never the owner's settings.
        com.threerings.projectx.client.ProjectXPrefs.setVSyncEnabled(false);
        var app=new ProjectXApp(peer?"PeerPlayer":"LanPlayer","lan-test-password",false,peer?"Peer-Knight":"Town-Knight",null,null,null,null);
        app.setTitle(peer?"Tortodrone disposable peer rendering check":"Tortodrone disposable combat rendering check");app.setVSyncEnabled(false);
        var probe=new CombatRenderingCheck(app,Path.of(args[2]),peer,trainingOnly);
        probe.healthOnly=Arrays.asList(args).contains("--health-only");
        probe.movementOnly=Arrays.asList(args).contains("--movement-only");
        if(probe.movementOnly)probe.actions=List.of(ACTIONS.getFirst(),ACTIONS.get(8));
        var timer=new Timer(true);
        var pending=new AtomicBoolean();
        timer.scheduleAtFixedRate(new TimerTask(){public void run(){if(pending.compareAndSet(false,true))app.getRunQueue().postRunnable(()->{try{probe.tick();}finally{pending.set(false);}});}},50,40);
        try{app.startup();}finally{timer.cancel();}
        if(probe.failure!=null)throw new AssertionError("Graphical combat acceptance failed",probe.failure);
        if(!probe.passed)throw new AssertionError("Graphical client exited before acceptance completed");
    }
    private static void seed(Path data)throws Exception {
        try(var store=new AccountStore(data)){
            for(String user:List.of("LanPlayer","PeerPlayer")) {
                store.create(user,"lan-test-password".toCharArray());
                char[] password=AccountStore.transform("lan-test-password".toCharArray());
                try{store.authenticate(user,password).characters().create(new Knight(0,user.equals("LanPlayer")?"Town-Knight":"Peer-Knight",
                    "Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));}
                finally{Arrays.fill(password,'\0');}
            }
            store.grantItems("LanPlayer","Town-Knight","Gear/Shield/Grey Owlite Shield",1,-1,-1);
            store.grantItems("LanPlayer","Town-Knight","Rarity/Spark of Life",2,-1,-1);
            for(String item:ACTIONS.stream().map(Action::weapon).distinct().toList())store.grantItems("LanPlayer","Town-Knight",item,1,-1,-1);
        }
    }
    private void tick(){
        if(done)return;
        try{
            long now=System.nanoTime();
            if(now>deadline)throw new AssertionError("Timed out at stage "+stage+", action "+index);
            if(!app.zr().isLoggedOn())return;
            // Fresh disposable preferences show Uplink automatically. Close that native
            // startup window through its normal API before exercising the controller.
            var root=app.getRoot();
            if(root!=null)for(int window=root.getWindowCount()-1;window>=0;window--){
                if(root.getWindow(window) instanceof com.threerings.projectx.client.UplinkWindow uplink)uplink.dismiss();
            }
            if(now-lastStatus>5_000_000_000L){
                lastStatus=now;Object current=app.Gu();
                System.out.println("STATE: peer="+peer+" stage="+stage+" actors="+authoritative.size()
                    +" view="+(current==null?"none":current.getClass().getName())+" loading="+app.GF().mG()
                    +(stage==20?konwayState(viewPawn()):""));
            }
            if(!listening){listening=true;app.zr().xc().a(new com.threerings.tudey.dobj.a(){public void c(SceneDeltaEvent e){receive(e);}});}
            if(!Class.forName("com.threerings.tudey.a.i").isInstance(app.Gu())||app.GF().mG())return;
            var view=new View(app.Gu());if(view.PO()==null)return;
            if(peer){peerTick(view);return;}
            if(stage>=3&&stage<=6&&GLFW.glfwGetWindowAttrib(app.getWindow(),GLFW.GLFW_FOCUSED)==0)
                throw new AssertionError("Keep the disposable combat test window focused; the stock background cap prevents animation/input sampling");
            if(stage==0){GLFW.glfwFocusWindow(app.getWindow());started=now;if(healthOnly||movementOnly){app.GF().bl(294);stage=2;}else stage=1;return;}
            if(healthOnly&&view.sceneId()==294&&stage>=2){healthTick(view,now);return;}
            if(stage==1&&now-started>5_000_000_000L){
                if(GLFW.glfwGetWindowAttrib(app.getWindow(),GLFW.GLFW_FOCUSED)==0)
                    throw new AssertionError("Disposable primary client must be focused for native town input");
                if(authoritative.values().stream().noneMatch(a->a.vw()!=app.CT().pawnId&&a instanceof com.threerings.projectx.data.actor.Knight))return;
                capture("haven");moveY=view.PO().acU().tz().y;moveNorth(view,true);started=now;stage=13;return;
            }
            if(stage==13&&now-started>1_500_000_000L){moveNorth(view,false);started=now;stage=14;return;}
            if(stage==14&&now-started>15_000_000_000L){
                var own=authoritative.get(app.CT().pawnId);
                throw new AssertionError("Peer did not render town movement; primary actor="+(own==null?"missing":own.tz()));
            }
            if(stage==14&&Files.isRegularFile(output.resolve("peer/peer-movement"))){
                var own=authoritative.get(app.CT().pawnId);
                if(own==null||own.tz().y<moveY+2)throw new AssertionError("Town movement did not advance on the modern collision map");
                boolean visible=false;
                for(var actor:authoritative.values())if(actor.vw()!=app.CT().pawnId){
                    var sprite=view.gk(actor.vw());
                    if(sprite!=null&&sprite.acL().isVisible()&&sprite.acL().getConfig()!=null){
                        var position=sprite.acL().getLocalTransform().tJ();
                        visible|=Math.hypot(position.x-actor.tz().x,position.y-actor.tz().y)<1;
                    }
                }
                if(!visible)throw new AssertionError("Primary client did not render its peer knight");
                capture("haven-shared-movement");System.out.println("CHECK: two graphical clients render shared Town Square and replicated movement");
                if(trainingOnly){app.GF().bl(294);stage=2;}
                else{app.GF().bl(TOUR[tour]);stage=11;}return;
            }
            if(stage==11&&view.sceneId()==TOUR[tour]){started=now;stage=12;return;}
            if(stage==12&&view.sceneId()==TOUR[tour]&&now-started>3_000_000_000L){
                capture("scene-"+TOUR[tour]);System.out.println("RENDER: modern scene "+TOUR[tour]+" loaded");
                if(++tour<TOUR.length){app.GF().bl(TOUR[tour]);stage=11;}
                else{app.GF().bl(294);stage=2;}
                return;
            }
            if(stage==2&&view.sceneId()==294&&view.PO().acU() instanceof Dungeoneer){
                var kinds=new HashSet<String>();for(var actor:authoritative.values())if(actor.vw()!=app.CT().pawnId)kinds.add(actor.getClass().getSimpleName());
                if(trainingSince==0)trainingSince=now;
                boolean complete=List.of("Monster","Stranger","Trap","Pickup").stream().allMatch(kinds::contains);
                if(!complete){if(now-trainingSince>10_000_000_000L)throw new AssertionError("Training Hall is missing native Monster, Stranger, Trap or Pickup actors; saw "+kinds);return;}
                boolean configured=true;
                for(String kind:List.of("Monster","Stranger","Trap","Pickup")){
                    boolean found=false;
                    for(var actor:authoritative.values())if(actor.getClass().getSimpleName().equals(kind)){
                        var sprite=view.gk(actor.vw());if(sprite!=null&&sprite.acL().getConfig()!=null){found=true;break;}
                    }
                    configured&=found;
                }
                if(!configured){if(now-trainingSince>10_000_000_000L)throw new AssertionError("Training actors did not initialize native sprites");return;}
                System.out.println("CHECK: Training Hall renders Monster, Stranger (Konway), Trap and Pickup actor sprites");started=now;stage=3;return;}
            if(stage==3&&now-started>2_000_000_000L){capture("training-idle");moveX=view.PO().acU().tz().x;moveY=view.PO().acU().tz().y;moveNorth(view,true);started=now;stage=8;return;}
            if(stage==8&&now-started>1_000_000_000L){moveNorth(view,false);started=now;stage=9;return;}
            if(stage==9&&now-started>1_000_000_000L){
                var server=authoritative.get(app.CT().pawnId);
                if(server==null||server.tz().y<moveY+2)throw new AssertionError("Native movement did not reach the training corridor away from its south exit");
                capture("training-movement");System.out.println("CHECK: native controlled movement and authoritative position");
                if(movementOnly){stage=4;return;}
                var stranger=authoritative.values().stream().filter(a->a instanceof com.threerings.projectx.data.actor.Stranger)
                    .filter(a->a.Mv().getName().equals("Character/NPC/Advanced Training Gym/Greeter"))
                    .filter(a->Math.hypot(a.tz().x-5.818864,a.tz().y-2.6403537)<.05).findFirst()
                    .orElseThrow(()->new AssertionError("Authored Konway actor is missing from its verified Training Hall position"));
                konwayActor=stranger.vw();trainingMap=SceneCatalog.load(Path.of("content/packs/vanilla/maps")).scene(294);
                konwayRoute=routeTo(trainingMap,server.tz().x,server.tz().y,stranger.tz().x,stranger.tz().y);
                konwayRouteIndex=0;konwayStarted=now;stage=20;return;
            }
            if(stage==20){
                var own=authoritative.get(app.CT().pawnId);var npc=authoritative.get(konwayActor);
                if(own==null||npc==null)throw new AssertionError("Training actors disappeared while approaching Konway");
                if(now-konwayStarted>45_000_000_000L)throw new AssertionError("Native Training movement timed out approaching Konway"+konwayState(own));
                if(konwayRouteIndex<konwayRoute.size()){
                    float[] point=konwayRoute.get(konwayRouteIndex);float dx=point[0]-own.tz().x,dy=point[1]-own.tz().y;
                    if(Math.hypot(dx,dy)<.30){konwayRouteIndex++;moveToward(view,0,0,false);return;}
                    moveToward(view,dx,dy,true);return;
                }
                // The final native movement intent faces the authored Stranger; it also
                // closes the last few centimetres needed by the stock interaction range.
                float dx=npc.tz().x-own.tz().x,dy=npc.tz().y-own.tz().y;
                Object interaction=field(own,"_actionEntity").get(own);
                if(interaction instanceof com.threerings.tudey.data.EntityKey.Actor key&&key.vw()==konwayActor){moveToward(view,dx,dy,false);flags(view,4);started=now;stage=21;return;}
                if(Math.hypot(dx,dy)>.82)moveToward(view,dx,dy,true);
                else moveToward(view,dx,dy,false);
                return;
            }
            if(stage==21){
                var dialog=app.CT().sceneDialog;
                if(dialog instanceof com.threerings.projectx.data.ConversationDialogInfo info){
                    flags(view,0);
                    if(!"n.atg_greeter".equals(info.name)||info.responses==null||info.responses.length!=2)
                        throw new AssertionError("Unexpected native Konway opening dialog: "+info.name+" responses="+Arrays.toString(info.responses));
                    initialConversationMessage=info.message;started=now;stage=24;return;
                }
                if(now-started>8_000_000_000L)throw new AssertionError("Native interaction did not open Konway's ConversationDialogInfo");
                return;
            }
            if(stage==24&&now-started>600_000_000L){
                if(!(app.CT().sceneDialog instanceof com.threerings.projectx.data.ConversationDialogInfo info)
                        ||!Objects.equals(info.message,initialConversationMessage))throw new AssertionError("Konway opening dialog changed before its native capture");
                capture("training-konway-dialog");conversationResponse=new Confirm();info.conversationService.f(0,conversationResponse);started=now;stage=22;return;
            }
            if(stage==22){
                if(conversationResponse.reason!=null)throw new AssertionError("Konway response refused: "+conversationResponse.reason);
                if(!conversationResponse.done)return;
                if(app.CT().sceneDialog instanceof com.threerings.projectx.data.ConversationDialogInfo next
                        &&!Objects.equals(next.message,initialConversationMessage)){
                    if(now-started<600_000_000L)return;
                    if(next.responses==null||next.responses.length!=1)throw new AssertionError("Konway's authored part 2a response list changed");
                    capture("training-konway-followup");
                    Object place=field(app.Gu(),"bJl").get(app.Gu());
                    if(!(place instanceof com.threerings.projectx.data.ProjectXSceneObject scene))throw new AssertionError("Missing native Training scene service");
                    scene.projectXSceneService.R(app.CT().sceneDialogCreated);started=now;stage=23;return;
                }
                if(now-started>8_000_000_000L)throw new AssertionError("Native Konway first response did not reach the authored follow-up node");
                return;
            }
            if(stage==23){
                if(app.CT().sceneDialog!=null&&now-started>5_000_000_000L)throw new AssertionError("Native scene service did not close Konway dialog");
                if(app.CT().sceneDialog==null&&now-started>250_000_000L){
                    System.out.println("CHECK: native Konway approach, interaction, first response, part 2a and scene-service close");
                    var own=authoritative.get(app.CT().pawnId);
                    konwayRoute=routeTo(trainingMap,own.tz().x,own.tz().y,moveX,moveY+3);konwayRouteIndex=0;konwayStarted=now;stage=26;
                }
                return;
            }
            if(stage==26){
                var own=authoritative.get(app.CT().pawnId);
                if(now-konwayStarted>45_000_000_000L)throw new AssertionError("Native movement did not return from Konway");
                if(konwayRouteIndex<konwayRoute.size()){
                    var point=konwayRoute.get(konwayRouteIndex);float dx=point[0]-own.tz().x,dy=point[1]-own.tz().y;
                    if(Math.hypot(dx,dy)<.30){konwayRouteIndex++;moveToward(view,0,0,false);}else moveToward(view,dx,dy,true);
                    return;
                }
                System.out.println("CHECK: returned to the safe Training corridor after Konway");
                vialSearchSince=now;stage=27;return;
            }
            if(stage==27){
                if(vialItemConfig!=null&&findItemId(vialItemConfig)!=0){assignVialQuickbar(now);return;}
                if(now-vialSearchSince>15_000_000_000L)throw new AssertionError("No native Training Vial pickup became available");
                var own=authoritative.get(app.CT().pawnId);
                var pickup=authoritative.values().stream().filter(a->a.getClass().getSimpleName().equals("Pickup"))
                    .filter(a->a.Mv().getName().startsWith("Item/Pickups/Vial/"))
                    .filter(a->(Math.abs(Math.abs(a.tz().x)-9.5)<.1||Math.abs(Math.abs(a.tz().x)-18.5)<.1)
                        &&List.of(.5,2.5,4.5,-1.5).stream().anyMatch(y->Math.abs(a.tz().y-y)<.1))
                    .min(Comparator.comparingDouble(a->Math.hypot(a.tz().x-own.tz().x,a.tz().y-own.tz().y))).orElse(null);
                if(pickup==null)return;
                vialPickupActor=pickup.vw();vialItemConfig=pickup.Mv().getName().replaceFirst("^Item/Pickups/","Pickup/");
                vialCountBefore=itemCount(vialItemConfig);vialItemId=0;
                konwayRoute=routeTo(trainingMap,own.tz().x,own.tz().y,pickup.tz().x,pickup.tz().y);konwayRouteIndex=0;konwayStarted=now;stage=28;return;
            }
            if(stage==28){
                var own=authoritative.get(app.CT().pawnId);var pickup=authoritative.get(vialPickupActor);
                if(now-konwayStarted>45_000_000_000L)throw new AssertionError("Native movement timed out approaching Training Vial");
                if(findItemId(vialItemConfig)!=0){assignVialQuickbar(now);return;}
                if(pickup==null){stage=27;vialSearchSince=now;return;}
                if(konwayRouteIndex<konwayRoute.size()){
                    var point=konwayRoute.get(konwayRouteIndex);float dx=point[0]-own.tz().x,dy=point[1]-own.tz().y;
                    if(Math.hypot(dx,dy)<.30){konwayRouteIndex++;moveToward(view,0,0,false);}else moveToward(view,dx,dy,true);
                    return;
                }
                float dx=pickup.tz().x-own.tz().x,dy=pickup.tz().y-own.tz().y;
                if(Math.hypot(dx,dy)>.70){moveToward(view,dx,dy,true);return;}
                moveToward(view,0,0,false);
                assignVialQuickbar(now);return;
            }
            if(stage==29){
                if(vialQuickbarReply.reason!=null)throw new AssertionError("Move Vial to quickbar slot 3 refused: "+vialQuickbarReply.reason);
                if(!vialQuickbarReply.done||app.CT().quickbar[3]!=vialItemId){if(now-started>8_000_000_000L)throw new AssertionError("Native quickbar did not assign the picked Vial to slot 3");return;}
                if(vialQuickbarSettled==0){vialQuickbarSettled=now;return;}
                if(now-vialQuickbarSettled<1_000_000_000L)return;
                Object hud=view.value().getClass().getMethod("Jb").invoke(view.value());
                Object panel=field(hud,"aSM").get(hud);
                var party=(com.threerings.projectx.dungeon.data.PartyObject)app.GQ().CW();
                System.out.println("QUICKBAR: hud="+hud.getClass().getName()+" panel="+panel+" member="+app.GQ().Px()
                    +" pickups="+(party==null?"no-party":party.hasPickups)+" oid="+app.CT().partyOid);
                if(!(panel instanceof com.threerings.projectx.dungeon.client.QuickbarPanel bar)||!bar.isVisible()||bar.getParent()==null)
                    throw new AssertionError("Native QuickbarPanel is not attached and visible after vial assignment");
                capture("training-vial-quickbar");System.out.println("CHECK: native Vial pickup and four-slot quickbar assignment, id="+vialItemId);
                vialUseReply=new Confirm();app.zr().bU(com.threerings.projectx.item.client.L.class).a(vialItemId,(Object)null,vialUseReply);
                started=now;stage=30;return;
            }
            if(stage==30){
                if(vialUseReply.reason!=null)throw new AssertionError("Native Vial use refused: "+vialUseReply.reason);
                var own=authoritative.get(app.CT().pawnId);
                if(vialUseReply.done&&own instanceof Dungeoneer knight&&field(knight,"_lift").getInt(knight)!=0&&knight.adU()==269){
                    capture("training-vial-held");vialLiftActor=field(knight,"_lift").getInt(knight);flags(view,4);started=now;stage=31;return;
                }
                if(now-started>8_000_000_000L)throw new AssertionError("Native Vial did not enter held activity 269 with a lift id");
                return;
            }
            if(stage==31&&now-started>180_000_000L){flags(view,0);stage=32;}
            // Observe from the first throw frame: a nearby wall can shatter a
            // correctly thrown vial before the 180 ms input press has ended.
            if(stage==31||stage==32){
                var own=authoritative.get(app.CT().pawnId);Actor thrown=null;
                for(var actor:authoritative.values())if(actor.vw()==vialLiftActor&&actor instanceof com.threerings.projectx.dungeon.data.actor.DungeonLift
                        &&actor.Mv().getName().equals("Dynamic/Lift Objects/Throwing Vial")&&actor.bR(2)
                        &&field(actor,"_faction").getInt(actor)==app.CT().pawnId){thrown=actor;break;}
                if(thrown!=null){
                    var sprite=view.gk(thrown.vw());if(sprite==null||sprite.acL().getConfig()==null)throw new AssertionError("Thrown native Vial has no rendered sprite");
                    if(own instanceof Dungeoneer knight&&field(knight,"_lift").getInt(knight)==0){
                        flags(view,0);
                        if(!vialThrownCaptured){vialLiftActor=thrown.vw();capture("training-vial-thrown");vialThrownCaptured=true;}
                        started=now;stage=34;return;
                    }
                }
                if(now-started>8_000_000_000L)throw new AssertionError("Native throw did not create a faction-owned Dynamic/Lift Objects/Throwing Vial actor");
                return;
            }
            if(stage==34){
                if(itemCount(vialItemConfig)!=vialCountBefore-1){if(now-started>8_000_000_000L)throw new AssertionError("Native Vial use did not consume exactly one item");return;}
                System.out.println("CHECK: native Vial hold/throw, DungeonLift flags and faction, one item consumed");
                var own=authoritative.get(app.CT().pawnId);var pos=own.tz();konwayRoute=routeTo(trainingMap,pos.x,pos.y,moveX,moveY+3);konwayRouteIndex=0;konwayStarted=now;stage=33;return;
            }
            if(stage==33){
                var own=authoritative.get(app.CT().pawnId);
                if(now-konwayStarted>45_000_000_000L)throw new AssertionError("Native movement did not return from the Vial pickup");
                if(konwayRouteIndex<konwayRoute.size()){
                    var point=konwayRoute.get(konwayRouteIndex);float dx=point[0]-own.tz().x,dy=point[1]-own.tz().y;
                    if(Math.hypot(dx,dy)<.30){konwayRouteIndex++;moveToward(view,0,0,false);}else moveToward(view,dx,dy,true);
                    return;
                }
                moveNorth(view,false);stage=4;return;
            }
            if(stage==17&&now-started>700_000_000L){moveEast(view,false);started=now;needleFacingReady=true;stage=18;return;}
            if(stage==18&&now-started>500_000_000L){stage=4;return;}
            if(stage==4){
                if(index==actions.size()){capture("training-complete");if(trainingOnly){finishImages();Files.writeString(output.resolve("primary-complete"),"");passed=done=true;System.out.println(movementOnly?"PASS: stock graphical Training movement, sword lunge and dodge with native impulse guards and settled prediction":"PASS: two stock graphical clients, focused Training Hall actors and native combat actions including Blitz Needle normal/charged projectiles");app.shutdown();}else{app.GF().bl(1);stage=7;}return;}
                var action=actions.get(index);
                if(action.name.equals("blitz-needle-charge")&&!needleFacingReady){moveEast(view,true);started=now;stage=17;return;}
                equip=new Confirm();long id=0;
                for(var item:app.CT().items)if(item.Vx().getName().equals(action.weapon)){id=item.VA();break;}
                if(id==0)throw new AssertionError("Missing seeded weapon "+action.weapon);
                app.zr().bU(com.threerings.projectx.item.client.L.class).a(id,6,equip);started=now;stage=5;return;
            }
            if(stage==5){
                if(!equip.done)return;if(equip.reason!=null)throw new AssertionError("Equip refused: "+equip.reason);
                var weapon=(Item)field(view.PO().acU(),"_weapon").get(view.PO().acU());
                Object impl=view.PO().implementation();var renderedWeapon=(Item)field(impl,"_weapon").get(impl);
                if(weapon==null||!weapon.Vx().getName().equals(actions.get(index).weapon)||renderedWeapon==null||!renderedWeapon.Vx().getName().equals(actions.get(index).weapon)){
                    if(now-started>5_000_000_000L)throw new AssertionError("Actor/sprite weapon did not converge to "+actions.get(index).weapon);
                    return;
                }
                var body=(com.threerings.config.ConfigReference<?>)view.PO().acU().Mv().kW().get("Model");
                if(body==null||body.kW().containsKey("Weapon")||body.kW().containsKey("Shield")||body.kW().containsKey("Weapon Idle"))throw new AssertionError("Interior base model duplicates held equipment");
                activities.clear();renderedActivities.clear();animations.clear();capturedProjectiles.clear();frames=0;burstFrames=0;projectile=false;released=false;aftermath=false;lastAnimation="";
                previousActors.clear();previousActors.addAll(authoritative.keySet());actionStamp=lastStamp;requiredStarted=Integer.MAX_VALUE;
                started=now;nextFrame=now+60_000_000L;flags(view,actions.get(index).flags);stage=6;
                System.out.println("ACTION: "+actions.get(index).name);return;
            }
            if(stage==6){
                var action=actions.get(index);int elapsed=(int)((now-started)/1_000_000L);
                if(!released&&elapsed>=action.held){flags(view,0);released=true;frames=0;nextFrame=now+60_000_000L;}
                var own=view.PO();int activity=((Dungeoneer)own.acU()).adU();
                var names=new TreeSet<String>();playing(own.acL(),names,0);
                String state=activity+":"+names;
                if(!state.equals(lastAnimation)){System.out.println("ANIMATION: "+action.name+" "+elapsed+"ms "+state);lastAnimation=state;}
                if(activity!=0&&activityPlaying(own,activity)){if(renderedActivities.add(activity))capture(action.name+"-activity-"+activity);animations.addAll(names);}
                // Activity/animation assertions still run on every sample. Bound
                // retained full-resolution frames per press/release phase too.
                if(now>=nextFrame&&frames<3){capture(action.name+(released?"-release-":"-")+String.format(Locale.ROOT,"%02d",frames++));nextFrame=now+300_000_000L;}
                if(!aftermath&&action.name.equals("bomb-charge")&&elapsed>action.held+1800){capture("bomb-charge-aftermath");aftermath=true;}
                if(action.name.equals("combuster-charge")&&burstFrames<3&&elapsed>action.held+1100+300*burstFrames)
                    capture("combuster-bursts-"+burstFrames++);
                // A held gun press fires normally before entering charge. Only
                // projectiles born after the required release activity qualify.
                for(var actor:authoritative.values())if(activities.contains(action.required)
                    &&!previousActors.contains(actor.vw())&&actor.adX()>=Math.max(actionStamp,requiredStarted)
                    &&Set.of("Bullet","Bomb","OrbitalBullet","CompoundOrbitalBullet").contains(actor.getClass().getSimpleName())
                    &&ownedProjectile(actor,action)){
                    var sprite=view.gk(actor.vw());
                    if(sprite!=null&&sprite.acL().getConfig()!=null&&sprite.acL().isVisible()&&capturedProjectiles.add(actor.vw())){
                        if(action.name.startsWith("blitz-needle")){
                            var shooter=authoritative.get(app.CT().pawnId);float facing=shooter.tA(),shot=actor.tA();
                            float difference=(float)Math.atan2(Math.sin(shot-facing),Math.cos(shot-facing));
                            if(needleHeadings.isEmpty())loadNeedleHeadings();
                            if(needleHeadings.getOrDefault(action.required,List.of()).stream().noneMatch(band->
                                Math.abs(Math.atan2(Math.sin(difference-band.rotation()),Math.cos(difference-band.rotation())))<=band.variance()/2+.035))
                                throw new AssertionError(action.name+" projectile rotation "+shot+" does not match native controller facing "+facing+" and authored spread");
                            if(action.name.equals("blitz-needle")){needleNormalFacing=facing;needleNormalSeen=true;}
                            else{if(!needleNormalSeen||Math.abs(Math.atan2(Math.sin(facing-needleNormalFacing),Math.cos(facing-needleNormalFacing)))<.5)
                                throw new AssertionError("Blitz Needle normal and charged shots did not use two native controller headings");needleChargedSeen=true;}
                            System.out.println("CHECK: "+action.name+" projectile rotation matches native facing "+facing+" and authored spread");
                        }
                        projectile=true;System.out.println("PROJECTILE: "+action.name+" "+actor.getClass().getSimpleName()+" "+actor.Mv());
                        // Validate every projectile, but retain representative
                        // pixels so Needle pellets cannot fill the image-encoding
                        // queue with a full frame for every projectile.
                        if(capturedProjectiles.size()<=2)capture(action.name+"-projectile-"+capturedProjectiles.size());
                    }
                }
                var server=authoritative.get(app.CT().pawnId);
                if(elapsed>action.held+2500&&server instanceof Dungeoneer knight&&knight.adU()==0){
                    if(!activities.contains(action.required)||!renderedActivities.contains(action.required))throw new AssertionError(action.name+" missed authoritative/rendered activity "+action.required+": "+activities+" / "+renderedActivities);
                    if(action.projectile&&!projectile)throw new AssertionError(action.name+" has no rendered server projectile");
                    if(movementOnly){
                        double drift=Math.hypot(own.acU().tz().x-server.tz().x,own.acU().tz().y-server.tz().y);
                        if(drift>.05)throw new AssertionError(action.name+" settled prediction differs from the server by "+drift);
                        System.out.println("CHECK: "+action.name+" settled prediction drift="+drift);
                    }
                    System.out.println("CHECK: "+action.name+" server="+activities+" rendered="+renderedActivities+" animations="+animations);
                    index++;stage=4;return;
                }
                if(elapsed>action.held+10000)throw new AssertionError(action.name+" failed to return to idle");
            }
            // Scene identity changes before its first rendered frame. Let Haven
            // settle before saving the return image and reporting completion.
            if(stage==7&&view.sceneId()==1){started=now;stage=10;return;}
            if(stage==10&&view.sceneId()==1&&now-started>2_000_000_000L){capture("haven-return");finishImages();Files.writeString(output.resolve("primary-complete"),"");passed=done=true;System.out.println("PASS: two stock graphical clients, seven modern Haven scenes and return transitions, eighteen combat actions, native animations and server projectile sprites");app.shutdown();}
        }catch(Throwable error){failure=error;done=true;error.printStackTrace();images.shutdown();
            try{images.awaitTermination(15,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            images.shutdownNow();app.shutdown();}
    }
    private boolean ownedProjectile(Actor actor,Action action)throws Exception {
        if((int)field(actor,"_clientOid").get(actor)==app.CT().xL())return true;
        if(!actor.getClass().getSimpleName().equals("Bomb"))return (int)field(actor,"_faction").get(actor)==app.CT().pawnId;
        String config=actor.Mv().getName();
        return action.name.equals("tortofist-charge")&&config.endsWith("Tortodrone Missile")
            ||(action.name.equals("combuster-charge")||action.name.equals("amputator-charge"))&&config.equals("Bullet/Sword/Parts/Burst Trail");
    }
    private boolean checkedStatuses,checkedEmergency,healthShieldEquipped,sawHurt,sawHealEffect,sawSparkEffect,healingWalk,checkedHealing;
    private Confirm sparkReply;
    private void healthTick(View view,long now)throws Exception {
        var party=(com.threerings.projectx.dungeon.data.PartyObject)app.GQ().CW();
        Object hud=view.value().getClass().getMethod("Jb").invoke(view.value());
        Object panel=field(hud,"aEc").get(hud);
        if(stage==2){
            if(!healthShieldEquipped){
                if(equip==null){
                    long id=0;for(var item:app.CT().items)if(item.Vx().getName().equals("Gear/Shield/Grey Owlite Shield"))id=item.VA();
                    if(id==0)throw new AssertionError("Missing reported shield fixture");
                    equip=new Confirm();app.zr().bU(com.threerings.projectx.item.client.L.class).a(id,2,equip);return;
                }
                if(!equip.done)return;if(equip.reason!=null)throw new AssertionError(equip.reason);healthShieldEquipped=true;
            }
            capture("health-before");
            party.speakService.a("Training chat remains connected",(byte)0);
            Object bound=field(panel,"aEO").get(panel);
            System.out.println("HEALTH HUD: panel="+panel.getClass().getName()+" bound="+(bound==party)+" party="+party+" fields="+field(panel,"aTs").get(panel)+","+field(panel,"aTt").get(panel));
            if(bound!=party)throw new AssertionError("Native health panel was not bound to the subscribed party");
            healthMaximum=party.health.iterator().next().maximum;
            trainingMap=SceneCatalog.load(Path.of("content/packs/vanilla/maps")).scene(294);
            var own=authoritative.get(app.CT().pawnId);
            var hazard=authoritative.values().stream().filter(a->a.getClass().getSimpleName().equals("Trap")).min(Comparator.comparingDouble(a->Math.hypot(a.tz().x-own.tz().x,a.tz().y-own.tz().y))).orElseThrow();
            hazardX=hazard.tz().x;hazardY=hazard.tz().y;konwayRoute=new ArrayList<>(routeTo(trainingMap,own.tz().x,own.tz().y,hazardX,hazardY));konwayRoute.add(new float[]{hazardX,hazardY});konwayRouteIndex=0;started=now;stage=40;return;
        }
        var own=authoritative.get(app.CT().pawnId);
        var hp=party.health.iterator().next();
        if(now-started>90_000_000_000L)throw new AssertionError("HUD damage timeout: hp="+hp.current+"/"+hp.maximum+" hud="+field(panel,"aTs").get(panel)+","+field(panel,"aTt").get(panel)+" actor="+own.tz());
        if(stage==41){
            if(sparkReply.done&&sparkReply.reason!=null)throw new AssertionError(sparkReply.reason);
            if(hp.current>0&&sawSparkEffect){
                if(!sawHurt||!sawHealEffect)throw new AssertionError("Missing hurt or healing feedback");
                capture("spark-revived");finishImages();passed=done=true;System.out.println("PASS: original Fire/Shock traps with lowered Grey Owlite Shield, hurt animation, healing sound effect and paid Spark revive effect");app.shutdown();
            }
            return;
        }
        if(healingWalk&&hp.current==healthMaximum){checkedHealing=true;healingWalk=false;konwayRoute=new ArrayList<>(routeTo(trainingMap,own.tz().x,own.tz().y,hazardX,hazardY));konwayRoute.add(new float[]{hazardX,hazardY});konwayRouteIndex=0;}
        if(konwayRouteIndex<konwayRoute.size()){
            float[] point=konwayRoute.get(konwayRouteIndex);float dx=point[0]-own.tz().x,dy=point[1]-own.tz().y;
            if(Math.hypot(dx,dy)<.25){konwayRouteIndex++;moveToward(view,0,0,false);}else moveToward(view,dx,dy,true);
            return;
        }
        moveToward(view,0,0,false);
        if(hp.current>0&&party.status.iterator().next().flags==0)checkedEmergency=true;
        if(hp.current<healthMaximum){
            int goal=field(panel,"aTs").getInt(panel),visible=field(panel,"aTt").getInt(panel);
            if(hp.current!=goal)System.out.println("HEALTH DAMAGE: native="+hp.current+"/"+hp.maximum+" hud="+goal+","+visible);
            if(goal!=hp.current)throw new AssertionError("Native health HUD did not consume the party damage update");
            if(visible>=healthMaximum)return;
            if(field(own,"_hitTime").getInt(own)<=0)throw new AssertionError("Player hit transient/sound timestamp missing");
            var status=party.status.iterator().next();
            if(status.conditions!=null&&Arrays.stream(status.conditions).anyMatch(c->c.getName().equals("Fire"))&&Arrays.stream(status.conditions).anyMatch(c->c.getName().equals("Shock"))){
                if(!checkedStatuses)System.out.println("CHECK: native player Fire and Shock conditions, expiry and hit transient timestamp");checkedStatuses=true;
                if(!checkedHealing&&!healingWalk){healingWalk=true;konwayRoute=new ArrayList<>(routeTo(trainingMap,own.tz().x,own.tz().y,trainingMap.spawnX(),trainingMap.spawnY()+1));konwayRoute.add(new float[]{trainingMap.spawnX(),trainingMap.spawnY()+1});konwayRouteIndex=0;}
            }
            if(hp.current>0&&status.flags==0)checkedEmergency=true;
            if(hp.current==0&&status.flags==0){
                if(!checkedStatuses||!checkedEmergency)throw new AssertionError("Missing statuses or first emergency revive");
                boolean spark=false;
                for(Class<?> type=hud.getClass();type!=null;type=type.getSuperclass())for(var f:type.getDeclaredFields()){
                    f.setAccessible(true);Object component=f.get(hud);
                    if(component instanceof com.threerings.projectx.dungeon.client.ai)spark=true;
                }
                if(!spark)return;
                capture("spark-panel");sparkReply=new Confirm();party.partyService.a(app.CT().knight,-1,sparkReply);stage=41;
            }
        }
    }
    private void peerTick(View view)throws Exception {
        if(!Files.exists(output.resolve("peer-ready"))){capture("town-peer");Files.writeString(output.resolve("peer-ready"),"");}
        for(var actor:authoritative.values())if(actor.vw()!=app.CT().pawnId&&actor instanceof com.threerings.projectx.data.actor.Knight){
            var sprite=view.gk(actor.vw());
            if(sprite==null||!sprite.acL().isVisible()||sprite.acL().getConfig()==null)continue;
            var position=sprite.acL().getLocalTransform().tJ();
            if(Math.hypot(position.x-actor.tz().x,position.y-actor.tz().y)>1)continue;
            if(!peerBaseline){peerStartX=actor.tz().x;peerStartY=actor.tz().y;peerRenderedX=position.x;peerRenderedY=position.y;peerBaseline=true;}
            if(!peerMotion&&Math.hypot(actor.tz().x-peerStartX,actor.tz().y-peerStartY)>2
                    &&Math.hypot(position.x-peerRenderedX,position.y-peerRenderedY)>1.5){
                System.out.println("CHECK: peer model transform moved from "+peerRenderedX+","+peerRenderedY+" to "+position.x+","+position.y);
                capture("town-peer-movement");peerMotion=true;Files.writeString(output.resolve("peer-movement"),"");
            }
        }
        if(Files.isRegularFile(output.getParent().resolve("primary-complete"))){
            if(!peerMotion)throw new AssertionError("Peer did not render the moving knight");
            finishImages();passed=done=true;System.out.println("PASS: graphical peer renders shared Town Square and the other knight's movement");app.shutdown();
        }
    }
    private void receive(SceneDeltaEvent event){
        lastStamp=event.QY();
        if(healthOnly&&event.aem()!=null)for(var effect:event.aem()){
            String reference=effect.Mv().toString();
            sawHealEffect|=reference.contains("heart_acquire_01.ogg");sawSparkEffect|=reference.contains("Player/Spark Rez");
        }
        if(event.aeh()==0)authoritative.clear();
        if(event.aej()!=null)for(var actor:event.aej())authoritative.put(actor.vw(),(Actor)actor.copy(null));
        if(event.aek()!=null)for(var delta:event.aek()){Actor old=authoritative.get(delta.vw());if(old!=null)authoritative.put(delta.vw(),(Actor)delta.apply(old));}
        if(event.ael()!=null)for(int id:event.ael())authoritative.remove(id);
        if(healthOnly&&authoritative.get(app.CT().pawnId) instanceof Dungeoneer own)sawHurt|=own.adU()==274;
        if(stage==6&&authoritative.get(app.CT().pawnId) instanceof Dungeoneer own){
            activities.add(own.adU());
            if(own.adU()==actions.get(index).required)requiredStarted=own.adV();
            if(movementOnly&&own.Ru()!=null&&own.adU()==actions.get(index).required&&!own.bR(Dungeoneer.ATTACK_IMPULSE_APPLIED))throw new AssertionError("Authoritative lunge is missing its native impulse guard");
        }
    }
    private static void flags(View view,int flags)throws Exception{
        // Stimulate only this application's controller; its unchanged frame builder,
        // local prediction and network transport remain active. No global input.
        field(view.acn(),"_flags").setInt(view.acn(),flags);
    }
    private static void moveNorth(View view,boolean moving)throws Exception{
        // Stock aO(float) derives keyboard heading from bOD[0]-bOD[1] (Y)
        // and bOD[3]-bOD[2] (X). Exercise the own controller's north axis,
        // away from the authored south portal, before repeated combat lunges.
        float[] axes=(float[])field(view.acn(),"bOD").get(view.acn());
        Arrays.fill(axes,0);axes[0]=1;face(view,axes);if(!moving)Arrays.fill(axes,0);flags(view,moving?1:0);
    }
    private static void moveEast(View view,boolean moving)throws Exception{
        float[] axes=(float[])field(view.acn(),"bOD").get(view.acn());
        Arrays.fill(axes,0);axes[3]=1;face(view,axes);if(!moving)Arrays.fill(axes,0);flags(view,moving?1:0);
    }
    private static void moveToward(View view,float dx,float dy,boolean moving)throws Exception{
        float[] axes=(float[])field(view.acn(),"bOD").get(view.acn());Arrays.fill(axes,0);
        float length=(float)Math.hypot(dx,dy);if(length>0){dx/=length;dy/=length;
            if(dy>0)axes[0]=dy;else axes[1]=-dy;if(dx>0)axes[3]=dx;else axes[2]=-dx;}
        if(length>0)face(view,axes);flags(view,moving?1:0);
    }
    private static void face(View view,float[] axes)throws Exception{
        Object controller=view.acn();for(Class<?> type=controller.getClass();type!=null;type=type.getSuperclass())try{
            var method=type.getDeclaredMethod("k",float[].class);method.setAccessible(true);method.invoke(controller,(Object)axes);return;
        }catch(NoSuchMethodException ignored){}throw new NoSuchMethodException("Native controller facing helper");
    }
    private static List<float[]> routeTo(SceneCatalog.MapScene map,float x,float y,float targetX,float targetY){
        record Cell(int x,int y){float wx(){return x*.25f;}float wy(){return y*.25f;}}
        Cell start=new Cell(Math.round(x*4),Math.round(y*4));var previous=new HashMap<Cell,Cell>();var pending=new ArrayDeque<Cell>();
        previous.put(start,null);pending.add(start);Cell end=null;int[][] directions={{1,0},{-1,0},{0,1},{0,-1}};
        while(!pending.isEmpty()){
            Cell here=pending.removeFirst();
            if(Math.hypot(here.wx()-targetX,here.wy()-targetY)<=1.9){end=here;break;}
            for(var d:directions){Cell next=new Cell(here.x()+d[0],here.y()+d[1]);float nx=next.wx(),ny=next.wy();
                if(Math.abs(next.x())>320||Math.abs(next.y())>320||previous.containsKey(next)||!map.collision().walkable(nx,ny)
                    ||map.portals().triggers().stream().anyMatch(t->t.intersects(nx,ny)))continue;
                previous.put(next,here);pending.addLast(next);
            }
        }
        if(end==null)throw new AssertionError("No collision-walkable native route to Konway");
        var cells=new ArrayList<Cell>();for(Cell p=end;p!=null;p=previous.get(p))cells.add(p);Collections.reverse(cells);
        var result=new ArrayList<float[]>();float px=x,py=y;
        for(int i=1;i<cells.size();){int far=i;
            for(int j=i;j<cells.size();j++){Cell candidate=cells.get(j);if(Math.hypot(candidate.wx()-px,candidate.wy()-py)>1.25
                    ||!map.collision().walkableSegment(px,py,candidate.wx(),candidate.wy())
                    ||!clearOfPortals(map,px,py,candidate.wx(),candidate.wy()))break;far=j;}
            Cell point=cells.get(far);result.add(new float[]{point.wx(),point.wy()});px=point.wx();py=point.wy();i=far+1;
        }
        return List.copyOf(result);
    }
    private String konwayState(Actor own){
        if(own==null)return " konway=own-missing";
        var npc=authoritative.get(konwayActor);if(npc==null)return " konway=npc-missing own="+own.tz();
        try{
            Object action=field(own,"_actionEntity").get(own),description=field(own,"_actionDesc").get(own);
            Object controller=new View(app.Gu()).acn();
            String key=action instanceof com.threerings.tudey.data.EntityKey.Actor actor?Integer.toString(actor.vw()):String.valueOf(action);
            String waypoint=konwayRouteIndex<konwayRoute.size()?Arrays.toString(konwayRoute.get(konwayRouteIndex)):"complete";
            return " konwayPos="+own.tz()+" npc="+npc.tz()+" dist="+Math.hypot(npc.tz().x-own.tz().x,npc.tz().y-own.tz().y)
                +" facing="+own.tA()+" actionEntity="+key+" actionDesc="+description+" controller="+controller.getClass().getName()
                +" route="+konwayRouteIndex+"/"+konwayRoute.size()+" waypoint="+waypoint;
        }catch(Exception error){return " konway diagnostics failed="+error;}
    }
    private Actor viewPawn(){return authoritative.get(app.CT().pawnId);}
    private void loadNeedleHeadings()throws Exception{
        for(var attack:FireCatalog.load(Path.of("content/packs/vanilla/combat/fires.json")).entries())if(attack.weaponConfig().equals(NEEDLE)){
            var headings=new ArrayList<Heading>();for(var fire:attack.fireRefs())collectNeedleHeadings(fire.action(),headings);
            needleHeadings.put(attack.activity(),List.copyOf(headings));
        }
    }
    private static void collectNeedleHeadings(FireCatalog.Action action,List<Heading> headings){
        if(action.kind()==FireCatalog.Kind.EMIT_PROJECTILE&&action.actor().nativeType().equals("BULLET")){
            if(!action.relative())throw new AssertionError("Needle fire action lost its captured relative facing");
            headings.add(new Heading(action.rotation(),action.rotationVariance()));
        }
        for(var child:action.children())collectNeedleHeadings(child,headings);
    }
    private static boolean clearOfPortals(SceneCatalog.MapScene map,float ax,float ay,float bx,float by){
        int steps=Math.max(1,(int)Math.ceil(Math.hypot(bx-ax,by-ay)/.05));
        for(int i=0;i<=steps;i++){float f=(float)i/steps,x=ax+(bx-ax)*f,y=ay+(by-ay)*f;
            if(map.portals().triggers().stream().anyMatch(t->t.intersects(x,y)))return false;}
        return true;
    }
    private boolean assignVialQuickbar(long now)throws Exception{
        vialItemId=findItemId(vialItemConfig);if(vialItemId==0)return false;
        vialCountBefore=itemCount(vialItemConfig);
        if(vialItemId>=0)throw new AssertionError("Training Vial pickup did not create a temporary native item id: "+vialItemId);
        if(app.CT().quickbar==null||app.CT().quickbar.length!=4)
            throw new AssertionError("Native quickbar is not a four-slot array");
        if(Arrays.stream(app.CT().quickbar).noneMatch(id->id==vialItemId)){
            if(vialQuickbarAwaitSince==0)vialQuickbarAwaitSince=now;
            if(now-vialQuickbarAwaitSince>4_000_000_000L)throw new AssertionError("Collected Vial id "+vialItemId+" is absent from native quickbar "+Arrays.toString(app.CT().quickbar));
            return false;
        }
        vialQuickbarReply=new Confirm();app.zr().bU(com.threerings.projectx.item.client.L.class)
            .a(vialItemId,com.threerings.projectx.item.data.ItemCodes.Location.QUICKBAR,3,vialQuickbarReply);
        started=now;stage=29;return true;
    }
    private long findItemId(String config){
        if(config==null)return 0;
        for(var item:app.CT().items)if(item.Vx().getName().equals(config))return item.VA();
        return 0;
    }
    private int itemCount(String config){
        if(config==null)return 0;int count=0;
        for(var item:app.CT().items)if(item.Vx().getName().equals(config))count+=item.getCount();
        return count;
    }
    // The stock jar contains both a package and a class named tudey.a;
    // Java source cannot name its obfuscated view/sprite types directly.
    private record View(Object value) {
        Sprite PO()throws Exception{Object sprite=value.getClass().getMethod("PO").invoke(value);return sprite==null?null:new Sprite(sprite);}
        Sprite gk(int id)throws Exception{Object sprite=value.getClass().getMethod("gk",int.class).invoke(value,id);return sprite==null?null:new Sprite(sprite);}
        int sceneId()throws Exception{return ((com.threerings.tudey.data.TudeySceneModel)value.getClass().getMethod("Sn").invoke(value)).sceneId;}
        Object acn()throws Exception{return value.getClass().getMethod("acn").invoke(value);}
    }
    private record Sprite(Object value) {
        Actor acU()throws Exception{return (Actor)value.getClass().getMethod("acU").invoke(value);}
        com.threerings.opengl.model.h acL()throws Exception{return (com.threerings.opengl.model.h)value.getClass().getMethod("acL").invoke(value);}
        Object implementation()throws Exception{return value.getClass().getMethod("acT").invoke(value);}
    }
    private static boolean activityPlaying(Sprite sprite,int activity)throws Exception{
        Object impl=sprite.implementation();
        var registry=(com.samskivert.util.D<?>)field(impl,"bQO").get(impl);Object current=registry.get(activity);
        if(current==null)return false;
        var animations=(com.threerings.opengl.model.a[])field(current,"bQQ").get(current);
        for(var animation:animations)if(animation!=null&&animation.isPlaying())return true;
        return false;
    }
    private static void playing(com.threerings.opengl.model.h model,Set<String> names,int depth)throws Exception{
        if(depth>8)return;
        for(var animation:model.getPlayingAnimations()){
            var config=(com.threerings.opengl.model.config.AnimationConfig)field(animation,"_config").get(animation);
            names.add((config==null?animation.getName():config.getName())+"@"+animation.getSpeed());
        }
        for(var child:model.getChildren())playing(child,names,depth+1);
    }
    private void capture(String name)throws Exception{
        int w=app.getWindowWidth(),h=app.getWindowHeight();var pixels=BufferUtils.createByteBuffer(w*h*3);
        GL11.glReadPixels(0,0,w,h,GL11.GL_RGB,GL11.GL_UNSIGNED_BYTE,pixels);
        var rgb=new int[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int i=(y*w+x)*3;rgb[(h-y-1)*w+x]=((pixels.get(i)&255)<<16)|((pixels.get(i+1)&255)<<8)|(pixels.get(i+2)&255);}
        writes.add(images.submit(()->{var image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);image.setRGB(0,0,w,h,rgb,0,w);ImageIO.write(image,"png",output.resolve(name+".png").toFile());return null;}));
    }
    private void finishImages()throws Exception{images.shutdown();for(var write:writes)write.get(30,TimeUnit.SECONDS);}
    private static Field field(Object object,String name)throws Exception{
        for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass())try{Field field=type.getDeclaredField(name);field.setAccessible(true);return field;}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(name);
    }
    private static final class Confirm implements com.threerings.presents.client.A.a {
        boolean done;String reason;public void xx(){done=true;}public void aT(String value){reason=value;done=true;}
    }
}
