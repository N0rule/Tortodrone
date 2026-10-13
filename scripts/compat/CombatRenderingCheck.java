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

/** Unchanged native controller/sprite/framebuffer probe; launched only by CheckCombatRendering. */
public final class CombatRenderingCheck {
    private record Action(String name,String weapon,int flags,int held,int required,boolean projectile) { }
    private static final String SWORD="Weapon/Sword/Calibur", GUN="Weapon/Handgun/Proto Gun", BOMB="Weapon/Bomb/Blast Bomb";
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
        new Action("dodge",SWORD,1024,120,286,false));
    private final ProjectXApp app;
    private final Path output;
    private final boolean peer;
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
    private int stage,index,frames,lastStamp,actionStamp,requiredStarted;
    private float moveX,moveY;
    private long deadline,started,nextFrame,lastStatus;
    private boolean listening,released,projectile,aftermath,done,passed;
    private Confirm equip;
    private String lastAnimation="";
    private Throwable failure;

    private CombatRenderingCheck(ProjectXApp app,Path output,boolean peer){this.app=app;this.output=output;this.peer=peer;deadline=System.nanoTime()+(peer?360:230)*1_000_000_000L;}
    public static void main(String[] args)throws Exception {
        if(args.length==2&&args[0].equals("--seed")){seed(Path.of(args[1]));return;}
        boolean peer=args.length==4&&args[0].equals("--peer");
        if(peer)args=Arrays.copyOfRange(args,1,args.length);
        if(args.length!=3)throw new IllegalArgumentException("Use CheckCombatRendering.java to launch this fixture");
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
        var probe=new CombatRenderingCheck(app,Path.of(args[2]),peer);
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
            for(String item:List.of(SWORD,GUN,BOMB))store.grantItems("LanPlayer","Town-Knight",item,1,-1,-1);
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
                    +" view="+(current==null?"none":current.getClass().getName())+" loading="+app.GF().mG());
            }
            if(!listening){listening=true;app.zr().xc().a(new com.threerings.tudey.dobj.a(){public void c(SceneDeltaEvent e){receive(e);}});}
            if(!Class.forName("com.threerings.tudey.a.i").isInstance(app.Gu())||app.GF().mG())return;
            var view=new View(app.Gu());if(view.PO()==null)return;
            if(peer){peerTick(view);return;}
            if(stage>=3&&stage<=6&&GLFW.glfwGetWindowAttrib(app.getWindow(),GLFW.GLFW_FOCUSED)==0)
                throw new AssertionError("Keep the disposable combat test window focused; the stock background cap prevents animation/input sampling");
            if(stage==0){GLFW.glfwFocusWindow(app.getWindow());started=now;stage=1;return;}
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
                app.GF().bl(TOUR[tour]);stage=11;return;
            }
            if(stage==11&&view.sceneId()==TOUR[tour]){started=now;stage=12;return;}
            if(stage==12&&view.sceneId()==TOUR[tour]&&now-started>3_000_000_000L){
                capture("scene-"+TOUR[tour]);System.out.println("RENDER: modern scene "+TOUR[tour]+" loaded");
                if(++tour<TOUR.length){app.GF().bl(TOUR[tour]);stage=11;}
                else{app.GF().bl(294);stage=2;}
                return;
            }
            if(stage==2&&view.sceneId()==294&&view.PO().acU() instanceof Dungeoneer){started=now;stage=3;return;}
            if(stage==3&&now-started>2_000_000_000L){capture("training-idle");moveX=view.PO().acU().tz().x;moveY=view.PO().acU().tz().y;moveNorth(view,true);started=now;stage=8;return;}
            if(stage==8&&now-started>1_000_000_000L){moveNorth(view,false);started=now;stage=9;return;}
            if(stage==9&&now-started>1_000_000_000L){
                var server=authoritative.get(app.CT().pawnId);
                if(server==null||server.tz().y<moveY+2)throw new AssertionError("Native movement did not reach the training corridor away from its south exit");
                capture("training-movement");System.out.println("CHECK: native controlled movement and authoritative position");stage=4;
            }
            if(stage==4){
                if(index==ACTIONS.size()){capture("training-complete");app.GF().bl(1);stage=7;return;}
                var action=ACTIONS.get(index);equip=new Confirm();long id=0;
                for(var item:app.CT().items)if(item.Vx().getName().equals(action.weapon)){id=item.VA();break;}
                if(id==0)throw new AssertionError("Missing seeded weapon "+action.weapon);
                app.zr().bU(com.threerings.projectx.item.client.L.class).a(id,6,equip);started=now;stage=5;return;
            }
            if(stage==5){
                if(!equip.done)return;if(equip.reason!=null)throw new AssertionError("Equip refused: "+equip.reason);
                var weapon=(Item)field(view.PO().acU(),"_weapon").get(view.PO().acU());
                Object impl=view.PO().implementation();var renderedWeapon=(Item)field(impl,"_weapon").get(impl);
                if(weapon==null||!weapon.Vx().getName().equals(ACTIONS.get(index).weapon)||renderedWeapon==null||!renderedWeapon.Vx().getName().equals(ACTIONS.get(index).weapon)){
                    if(now-started>5_000_000_000L)throw new AssertionError("Actor/sprite weapon did not converge to "+ACTIONS.get(index).weapon);
                    return;
                }
                var body=(com.threerings.config.ConfigReference<?>)view.PO().acU().Mv().kW().get("Model");
                if(body==null||body.kW().containsKey("Weapon")||body.kW().containsKey("Shield")||body.kW().containsKey("Weapon Idle"))throw new AssertionError("Interior base model duplicates held equipment");
                activities.clear();renderedActivities.clear();animations.clear();capturedProjectiles.clear();frames=0;projectile=false;released=false;aftermath=false;lastAnimation="";
                previousActors.clear();previousActors.addAll(authoritative.keySet());actionStamp=lastStamp;requiredStarted=Integer.MAX_VALUE;
                started=now;nextFrame=now+60_000_000L;flags(view,ACTIONS.get(index).flags);stage=6;
                System.out.println("ACTION: "+ACTIONS.get(index).name);return;
            }
            if(stage==6){
                var action=ACTIONS.get(index);int elapsed=(int)((now-started)/1_000_000L);
                if(!released&&elapsed>=action.held){flags(view,0);released=true;frames=0;nextFrame=now+60_000_000L;}
                var own=view.PO();int activity=((Dungeoneer)own.acU()).adU();
                var names=new TreeSet<String>();playing(own.acL(),names,0);
                String state=activity+":"+names;
                if(!state.equals(lastAnimation)){System.out.println("ANIMATION: "+action.name+" "+elapsed+"ms "+state);lastAnimation=state;}
                if(activity!=0&&activityPlaying(own,activity)){if(renderedActivities.add(activity))capture(action.name+"-activity-"+activity);animations.addAll(names);}
                if(now>=nextFrame&&frames<12){capture(action.name+(released?"-release-":"-")+String.format(Locale.ROOT,"%02d",frames++));nextFrame=now+120_000_000L;}
                if(!aftermath&&action.name.equals("bomb-charge")&&elapsed>action.held+1800){capture("bomb-charge-aftermath");aftermath=true;}
                // A held gun press fires normally before entering charge. Only
                // projectiles born after the required release activity qualify.
                for(var actor:authoritative.values())if(activities.contains(action.required)
                    &&!previousActors.contains(actor.vw())&&actor.adX()>=Math.max(actionStamp,requiredStarted)
                    &&Set.of("Bullet","Bomb","OrbitalBullet","CompoundOrbitalBullet").contains(actor.getClass().getSimpleName())
                    &&(int)field(actor,"_clientOid").get(actor)==app.CT().xL()){
                    var sprite=view.gk(actor.vw());
                    if(sprite!=null&&sprite.acL().getConfig()!=null&&sprite.acL().isVisible()&&capturedProjectiles.add(actor.vw())){
                        projectile=true;System.out.println("PROJECTILE: "+action.name+" "+actor.getClass().getSimpleName()+" "+actor.Mv());
                        capture(action.name+"-projectile-"+capturedProjectiles.size());
                    }
                }
                var server=authoritative.get(app.CT().pawnId);
                if(elapsed>action.held+2500&&server instanceof Dungeoneer knight&&knight.adU()==0){
                    if(!activities.contains(action.required)||!renderedActivities.contains(action.required))throw new AssertionError(action.name+" missed authoritative/rendered activity "+action.required+": "+activities+" / "+renderedActivities);
                    if(action.projectile&&!projectile)throw new AssertionError(action.name+" has no rendered server projectile");
                    System.out.println("CHECK: "+action.name+" server="+activities+" rendered="+renderedActivities+" animations="+animations);
                    index++;stage=4;return;
                }
                if(elapsed>action.held+10000)throw new AssertionError(action.name+" failed to return to idle");
            }
            // Scene identity changes before its first rendered frame. Let Haven
            // settle before saving the return image and reporting completion.
            if(stage==7&&view.sceneId()==1){started=now;stage=10;return;}
            if(stage==10&&view.sceneId()==1&&now-started>2_000_000_000L){capture("haven-return");finishImages();Files.writeString(output.resolve("primary-complete"),"");passed=done=true;System.out.println("PASS: two stock graphical clients, seven modern Haven scenes and return transitions, nine combat actions, native animations and server projectile sprites");app.shutdown();}
        }catch(Throwable error){failure=error;done=true;error.printStackTrace();images.shutdownNow();app.shutdown();}
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
        if(event.aeh()==0)authoritative.clear();
        if(event.aej()!=null)for(var actor:event.aej())authoritative.put(actor.vw(),(Actor)actor.copy(null));
        if(event.aek()!=null)for(var delta:event.aek()){Actor old=authoritative.get(delta.vw());if(old!=null)authoritative.put(delta.vw(),(Actor)delta.apply(old));}
        if(event.ael()!=null)for(int id:event.ael())authoritative.remove(id);
        if(stage==6&&authoritative.get(app.CT().pawnId) instanceof Dungeoneer own){
            activities.add(own.adU());
            if(own.adU()==ACTIONS.get(index).required)requiredStarted=own.adV();
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
        Arrays.fill(axes,0);axes[0]=moving?1:0;flags(view,moving?1:0);
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
