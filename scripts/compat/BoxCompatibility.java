import java.io.*;
import java.util.*;
import java.nio.file.*;
import com.threerings.config.*;
import com.threerings.projectx.item.config.*;
import org.tortodrone.resources.*;
import com.threerings.presents.dobj.*;
import com.threerings.presents.net.*;
import com.threerings.presents.client.A;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;

/** Installed, unmodified ItemMarshaller and reader are the wire oracle; no client patching. */
public final class BoxCompatibility {
    private static final class Dispatch extends com.threerings.presents.client.x {
        InvocationRequestEvent event;
        @Override public void a(int oid,int code,int method,Object[] args,Transport transport){event=new InvocationRequestEvent(oid,code,method,args);}
    }
    public static void main(String[] args)throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        if(args.length!=1)throw new IllegalArgumentException("Usage: BoxCompatibility game-directory");
        bindings(Path.of(args[0]));
        var dispatch=new Dispatch();var service=new com.threerings.projectx.item.data.ItemMarshaller();service.a(3,9,dispatch);
        for(int option:new int[]{-1,0,1}) {
            service.b(123L,option,(A.c)null);
            var bytes=new ByteArrayOutputStream();var nativeOut=new com.threerings.io.w(bytes);nativeOut.bf(new ForwardEventRequest(dispatch.event));nativeOut.flush();
            var invocation=(SessionDecoder.Invocation)new HandshakeCodec().sessionRequest(bytes.toByteArray()).event();
            check(invocation.oid()==3&&invocation.service()==9&&invocation.method()==12,"native method 12");
            check(invocation.arguments().size()==3&&invocation.arguments().get(0).equals(123L)&&invocation.arguments().get(1).equals(option),"native instance and key option argument layout");
            check(invocation.arguments().get(2) instanceof SessionDecoder.Listener l&&l.type().equals("com.threerings.presents.data.InvocationMarshaller$ResultMarshaller"),"native result listener");
        }

