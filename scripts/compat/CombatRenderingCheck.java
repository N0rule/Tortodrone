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
    private boolean readyRoomOnly;
    private boolean freshOpening;
    private boolean campStory,campStoryReturn,campProgression,campProgressionReturn;
    private int campPanel;
    private long campResponseAt;
    private int readyRoomDestination=1;
    private Confirm openingCreation;
    private boolean dialogueOnly;
    private boolean townDialogsOnly;
    private boolean depotOnly,boxesOnly,multiplayerOnly,sharedWorldOnly,itemsOnly;
    private boolean hazardsOnly;
    private boolean healthOnly,arsenalBlinkOnly,arsenalStationOnly;
    private boolean movementOnly;
    private boolean vortexShieldOnly;
    private boolean equipmentGroups;
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
    private boolean fullscreenApplied;

    private CombatRenderingCheck(ProjectXApp app,Path output,boolean peer,boolean trainingOnly){this.app=app;this.output=output;this.peer=peer;this.trainingOnly=trainingOnly;deadline=System.nanoTime()+(peer?480:trainingOnly?420:380)*1_000_000_000L;}
    public static void main(String[] args)throws Exception {
        if(args.length==2&&args[0].equals("--seed-camp")){
            // A disposable completed-tutorial checkpoint isolates the changed Camp interaction.
            try(var store=new AccountStore(Path.of(args[1]))){
                store.create("LanPlayer","lan-test-password".toCharArray());
                char[] password=AccountStore.transform("lan-test-password".toCharArray());
                try{var characters=store.authenticate("LanPlayer",password).characters();
                    var knight=characters.createForOpening(new Knight(0,"Town-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0),1);
                    characters.completeTutorial(knight.id(),1,characters.worldProfile(knight.name(),1));
                }finally{Arrays.fill(password,'\0');}
            }return;
        }
        if(args.length==2&&args[0].equals("--seed-opening")){
            try(var store=new AccountStore(Path.of(args[1]))){store.create("LanPlayer","lan-test-password".toCharArray());}return;
        }
        if(args.length==2&&(args[0].equals("--seed-equipment")||args[0].equals("--seed-items")||args[0].equals("--seed-multiplayer")||args[0].equals("--seed-boxes")||args[0].equals("--seed")||args[0].equals("--seed-depot"))){
            seed(Path.of(args[1]));
            if(args[0].equals("--seed-equipment"))seedEquipment(Path.of(args[1]));
            if(args[0].equals("--seed-items"))try(var store=new AccountStore(Path.of(args[1]))){
                for(String config:ITEM_ACTIONS)store.grantItems("LanPlayer","Town-Knight",config,2,-1,-1);
            }
            if(args[0].equals("--seed-multiplayer"))try(var store=new AccountStore(Path.of(args[1]))){
                store.grantItems("LanPlayer","Town-Knight","Upgrade/Prize Box/Surge Prize Box",1,-1,-1);
                store.grantItems("PeerPlayer","Peer-Knight","Usable/Confetti",2,-1,-1);
            }
            if(args[0].equals("--seed-boxes"))seedBoxes(Path.of(args[1]));
            if(args[0].equals("--seed-depot"))try(var store=new AccountStore(Path.of(args[1]))){
                store.setCurrency("LanPlayer","Town-Knight",org.tortodrone.character.Currency.CROWNS,100000,-1,-1);
                store.setCurrency("LanPlayer","Town-Knight",org.tortodrone.character.Currency.ENERGY,100000,-1,-1);
                var depot=store.depot();var snapshot=depot.snapshot();
                var key=snapshot.offers().stream().filter(o->o.good().item().equals("Ticket/Keys/Silver Key")).findFirst().orElseThrow();
                depot.schedule(snapshot.revision(),new org.tortodrone.storage.DepotStore.Promotion(0,key.id(),0,Long.MAX_VALUE,0,500,750,true,false));
            }
            return;
        }
        boolean peer=args.length>=4&&args[0].equals("--peer");
        if(peer)args=Arrays.copyOfRange(args,1,args.length);
        boolean sharedWorldOnly=Arrays.asList(args).contains("--multiplayer-world-only");
        boolean multiplayerOnly=sharedWorldOnly||Arrays.asList(args).contains("--multiplayer-only");
        boolean trainingOnly=Arrays.asList(args).contains("--camp-progression-only")||Arrays.asList(args).contains("--camp-progression-return-only")||Arrays.asList(args).contains("--camp-story-only")||Arrays.asList(args).contains("--camp-story-return-only")||Arrays.asList(args).contains("--opening-tutorial-only")||Arrays.asList(args).contains("--opening-return-only")||Arrays.asList(args).contains("--tutorial-only")||Arrays.asList(args).contains("--tutorial-entry-only")||Arrays.asList(args).contains("--ready-room-only")||Arrays.asList(args).contains("--equipment-groups")||multiplayerOnly||!peer&&args.length==4&&(args[3].equals("--items-only")||(args[3].equals("--arsenal-blink-only")||args[3].equals("--arsenal-station-only"))||args[3].equals("--boxes-only")||args[3].equals("--town-dialogs-only")||args[3].equals("--dialogue-only")||args[3].equals("--training-only")||args[3].equals("--health-only")||args[3].equals("--movement-only")||args[3].equals("--depot-only")||args[3].equals("--hazards-only")||args[3].equals("--vortex-shield-only"));
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
        var app=new ProjectXApp(peer?"PeerPlayer":"LanPlayer","lan-test-password",false,Arrays.asList(args).contains("--opening-tutorial-only")?null:peer?"Peer-Knight":"Town-Knight",null,null,null,null);
        app.setTitle(peer?"Tortodrone disposable peer rendering check":"Tortodrone disposable combat rendering check");app.setVSyncEnabled(false);
        var probe=new CombatRenderingCheck(app,Path.of(args[2]),peer,trainingOnly);probe.dialogueOnly=!peer&&args.length==4&&args[3].equals("--dialogue-only");
        probe.campProgression=Arrays.asList(args).contains("--camp-progression-only");probe.campProgressionReturn=Arrays.asList(args).contains("--camp-progression-return-only");
        probe.campStory=probe.campProgression||Arrays.asList(args).contains("--camp-story-only");probe.campStoryReturn=Arrays.asList(args).contains("--camp-story-return-only");
        probe.freshOpening=Arrays.asList(args).contains("--opening-tutorial-only");
        probe.tutorialOnly=probe.freshOpening||Arrays.asList(args).contains("--tutorial-only");
        probe.tutorialEntryOnly=probe.tutorialOnly||Arrays.asList(args).contains("--tutorial-entry-only");
        if(probe.tutorialOnly)probe.tutorialMap=org.tortodrone.resources.ContentManager.load(Path.of(System.getProperty("appdir")).getParent()).scenes().scene(398);
        probe.readyRoomOnly=probe.campStory||probe.campStoryReturn||probe.campProgressionReturn||Arrays.asList(args).contains("--ready-room-only")||Arrays.asList(args).contains("--opening-return-only");
        if(probe.campStory||probe.campStoryReturn||probe.campProgressionReturn||Arrays.asList(args).contains("--opening-return-only"))probe.readyRoomDestination=217;
        probe.multiplayerOnly=multiplayerOnly;probe.sharedWorldOnly=sharedWorldOnly;
        probe.hazardsOnly=Arrays.asList(args).contains("--hazards-only");
        probe.boxesOnly=Arrays.asList(args).contains("--boxes-only");
        probe.itemsOnly=Arrays.asList(args).contains("--items-only");
        probe.depotOnly=Arrays.asList(args).contains("--depot-only");
        probe.townDialogsOnly=Arrays.asList(args).contains("--town-dialogs-only");
        probe.arsenalStationOnly=Arrays.asList(args).contains("--arsenal-station-only");
        probe.arsenalBlinkOnly=probe.arsenalStationOnly||Arrays.asList(args).contains("--arsenal-blink-only");
        probe.healthOnly=Arrays.asList(args).contains("--health-only");
        probe.movementOnly=Arrays.asList(args).contains("--movement-only");
        probe.vortexShieldOnly=Arrays.asList(args).contains("--vortex-shield-only");
        probe.equipmentGroups=Arrays.asList(args).contains("--equipment-groups");
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
            store.grantItems("LanPlayer","Town-Knight","Weapon/Bomb/Graviton Vortex",1,-1,-1);
            for(String item:ACTIONS.stream().map(Action::weapon).distinct().toList())store.grantItems("LanPlayer","Town-Knight",item,1,-1,-1);
        }
    }
    private record EquipmentGroup(String name,String item,int slot,int flags,int held,int activity,boolean projectile) { }
    private static final List<EquipmentGroup> EQUIPMENT_GROUPS=List.of(
        new EquipmentGroup("armor-normal","Gear/Armor/Spiral Culet (Blue)",1,0,300,0,false),
        new EquipmentGroup("armor-dual","Gear/Armor/Wolver Coat",1,0,300,0,false),
        new EquipmentGroup("armor-status","Gear/Armor/Vog Cub Coat",1,0,300,0,false),
        new EquipmentGroup("shield-defense","Gear/Shield/Proto Shield",2,64,700,284,false),
        new EquipmentGroup("shield-bash","Gear/Shield/Defender",2,2048,120,287,false),
        new EquipmentGroup("shield-buckler","Gear/Shield/Swiftstrike Buckler",2,2048,120,287,false),
        new EquipmentGroup("sword-three-hit-charge",SWORD,6,4,4000,290,false),
        new EquipmentGroup("sword-heavy-charge","Weapon/Sword/Troika",6,4,4000,290,false),
        // Cutter's ghost damage actors have a one-millisecond fuse, like the
        // native Dud. Their visible charge is an animation, not projectile flight.
        new EquipmentGroup("sword-five-hit-charge","Weapon/Sword/Cutter",6,4,4000,290,false),
        new EquipmentGroup("sword-saber-charge","Weapon/Sword/Flourish",6,4,4000,290,false),
        new EquipmentGroup("sword-rocket-charge","Weapon/Sword/Rocket Hammer 3",6,4,4000,290,false),
        new EquipmentGroup("sword-status-charge","Weapon/Sword/Combuster",6,4,4000,290,true),
        new EquipmentGroup("gun-simple",GUN,6,4,120,292,true),
        new EquipmentGroup("gun-heavy-charge","Weapon/Handgun/Magnus",6,4,4000,290,true),
        new EquipmentGroup("gun-needle-charge",NEEDLE,6,4,4000,290,true),
        new EquipmentGroup("gun-pulse-charge","Weapon/Handgun/Pulsar",6,4,4000,290,true),
        new EquipmentGroup("gun-alchemer-charge","Weapon/Handgun/Voltech Alchemer Mk II",6,4,4000,290,true),
        new EquipmentGroup("gun-tortofist-charge","Weapon/Handgun/Tortafist/Grand Tortofist",6,4,4000,290,true),
        new EquipmentGroup("bomb-charge-only",BOMB,6,4,4000,290,true),
        new EquipmentGroup("bomb-shard","Weapon/Bomb/Crystal Bomb",6,4,4000,290,true),
        new EquipmentGroup("bomb-chemical","Weapon/Bomb/Fiery Vaporizer",6,4,4000,290,true),
        new EquipmentGroup("bomb-relic","Weapon/Bomb/Electron Bomb",6,4,4000,290,true),
        new EquipmentGroup("bomb-vortex","Weapon/Bomb/Graviton Vortex",6,4,4000,290,true),
        new EquipmentGroup("sprite-drakon","Drakon",-1,128,120,288,true),
        new EquipmentGroup("sprite-maskeraith","Maskeraith",-1,128,120,288,true),
        new EquipmentGroup("sprite-seraphynx-aura","Seraphynx",-1,384,120,288,false));
    private static void seedEquipment(Path data)throws Exception {
        try(var store=new AccountStore(data)){
            store.setGameMaster("LanPlayer",true);
            for(var group:EQUIPMENT_GROUPS)if(group.slot>=0){
                if(store.worldProfile("LanPlayer","Town-Knight").items().stream().noneMatch(i->i.config().equals(group.item)))
                    store.grantItems("LanPlayer","Town-Knight",group.item,1,-1,-1);
                var profile=store.worldProfile("LanPlayer","Town-Knight");var item=profile.items().stream().filter(i->i.config().equals(group.item)).findFirst().orElseThrow();
                int levels=store.townRules().gear().gear(item.config()).levels();
                store.itemModifiers("LanPlayer","Town-Knight",item.id(),new org.tortodrone.character.ItemModifiers(Math.max(0,levels-1),0,List.of()),-1,-1,profile.revision());
            }
            char[] password=AccountStore.transform("lan-test-password".toCharArray());
            try{
                var account=store.authenticate("LanPlayer",password);var town=account.characters().town(store.townRules());int knight=account.characters().roster().getFirst().id();
                for(String config:List.of("Drakon","Maskeraith","Seraphynx")){
                    var created=town.adminSprite(account.id(),knight,account.authRevision(),8,List.of(new org.tortodrone.protocol.SessionDecoder.ConfigReference(config,Map.of()))).profile();
                    int id=created.town().sprites().stream().filter(s->s.config().equals(config)).findFirst().orElseThrow().id();
                    town.sprite(account.id(),knight,account.authRevision(),4,List.of(id),true);
                    town.adminSprite(account.id(),knight,account.authRevision(),45,List.of(99));
                }
            }finally{Arrays.fill(password,'\0');}
        }
    }
    private Path groupRoot(){return peer?output.getParent():output;}
    private boolean groupActivity,groupProjectile,groupOwnCaptured;
    private boolean groupOrigin;
    private float groupOriginX,groupOriginY;
    private int groupPeerIndex=-1;
    private void equipmentGroupsTick(View view,long now)throws Exception {
        Path root=groupRoot();
        if(peer&&!Files.exists(output.resolve("peer-ready")))Files.writeString(output.resolve("peer-ready"),"");
        if(stage==0){GLFW.glfwSetWindowSize(app.getWindow(),960,720);app.GF().bl(294);stage=1;started=now;return;}
        if(view.sceneId()!=294)return;
        var remote=authoritative.values().stream().filter(a->a instanceof Dungeoneer&&a.vw()!=app.CT().pawnId).findFirst().orElse(null);
        if(remote==null){if(stage>1&&now-started>10_000_000_000L)throw new AssertionError("Second knight left the grouped Training scenario");return;}
        if(view.PO().acU().vw()!=app.CT().pawnId)throw new AssertionError("Camera ownership changed to the second knight");
        if(peer){
            var own=view.PO().acU();float dx=remote.tz().x-own.tz().x,dy=remote.tz().y-own.tz().y;
            moveToward(view,dx,dy,Math.hypot(dx,dy)>3);
            if(Files.exists(root.resolve("groups-complete"))){finishImages();passed=done=true;System.out.println("PASS: second stock knight observed every equipment group in the shared Training Hall");app.shutdown();return;}
            if(!Files.exists(root.resolve("group-current")))return;
            int current=Integer.parseInt(Files.readString(root.resolve("group-current")).trim());
            if(current!=groupPeerIndex){groupPeerIndex=current;groupActivity=groupProjectile=false;previousActors.clear();previousActors.addAll(authoritative.keySet());}
            var group=EQUIPMENT_GROUPS.get(current);var sprite=view.gk(remote.vw());
            if(((Dungeoneer)remote).adU()==group.activity&&sprite!=null&&sprite.acL().isVisible()&&(group.activity==0||activityPlaying(sprite,group.activity))){
                if(!groupActivity)System.out.println("OBSERVED ACTIVITY: "+current+" "+group.name+" "+group.activity);groupActivity=true;
            }
            for(var actor:authoritative.values())if(!previousActors.contains(actor.vw())&&!(actor instanceof Dungeoneer)&&(actor.Mv().getName().startsWith("Bullet/")||actor.Mv().getName().startsWith("Bomb/"))){
                var rendered=view.gk(actor.vw());if(rendered!=null&&rendered.acL().isVisible()&&rendered.acL().getConfig()!=null)groupProjectile=true;
            }
            if(group.slot==-1){
                var pet=authoritative.values().stream().filter(a->a instanceof com.threerings.projectx.dungeon.data.actor.SpriteActor p&&p.adU()==(group.flags>>>7)).findFirst().orElse(null);
                if(pet==null||view.gk(pet.vw())==null||view.gk(pet.vw()).acL().getConfig()==null)return;
                int[] recharge=(int[])field(pet,"_nextSkill").get(pet);
                if(recharge[0]!=recharge[1]||recharge[1]!=recharge[2])throw new AssertionError("Sprite shared cooldown differs between slots");
            }
            if(groupActivity&&(!group.projectile||groupProjectile)&&!Files.exists(root.resolve("group-seen-"+current))){
                capture("group-"+current+"-"+group.name);Files.writeString(root.resolve("group-seen-"+current),"");
                System.out.println("CHECK: remote group "+current+" "+group.name+" activity="+group.activity+" projectile="+groupProjectile);
            }
            return;
        }
        if(index==EQUIPMENT_GROUPS.size()){
            capture("groups-complete");finishImages();Files.writeString(root.resolve("groups-complete"),"");passed=done=true;
            System.out.println("PASS: two stock knights in Training Hall; "+index+" armor/shield/sword/gun/bomb/sprite groups, leveled equipment, native animations and replicated projectiles");app.shutdown();return;
        }
        var group=EQUIPMENT_GROUPS.get(index);
        if(stage==1){
            var position=view.PO().acU().tz();
            if(!groupOrigin){groupOrigin=true;groupOriginX=position.x;groupOriginY=position.y+2;}
            float dx=groupOriginX-position.x,dy=groupOriginY-position.y;
            moveToward(view,dx,dy,Math.hypot(dx,dy)>.4);
            if(Math.hypot(dx,dy)>.4)return;
            equip=new Confirm();
            if(group.slot<0){
                int id=0;for(var sprite:app.CT().sprites)if(sprite.Mv().getName().equals(group.item))id=sprite.vw();
                if(id==0)throw new AssertionError("Missing seeded sprite "+group.item);
                app.zr().bU(com.threerings.projectx.sprites.a.a.class).o(id,equip);
            }else{
                long id=0;for(var item:app.CT().items)if(item.Vx().getName().equals(group.item))id=item.VA();
                if(id==0)throw new AssertionError("Missing seeded item "+group.item);
                app.zr().bU(com.threerings.projectx.item.client.L.class).a(id,group.slot,equip);
            }
            started=now;stage=2;return;
        }
        if(stage==2){
            if(!equip.done)return;if(equip.reason!=null)throw new AssertionError(group.name+": "+equip.reason);
            var own=(Dungeoneer)view.PO().acU();if(own.adU()!=0)return;
            if(group.slot==6){var weapon=(Item)field(own,"_weapon").get(own);var rendered=(Item)field(view.PO().implementation(),"_weapon").get(view.PO().implementation());if(weapon==null||rendered==null||!weapon.Vx().getName().equals(group.item)||!rendered.Vx().getName().equals(group.item))return;}
            if(group.slot<0){
                var pet=authoritative.values().stream().filter(a->a instanceof com.threerings.projectx.dungeon.data.actor.SpriteActor).findFirst().orElse(null);
                if(pet==null)return;int[] cooldown=(int[])field(pet,"_nextSkill").get(pet);if(cooldown[0]>lastStamp)return;
            }
            Files.writeString(root.resolve("group-current.tmp"),Integer.toString(index));
            Files.move(root.resolve("group-current.tmp"),root.resolve("group-current"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            previousActors.clear();previousActors.addAll(authoritative.keySet());
            moveNorth(view,false);groupOwnCaptured=false;released=false;started=now;flags(view,group.flags);stage=3;System.out.println("GROUP: "+index+" "+group.name);return;
        }
        if(stage==3){
            int elapsed=(int)((now-started)/1_000_000);
            if(!released&&elapsed>=group.held){flags(view,0);released=true;}
            // The controlling advancer predicts and reconciles its activity before
            // the sprite tick. Its transient actor value can miss an animation
            // sample; the peer separately asserts the exact native activity/animation.
            if(!groupOwnCaptured&&elapsed>=200&&view.PO().acL().isVisible()&&view.PO().acL().getConfig()!=null){
                capture("group-"+index+"-"+group.name);groupOwnCaptured=true;
            }
            if(elapsed>group.held+2200&&released&&groupOwnCaptured&&Files.exists(root.resolve("group-seen-"+index))){flags(view,0);index++;stage=1;started=now;return;}
            if(elapsed>group.held+10000)throw new AssertionError("Equipment group was not rendered by both knights: "+group.name+" local="+groupOwnCaptured+" peer="+Files.exists(root.resolve("group-seen-"+index)));
        }
    }
    private static void seedBoxes(Path data)throws Exception {
        // Seed only this disposable account; use the shipped approximation tables unchanged.
        try(var store=new AccountStore(data)) {
            for(String item:List.of("Upgrade/Lockbox","Upgrade/Lockboxes/Slime/000","Upgrade/Prize Box/Surge Prize Box","Upgrade/Prize Box/Almire Furniture Box","Upgrade/Prize Box/Disguise Prize Box","Upgrade/Prize Box/Dark Harvest Prize Box, Complete","Ticket/Keys/Silver Key","Ticket/Keys/Slime Key"))
                store.grantItems("LanPlayer","Town-Knight",item,item.equals("Ticket/Keys/Silver Key")?3:2,-1,-1);
        }
    }
    private int boxIndex,boxBefore,silverBefore,slimeBefore;
    private Map<Long,Integer> boxInventoryBefore;
    private boolean boxCancelled,boxActivitySeen,boxActorCaptured;
    private com.threerings.opengl.gui.aD boxArsenal;
    private com.threerings.projectx.item.client.F boxMenu;
    private com.threerings.projectx.item.client.Y boxWindow;
    private static final String[] BOX_ITEMS={"Upgrade/Lockbox","Upgrade/Lockboxes/Slime/000","Upgrade/Lockboxes/Slime/000","Upgrade/Prize Box/Surge Prize Box","Upgrade/Prize Box/Almire Furniture Box","Upgrade/Prize Box/Disguise Prize Box","Upgrade/Prize Box/Dark Harvest Prize Box, Complete"};
    private static final String[] BOX_ACTIONS={"unlock_0","unlock_0","unlock_1","open","open","open","open"};
    private void boxesTick(long now)throws Exception {
        if(stage==0) {
            var arsenal=new com.threerings.projectx.item.client.ArsenalPanel(app);
            boxArsenal=new com.threerings.opengl.gui.aD(app,null);
            // Retail contains a class and a package both named gui.d; resolve its layout by name.
            boxArsenal.getClass().getMethod("setLayoutManager",Class.forName("com.threerings.opengl.gui.d.g")).invoke(boxArsenal,Class.forName("com.threerings.opengl.gui.d.c").getConstructor().newInstance());boxArsenal.add(arsenal,Class.forName("com.threerings.opengl.gui.d.c").getField("CENTER").get(null));
            app.GG().addWindow(boxArsenal,false);boxArsenal.pack();boxArsenal.center();
            arsenal.e(com.threerings.projectx.item.data.ItemCodes.Group.UPGRADE);started=now;stage=70;return;
        }
        String config=BOX_ITEMS[boxIndex];
        if(stage==70&&now-started>1_000_000_000L) {
            Item item=null;for(var candidate:app.CT().items)if(candidate.Vx().getName().equals(config)){item=candidate;break;}
            if(item==null)throw new AssertionError("Missing fixture box: "+config);
            boxActivitySeen=boxActorCaptured=false;boxBefore=itemCount(config);silverBefore=itemCount("Ticket/Keys/Silver Key");slimeBefore=itemCount("Ticket/Keys/Slime Key");boxInventoryBefore=boxInventory();
            boxMenu=new com.threerings.projectx.item.client.F(app,boxArsenal,item);boxMenu.c(220,220,false);
            capture("box-"+boxIndex+"-arsenal-menu");boxMenu.n(BOX_ACTIONS[boxIndex],null);started=now;stage=71;return;
        }
        if(stage==71&&now-started>1_000_000_000L) {
            for(int n=0;n<app.getRoot().getWindowCount();n++)if(app.getRoot().getWindow(n) instanceof com.threerings.projectx.client.ui.k confirm) {
                capture("box-"+boxIndex+"-confirmation");
                if(boxIndex==0&&!boxCancelled) {
                    ((com.threerings.opengl.gui.e)field(confirm,"awp").get(confirm)).doClick();boxCancelled=true;started=now;stage=74;return;
                }
                ((com.threerings.opengl.gui.e)field(confirm,"aCW").get(confirm)).doClick();started=now;stage=72;return;
            }
            // A native preference may suppress subsequent confirmations in this disposable process.
            for(int n=0;n<app.getRoot().getWindowCount();n++)if(app.getRoot().getWindow(n) instanceof com.threerings.projectx.item.client.Y){started=now;stage=72;return;}
        }
        if(stage==74&&now-started>1_000_000_000L) {
            if(itemCount(config)!=boxBefore||itemCount("Ticket/Keys/Silver Key")!=silverBefore||!boxInventory().equals(boxInventoryBefore))throw new AssertionError("Cancelled opening changed inventory");
            boxMenu.n(BOX_ACTIONS[boxIndex],null);started=now;stage=71;return;
        }
        if(stage==72) {
            var own=authoritative.get(app.CT().pawnId);
            if(!boxActivitySeen&&own instanceof com.threerings.projectx.data.actor.Knight knight&&knight.adU()==265){
                var references=knight.bY(List.class);
                if(references==null||references.size()<2||!((com.threerings.config.ConfigReference<?>)references.getFirst()).getName().equals(config))throw new AssertionError("Opening actor lacks box/reward references");
                boxActivitySeen=true;
            }
            if(boxActivitySeen&&!boxActorCaptured&&now-started>4_000_000_000L){capture("box-"+boxIndex+"-opening-actor");boxActorCaptured=true;}
            for(int n=0;n<app.getRoot().getWindowCount();n++)if(app.getRoot().getWindow(n) instanceof com.threerings.projectx.item.client.Y window)boxWindow=window;
            if(boxWindow==null||now-started<10_000_000_000L)return;
            if(!boxActivitySeen)throw new AssertionError("Native actor opening animation was never published");
            var prizes=(List<?>)field(boxWindow,"bvi").get(boxWindow);
            if(prizes==null||prizes.isEmpty()||boxIndex==3&&prizes.size()<2)throw new AssertionError("Native opening result did not resolve awarded IDs and the guaranteed Surge bundle");
            int awarded=0;
            for(Object raw:prizes) {
                var prize=(com.google.common.collect.Multiset.Entry<?>)raw;Long id=(Long)prize.getElement();
                var held=app.CT().items.f(id);
                if(held==null||held.getCount()!=boxInventoryBefore.getOrDefault(id,0)+prize.getCount())throw new AssertionError("Native reveal differs from committed inventory delta");
                if(boxIndex==4&&!(held instanceof com.threerings.projectx.item.data.FurniItem))throw new AssertionError("Furniture box returned a different subtype");
                awarded+=prize.getCount();
            }
            if(boxIndex==5&&prizes.stream().map(e->app.CT().items.f((Long)((com.google.common.collect.Multiset.Entry<?>)e).getElement())).noneMatch(i->i.VE()!=null&&i.VE()>System.currentTimeMillis()))throw new AssertionError("Disguise bundle lacks persisted native expiration");
            int silverSpent=boxIndex==0||boxIndex==2?1:0,slimeSpent=boxIndex==1?1:0;
            if(itemCount(config)!=boxBefore-1||itemCount("Ticket/Keys/Silver Key")!=silverBefore-silverSpent||itemCount("Ticket/Keys/Slime Key")!=slimeBefore-slimeSpent||boxInventory().values().stream().mapToInt(Integer::intValue).sum()!=boxInventoryBefore.values().stream().mapToInt(Integer::intValue).sum()-1-silverSpent-slimeSpent+awarded)
                throw new AssertionError("Opening consumed or rewarded the wrong items");
            capture("box-"+boxIndex+"-native-reward");boxWindow.dismiss();boxWindow=null;boxMenu.dismiss();
            if(++boxIndex==BOX_ITEMS.length) {
                boxArsenal.dismiss();finishImages();passed=done=true;
                System.out.println("PASS: unchanged Arsenal menu, cancel/confirm, Silver Lockbox, both Slime key choices, keyless Prize Boxes including Dark Harvest, scene-owned opening animations and native reward windows with multi-item Surge/Disguise bundles, furniture and timed rewards");app.shutdown();return;
            }
            started=now;stage=70;
        }
    }
    private Map<Long,Integer> boxInventory(){
        var result=new HashMap<Long,Integer>();for(var item:app.CT().items)result.put(item.Mw(),item.getCount());return result;
    }
    private static final String[] ITEM_ACTIONS={"Usable/Fireworks/Small/Small Fireworks, Amber","Usable/Fireworks/Medium/Medium Fireworks, Amber","Usable/Fireworks/Large/Large Fireworks, Amber","Usable/Jar O Fireflies","Usable/Confetti"};
    private int itemActionIndex,itemBefore;
    private boolean itemAnimationSeen,itemEffectSeen,itemCaptured,itemWindowClosed;
    private void itemsTick(View view,long now)throws Exception {
        if(stage==0){GLFW.glfwFocusWindow(app.getWindow());started=now;stage=90;return;}
        String config=ITEM_ACTIONS[itemActionIndex];
        if(stage==90&&now-started>1_000_000_000L){
            var arsenal=new com.threerings.projectx.item.client.ArsenalPanel(app);boxArsenal=new com.threerings.opengl.gui.aD(app,null);
            boxArsenal.getClass().getMethod("setLayoutManager",Class.forName("com.threerings.opengl.gui.d.g")).invoke(boxArsenal,Class.forName("com.threerings.opengl.gui.d.c").getConstructor().newInstance());
            boxArsenal.add(arsenal,Class.forName("com.threerings.opengl.gui.d.c").getField("CENTER").get(null));
            app.GG().addWindow(boxArsenal,false);boxArsenal.pack();boxArsenal.center();arsenal.e(com.threerings.projectx.item.data.ItemCodes.Group.UPGRADE);
            flags(view,2);started=now;stage=91;return;
        }
        if(stage==91&&now-started>500_000_000L){
            var item=app.CT().items.f(findItemId(config));itemBefore=itemCount(config);
            boxMenu=new com.threerings.projectx.item.client.F(app,boxArsenal,item);boxMenu.c(220,220,false);
            capture("item-"+itemActionIndex+"-arsenal-use");itemAnimationSeen=itemEffectSeen=itemCaptured=itemWindowClosed=false;
            boxMenu.n("use",null);flags(view,0);started=now;stage=92;return;
        }
        if(stage==92){
            if(!itemWindowClosed&&now-started>150_000_000L){boxArsenal.dismiss();itemWindowClosed=true;return;}
            var own=authoritative.get(app.CT().pawnId);
            if(own instanceof com.threerings.projectx.data.actor.Knight knight&&knight.adU()==267){
                itemAnimationSeen=true;
                if(itemWindowClosed&&!itemCaptured&&activityPlaying(view.gk(own.vw()),267)){capture("item-"+itemActionIndex+"-animation");itemCaptured=true;}
            }
            if(now-started>3_000_000_000L){
                if(!itemAnimationSeen||!itemCaptured||itemActionIndex<4&&!itemEffectSeen||itemCount(config)!=itemBefore-1)
                    throw new AssertionError("Native item use failed: "+config+" animation="+itemAnimationSeen+" captured="+itemCaptured+" effect="+itemEffectSeen+" count="+itemCount(config));
                capture("item-"+itemActionIndex+"-effect");
                if(++itemActionIndex==ITEM_ACTIONS.length){finishImages();passed=done=true;System.out.println("PASS: unchanged Arsenal Use, three firework sizes, fireflies and confetti; native animations/effects and single inventory consumption");app.shutdown();return;}
                stage=90;started=now;
            }
        }
    }
    private boolean tutorialEntryOnly;
    private void tutorialEntryTick(long now)throws Exception {
        if(app.CT()==null||app.Gu()==null)return;
        if(started==0)started=now;
        if(stage<3&&now-started>60_000_000_000L)throw new AssertionError("Tutorial entry timed out; zone="+app.CT().zoneId+" loading="+app.GF().mG());
        if(stage==0&&app.CT().zoneId==org.tortodrone.world.ReadyRoom.ZONE&&Class.forName("com.threerings.projectx.client.dR").isInstance(app.Gu())&&!app.GF().mG()){
            Object controller=new View(app.Gu()).acn();
            controller.getClass().getMethod("a",Object.class,String.class,Object.class).invoke(controller,null,"town",null);stage=1;return;
        }
        if(app.GF().mG()||!Class.forName("com.threerings.tudey.a.i").isInstance(app.Gu()))return;
        var view=new View(app.Gu());
        if(stage<=1&&view.sceneId()==1&&view.PO()!=null){GLFW.glfwFocusWindow(app.getWindow());app.GF().bl(398);stage=2;started=now;System.out.println("CHECK: native scene travel to original Crash Site 398");return;}
        if((stage==2||freshOpening&&stage==0)&&view.sceneId()==398&&view.PO()!=null&&view.PO().acU() instanceof Dungeoneer){stage=3;started=now;}
        if(tutorialOnly&&stage>=3){tutorialPlayTick(view,now);return;}
        if(stage==3&&now-started>5_000_000_000L){capture("crash-site-entrance");finishImages();passed=done=true;System.out.println("PASS: stock graphical Crash Site entry and controlled Dungeoneer remain connected for five seconds");app.shutdown();}
    }
    private boolean tutorialOnly;
    private SceneCatalog.MapScene tutorialMap;
    private int tutorialWaypoint,tutorialNode,tutorialBoss;
    private List<org.tortodrone.world.ScenePathing.Point> tutorialPath=List.of();
    private long tutorialResponseAt,tutorialProgressAt,tutorialCombatAt,tutorialRepathAt,tutorialRouteRetryAt,tutorialEnemySince;
    private double tutorialBestDistance=Double.POSITIVE_INFINITY;
    private final Map<String,org.tortodrone.world.SceneContent.Spec> tutorialSpecs=new HashMap<>();
    private String tutorialDialog="";
    private int tutorialEnemy;
    private float tutorialEnemyGoalX,tutorialEnemyGoalY;
    private float tutorialEnemyPoseX,tutorialEnemyPoseY;
    private float tutorialCollectedHeat;
    private long tutorialDeathAt;
    private final Set<String> tutorialEnemyAttacks=new HashSet<>();
    private final Set<String> tutorialHelperAttacks=new HashSet<>(),tutorialHelperDamage=new HashSet<>();
    private final Set<Integer> tutorialGiantHeads=new HashSet<>();
    private boolean tutorialGiantDamage;
    private com.threerings.projectx.dungeon.data.PartyObject tutorialObservedParty;
    private float tutorialLastX,tutorialLastY;
    private static final float[][] TUTORIAL_WALK={{-21,-60},{-1.5f,-69},{-9.5f,-49},{-13,-29},{-26,-21},{-22.5f,-12},{-7.5f,-10},{-4.5f,-4},{-3.5f,7.5f},{-3.5f,16.5f},{8.5f,39.5f},{7.5f,50.5f},{11.5f,57.5f}};
    private void tutorialPlayTick(View view,long now)throws Exception {
        if(view.sceneId()==217){if(stage!=6){stage=6;started=now;capture("rescue-camp-arrival");}if(now-started>5_000_000_000L){
            if(!tutorialEnemyAttacks.containsAll(Set.of("Character/NPC/Monster/Slime/Jelly Cube (Intro)","Character/NPC/Monster/Beast/Chromalisk/Chromalisk Whelp (Intro)","Character/NPC/Monster/Construct/Gun Puppy/Gun Puppy (TG)")))throw new AssertionError("Tutorial did not exercise all three sourced enemy families: "+tutorialEnemyAttacks);
            boolean heated=false;for(var item:app.CT().items)if(item instanceof com.threerings.projectx.item.data.LevelItem&&field(item,"_heatProgress").getFloat(item)>0)heated=true;
            if(tutorialCollectedHeat<=0||!heated)throw new AssertionError("Collected floor heat did not reach native equipment progress at Camp");
            if(!tutorialHelperAttacks.containsAll(Set.of("Bygs","Warden","Wend"))||tutorialHelperDamage.isEmpty())throw new AssertionError("Original helper combat missing: attacks="+tutorialHelperAttacks+" Giant damage="+tutorialHelperDamage);
            if(!tutorialGiantHeads.containsAll(Set.of(0,1))||!tutorialGiantDamage)throw new AssertionError("Giant retaliation missing: native heads="+tutorialGiantHeads+" damage="+tutorialGiantDamage);
            capture("rescue-camp-settled");finishImages();passed=done=true;System.out.println(freshOpening?"PASS: native fresh-account knight creation -> automatic private Crash Site -> real movement/conversations/combat/helpers/Giant -> original doors/portal -> Camp and native heat settlement; reconnect checked by parent scenario":"PASS: stock graphical GM preview -> native combat/helpers/calibrated Giant -> original doors/portal -> Camp and heat; fresh creation/reconnect are separate scenarios");app.shutdown();}return;}
        if(view.sceneId()!=398)return;
        var own=authoritative.get(app.CT().pawnId);if(own==null)return;
        if(app.GQ().CW() instanceof com.threerings.projectx.dungeon.data.LevelPartyObject party){
            if(tutorialObservedParty!=party){tutorialObservedParty=party;party.a(new com.threerings.projectx.dungeon.data.PartyObject.b(){public void a(com.threerings.projectx.dungeon.data.PartyObject.DamageEvent event){
                var target=authoritative.get(event.QU());var source=authoritative.get(event.Qx());
                int damage=target instanceof Dungeoneer?event.QZ()>>>20:event.QZ();
                if(damage>0&&target!=null&&source!=null&&source.Mv().getName().equals("Character/NPC/Monster/Boss/Tutorial, Ironwood Giant/Ironwood Giant")){
                    if(!tutorialGiantDamage)System.out.println("GIANT DAMAGE: target="+event.QU()+" amount="+damage);tutorialGiantDamage=true;
                }
                if(event.QZ()>0&&target!=null&&source!=null&&target.Mv().getName().contains("Ironwood Giant")&&source.Mv().getName().startsWith("Character/Scripted/Missions/Tutorial/Crashsite/Mini Boss/Knights/")){
                    String role=source.Mv().getName().substring(source.Mv().getName().lastIndexOf('/')+1);if(tutorialHelperDamage.add(role))System.out.println("HELPER DAMAGE: "+role+" -> Giant amount="+event.QZ());
                }
            }},false);}
            var heat=party.heat.f(app.CT().xL());
            if(heat!=null&&heat.amount>tutorialCollectedHeat){if(tutorialCollectedHeat==0)capture("tutorial-heat-collected");tutorialCollectedHeat=heat.amount;System.out.println("HEAT: native floor amount="+heat.amount);}
            var health=party.health.f(app.CT().xL());
            if(health!=null&&health.current<=0){flags(view,0);if(tutorialDeathAt==0){tutorialDeathAt=now;capture("tutorial-pawn-dead");}if(now-tutorialDeathAt>1_000_000_000L)throw new AssertionError("Tutorial pawn died during normal combat; no death/health shortcut used");return;}
        }
        if(app.CT().sceneDialog instanceof com.threerings.projectx.data.ConversationDialogInfo info){
            flags(view,0);String key=info.name+":"+info.message;
            if(!key.equals(tutorialDialog)&&now>=tutorialResponseAt){tutorialDialog=key;tutorialResponseAt=now+750_000_000L;capture("tutorial-dialog-"+tutorialWaypoint+"-"+Math.abs(key.hashCode()));System.out.println("DIALOG: "+key);info.conversationService.f(0,new Confirm());}
            return;
        }
        if(tutorialProgressAt==0){capture("crash-site-entrance");tutorialProgressAt=now;tutorialLastX=own.tz().x;tutorialLastY=own.tz().y;}
        for(var actor:authoritative.values()){
            if(actor.Mv().getName().equals("Character/NPC/Monster/Boss/Tutorial, Ironwood Giant/Ironwood Giant")){
                tutorialBoss=actor.vw();
                if(actor instanceof com.threerings.projectx.dungeon.data.actor.Monster monster&&monster.adU()>=4&&tutorialGiantHeads.add(monster.adU()-4)){
                    capture("tutorial-giant-head-"+(monster.adU()-4));System.out.println("GIANT ATTACK: native head="+(monster.adU()-4));
                }
            }
            if(actor instanceof com.threerings.projectx.dungeon.data.actor.Monster monster&&monster.adU()>=4&&actor.bR(16)&&actor.Mv().getName().startsWith("Character/Scripted/Missions/Tutorial/Crashsite/Mini Boss/Knights/")){
                String role=actor.Mv().getName().substring(actor.Mv().getName().lastIndexOf('/')+1);if(tutorialHelperAttacks.add(role)){capture("tutorial-helper-"+role);System.out.println("HELPER ATTACK: "+role+" activity="+monster.adU());}
            }
        }
        if(stage==3||stage==4){
            // Fight nearby live enemies with the stock controller. Do not drive through
            // them or change health/actor state in order to keep the route moving.
            Actor nearest=null;double distance=own.tz().y>55?14:4.2;
            for(var actor:authoritative.values())if(actor instanceof com.threerings.projectx.dungeon.data.actor.Monster monster
                    &&actor.Mv().getName().startsWith("Character/NPC/Monster/")&&!actor.Mv().getName().contains("Ironwood Giant")&&monsterHealth(actor)>0){
                if(monster.adU()>=4&&tutorialEnemyAttacks.add(actor.Mv().getName())){capture("tutorial-enemy-attack-"+tutorialEnemyAttacks.size());System.out.println("ENEMY: native attack "+actor.Mv().getName()+" activity="+monster.adU()+" at="+actor.tz());}
                double d=Math.hypot(actor.tz().x-own.tz().x,actor.tz().y-own.tz().y);if(d<distance){distance=d;nearest=actor;}
            }
            var locked=authoritative.get(tutorialEnemy);
            if(locked!=null&&monsterHealth(locked)>0){double d=Math.hypot(locked.tz().x-own.tz().x,locked.tz().y-own.tz().y);if(d<(own.tz().y>55?14:4.2)){nearest=locked;distance=d;}}
            if(nearest!=null){
                if(tutorialEnemy!=nearest.vw()||Math.hypot(nearest.tz().x-tutorialEnemyPoseX,nearest.tz().y-tutorialEnemyPoseY)>.35){
                    tutorialEnemy=nearest.vw();tutorialEnemySince=now;tutorialPath=List.of();tutorialProgressAt=now;tutorialBestDistance=Double.POSITIVE_INFINITY;
                    tutorialEnemyPoseX=nearest.tz().x;tutorialEnemyPoseY=nearest.tz().y;
                    tutorialEnemyGoalX=nearest.tz().x-(float)((nearest.tz().x-own.tz().x)/distance)*1.25f;
                    tutorialEnemyGoalY=nearest.tz().y-(float)((nearest.tz().y-own.tz().y)/distance)*1.25f;
                    System.out.println("COMBAT: approaching "+nearest.Mv().getName()+" id="+tutorialEnemy);
                }
                boolean moving=distance>1.4;
                if(moving&&own.tz().y>55){
                    tutorialWalk(view,own,tutorialEnemyGoalX,tutorialEnemyGoalY,now);
                }else moveToward(view,nearest.tz().x-own.tz().x,nearest.tz().y-own.tz().y,moving);
                flags(view,(moving?1:0)|((now-tutorialEnemySince)%1_250_000_000L<100_000_000L?4:0));
                if(!moving){tutorialProgressAt=now;tutorialBestDistance=Double.POSITIVE_INFINITY;}return;
            }
            if(tutorialEnemy!=0){tutorialEnemy=0;tutorialPath=List.of();tutorialCombatAt=now;}
        }
        if(stage==3&&tutorialWaypoint<TUTORIAL_WALK.length){
            var target=TUTORIAL_WALK[tutorialWaypoint];
            if(tutorialWalk(view,own,target[0],target[1],now)){System.out.println("WALK: "+tutorialWaypoint+" -> "+own.tz());tutorialWaypoint++;tutorialPath=List.of();tutorialNode=0;tutorialProgressAt=now;tutorialBestDistance=Double.POSITIVE_INFINITY;}
            return;
        }
        if(stage==3){
            var boss=authoritative.values().stream().filter(a->a.Mv().getName().equals("Character/NPC/Monster/Boss/Tutorial, Ironwood Giant/Ironwood Giant")).findFirst().orElse(null);
            if(boss==null){
                if(tutorialBoss!=0&&!tutorialHelperDamage.isEmpty()){stage=5;tutorialWaypoint=0;tutorialPath=List.of();tutorialNode=0;tutorialProgressAt=now;capture("ironwood-giant-removed");return;}
                if(now-started>90_000_000_000L)throw new AssertionError("Original conversation did not spawn Giant");return;
            }
            tutorialBoss=boss.vw();if(tutorialWalk(view,own,boss.tz().x,boss.tz().y-1.75f,now)){flags(view,0);stage=4;tutorialCombatAt=now;capture("ironwood-giant-encounter");}return;
        }
        if(stage==4){var boss=authoritative.get(tutorialBoss);
            if(boss==null){stage=5;tutorialWaypoint=0;tutorialPath=List.of();tutorialNode=0;tutorialProgressAt=now;capture("ironwood-giant-removed");return;}
            moveToward(view,boss.tz().x-own.tz().x,boss.tz().y-own.tz().y,false);
            boolean guard=tutorialGiantDamage&&boss instanceof com.threerings.projectx.dungeon.data.actor.Monster monster
                &&(monster.adU()==4||monster.adU()==5&&lastStamp-monster.adV()<2800);
            flags(view,guard?64:(now-tutorialCombatAt)%1_250_000_000L<100_000_000L?4:0);
            if(now-tutorialCombatAt>180_000_000_000L)throw new AssertionError("Normal stock sword input failed to defeat Giant; health="+monsterHealth(boss));return;
        }
        if(stage==5){float[][] exit={{13.5f,70},{13.5f,90.5f},{13,96.5f}};
            if(tutorialWaypoint<exit.length){if(tutorialWalk(view,own,exit[tutorialWaypoint][0],exit[tutorialWaypoint][1],now)){tutorialWaypoint++;tutorialPath=List.of();tutorialNode=0;tutorialProgressAt=now;capture("tutorial-exit-walk-"+tutorialWaypoint);}}
            else {flags(view,0);var root=app.getRoot();for(int i=0;i<root.getWindowCount();i++)if(root.getWindow(i) instanceof com.threerings.projectx.dungeon.client.EndOfLevelWindow report)report.bX(true);}
        }
    }
    private boolean tutorialWalk(View view,Actor own,float x,float y,long now)throws Exception {
        // Route planning provides input targets only. The stock controller and server
        // still decide movement, collision, attack eligibility and progression.
        if(Math.hypot(own.tz().x-x,own.tz().y-y)<.35){flags(view,0);return true;}
        if(tutorialPath.isEmpty()){
            if(now<tutorialRouteRetryAt){flags(view,0);return false;}
            if(tutorialSpecs.isEmpty())for(var e:tutorialMap.content().entries()){
                tutorialSpecs.putIfAbsent(e.spec().config().name(),e.spec());
                if(!e.spec().text("action","").isEmpty())tutorialSpawnSpecs(org.tortodrone.world.SceneActions.parse(e.spec().text("action","")));
            }
            int mask=Math.round(tutorialMap.content().stats().get("player.collision-mask"));
            tutorialPath=org.tortodrone.world.ScenePathing.route(own.tz().x,own.tz().y,x,y,(sx,sy,tx,ty)->{
                int count=Math.max(1,(int)Math.ceil(Math.hypot(tx-sx,ty-sy)/.1));
                for(int i=1;i<=count;i++){float px=sx+(tx-sx)*i/count,py=sy+(ty-sy)*i/count;
                    if(!tutorialMap.staticCollision().floorSupported(px,py)||tutorialMap.staticCollision().penetration(mask,px,py,org.tortodrone.world.ScenePortals.PAWN_RADIUS).present())return false;
                    for(var obstacle:authoritative.values())if(obstacle.vw()!=own.vw()){
                        var spec=tutorialSpecs.get(obstacle.Mv().getName());
                        if(spec==null||(spec.integer("collision-flags",0)&mask)==0||spec.text("damage-action","").equals("destroy")||Set.of("TimedBlock","StoneBlock").contains(spec.text("kind",""))||spec.text("kind","").equals("Door")&&obstacle.bR(2))continue;
                        if(spec.contains(obstacle.tz().x,obstacle.tz().y,obstacle.tA(),px,py,org.tortodrone.world.ScenePortals.PAWN_RADIUS))return false;
                    }
                }return true;
            });tutorialNode=0;
            if(tutorialPath.isEmpty()){flags(view,0);tutorialRouteRetryAt=now+500_000_000L;if(now-tutorialProgressAt>20_000_000_000L)throw new AssertionError("No collision route after authored encounter delays from "+own.tz()+" to "+x+","+y);return false;}
        }
        while(tutorialNode<tutorialPath.size()&&Math.hypot(own.tz().x-tutorialPath.get(tutorialNode).x(),own.tz().y-tutorialPath.get(tutorialNode).y())<.2)tutorialNode++;
        if(tutorialNode==tutorialPath.size()){tutorialPath=List.of();return false;}
        var point=tutorialPath.get(tutorialNode);moveToward(view,point.x()-own.tz().x,point.y()-own.tz().y,true);
        double distance=Math.hypot(own.tz().x-x,own.tz().y-y);
        if(distance<tutorialBestDistance-.12){tutorialBestDistance=distance;tutorialProgressAt=now;}
        if(now-tutorialRepathAt>5_000_000_000L){tutorialRepathAt=now;System.out.println("NAV: waypoint="+tutorialWaypoint+" at="+own.tz()+" node="+point+" target="+x+","+y);}
        if(now-tutorialProgressAt>2_000_000_000L&&now-tutorialRepathAt>1_000_000_000L){tutorialPath=List.of();}
        if(now-tutorialProgressAt>500_000_000L)flags(view,(now-tutorialProgressAt)%1_250_000_000L<150_000_000L?4:1);
        if(now-tutorialProgressAt>15_000_000_000L)throw new AssertionError("Normal tutorial input blocked at "+own.tz()+" toward "+point+" waypoint="+tutorialWaypoint);
        return false;
    }
    private void tutorialSpawnSpecs(org.tortodrone.world.SceneActions.Action action){
        if(action==null)return;
        if(action instanceof org.tortodrone.world.SceneActions.Spawn spawn){var spec=new org.tortodrone.world.SceneContent.Spec(spawn.fields());tutorialSpecs.putIfAbsent(spec.config().name(),spec);}
        else if(action instanceof org.tortodrone.world.SceneActions.Delay delay)tutorialSpawnSpecs(delay.action());
        else if(action instanceof org.tortodrone.world.SceneActions.Sequence sequence)sequence.actions().forEach(this::tutorialSpawnSpecs);
        else if(action instanceof org.tortodrone.world.SceneActions.Choice choice)choice.actions().forEach(this::tutorialSpawnSpecs);
        else if(action instanceof org.tortodrone.world.SceneActions.Limited limited){tutorialSpawnSpecs(limited.action());tutorialSpawnSpecs(limited.otherwise());}
    }
    private void checkCampObjective(){
        if(app.CT().objectives.size()!=1)throw new AssertionError("Expected one persistent Camp objective");
        var objective=app.CT().objectives.iterator().next();
        if(objective.jz()!=0||objective.persistence!=com.threerings.projectx.config.Persistence.KNIGHT||!(objective.config instanceof com.threerings.projectx.config.ObjectiveConfig.Simple simple)||!simple.text.equals("m.find_rhendon"))throw new AssertionError("Native Camp objective fields differ");
    }
    private void campStoryTick(View view,long now)throws Exception{
        var own=authoritative.get(app.CT().pawnId);if(own==null||view.sceneId()!=217)return;
        if(stage==2){GLFW.glfwFocusWindow(app.getWindow());capture("camp-initial");tutorialMap=org.tortodrone.resources.ContentManager.load(Path.of(System.getProperty("appdir")).getParent()).scenes().scene(217);tutorialPath=List.of();tutorialSpecs.clear();tutorialProgressAt=now;tutorialBestDistance=Double.POSITIVE_INFINITY;stage=7;}
        var greta=authoritative.values().stream().filter(a->a.Mv().getName().equals("Character/NPC/Rescue Camp/Greta 2")).findFirst().orElseThrow();
        if(stage==7){if(now-started<2_000_000_000L)return;if(now-started>4_000_000_000L&&now-started<4_100_000_000L)capture("camp-walk");if(!tutorialWalk(view,own,greta.tz().x,greta.tz().y-1.15f,now))return;moveToward(view,greta.tz().x-own.tz().x,greta.tz().y-own.tz().y,false);flags(view,0);stage=8;started=now;return;}
        if(stage==8&&now-started>500_000_000L){flags(view,4);stage=9;started=now;return;}
        if(stage==9){flags(view,0);
            if(app.CT().sceneDialog instanceof com.threerings.projectx.data.ConversationDialogInfo info){
                if(campPanel<3&&info.message.equals("m.rc_greet2_"+(campPanel+1)+"a")&&now>=campResponseAt){
                    capture("camp-greta-panel-"+(campPanel+1));System.out.println("DIALOG: "+info.name+":"+info.message);
                    info.conversationService.f(0,new Confirm());campPanel++;campResponseAt=now+1_000_000_000L;
                }
            }else if(campPanel==3&&!app.CT().objectives.isEmpty()&&now>=campResponseAt){
                checkCampObjective();capture("camp-find-rhendon-objective");if(campProgression){stage=10;campPanel=0;tutorialPath=List.of();tutorialProgressAt=now;tutorialBestDistance=Double.POSITIVE_INFINITY;return;}finishImages();passed=done=true;
                System.out.println("PASS: original Camp entry -> normal collision walk -> native Greta interaction -> all three localized panels/image -> KNIGHT findRhendon objective; completed-tutorial checkpoint fixture, reconnect checked separately");app.shutdown();
            }
            if(now-started>15_000_000_000L)throw new AssertionError("Greta conversation/objective did not finish; panel="+campPanel);
        }
        if(stage==10){
            var rhendon=authoritative.values().stream().filter(a->a.Mv().getName().equals("Character/NPC/Rescue Camp/Rhendon, Rescue Camp")).findFirst().orElseThrow();
            if(!tutorialWalk(view,own,rhendon.tz().x,rhendon.tz().y-1.15f,now))return;
            moveToward(view,rhendon.tz().x-own.tz().x,rhendon.tz().y-own.tz().y,false);flags(view,0);stage=11;started=now;return;
        }
        if(stage==11&&now-started>500_000_000L){flags(view,4);stage=12;started=now;return;}
        if(stage==12){flags(view,0);
            if(app.CT().sceneDialog instanceof com.threerings.projectx.data.ConversationDialogInfo info){
                if(campPanel<3&&info.message.equals("m.securing_camp_"+(campPanel+1)+"a")&&now>=campResponseAt){
                    capture("camp-rhendon-panel-"+(campPanel+1));System.out.println("DIALOG: "+info.name+":"+info.message);
                    info.conversationService.f(0,new Confirm());campPanel++;campResponseAt=now+1_500_000_000L;
                }
            }else if(campPanel==3&&app.CT().sceneDialog instanceof com.threerings.projectx.mission.data.MissionCompleteDialogInfo info&&now>=campResponseAt){
                checkCampMission();if(!info.missionId.equals("securing_camp")||!info.immediate||!info.newRank||info.prestige!=10||info.rewards.size()!=20)throw new AssertionError("Native mission completion dialog differs");
                capture("camp-securing-mission-complete");finishImages();passed=done=true;
                System.out.println("PASS: native Camp walk/Greta objective -> Rhendon full briefing -> Securing Camp completion window, exact rewards, rank 1-2, prestige 10 and Missions button; checkpoint fixture, reconnect checked separately");app.shutdown();
            }
            if(now-started>20_000_000_000L)throw new AssertionError("Rhendon briefing/mission did not complete; panel="+campPanel+" dialog="+app.CT().sceneDialog);
        }
    }
    private void checkCampMission(){
        var player=app.CT();var receipt=player.completedMissions.f("securing_camp");
        if(receipt==null||receipt.completed<=0||receipt.difficulty!=null||player.rank.rank!=1||player.rank.subrank!=2||player.prestige!=10||!player.objectives.isEmpty()||itemCount("Rarity/Fire Crystal/Cracked")!=15||itemCount("Rarity/Spark of Life")!=5)throw new AssertionError("Native Securing Camp state/rewards differ");
    }
    private void readyRoomTick(long now)throws Exception{
        if(app.CT()==null||app.Gu()==null)return;
        if(stage==0&&app.CT().zoneId==org.tortodrone.world.ReadyRoom.ZONE&&Class.forName("com.threerings.projectx.client.dR").isInstance(app.Gu())&&!app.GF().mG()){
            if(started==0)started=now;if(now-started<3_000_000_000L)return;
            capture("ready-room");Object controller=new View(app.Gu()).acn();
            controller.getClass().getMethod("a",Object.class,String.class,Object.class).invoke(controller,null,"town",null);
            stage=1;started=now;System.out.println("CHECK: native Ready Room town action");
        }else if(stage==1){
            if(now-started>20_000_000_000L)throw new AssertionError("Ready Room return did not load Haven; zone="+app.CT().zoneId+" loading="+app.GF().mG());
            if(!Class.forName("com.threerings.projectx.town.a.c").isInstance(app.Gu()))return;
            var model=(com.threerings.tudey.data.TudeySceneModel)app.Gu().getClass().getMethod("Sn").invoke(app.Gu());
            if(model!=null&&model.sceneId==readyRoomDestination&&!app.GF().mG()&&new View(app.Gu()).PO()!=null&&new View(app.Gu()).PO().acU().vw()==app.CT().pawnId){
                stage=2;started=now;
            }
        }else if(stage==2&&now-started>3_000_000_000L){
                String shot="ready-room-return-"+readyRoomDestination;capture(shot);finishImages();
                var image=ImageIO.read(output.resolve(shot+".png").toFile());
                if(Arrays.stream(image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth())).distinct().limit(33).count()<33)throw new AssertionError("Haven framebuffer remained blank after the transition");
                if(campStoryReturn)checkCampObjective();if(campProgressionReturn)checkCampMission();
                passed=done=true;System.out.println("PASS: stock graphical Ready Room returns to saved scene "+readyRoomDestination+" and renders its controlled pawn");app.shutdown();
        }
    }
    private void tick(){
        if(done)return;
        try{
            long now=System.nanoTime();
            if(!fullscreenApplied&&!peer&&!multiplayerOnly&&!equipmentGroups&&app.getWindow()!=0){
                long monitor=GLFW.glfwGetPrimaryMonitor();var mode=GLFW.glfwGetVideoMode(monitor);
                if(mode==null)throw new AssertionError("No video mode for the isolated test client's monitor");
                GLFW.glfwSetWindowMonitor(app.getWindow(),monitor,0,0,mode.width(),mode.height(),mode.refreshRate());fullscreenApplied=true;
            }
            if(now>deadline)throw new AssertionError("Timed out at stage "+stage+", action "+index);
            if(!app.zr().isLoggedOn())return;
            if(freshOpening&&app.CT()!=null&&app.CT().bagId==0){
                if(openingCreation==null){
                    openingCreation=new Confirm();
                    ((com.threerings.projectx.client.aU)app.zr().bU(com.threerings.projectx.client.aU.class)).a(null,new com.threerings.util.Name("Town-Knight"),"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,com.threerings.projectx.data.ProjectXCodes.KnightHeight.BASE,com.threerings.projectx.data.ProjectXCodes.EyeType.DEFAULT,null,List.of(),null,openingCreation);
                    System.out.println("CHECK: native knight creation on a fresh ordinary account");
                }else if(openingCreation.done&&openingCreation.reason!=null)throw new AssertionError("Native creation refused: "+openingCreation.reason);
                return;
            }
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
            if(tutorialEntryOnly){if(!listening){listening=true;app.zr().xc().a(new com.threerings.tudey.dobj.a(){public void c(SceneDeltaEvent e){receive(e);}});}tutorialEntryTick(now);return;}
            if(readyRoomOnly){if(!listening){listening=true;app.zr().xc().a(new com.threerings.tudey.dobj.a(){public void c(SceneDeltaEvent e){receive(e);}});}if(campStory&&stage>=2)campStoryTick(new View(app.Gu()),now);else readyRoomTick(now);return;}
            if(!listening){listening=true;app.zr().xc().a(new com.threerings.tudey.dobj.a(){public void c(SceneDeltaEvent e){receive(e);}});}
            if(!Class.forName("com.threerings.tudey.a.i").isInstance(app.Gu())||app.GF().mG())return;
            var view=new View(app.Gu());if(view.PO()==null)return;
            if(equipmentGroups){equipmentGroupsTick(view,now);return;}
            if(itemsOnly){itemsTick(view,now);return;}
            if(multiplayerOnly){if(sharedWorldOnly)sharedWorldTick(view,now);else multiplayerTick(view,now);return;}
            if(peer){peerTick(view);return;}
            if(townDialogsOnly){townDialogsTick(view,now);return;}
            if(boxesOnly){boxesTick(now);return;}
            if(depotOnly){depotTick(view,now);return;}
            if(hazardsOnly){hazardsTick(view,now);return;}
            if(stage>=3&&stage<=6&&GLFW.glfwGetWindowAttrib(app.getWindow(),GLFW.GLFW_FOCUSED)==0)
                throw new AssertionError("Keep the disposable combat test window focused; the stock background cap prevents animation/input sampling");
            if(stage==0){if(arsenalBlinkOnly||healthOnly){GLFW.glfwSetWindowSizeLimits(app.getWindow(),1280,800,GLFW.GLFW_DONT_CARE,GLFW.GLFW_DONT_CARE);GLFW.glfwSetWindowSize(app.getWindow(),1280,900);GLFW.glfwMaximizeWindow(app.getWindow());}if(arsenalBlinkOnly&&!app.GQ().Pu())throw new AssertionError("Solo Town commands incorrectly require mission-abandonment confirmation");GLFW.glfwFocusWindow(app.getWindow());started=now;if(arsenalBlinkOnly||dialogueOnly||vortexShieldOnly||healthOnly||movementOnly){app.GF().bl(294);stage=2;}else stage=1;return;}
            if(arsenalBlinkOnly&&stage==43&&view.sceneId()==1){
                if(!app.GQ().Pu())throw new AssertionError("Returning to Haven retained a solo mission party");
                app.nk().a(null,"/training",false);blinkHidden=blinkVisible=false;started=now;stage=45;return;
            }
            if(arsenalBlinkOnly&&(view.sceneId()==294||view.sceneId()==318)&&stage>=2){arsenalBlinkTick(view,now);return;}
            if(arsenalBlinkOnly&&stage>=43){if(now-started>10_000_000_000L)throw new AssertionError("Native town/training command round trip timed out");return;}
            if(healthOnly&&view.sceneId()==294&&stage>=2){healthTick(view,now);return;}
            if(vortexShieldOnly&&view.sceneId()==294&&stage>=2){vortexShieldTick(view,now);return;}
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
                konwayActor=stranger.vw();trainingMap=SceneCatalog.load(Path.of("content/maps")).scene(294);
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
                    if(dialogueOnly&&!info.message.equals("r.atg_greeter_2a"))throw new AssertionError("Edited YAML sentence did not reach the stock client");
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
                    if(dialogueOnly){passed=done=true;System.out.println("PASS: stock graphical YAML dialogue edit, Konway interaction, response transition and native close");app.shutdown();return;}
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
        }catch(Throwable error){try{capture("acceptance-failed-stage-"+stage);}catch(Throwable ignored){}failure=error;done=true;error.printStackTrace();images.shutdown();
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
    private boolean sawShieldBump,sawVortexImplode,sawVortexExplode,sawVortexPull;
    private int bumpFrames,vortexFrames,vortexTarget;
    private float vortexDistance;
    private void vortexShieldTick(View view,long now)throws Exception {
        var own=authoritative.get(app.CT().pawnId);if(own==null||view.PO()==null)return;
        if(stage==2){
            if(!(own instanceof Dungeoneer)||authoritative.size()<200||app.GF().mG()||!view.PO().acL().isVisible())return;
            if(trainingSince==0)trainingSince=now;if(now-trainingSince<3_000_000_000L)return;
            long id=findItemId("Gear/Shield/Grey Owlite Shield");if(id==0)return;
            equip=new Confirm();app.zr().bU(com.threerings.projectx.item.client.L.class).a(id,2,equip);started=now;stage=70;return;
        }
        if(stage==70){
            if(!equip.done)return;if(equip.reason!=null)throw new AssertionError(equip.reason);
            if(now-started<700_000_000L)return;
            capture("shield-bump-before");flags(view,64);started=now;stage=71;return;
        }
        if(stage==71){
            if(sawShieldBump&&bumpFrames<3&&now-started>150_000_000L*bumpFrames){capture("shield-bump-vfx-"+bumpFrames++);}
            if(now-started<800_000_000L)return;
            flags(view,0);
            if(!sawShieldBump)throw new AssertionError("Controlling client did not receive shield bump VFX");
            equip=new Confirm();app.zr().bU(com.threerings.projectx.item.client.L.class).a(findItemId("Weapon/Bomb/Graviton Vortex"),6,equip);started=now;stage=72;return;
        }
        if(stage==72){
            if(!equip.done)return;if(equip.reason!=null)throw new AssertionError(equip.reason);
            flags(view,0);
            var weapon=(Item)field(view.PO().acU(),"_weapon").get(view.PO().acU());
            var renderedWeapon=(Item)field(view.PO().implementation(),"_weapon").get(view.PO().implementation());
            if(now-started<800_000_000L||((Dungeoneer)own).adU()!=0||weapon==null||renderedWeapon==null
                ||!weapon.Vx().getName().equals("Weapon/Bomb/Graviton Vortex")||!renderedWeapon.Vx().getName().equals("Weapon/Bomb/Graviton Vortex"))return;
            var target=authoritative.values().stream().filter(a->a.getClass().getSimpleName().equals("Monster")&&a.tz().y>own.tz().y+1.7&&Math.abs(a.tz().x-own.tz().x)<.1)
                .min(Comparator.comparingDouble(a->Math.hypot(a.tz().x-own.tz().x,a.tz().y-own.tz().y))).orElseThrow();
            vortexTarget=target.vw();vortexDistance=(float)Math.hypot(target.tz().x-own.tz().x,target.tz().y-own.tz().y);
            flags(view,4);started=now;stage=73;return;
        }
        if(stage==73){
            long elapsed=now-started;
            if(elapsed>=4_000_000_000L)flags(view,0);
            for(var actor:authoritative.values())if(actor instanceof com.threerings.projectx.dungeon.data.actor.Bomb){
                String name=actor.Mv().getName();var sprite=view.gk(actor.vw());
                // These invisible carriers render creation/destruction transients,
                // rather than a persistent actor model. Review the captured pixels too.
                if(sprite==null||!sprite.acU().Mv().getName().equals(name))continue;
                if(name.equals("Bomb/Parts/Gravidos (Implode) (Charged)"))sawVortexImplode=true;
                if(name.equals("Bomb/Parts/Gravidos (Explode) (Charged)"))sawVortexExplode=true;
            }
            var target=authoritative.get(vortexTarget);
            if(target!=null&&Math.hypot(target.tz().x-own.tz().x,target.tz().y-own.tz().y)<vortexDistance-.5)sawVortexPull=true;
            if(elapsed>5_500_000_000L+vortexFrames*250_000_000L&&vortexFrames<16)capture("vortex-stage-"+vortexFrames++);
            if(elapsed<10_000_000_000L)return;
            if(!sawVortexImplode||!sawVortexExplode||!sawVortexPull)throw new AssertionError("Missing rendered vortex stage or monster pull: "+sawVortexImplode+"/"+sawVortexExplode+"/"+sawVortexPull);
            finishImages();passed=done=true;System.out.println("PASS: stock controlling client receives shield bump VFX; native vortex implode/explode stages initialize and pull a nearby cube");app.shutdown();
        }
    }
    private boolean blinkHidden,blinkVisible,arsenalOpened,arrivalFlag;
    private long arsenalPressed,arsenalOpenedAt;
    private void arsenalBlinkTick(View view,long now)throws Exception {
        var pawn=authoritative.get(app.CT().pawnId);if(!(pawn instanceof Dungeoneer own))return;
        if(arsenalStationOnly&&stage==46){
            if(now-started<1_500_000_000L){moveToward(view,-1,0,true);return;}
            flags(view,0);
            if(arsenalStationState()==0){
                capture("native-arsenal-closed");finishImages();passed=done=true;
                System.out.println("PASS: native Arsenal opens on interaction, retains its shared state while in range and closes after departure");app.shutdown();return;
            }
            if(now-started>5_000_000_000L)throw new AssertionError("Arsenal remained open after leaving its range");
            return;
        }
        if(stage==43)return;
        if(stage==45){
            if(own.bR(Dungeoneer.INVULNERABLE)){blinkVisible|=view.PO().acL().isVisible();blinkHidden|=!view.PO().acL().isVisible();}
            if(blinkVisible&&blinkHidden){capture("training-command-entry-blink");app.GF().bl(318);blinkHidden=blinkVisible=false;started=now;stage=44;}
            else if(now-started>5_000_000_000L)throw new AssertionError("/training did not arrive with visible entry blinking");
            return;
        }
        if(stage==44){
            if(view.sceneId()!=318)return;
            if(!blinkVisible||!blinkHidden){
                if(own.bR(Dungeoneer.INVULNERABLE)){blinkVisible|=view.PO().acL().isVisible();blinkHidden|=!view.PO().acL().isVisible();}
                if(now-started>5_000_000_000L)throw new AssertionError("Hall of Heroes entry did not visibly blink");return;
            }
            capture("hall-of-heroes-entry-blink");finishImages();passed=done=true;
            System.out.println("PASS: native Arsenal inventory, Training and Hall of Heroes entry blinking, and /training without a stale town confirmation");app.shutdown();return;
        }
        boolean protectedHit=own.bR(Dungeoneer.INVULNERABLE);
        if(stage==2&&protectedHit)arrivalFlag=true;
        if(protectedHit){boolean visible=view.PO().acL().isVisible();blinkHidden|=!visible;blinkVisible|=visible;}
        if(now-started>25_000_000_000L)throw new AssertionError("Arsenal/blink timeout: prompt="+own.Ns()+" policy="+app.CT().equipPolicy+" flags="+own.bR(64)+" visible="+blinkVisible+" hidden="+blinkHidden+" position="+own.tz());
        if(stage==2){
            if(arsenalStationOnly&&now-started<1_000_000_000L)return; // Let the native entry HUD finish being added.
            if(arsenalStationOnly&&arsenalStationState()!=0)throw new AssertionError("Arsenal opened before interaction");
            moveEast(view,false);
            if(own.Ns() instanceof com.threerings.tudey.data.EntityKey.Entry entry&&Integer.valueOf(1955).equals(entry.getKey())){
                flags(view,4);arsenalPressed=now;stage=42;return;
            }
            return;
        }
        if(now-arsenalPressed>180_000_000L)flags(view,0);
        Object hud=view.value().getClass().getMethod("Jb").invoke(view.value());Object inventory=field(hud,"aDN").get(hud);
        if(inventory instanceof com.threerings.opengl.gui.aD window&&window.isAdded()){
            if(!arsenalOpened){arsenalOpenedAt=now;arsenalOpened=true;System.out.println("CHECK: server interaction opened the native Arsenal window; policy="+app.CT().equipPolicy);}
            if(blinkVisible&&blinkHidden&&now-arsenalOpenedAt>2_000_000_000L){
                if(!arrivalFlag)throw new AssertionError("Training did not receive entry protection");
                if(app.GG().getDisplayWidth()<1024)throw new AssertionError("Native Arsenal viewport is too small for visual verification");
                Object equipment=field(hud,"aDX").get(hud);if(!(equipment instanceof com.threerings.opengl.gui.aD equipmentWindow)||!equipmentWindow.isAdded())throw new AssertionError("Native equipment window did not open");
                capture("native-arsenal-open");capture("native-entry-blink");
                ((com.threerings.opengl.gui.aD)inventory).dismiss();equipmentWindow.dismiss();
                if(arsenalStationOnly){
                    if(arsenalStationState()!=1)throw new AssertionError("Arsenal did not retain its opening state while in range");
                    started=now;stage=46;return;
                }
                app.nk().a(null,"/haven",false);started=now;stage=43;}
        }
    }
    private int arsenalStationState()throws Exception {
        for(var actor:authoritative.values())if(actor.Mv().getName().startsWith("Dynamic/Misc/Arsenal/")&&actor instanceof com.threerings.projectx.dungeon.data.actor.Switch)
            return field(actor,"_state").getInt(actor);
        throw new AssertionError("Missing native Arsenal switch");
    }
    private boolean checkedStatuses,checkedEmergency,healthShieldEquipped,sawHurt,sawHealEffect,sawSparkEffect,healingWalk,checkedHealing;
    private Confirm sparkReply;
    private Confirm fullCapsuleReply;
    private long fullCapsuleId,fullCapsuleStarted,sparkVisualStarted,healthSceneReady;
    private boolean fullCapsuleAnimated,fullCapsuleChecked;
    private void healthTick(View view,long now)throws Exception {
        var party=(com.threerings.projectx.dungeon.data.PartyObject)app.GQ().CW();
        Object hud=view.value().getClass().getMethod("Jb").invoke(view.value());
        Object panel=field(hud,"aEc").get(hud);
        if(stage==2){
            if(healthSceneReady==0){healthSceneReady=now;return;}
            // Let the native scene-entry crossfade finish before inspecting the use animation.
            if(now-healthSceneReady<3_000_000_000L)return;
            if(app.GG().getDisplayWidth()<1024)throw new AssertionError("Health acceptance requires a visible native scene viewport");
            if(!fullCapsuleChecked){
                if(fullCapsuleReply==null){
                    for(var item:app.CT().items)if(item.VA()<0&&item.Vx().getName().equals("Pickup/Capsule/Health Capsule"))fullCapsuleId=item.VA();
                    if(fullCapsuleId==0){if(now-started>20_000_000_000L)throw new AssertionError("Health Capsule fixture was not collected");return;}
                    fullCapsuleReply=new Confirm();fullCapsuleStarted=now;
                    app.zr().bU(com.threerings.projectx.item.client.L.class).a(fullCapsuleId,(Object)null,fullCapsuleReply);return;
                }
                if(fullCapsuleReply.done&&fullCapsuleReply.reason!=null)throw new AssertionError("Full-health capsule rejected: "+fullCapsuleReply.reason);
                var capsuleActor=authoritative.get(app.CT().pawnId);
                if(capsuleActor instanceof Dungeoneer knight&&knight.adU()==5&&!fullCapsuleAnimated&&now-fullCapsuleStarted>=300_000_000L&&activityPlaying(view.PO(),5)){capture("full-health-capsule-use");fullCapsuleAnimated=true;}
                if(now-fullCapsuleStarted<2_000_000_000L)return;
                boolean retained=false;for(var item:app.CT().items)if(item.VA()==fullCapsuleId&&item.getCount()==1)retained=true;
                var hp=party.health.iterator().next();
                if(!fullCapsuleReply.done||!fullCapsuleAnimated||!retained||hp.current!=hp.maximum||sawHealEffect)throw new AssertionError("Full-health capsule did not complete as animation-only use: confirmed="+fullCapsuleReply.done+" animation="+fullCapsuleAnimated+" retained="+retained+" hp="+hp.current+"/"+hp.maximum+" healEffect="+sawHealEffect);
                fullCapsuleChecked=true;System.out.println("CHECK: native full-health capsule animation, successful confirmation, unchanged inventory/HP and no healing effect");
            }
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
            trainingMap=SceneCatalog.load(Path.of("content/maps")).scene(294);
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
                if(sparkVisualStarted==0)sparkVisualStarted=now;
                if(now-sparkVisualStarted<300_000_000L)return;
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
    private Path mpRoot(){return peer?output.getParent():output;}
    private void mpMark(String name)throws Exception{Files.writeString(mpRoot().resolve(name),"");}
    private boolean mpHas(String name){return Files.isRegularFile(mpRoot().resolve(name));}
    private void mpFinish(String message)throws Exception{capture("multiplayer-final");finishImages();passed=done=true;System.out.println("PASS: "+message);app.shutdown();}
    private void checkTrainingHud()throws Exception{
        var view=new View(app.Gu());
        Object hud=view.value().getClass().getMethod("Jb").invoke(view.value());
        Object panel=field(hud,"aSM").get(hud);
        var party=app.GQ().CW();
        if(!(party instanceof com.threerings.projectx.dungeon.data.LevelPartyObject level)||!level.hasPickups||!app.GQ().Px()
                ||!(panel instanceof com.threerings.projectx.dungeon.client.QuickbarPanel bar)||!bar.isVisible()||bar.getParent()==null)
            throw new AssertionError("Shared Training must attach the native item/sprite quickbar and revive-capable HUD");
        System.out.println("CHECK: shared Training quickbar is visible with native level HUD for "+app.CT().knight);
    }
    private int mpTour;
    private Result mpBox;
    private Confirm mpUse;
    private void multiplayerTick(View view,long now)throws Exception {
        // PO is the stock camera target sprite. It must remain the authenticated pawn.
        if(view.PO().acU().vw()!=app.CT().pawnId){
            Object controller=view.acn();var scene=(com.threerings.tudey.data.TudeySceneObject)field(controller,"bJl").get(controller);
            throw new AssertionError("Camera owns another pawn: expected "+app.CT().pawnId+" got "+view.PO().acU().vw()+" scene "+view.sceneId()+" client="+app.CT().xL()+" mapped="+scene.gE(app.CT().xL())+" controlled="+field(controller,"bOy").getInt(controller)+" target="+field(controller,"bOx").getInt(controller)+" occupants="+scene.occupantInfo);
        }
        if(!authoritative.containsKey(app.CT().pawnId))return;
        var remote=authoritative.values().stream().filter(a->a.vw()!=app.CT().pawnId&&a instanceof com.threerings.projectx.data.actor.Knight).map(com.threerings.projectx.data.actor.Knight.class::cast).findFirst().orElse(null);
        if(peer){
            if(!Files.exists(output.resolve("peer-ready"))){capture("multiplayer-haven");Files.writeString(output.resolve("peer-ready"),"");}
            if(mpHas("mp-disconnected")){
                if(stage==0){app.GF().bl(294);stage=90;return;}
                if(stage==90&&view.sceneId()==294&&remote!=null){mpMark("mp-reconnected");stage=91;}
                if(stage==91&&mpHas("mp-complete"))mpFinish("reconnected stock peer shares Training with correct camera and no duplicate knights");
                return;
            }
            if(stage==0&&mpHas("mp-a-travel")){app.GF().bl(2);stage=1;mpTour=0;return;}
            if(stage==1&&view.sceneId()==(mpTour%2==0?2:1)){
                if(++mpTour==4){mpMark("mp-b-travel");stage=2;}else app.GF().bl(mpTour%2==0?2:1);return;
            }
            if(stage==2&&remote!=null&&remote.adU()==265){
                System.out.println("CHECK: remote box activity "+remote.adV()+" rendered="+(view.gk(remote.vw())==null?"missing":((com.threerings.projectx.data.actor.Knight)view.gk(remote.vw()).acU()).adU()));
                var sprite=view.gk(remote.vw());
                if(sprite!=null&&activityPlaying(sprite,265)){
                    var data=(List<?>)field(remote,"_activityData").get(remote);
                    if(data.size()<2||data.get(0).equals(data.get(1)))throw new AssertionError("Peer reveal substituted the box for its actual reward");
                    capture("multiplayer-remote-box");mpMark("mp-box-seen");stage=3;
                }
            }
            if(stage==3&&mpHas("mp-confetti-go")){mpUse=new Confirm();app.zr().bU(com.threerings.projectx.item.client.L.class).a(findItemId("Usable/Confetti"),(Object)null,mpUse);stage=4;}
            if(stage==4&&mpUse.done){if(mpUse.reason!=null)throw new AssertionError(mpUse.reason);if(mpHas("mp-training")){app.GF().bl(294);stage=5;}}
            if(stage==5&&view.sceneId()==294&&remote!=null){checkTrainingHud();mpMark("mp-shared");capture("multiplayer-training");stage=6;}
            if(stage==6&&remote!=null&&remote.adU()>=292){var sprite=view.gk(remote.vw());if(sprite!=null&&activityPlaying(sprite,remote.adU())){capture("multiplayer-remote-combat");mpMark("mp-combat-seen");stage=7;}}
            if(stage==7&&mpHas("mp-disconnect-go")){mpMark("mp-disconnected");mpFinish("stock peer retains its camera during travel and renders remote Prize Box and combat; Confetti use confirmed");}
            return;
        }
        if(stage==0&&remote!=null){capture("multiplayer-haven");app.GF().bl(2);stage=1;mpTour=0;return;}
        if(stage==1&&view.sceneId()==(mpTour%2==0?2:1)){
            if(++mpTour==4){mpMark("mp-a-travel");stage=2;}else app.GF().bl(mpTour%2==0?2:1);return;
        }
        if(stage==2&&mpHas("mp-b-travel")&&remote!=null){mpBox=new Result();app.zr().bU(com.threerings.projectx.item.client.L.class).b(findItemId("Upgrade/Prize Box/Surge Prize Box"),-1,mpBox);stage=3;started=now;}
        if(stage==3&&now-started>25_000_000_000L&&!mpHas("mp-box-seen"))throw new AssertionError("Remote box animation did not render");
        if(stage==3&&mpBox.done){if(mpBox.reason!=null)throw new AssertionError(mpBox.reason);if(mpHas("mp-box-seen")&&now-started>9_000_000_000L){mpMark("mp-confetti-go");stage=4;}}
        if(stage==4&&remote!=null&&remote.adU()==267){var sprite=view.gk(remote.vw());if(sprite!=null&&activityPlaying(sprite,267)){capture("multiplayer-remote-confetti");mpMark("mp-training");app.GF().bl(294);stage=5;}}
        if(stage==5&&view.sceneId()==294&&remote!=null&&mpHas("mp-shared")){
            checkTrainingHud();
            if(authoritative.values().stream().filter(a->a instanceof Dungeoneer).count()!=2)throw new AssertionError("Shared Training has duplicate/missing players");
            // Native attack intent; the peer must render the resulting activity.
            GLFW.glfwFocusWindow(app.getWindow());flags(view,4);started=now;stage=6;
        }
        if(stage==6&&now-started>250_000_000L){flags(view,0);if(mpHas("mp-combat-seen")){mpMark("mp-disconnect-go");stage=7;}}
        if(stage==7&&mpHas("mp-disconnected")&&remote==null){mpMark("mp-survivor");capture("multiplayer-survivor");stage=8;}
        if(stage==8&&mpHas("mp-reconnected")&&remote!=null){
            if(authoritative.values().stream().filter(a->a instanceof Dungeoneer).count()!=2)throw new AssertionError("Reconnect duplicated actors");
            mpMark("mp-complete");mpFinish("two stock clients: Haven, independent repeated travel/camera ownership, remote Prize Box/Confetti/combat, shared Training, disconnect survival and reconnect");
        }
    }
    private int sharedEnemy;
    private float sharedEnemyHealth;
    private float monsterHealth(Actor actor)throws Exception{return actor==null?0:field(actor,"_healthPct").getFloat(actor);}
    /** A native training cube is placed in front of the disposable spawn by the parent fixture. */
    private void sharedWorldTick(View view,long now)throws Exception {
        if(view.PO().acU().vw()!=app.CT().pawnId)throw new AssertionError("Shared-world camera changed ownership");
        var own=authoritative.get(app.CT().pawnId);if(own==null)return;
        if(stage==0){if(peer)Files.writeString(output.resolve("peer-ready"),"");app.GF().bl(294);stage=1;started=now;return;}
        if(view.sceneId()!=294)return;
        if(stage==1&&authoritative.values().stream().filter(a->a instanceof Dungeoneer).count()==2){
            checkTrainingHud();
            mpUse=new Confirm();app.zr().bU(com.threerings.projectx.item.client.L.class).a(findItemId(GUN),6,mpUse);stage=18;started=now;
        }
        if(stage==18&&mpUse.done){
            if(mpUse.reason!=null)throw new AssertionError(mpUse.reason);
            var weapon=(Item)field(view.PO().acU(),"_weapon").get(view.PO().acU());
            if(weapon==null||!weapon.Vx().getName().equals(GUN))return;
            var target=authoritative.values().stream().filter(a->a.getClass().getSimpleName().equals("Monster")&&monsterAlive(a))
                .min(Comparator.comparingDouble(a->Math.hypot(a.tz().x-own.tz().x,a.tz().y-own.tz().y))).orElseThrow();
            if(Math.hypot(target.tz().x-own.tz().x,target.tz().y-own.tz().y)>4)throw new AssertionError("Missing nearby disposable target");
            sharedEnemy=target.vw();sharedEnemyHealth=monsterHealth(target);
            moveToward(view,target.tz().x-own.tz().x,target.tz().y-own.tz().y,false);
            mpMark(peer?"world-shooter-b":"world-shooter-a");stage=2;started=now;
        }
        if(stage==2&&mpHas("world-shooter-a")&&mpHas("world-shooter-b")){
            stage=peer?10:3;started=now;
        }
        if(stage==3){
            flags(view,(now-started)%3_000_000_000L<160_000_000L?4:0);
            if(monsterHealth(authoritative.get(sharedEnemy))<sharedEnemyHealth){
                flags(view,0);sharedEnemyHealth=monsterHealth(authoritative.get(sharedEnemy));
                capture("shared-enemy-primary-hit");mpMark("world-hit-a");stage=4;started=now;
            }
        }
        if(stage==10&&mpHas("world-hit-a")&&monsterHealth(authoritative.get(sharedEnemy))<sharedEnemyHealth){
            sharedEnemyHealth=monsterHealth(authoritative.get(sharedEnemy));capture("shared-enemy-observed-primary-hit");stage=11;started=now;
        }
        if(stage==11){
            flags(view,(now-started)%3_000_000_000L<160_000_000L?4:0);
            if(monsterHealth(authoritative.get(sharedEnemy))<sharedEnemyHealth){
                flags(view,0);capture("shared-enemy-peer-hit");mpMark("world-hit-b");stage=12;started=now;
            }
        }
        if(stage==4&&mpHas("world-hit-b")&&monsterHealth(authoritative.get(sharedEnemy))<sharedEnemyHealth){
            capture("shared-enemy-observed-peer-hit");mpMark("world-complete");
            mpFinish("two stock players independently damage the same nearby Training enemy and both observe each other's damage");
        }
        if(stage==12&&mpHas("world-complete"))mpFinish("stock peer independently damages the same Training enemy and observes the other player's hit");
        if(now-started>60_000_000_000L)throw new AssertionError("Shared-world stage "+stage+" stalled at "+own.tz());
    }
    private boolean monsterAlive(Actor actor){try{return monsterHealth(actor)>0;}catch(Exception error){throw new AssertionError(error);}}
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
    private int potId,hazardRound;
    private final Set<Integer> hazardIds=new HashSet<>();
    private boolean hazardRendered;
    private long hazardSince;
    private void hazardsTick(View view,long now)throws Exception {
        if(stage==0){if(arsenalBlinkOnly||healthOnly){GLFW.glfwSetWindowSizeLimits(app.getWindow(),1280,800,GLFW.GLFW_DONT_CARE,GLFW.GLFW_DONT_CARE);GLFW.glfwSetWindowSize(app.getWindow(),1280,900);GLFW.glfwMaximizeWindow(app.getWindow());}if(arsenalBlinkOnly&&!app.GQ().Pu())throw new AssertionError("Solo Town commands incorrectly require mission-abandonment confirmation");GLFW.glfwFocusWindow(app.getWindow());app.GF().bl(294);stage=70;started=now;return;}
        if(view.sceneId()!=294)return;
        var own=authoritative.get(app.CT().pawnId);if(!(own instanceof Dungeoneer knight))return;
        String potConfig="Dynamic/Lift Objects/"+(hazardRound%2==0?"Fire Pot":"Oil Pot");
        String hazardConfig="Bomb/Monster/"+(hazardRound%2==0?"ScorcherTorch":"Oil Slick");
        if(stage==70){
            var pot=authoritative.values().stream().filter(a->a.Mv().getName().equals(potConfig)&&!a.bR(2)&&!a.bR(4)).findFirst().orElse(null);
            if(pot==null){if(now-started>15_000_000_000L)throw new AssertionError("Pot did not respawn: "+potConfig);return;}
            potId=pot.vw();float dx=pot.tz().x-own.tz().x,dy=pot.tz().y-own.tz().y;
            moveToward(view,dx,dy,Math.hypot(dx,dy)>1.1);
            Object prompt=field(own,"_actionEntity").get(own);
            if(prompt instanceof com.threerings.tudey.data.EntityKey.Actor key&&key.vw()==potId){flags(view,4);stage=71;started=now;}
            return;
        }
        if(stage==71){
            flags(view,0);
            if(field(knight,"_lift").getInt(knight)==potId&&now-started>1_000_000_000L){moveToward(view,1,0,false);flags(view,4);stage=72;started=now;}
            else if(now-started>8_000_000_000L)throw new AssertionError("Pot was not lifted");return;
        }
        if(stage==72){
            flags(view,0);
            for(var actor:authoritative.values())if(actor.Mv().getName().equals(hazardConfig)){
                if(!(actor instanceof com.threerings.projectx.dungeon.data.actor.Bomb))throw new AssertionError("Hazard is not a native Bomb");
                hazardIds.add(actor.vw());
                var sprite=view.gk(actor.vw());
                if(sprite!=null&&sprite.acL().isVisible()&&sprite.acL().getConfig()!=null)hazardRendered=true;
            }
            int expected=hazardRound%2==0?1:9;
            if(hazardIds.size()==expected&&hazardRendered){
                if(hazardSince==0)hazardSince=now;
                if(now-hazardSince>600_000_000L){capture("pot-"+hazardRound+"-"+hazardConfig.substring(13));stage=73;started=now;}
            }else if(now-started>8_000_000_000L)throw new AssertionError("Hazard did not render: "+hazardConfig+" count="+hazardIds.size());return;
        }
        if(stage==73){
            if(hazardIds.stream().anyMatch(authoritative::containsKey)){
                if(now-started>17_000_000_000L)throw new AssertionError("Hazard was not removed");return;
            }
            if(!app.zr().isLoggedOn())throw new AssertionError("Client disconnected after pot throw");
            System.out.println("CHECK: "+potConfig+" native Bomb sprites rendered and expired, connection alive");
            hazardIds.clear();hazardRendered=false;hazardSince=0;
            if(++hazardRound<4){stage=70;started=now;return;}
            finishImages();passed=done=true;System.out.println("PASS: repeated native Fire/Oil Pot pickup, throw, Bomb hazard rendering, expiry and session survival");app.shutdown();
        }
    }

    private com.threerings.projectx.exchange.client.e depotWindow;
    private String depotItem;
    private int depotCount,depotQuantity,depotPrice,depotCrowns,depotEnergy,depotOfferId;
    private boolean depotUsesEnergy;
    private Confirm depotBuy,depotBilling;
    private Result depotInfo;
    private int townDialogCycles;
    private long townDialogMaximum;
    private void townDialogsTick(View view,long now)throws Exception {
        Object place=field(app.Gu(),"bJl").get(app.Gu());
        if(!(place instanceof com.threerings.projectx.town.data.TownSceneObject scene)||view.sceneId()!=1)return;
        if(stage==0){if(arsenalBlinkOnly){GLFW.glfwSetWindowSizeLimits(app.getWindow(),1280,800,GLFW.GLFW_DONT_CARE,GLFW.GLFW_DONT_CARE);GLFW.glfwSetWindowSize(app.getWindow(),1280,900);GLFW.glfwMaximizeWindow(app.getWindow());}if(arsenalBlinkOnly&&!app.GQ().Pu())throw new AssertionError("Solo Town commands incorrectly require mission-abandonment confirmation");GLFW.glfwFocusWindow(app.getWindow());started=now;stage=80;return;}
        if(stage==80){
            if(now-started<3_000_000_000L)return;
            equip=new Confirm();scene.townSceneService.m(equip);started=now;stage=81;return;
        }
        if(stage==81){
            if(equip.reason!=null)throw new AssertionError("Native crafter refused: "+equip.reason);
            if(!equip.done||!(app.CT().sceneDialog instanceof com.threerings.projectx.craft.data.CrafterDialogInfo)){
                if(now-started>20_000_000_000L)throw new AssertionError("Native crafter open queued for over 20 seconds");return;
            }
            long elapsed=now-started;townDialogMaximum=Math.max(townDialogMaximum,elapsed);
            System.out.printf(Locale.ROOT,"DIALOG: cycle=%d open=%.1fms%n",townDialogCycles,elapsed/1e6);
            started=now;stage=82;return;
        }
        if(stage==82){
            if(now-started<(townDialogCycles==0?600_000_000L:100_000_000L))return;
            if(townDialogCycles==0){
                boolean visible=false;for(int i=0;i<app.getRoot().getWindowCount();i++)visible|=Class.forName("com.threerings.projectx.craft.a.o").isInstance(app.getRoot().getWindow(i));
                if(!visible)throw new AssertionError("Crafter data arrived without a native window");
                capture("haven-crafter-open");
            }
            scene.projectXSceneService.R(app.CT().sceneDialogCreated);started=now;stage=83;return;
        }
        if(stage==83){
            if(app.CT().sceneDialog!=null){if(now-started>20_000_000_000L)throw new AssertionError("Native crafter close queued for over 20 seconds");return;}
            long elapsed=now-started;townDialogMaximum=Math.max(townDialogMaximum,elapsed);
            System.out.printf(Locale.ROOT,"DIALOG: cycle=%d close=%.1fms%n",townDialogCycles,elapsed/1e6);
            if(++townDialogCycles<12){equip=new Confirm();scene.townSceneService.m(equip);started=now;stage=81;return;}
            started=now;stage=84;return;
        }
        if(stage==84&&now-started>600_000_000L){
            for(int i=0;i<app.getRoot().getWindowCount();i++)if(Class.forName("com.threerings.projectx.craft.a.o").isInstance(app.getRoot().getWindow(i)))throw new AssertionError("Crafter window remained after native close");
            capture("haven-crafter-closed");finishImages();passed=done=true;
            System.out.printf(Locale.ROOT,"PASS: 12 rapid stock Haven crafter open/close cycles; maximum round trip %.1fms%n",townDialogMaximum/1e6);app.shutdown();
        }
    }
    private void depotTick(View view,long now)throws Exception {
        var own=authoritative.get(app.CT().pawnId);if(own==null)return;
        if(stage==0){
            GLFW.glfwFocusWindow(app.getWindow());
            trainingMap=SceneCatalog.load(Path.of("content/maps")).scene(1);
            var entry=trainingMap.content().entries().stream().filter(e->e.spec().text("config","").equals("Shop/Haven/Spiral Quartermaster")).findFirst().orElseThrow();
            var npc=authoritative.values().stream().filter(a->Math.hypot(a.tz().x-entry.x(),a.tz().y-entry.y())<.1).findFirst().orElseThrow();
            konwayActor=npc.vw();konwayRoute=routeTo(trainingMap,own.tz().x,own.tz().y,npc.tz().x,npc.tz().y);konwayRouteIndex=0;started=now;stage=60;return;
        }
        if(stage==60){
            var npc=authoritative.get(konwayActor);
            if(konwayRouteIndex<konwayRoute.size()){
                var point=konwayRoute.get(konwayRouteIndex);float dx=point[0]-own.tz().x,dy=point[1]-own.tz().y;
                if(Math.hypot(dx,dy)<.30){konwayRouteIndex++;moveToward(view,0,0,false);}else moveToward(view,dx,dy,true);return;
            }
            float dx=npc.tz().x-own.tz().x,dy=npc.tz().y-own.tz().y;
            Object prompt=field(own,"_actionEntity").get(own);
            if(prompt instanceof com.threerings.tudey.data.EntityKey.Actor key&&key.vw()==konwayActor){moveToward(view,dx,dy,false);flags(view,4);started=now;stage=61;return;}
            moveToward(view,dx,dy,Math.hypot(dx,dy)>.82);return;
        }
        if(stage==61){
            flags(view,0);
            for(int i=0;i<app.getRoot().getWindowCount();i++)if(app.getRoot().getWindow(i) instanceof com.threerings.projectx.exchange.client.e depot)depotWindow=depot;
            if(depotWindow==null){if(now-started>10_000_000_000L)throw new AssertionError("Supply Depot NPC did not construct its window");return;}
            if(!(app.CT().sceneDialog instanceof com.threerings.projectx.exchange.data.DepotDialogInfo))throw new AssertionError("Missing native Depot dialog");
            @SuppressWarnings("unchecked") var offers=(Map<Integer,com.threerings.projectx.mission.data.RankGood>)field(depotWindow,"bmK").get(depotWindow);
            if(offers==null||offers.isEmpty())return;
            var entry=offers.entrySet().stream().filter(e->e.getValue().special&&e.getValue().good instanceof com.threerings.projectx.shop.config.GoodConfig.Item&&((com.threerings.projectx.shop.config.GoodConfig.Item)e.getValue().good).item.getName().equals("Ticket/Keys/Silver Key")).findFirst().orElseThrow(()->new AssertionError("Featured Depot offers lost the native special flag"));
            var good=(com.threerings.projectx.shop.config.GoodConfig.Item)entry.getValue().good;
            int energy=(int)field(good,"_realEnergy").get(good),crowns=(int)field(good,"_realPrice").get(good);
            if(!(boolean)field(good,"_sale").get(good)||(int)field(good,"_originalPrice").get(good)!=750||energy!=500)throw new AssertionError("Native discounted sale fields missing");
            depotUsesEnergy=energy>0;depotPrice=depotUsesEnergy?energy:crowns;depotItem=good.item.getName();depotQuantity=good.count;depotCount=itemCount(depotItem);
            depotCrowns=app.CT().crowns;depotEnergy=app.CT().energy;depotOfferId=entry.getKey();started=now;stage=64;return;
        }
        if(stage==64){
            if(now-started<1_000_000_000L)return;
            if(depotWindow.getGroup()!=com.threerings.projectx.item.data.ItemCodes.Group.SPECIALS||((List<?>)field(depotWindow,"bmJ").get(depotWindow)).isEmpty())throw new AssertionError("Kozma did not display populated Specials");
            capture("depot-npc-offers");
            depotBuy=new Confirm();((com.threerings.projectx.mission.client.q)app.zr().bU(com.threerings.projectx.mission.client.q.class)).f(depotOfferId,depotPrice,depotBuy);
            started=now;stage=62;return;
        }
        if(stage==62){
            if(depotBuy.reason!=null)throw new AssertionError("In-game Depot purchase refused: "+depotBuy.reason);
            if(!depotBuy.done||now-started<1_000_000_000L)return;
            if(itemCount(depotItem)!=depotCount+depotQuantity||app.CT().crowns!=depotCrowns-(depotUsesEnergy?0:depotPrice)||app.CT().energy!=depotEnergy-(depotUsesEnergy?depotPrice:0))throw new AssertionError("Native in-game purchase delivery/debit differs");
            capture("depot-in-game-purchase");depotWindow.fl("purchase");
            depotInfo=new Result();depotBilling=new Confirm();
            var billing=(com.threerings.projectx.billing.a.a)app.zr().bU(com.threerings.projectx.billing.a.a.class);
            billing.b("en",depotInfo);billing.c(0,"",depotBilling);started=now;stage=63;return;
        }
        if(stage==63){
            if(!depotInfo.done||!depotBilling.done||now-started<2_000_000_000L)return;
            if(depotInfo.reason==null||depotBilling.reason==null)throw new AssertionError("Unsupported billing operation reported success");
            if(!app.zr().isLoggedOn()||itemCount(depotItem)!=depotCount+depotQuantity)throw new AssertionError("Billing refusal altered session or inventory");
            capture("depot-disabled-purchase-tab");finishImages();passed=done=true;
            System.out.println("PASS: stock Supply Depot NPC opens, offers load, in-game purchase delivers/debits, purchase tab stays visible and billing requests fail safely");app.shutdown();
        }
    }
    private static final class Result implements com.threerings.presents.client.A.c {
        boolean done;String reason;Object value;
        public void br(Object result){value=result;done=true;}public void aT(String error){reason=error;done=true;}
    }
    private void receive(SceneDeltaEvent event){
        lastStamp=event.QY();
        if(itemsOnly&&event.aem()!=null)for(var effect:event.aem()){
            String ref=effect.Mv().toString();if(ref.contains("fx_fire.dat")||ref.contains("Player/Release Fireflies"))itemEffectSeen=true;
        }
        if(vortexShieldOnly&&event.aem()!=null)for(var effect:event.aem())if(effect.Mv().toString().contains("fx_shieldpush.dat")){
            try{if(field(effect,"_clientOid").getInt(effect)!=0||field(effect,"_target").get(effect)==null)throw new AssertionError("Shield bump is predicted away or has no source actor target");}
            catch(Exception error){throw new AssertionError(error);}sawShieldBump=true;
        }
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
        if(length>0)face(view,axes);if(!moving)Arrays.fill(axes,0);flags(view,moving?1:0);
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
        for(var attack:FireCatalog.load(Path.of("content/_internal/fires.json")).entries())if(attack.weaponConfig().equals(NEEDLE)){
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
        int sceneId()throws Exception{var model=(com.threerings.tudey.data.TudeySceneModel)value.getClass().getMethod("Sn").invoke(value);return model==null?0:model.sceneId;}
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
        int previousReadBuffer=GL11.glGetInteger(GL11.GL_READ_BUFFER);
        if(tutorialEntryOnly)GL11.glReadBuffer(GL11.GL_FRONT);
        GL11.glReadPixels(0,0,w,h,GL11.GL_RGB,GL11.GL_UNSIGNED_BYTE,pixels);
        if(tutorialEntryOnly)GL11.glReadBuffer(previousReadBuffer);
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
