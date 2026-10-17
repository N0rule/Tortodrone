import java.io.*;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.town.*;
import org.tortodrone.protocol.*;
import com.threerings.io.v;
import com.threerings.presents.dobj.*;
import com.threerings.presents.net.*;
import com.threerings.projectx.data.PlayerObject;

/** Focused stock-reader check for Town messages; no server or graphical client is launched. */
public final class TownCompatibility {
    public static void main(String[] args)throws Exception{
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        var sprite=new TownState.Sprite(7,"Drakon","Sparky",16,.5f,12345L,0,1,Set.of(6),0,List.of(1,2,0,0,0,0,0,0,0),List.of(0,0,0),9);
        var item=new WorldProfile.Item(20,WorldProfile.Slot.SWORD,"Weapon/Sword/Calibur",null,0,null,false,true,false,1,false,2,.4f,List.of(new StarterCatalog.Model("Weapon/Damage 1",Map.of())));
        var mail=new AuctionState.Mail(10,42,123456L,"~Auction","~Result",100,null,false,false);
        var state=new TownState(List.of(new TownState.LearnedRecipe(5,"Sword/Calibur",0,0)),List.of(sprite),7,List.of(new TownState.Harness(9,"Iron Harness")),List.of(mail));
        var profile=new WorldProfile(42,1,1,1000,100,1,List.of(item),KnightProgress.RECRUIT,state);
        var codec=new SessionCodec();var bytes=new ByteArrayOutputStream();
        append(bytes,HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"));append(bytes,HandshakeCodec.authenticationResponse((short)2,true,"fixture","Fixture"));append(bytes,codec.player((short)3,1002,1,"Fixture"));append(bytes,codec.itemChanges(1002,choices(),new WorldProfile(42,1,1,0,0,1,List.of()),profile));append(bytes,codec.townState(1002,profile));
        var source=new org.tortodrone.world.ActorState(1000000000,0,"",0,1,0,0,0,0,0,0,0,new StarterCatalog.Model("Shop/Haven/Alchemy",Map.of()),false);
        for(String kind:List.of("OpenCrafter","OpenCatalogCrafter","OpenUnbinder","OpenAccessorizer","OpenVariantMaker","OpenAuction","OpenShop","OpenDepot","OpenHarnessInstaller")){
            var station=new TownCatalog.Station("",kind,"","NORMAL","~","~","","",kind.equals("OpenCatalogCrafter")?List.of("Sprite/Food/Power Mote"):List.of());append(bytes,codec.townDialog(1002,10,station,source,123456,101));
        }
        append(bytes,codec.townShop(1002,List.of(new TownCatalog.Good("Weapon/Sword/Calibur",1,100,0,0,"",0)),List.of(-1)));
        append(bytes,codec.townResult(1002,(short)4,sprite,profile));append(bytes,codec.townResult(1002,(short)5,List.of(mail),profile));
        var listing=new AuctionState.Listing(5,42,item,100,1000,0,Long.MAX_VALUE,false,false,true,false,10);append(bytes,codec.townResult(1002,(short)6,new AuctionState.Page(1,List.of(listing)),profile));
        var featured=new AuctionState.Listing(6,0,item,100,1000,0,Long.MAX_VALUE,false,false,false,false,0,false,10,9,12345);
        append(bytes,codec.townResult(1002,(short)7,new AuctionState.Page(1,List.of(featured)),profile));
        append(bytes,codec.townResult(1002,(short)8,new TownCatalog.DepotOffers(Map.of(7,new TownCatalog.DepotGood(new TownCatalog.Good("Weapon/Sword/Calibur",1,500,200,0,"",0),true))),profile));
        int callbackEnd=bytes.size();
        var actor=new org.tortodrone.world.ActorState(42,1002,"Fixture",256,1,0,0,0,0,0,0,1,new StarterCatalog.Model("Character/PC/Default",Map.of()),false,null,null,null,null,new org.tortodrone.world.SceneKnightState(null,0,org.tortodrone.world.SceneStatus.State.NORMAL,KnightProgress.RECRUIT,sprite,new TownState.Harness(9,"Iron Harness")));
        var changed=new org.tortodrone.world.ActorState(42,1002,"Fixture",256,1,1,0,0,0,0,0,1,actor.config(),false,null,null,null,null,new org.tortodrone.world.SceneKnightState(null,0));
        append(bytes,codec.sceneDelta(1002,10,0,(short)0,0,100,(short)50,List.of(actor),List.of(),new int[0],Map.of(),false));
        append(bytes,codec.sceneDelta(1002,10,0,(short)0,100,150,(short)50,List.of(),List.of(changed),new int[0],Map.of(42,actor),false));
        var companion=new org.tortodrone.world.ActorState(1700000000,0,"",0,1,0,0,0,0,0,0,1,new StarterCatalog.Model("Sprite/Base",Map.of()),false,null,null,null,null,null,new org.tortodrone.world.ScenePetState(42,sprite,new TownState.Harness(9,"Iron Harness"),false,List.of(0,0,0),0,0,null));
        var casting=new org.tortodrone.world.ActorState(companion.id(),0,"",0,1,1,0,1,0,0,1,10,companion.config(),false,null,null,null,null,null,new org.tortodrone.world.ScenePetState(42,sprite,new TownState.Harness(9,"Iron Harness"),true,List.of(100,200,300),40,80,"0"));
        append(bytes,codec.sceneDelta(1002,10,0,(short)0,150,200,(short)50,List.of(companion),List.of(),new int[0],Map.of(),true));
        append(bytes,codec.sceneDelta(1002,10,0,(short)0,200,250,(short)50,List.of(),List.of(casting),new int[0],Map.of(companion.id(),companion),true));
        var dungeon=new org.tortodrone.world.ActorState(42,1002,"Fixture",256,1,0,0,0,0,0,0,1,actor.config(),false,null,null,null,null,new org.tortodrone.world.SceneKnightState(null,0,org.tortodrone.world.SceneStatus.State.NORMAL,KnightProgress.RECRUIT,sprite,new TownState.Harness(9,"Iron Harness"),companion.id(),1));
        append(bytes,codec.sceneDelta(1002,10,0,(short)0,250,300,(short)50,List.of(dungeon),List.of(),new int[0],Map.of(),true));
        append(bytes,codec.sceneDelta(1002,10,0,(short)0,300,350,(short)50,List.of(),List.of(dungeon),new int[0],Map.of(42,dungeon),true));
        var auxiliary=new java.util.ArrayList<org.tortodrone.world.ActorState>();int auxiliaryId=1700000001;
        for(String kind:List.of("ACTOR","LASER","ORBITAL")){
            var orbit=kind.equals("ORBITAL")?new org.tortodrone.world.ProjectileState.Orbit(.5f,42,.5f,0,0,0,42):null;
            var projectile=new org.tortodrone.world.ProjectileState(kind,new StarterCatalog.Model("Fixture",Map.of()),0,1,Integer.MAX_VALUE,0,0,Float.NEGATIVE_INFINITY,4,0,42,false,orbit);
            auxiliary.add(new org.tortodrone.world.ActorState(auxiliaryId++,0,"",0,1,0,0,0,0,0,0,1,projectile.config(),false,null,projectile));
        }
        int beforeAuxiliary=bytes.size();
        append(bytes,codec.sceneDelta(1002,10,0,(short)0,350,400,(short)50,auxiliary,List.of(),new int[0],Map.of(),true));
        int afterAuxiliary=bytes.size();
        append(bytes,codec.sceneDelta(1002,10,0,(short)0,400,450,(short)50,List.of(),auxiliary,new int[0],Map.of(),true));
        var in=new v(new ByteArrayInputStream(bytes.toByteArray()));in.qe();in.qe();var player=(PlayerObject)((ObjectResponse)in.qe()).yk();apply(in,player);apply(in,player);
        if(!field(player.items.iterator().next(),"_level").equals(2))throw new AssertionError("Native crafted item level missing");
        if(!field(player.items.iterator().next(),"_heatProgress").equals(.4f)||java.lang.reflect.Array.getLength(field(player.items.iterator().next(),"_variants"))!=1)throw new AssertionError("Native heat or UV item state differs");
        if(player.sprites.size()!=1||player.recipes.size()!=1||player.equippedSprite!=7||player.harnesses.size()!=1||player.newMailInfo.size()!=1)throw new AssertionError("Native Town sets differ");
        for(int i=0;i<9;i++){apply(in,player);if(player.sceneDialog==null||player.sceneDialogCreated!=123456)throw new AssertionError("Native station dialog missing");}
        apply(in,player);if(!(player.uniqueDialog instanceof com.threerings.projectx.shop.data.UniqueShopInfo))throw new AssertionError("Native shop stock missing");
        for(int i=0;i<3;i++)if(!(in.qe() instanceof EventNotification))throw new AssertionError("Missing Town callback");
        var featuredReply=(com.threerings.presents.dobj.InvocationResponseEvent)((EventNotification)in.qe()).yj();
        var nativeFeatured=((com.threerings.projectx.auction.data.ListingsPage)featuredReply.yd()[0]).listings.getFirst();
        if(nativeFeatured.featured==null||nativeFeatured.featured.total!=10||nativeFeatured.featured.remaining!=9||nativeFeatured.myAuction)throw new AssertionError("Featured stock/ownership fields differ");
        var depotReply=(com.threerings.presents.dobj.InvocationResponseEvent)((EventNotification)in.qe()).yj();
        var nativeGood=(com.threerings.projectx.mission.data.RankGood)((Map<?,?>)depotReply.yd()[0]).get(7);
        if(nativeGood==null||!field(nativeGood.good,"_realPrice").equals(500)||!field(nativeGood.good,"_realEnergy").equals(200)||!nativeGood.special||!nativeGood.TY()||nativeGood.promoHeader!=null||nativeGood.promoDetail!=null)throw new AssertionError("Depot good price/promotion fields differ");
        if(bytes.size()-in.available()!=callbackEnd)throw new AssertionError("Callback boundary differs by "+(bytes.size()-in.available()-callbackEnd)+" bytes");
        var added=(com.threerings.tudey.dobj.SceneDeltaEvent)((EventNotification)in.qe()).yj();var nativeActor=(com.threerings.projectx.data.actor.Knight)added.aej()[0];if(field(nativeActor,"_sprite")==null)throw new AssertionError("Native equipped sprite absent from Knight");
        var delta=(com.threerings.tudey.dobj.SceneDeltaEvent)((EventNotification)in.qe()).yj();nativeActor=(com.threerings.projectx.data.actor.Knight)delta.aek()[0].apply(nativeActor);if(field(nativeActor,"_sprite")!=null)throw new AssertionError("Native sprite removal delta failed");
        var petAdded=(com.threerings.tudey.dobj.SceneDeltaEvent)((EventNotification)in.qe()).yj();var nativePet=(com.threerings.projectx.dungeon.data.actor.SpriteActor)petAdded.aej()[0];
        if(!field(nativePet,"_knightId").equals(42))throw new AssertionError("Pet owner missing");
        var petDelta=(com.threerings.tudey.dobj.SceneDeltaEvent)((EventNotification)in.qe()).yj();nativePet=(com.threerings.projectx.dungeon.data.actor.SpriteActor)petDelta.aek()[0].apply(nativePet);
        if(!field(nativePet,"_follow").equals(true)||!field(nativePet,"_shieldHealth").equals(40)||((int[])field(nativePet,"_nextSkill"))[2]!=300||!field(nativePet,"_shieldPower").equals("0"))throw new AssertionError("Native pet delta differs");
        var dungeonAdd=(com.threerings.tudey.dobj.SceneDeltaEvent)((EventNotification)in.qe()).yj();var nativeDungeon=dungeonAdd.aej()[0];
        if(!field(nativeDungeon,"_spriteId").equals(companion.id())||!field(nativeDungeon,"_spriteAttack").equals(1))throw new AssertionError("Dungeon companion identity missing");
        var dungeonUpdate=(com.threerings.tudey.dobj.SceneDeltaEvent)((EventNotification)in.qe()).yj();nativeDungeon=(com.threerings.tudey.data.actor.Actor)dungeonUpdate.aek()[0].apply(nativeDungeon);
        if(!field(nativeDungeon,"_spriteId").equals(companion.id()))throw new AssertionError("Dungeon companion delta differs");
        if(bytes.size()-in.available()!=beforeAuxiliary)throw new AssertionError("Dungeon packet boundary differs by "+(bytes.size()-in.available()-beforeAuxiliary)+" bytes");
        var auxAdd=(com.threerings.tudey.dobj.SceneDeltaEvent)((EventNotification)in.qe()).yj();if(bytes.size()-in.available()!=afterAuxiliary)throw new AssertionError("Auxiliary packet boundary differs by "+(bytes.size()-in.available()-afterAuxiliary)+" bytes");var auxUpdate=(com.threerings.tudey.dobj.SceneDeltaEvent)((EventNotification)in.qe()).yj();
        for(int i=0;i<3;i++){var nativeAux=auxUpdate.aek()[i].apply(auxAdd.aej()[i]);if(i==1&&!field(nativeAux,"_endPoint").equals(new com.threerings.math.Vector2f(4,0)))throw new AssertionError("Laser endpoint differs");if(i==2&&!field(nativeAux,"_followId").equals(42))throw new AssertionError("Orbital follow identity differs");}
        if(in.available()!=0)throw new AssertionError("Trailing Town wire bytes");
        upstream();
        serviceMessages();
        System.out.println("PASS: stock reader accepts recipes, sprites, harnesses, stations, auction listings and attachment mail");
    }
    private static void serviceMessages()throws Exception {
        var codec=new SessionCodec(ItemCatalog.load(java.nio.file.Path.of("content/packs/vanilla/items/catalog.json")));var bytes=new ByteArrayOutputStream();
        append(bytes,HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"));
        append(bytes,HandshakeCodec.authenticationResponse((short)2,true,"fixture","Fixture"));
        append(bytes,codec.controllerAction(1002,"forge",null));
        append(bytes,codec.controllerAction(1002,"show_arsenal_station",null));
        var registry=new org.tortodrone.world.SceneRegistry(org.tortodrone.world.SceneCatalog.load(java.nio.file.Path.of("content/packs/vanilla/maps")));
        append(bytes,codec.scene((short)3,registry.entry(),List.of()));
        append(bytes,codec.equipPolicy(1002,true));
        var giftContent=new WorldProfile.Item(0,WorldProfile.Slot.SWORD,"Weapon/Sword/Calibur",null,0,null,false,true,false,1,false,2,.4f,List.of(new StarterCatalog.Model("Weapon/Damage 1",Map.of())));
        var giftItem=new WorldProfile.Item(70,WorldProfile.Slot.OTHER,"Upgrade/Gift Box/Checker Gift Box (Blue)",null,0,null,false,false,false,1,false).withGift(new WorldProfile.Gift(1,"Gift-Alice","A gift for you",List.of(giftContent)));
        var owner=new WorldProfile(42,1,1,0,0,1,List.of(giftItem));
        append(bytes,codec.player((short)4,1002,1,"Fixture"));append(bytes,codec.itemChanges(1002,choices(),new WorldProfile(42,1,1,0,0,1,List.of()),owner));
        append(bytes,codec.townResult(1002,(short)8,new SessionDecoder.Multiset(Map.of(71L,1)),owner));
        var in=new v(new ByteArrayInputStream(bytes.toByteArray()));in.qe();in.qe();
        for(String command:List.of("forge","show_arsenal_station")){
            var event=(MessageEvent)((EventNotification)in.qe()).yj();
            var action=(com.threerings.tudey.config.ClientActionConfig.ControllerAction)event.yd()[0];
            if(!event.getName().equals("forceClientAction")||!action.command.equals(command)||!action.argument.equals(""))throw new AssertionError("Native controller action layout differs");
        }
        var scene=(com.threerings.projectx.data.ProjectXSceneObject)((ObjectResponse)in.qe()).yk();
        if(scene.mapIcons.size()!=registry.entry().map().content().mapIcons().size()||scene.mapIcons.isEmpty())throw new AssertionError("Native scene minimap markers missing");
        for(var icon:scene.mapIcons)if(!icon.getIcon().startsWith("ui/minimap/"))throw new AssertionError("Non-stock minimap icon");
        var policy=(AttributeChangedEvent)((EventNotification)in.qe()).yj();
        if(policy.getValue()!=PlayerObject.EquipPolicy.ANY)throw new AssertionError("Native equipment policy differs");
        var player=(PlayerObject)((ObjectResponse)in.qe()).yk();apply(in,player);
        var gift=(com.threerings.projectx.item.data.GiftBoxItem)player.items.iterator().next();
        if(!gift.getMessage().equals("A gift for you")||!field(gift,"_wrapperName").equals("Gift-Alice")||((com.google.common.collect.Multiset<?>)field(gift,"_gifts")).size()!=1)throw new AssertionError("Native gift contents or author fields differ");
        var unwrapped=(InvocationResponseEvent)((EventNotification)in.qe()).yj();
        if(!unwrapped.yd()[0].equals(com.google.common.collect.ImmutableMultiset.of(71L))||in.available()!=0)throw new AssertionError("Native unwrap callback differs");
    }
    private static void upstream()throws Exception{
        var buys=com.google.common.collect.HashMultiset.<Integer>create();buys.add(2,3);var sells=com.google.common.collect.HashMultiset.<Long>create();sells.add(45L,2);
        var confirm=new com.threerings.presents.data.InvocationMarshaller.ConfirmMarshaller();
        var invocation=decode(new com.threerings.presents.dobj.InvocationRequestEvent(10,100,1,new Object[]{buys,sells,confirm}));
        if(!((SessionDecoder.Multiset)invocation.arguments().get(0)).counts().equals(Map.of(2,3))||!((SessionDecoder.Multiset)invocation.arguments().get(1)).counts().equals(Map.of(45L,2)))throw new AssertionError("Native shop multiset layout differs");
        invocation=decode(new com.threerings.presents.dobj.InvocationRequestEvent(10,101,1,new Object[]{new java.util.HashSet<Integer>(Set.of(1,2)),confirm}));
        if(!new java.util.HashSet<>((List<?>)invocation.arguments().getFirst()).equals(Set.of(1,2)))throw new AssertionError("Native harness index set layout differs");
        var search=new com.threerings.projectx.auction.data.Search();search.group=com.threerings.projectx.item.data.ItemCodes.Group.ARMOR;search.configs=new java.util.HashSet<>(Set.of(123));search.sort=com.threerings.projectx.auction.data.Search.Sort.BID_PRICE;search.ascending=true;
        var result=new com.threerings.presents.data.InvocationMarshaller.ResultMarshaller();invocation=decode(new com.threerings.presents.dobj.InvocationRequestEvent(3,15,5,new Object[]{search,0,result}));
        var parsed=(SessionDecoder.AuctionSearch)invocation.arguments().get(0);if(parsed.sort()!=1||!parsed.ascending()||!parsed.configs().equals(search.configs))throw new AssertionError("Native auction search layout differs");
    }
    private static SessionDecoder.Invocation decode(com.threerings.presents.dobj.InvocationRequestEvent event)throws Exception{
        var bytes=new ByteArrayOutputStream();var nativeOut=new com.threerings.io.w(bytes);nativeOut.bf(new com.threerings.presents.net.ForwardEventRequest(event));nativeOut.flush();
        return (SessionDecoder.Invocation)new HandshakeCodec().sessionRequest(bytes.toByteArray()).event();
    }
    private static Object field(Object value,String name)throws Exception{for(Class<?> type=value.getClass();type!=null;type=type.getSuperclass())try{var field=type.getDeclaredField(name);field.setAccessible(true);return field.get(value);}catch(NoSuchFieldException absent){}throw new AssertionError("Missing native field: "+name);}
    private static Knight choices(){return new Knight(1,"Fixture","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,1);}
    private static void append(ByteArrayOutputStream out,byte[] frame){out.write(frame,4,frame.length-4);}
    private static void apply(v in,DObject object)throws Exception{var event=((EventNotification)in.qe()).yj();if(event instanceof CompoundEvent compound)for(var child:compound.xE())child.b(object);else event.b(object);}
}