        check(com.threerings.projectx.item.config.ItemConfig.Lockbox.KeyType.NONE.ticketTypes.isEmpty(),"keyless options");
        check(com.threerings.projectx.item.config.ItemConfig.Lockbox.KeyType.SILVER.ticketTypes.toString().equals("[LOCKBOX_KEY]"),"Silver option");
        check(com.threerings.projectx.item.config.ItemConfig.Lockbox.KeyType.SLIME.ticketTypes.toString().equals("[SLIME_KEY, LOCKBOX_KEY]"),"Slime options in native menu order");
        var bytes=new ByteArrayOutputStream();var catalog=ItemYaml.load(Path.of("content/items"));var codec=new SessionCodec(catalog);
        append(bytes,HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"));
        append(bytes,HandshakeCodec.authenticationResponse((short)2,true,"synthetic","Alice"));
        var empty=new WorldProfile(42,1,1,0,0,1,List.of(),KnightProgress.RECRUIT);
        var item=new WorldProfile.Item(777,WorldProfile.Slot.OTHER,"Material/Brimstone",null,0,null,true,false,false,3,false);
        var upgrade=catalog.require("trinket_slot_2_days");
        var inactive=new WorldProfile.Item(778,WorldProfile.Slot.OTHER,upgrade.config(),null,0,null,true,false,false,1,false);
        var furniture=catalog.entries().stream().filter(e->e.grantable()&&e.variant()==ItemCatalog.Variant.FURNITURE_ITEM).findFirst().orElseThrow();
        long expiry=1_900_000_000_000L;var disguise=catalog.require("gremlin_disguise");
        var furni=new WorldProfile.Item(779,WorldProfile.Slot.OTHER,furniture.config(),null,0,null,false,false,false,2,false);
        var timed=new WorldProfile.Item(780,disguise.slot(),disguise.config(),null,0,null,false,false,false,1,false).withExpiration(expiry);
        var after=new WorldProfile(42,1,1,0,0,2,List.of(item,inactive,furni,timed),KnightProgress.RECRUIT);
        append(bytes,codec.itemChanges(10,null,empty,after));
        append(bytes,codec.townResult(10,(short)9,new SessionDecoder.Multiset(Map.of(777L,3)),after));
        append(bytes,codec.invocationFailure(10,(short)10,"m.inventory_full"));
        var weapon=(StarterCatalog.Model)catalog.require("untimely_scythe").arguments(256).get("Weapon");
        append(bytes,codec.townResult(10,(short)11,weapon,after));
        var in=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));in.qe();in.qe();
        var added=((EventNotification)in.qe()).yj();check(added instanceof CompoundEvent,"native inventory update before reveal");
        var decoded=((CompoundEvent)added).xE().stream().filter(e->e instanceof EntryAddedEvent<?> a&&a.xZ() instanceof com.threerings.projectx.item.data.UpgradingItem).map(e->(com.threerings.projectx.item.data.UpgradingItem)((EntryAddedEvent<?>)e).xZ()).findFirst().orElseThrow();
        check(decoded.VA()==778&&decoded.Cs()&&decoded.getCount()==1&&decoded.VE()==null,"native inactive upgrade subclass, identity, binding and no running timer");
        var newItems=((CompoundEvent)added).xE().stream().filter(e->e instanceof EntryAddedEvent<?>).map(e->((EntryAddedEvent<?>)e).xZ()).filter(e->e instanceof com.threerings.projectx.item.data.Item).map(e->(com.threerings.projectx.item.data.Item)e).toList();
        var furnitureRead=newItems.stream().filter(e->e.VA()==779).findFirst().orElseThrow();
        check(furnitureRead instanceof com.threerings.projectx.item.data.FurniItem&&furnitureRead.getCount()==2&&furnitureRead.VE()==null,"native undeployed FurniItem layout");
        var timedRead=newItems.stream().filter(e->e.VA()==780).findFirst().orElseThrow();
        check(timedRead instanceof com.threerings.projectx.item.data.LevelItem&&Objects.equals(timedRead.VE(),expiry),"native absolute Long expiration layout");
        var result=(InvocationResponseEvent)((EventNotification)in.qe()).yj();
        check(result.yc()==1&&result.yf()==9&&result.yd()[0] instanceof com.google.common.collect.Multiset<?> m&&m.count(777L)==3,"native result multiset of item IDs and quantities");
        var failure=(InvocationResponseEvent)((EventNotification)in.qe()).yj();check(failure.yc()==0&&failure.yf()==10,"native failure callback");
        var customResult=(InvocationResponseEvent)((EventNotification)in.qe()).yj();
        var nativeWeapon=(ConfigReference<?>)customResult.yd()[0];
        for(String channel:List.of("Blue","Green","Red")) {
            var wanted=(StarterCatalog.CustomColorization)weapon.arguments().get(channel);
            var actual=(com.threerings.opengl.renderer.config.ColorizationConfig.CustomOffsets)nativeWeapon.kW().get(channel);
            check(actual.clazz==wanted.clazz()&&actual.offsets.hue==wanted.hue()&&actual.offsets.saturation==wanted.saturation()&&actual.offsets.value==wanted.value(),"native custom color triplet: "+channel);
        }
        check(in.available()==0,"no trailing bytes");
        System.out.println("PASS: stock method 12, all key options, ResultMarshaller, inventory-before-result, Long multiset quantities, inactive UpgradingItem, furniture, timed costume and failure serialization");
    }
    private static void bindings(Path game)throws Exception {
        game=game.toRealPath();
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,game.resolve("rsrc").toString());
        var manager=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");manager.init();
        var items=ItemYaml.load(Path.of("content/items"));
        var pinned=Yaml.map(Yaml.map(Json.parseCatalog(Files.readString(Path.of("content/_internal/box-bindings.json"))),"bindings").get("boxes"),"boxes");
        int count=0;
        for(var item:items.entries())if(item.type().equals("Lockbox")) {
            var config=manager.a(ItemConfig.class,new ConfigReference<ItemConfig>(item.config()));
            check(config!=null&&config.w(manager) instanceof ItemConfig.Lockbox,"native Lockbox identity");
            var nativeBox=(ItemConfig.Lockbox)config.w(manager);
            var expected=nativeBox.key.ticketTypes.stream().map(t->switch(t){case SLIME_KEY->"slime_key";case LOCKBOX_KEY->"silver_key";default->throw new AssertionError("Unverified key type: "+t);}).toList();
            check(Yaml.map(pinned.get(items.referenceId(item)),"box").get("keyOptions").equals(expected),"native key binding: "+items.referenceId(item));count++;
        }
        for(String key:List.of("silver_key","slime_key","shadow_key")) {
            var nativeKey=(ItemConfig.Ticket)manager.a(ItemConfig.class,new ConfigReference<ItemConfig>(items.require(key).config())).w(manager);
            check(nativeKey.type.name().equals(key.equals("silver_key")?"LOCKBOX_KEY":key.toUpperCase(Locale.ROOT)),"native key identity: "+key);
        }
        for(var item:items.entries())if(item.variant()==ItemCatalog.Variant.UPGRADE_ITEM&&item.grantable()) {
            var config=(ItemConfig.Upgrade)manager.a(ItemConfig.class,new ConfigReference<ItemConfig>(item.config())).w(manager);
            var nativeItem=config.a(null,item.stockId(),new ConfigReference<ItemConfig>(item.config()));
            check(nativeItem.getClass()==com.threerings.projectx.item.data.UpgradingItem.class&&config.expireDays==0&&(config.expireEvent==null||config.expireEvent.isBlank()),"verified inactive upgrade representation: "+item.config());
        }
        var context=(com.threerings.projectx.item.b.a)java.lang.reflect.Proxy.newProxyInstance(BoxCompatibility.class.getClassLoader(),new Class[]{com.threerings.projectx.item.b.a.class},(proxy,method,arguments)->{
            if(method.getName().equals("getConfigManager"))return manager;throw new UnsupportedOperationException(method.getName());
        });
        int furnishings=0;
        for(var entry:items.entries())if(entry.grantable()&&entry.variant()==ItemCatalog.Variant.FURNITURE_ITEM) {
            var config=manager.a(ItemConfig.class,new ConfigReference<ItemConfig>(entry.config())).w(manager);
            var actual=config.a(context,entry.stockId(),new ConfigReference<ItemConfig>(entry.config()));
            check(actual.getClass()==com.threerings.projectx.item.data.FurniItem.class&&config.expireDays==0,"native furniture factory: "+entry.config());furnishings++;
        }
        check(furnishings==244,"complete playable furniture representation");
        for(String id:List.of("gun_puppy_disguise","gremlin_disguise","mechaknight_disguise","trojan_disguise","untimely_scythe")) {
            var entry=items.require(id);var config=manager.a(ItemConfig.class,new ConfigReference<ItemConfig>(entry.config())).w(manager);
            check(entry.expireDays()==config.expireDays&&(config.expireEvent==null||config.expireEvent.isBlank()),"native expiration definition: "+id);
            var arguments=new ArgumentMap();config.a(256,arguments);
            String parameter=id.equals("untimely_scythe")?"Weapon":"Helmet";
            var expected=(ConfigReference<?>)arguments.get(parameter);var represented=(StarterCatalog.Model)entry.arguments(256).get(parameter);
            check(expected.getName().equals(represented.name()),"native prize model reference: "+id);
        }
        var rewards=new HashSet<String>();
        for(var box:org.tortodrone.loot.BoxCatalog.load(Path.of("content"),items).entries())if(box.enabled()) {
            var all=new ArrayList<>(box.guaranteed());box.groups().values().forEach(g->g.entries().forEach(e->all.addAll(e.rewards())));
            for(var reward:all)if(reward instanceof org.tortodrone.loot.BoxDefinition.Item item)rewards.add(item.config());
        }
        for(String reward:rewards) {
            var entry=items.require(reward);var original=manager.a(ItemConfig.class,new ConfigReference<ItemConfig>(reward)).w(manager);
            var actual=original.a(context,entry.stockId(),new ConfigReference<ItemConfig>(reward));
            String expected=entry.slot()!=WorldProfile.Slot.OTHER?"LevelItem":switch(entry.variant()) {
                case ITEM->"Item";case LEVEL_ITEM->"LevelItem";case SEED_ITEM->"SeedItem";case UPGRADE_ITEM->"UpgradingItem";case FURNITURE_ITEM->"FurniItem";
            };
            check(actual.getClass().getSimpleName().equals(expected),"native reward factory: "+reward+" expected "+expected+" got "+actual.getClass().getName());
        }
        System.out.println("PASS: "+rewards.size()+" distinct prize item factories and 244 furniture bindings");
        check(count==pinned.size(),"complete indexed box classification");
        System.out.println("PASS: "+count+" native box key bindings and Silver/Slime/Shadow ticket identities");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static void append(ByteArrayOutputStream out,byte[] frame){out.write(frame,4,frame.length-4);}
}
