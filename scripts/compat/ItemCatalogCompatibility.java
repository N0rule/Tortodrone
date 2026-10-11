import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.projectx.item.config.*;
import org.tortodrone.character.*;

/** Stock metadata and equipment-model check, using unchanged installed classes. */
public final class ItemCatalogCompatibility {
    public static void main(String[] args)throws Exception {
        if(args.length<2||args.length>3||args.length==3&&!args[2].equals("--sample"))
            throw new IllegalArgumentException("Usage: ItemCatalogCompatibility game-directory catalog.json [--sample]");
        boolean sample=args.length==3;
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,args[0]+"/rsrc");
        var manager=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");manager.init();
        var catalog=ItemCatalog.load(Path.of(args[1]));int checked=0;
        var selected=sample?sampleEntries(catalog):catalog.entries().stream().filter(ItemCatalog.Entry::grantable).toList();
        var selectedConfigs=selected.stream().map(e->e.config().toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        for(var entry:catalog.entries()) {
            var config=manager.d(ItemConfig.class,entry.config());var original=config.w(manager);
            if(config.getConfigId()!=entry.stockId()||!original.getClass().getSimpleName().equals(entry.type())
                ||original.rarity!=entry.rarity()||original.stackable!=entry.stackable())throw new AssertionError("Metadata mismatch: "+entry.config());
            if(!entry.grantable()||sample&&!selectedConfigs.contains(entry.config().toLowerCase(Locale.ROOT)))continue;
            boolean stockPermanent=original instanceof ItemConfig.Level l&&l.isPermanent||original instanceof ItemConfig.Artifact;
            if(entry.permanent()!=stockPermanent)throw new AssertionError("Permanent flag mismatch");
            if(entry.slot()==WorldProfile.Slot.OTHER)continue;
            for(int color:List.of(256,263,511)) {
                var expected=new ArgumentMap();original.a(color,expected);
                var actual=entry.arguments(color);
                var stock=ItemCatalogExport.convert(expected,expected);
                if(original instanceof ItemConfig.Weapon w){var copy=new TreeMap<>(stock);copy.put("Weapon Idle",w.UU());stock=Map.copyOf(copy);}
                if(!actual.equals(stock))throw new AssertionError("Equipment model mismatch: "+entry.config()+" color="+color+"\nactual="+actual+"\nstock="+stock);
            }
            checked++;
        }
        var starters=ItemCatalog.starter().entries();
        for(var e:starters) {
            var exported=catalog.require(e.config());
            if(!e.arguments(263).equals(exported.arguments(263)))throw new AssertionError("Starter fallback differs: "+e.config());
        }
        wireRace(catalog,manager);
        wirePlayerItems(catalog,manager,selected);
        System.out.println("PASS: "+catalog.entries().size()+" stock item metadata records and "+checked+
            (sample?" representative":" exhaustive")+" grantable item model arguments across three colors, including "+starters.size()+" starter fallbacks");
    }
    static List<ItemCatalog.Entry> sampleEntries(ItemCatalog catalog)throws IOException {
        var all=catalog.entries().stream().filter(ItemCatalog.Entry::grantable).toList();
        var selected=new LinkedHashMap<String,ItemCatalog.Entry>();
        for(var entry:all)selected.putIfAbsent(entry.type()+"/"+entry.variant(),entry);
        for(var starter:ItemCatalog.starter().entries()) {
            var entry=catalog.find(starter.config());
            if(entry==null||!entry.grantable())throw new IOException("--sample requires grantable stock starter: "+starter.config());
            selected.put("starter/"+entry.config(),entry);
        }
        var formerlyExcluded=all.stream().filter(e->isWeapon(e.type())&&wasPathExcluded(e.config())).findFirst()
            .orElseThrow(()->new IOException("--sample requires a grantable formerly path-excluded weapon"));
        selected.put("formerly-excluded-weapon/"+formerlyExcluded.variant(),formerlyExcluded);
        return List.copyOf(selected.values());
    }
    private static boolean isWeapon(String type) {
        return Set.of("Sword","Handgun","SwingingHandgun","Bomb").contains(type);
    }
    private static boolean wasPathExcluded(String config) {
        String path=config.toLowerCase(Locale.ROOT);
        return path.contains("/dev/")||path.contains("(dev)")||path.contains("/test")
            ||path.contains("/pvp/")||path.contains("ideal sword");
    }
    static void wirePlayerItems(ItemCatalog catalog,ConfigManager manager,List<ItemCatalog.Entry> selected)throws Exception {
        // Selected references are read back with exact stock class, count, flags, seed and definition.
        var ctx=(com.threerings.projectx.item.b.a)java.lang.reflect.Proxy.newProxyInstance(ItemCatalogCompatibility.class.getClassLoader(),
            new Class[]{com.threerings.projectx.item.b.a.class},(p,m,a)->{if(m.getName().equals("getConfigManager"))return manager;throw new UnsupportedOperationException(m.getName());});
        var knight=new Knight(1,"Items-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,1);
        var all=selected;
        var inventory=catalog.entries().stream().filter(ItemCatalog.Entry::grantable).toList();
        for(int offset=0;offset<all.size();offset+=200) {
            var batch=all.subList(offset,Math.min(offset+200,all.size()));var items=new ArrayList<WorldProfile.Item>();
            for(int i=0;i<batch.size();i++){var e=batch.get(i);items.add(new WorldProfile.Item(i+1,e.slot(),e.config(),null,0,null,e.locked()||e.permanent(),e.bindOnEquip(),false,e.stackable()?17:1,e.permanent()));}
            var profile=new WorldProfile(10,1,1,0,0,1,items);var player=readSelected(knight,profile,catalog);
            for(int i=0;i<batch.size();i++) {
                var e=batch.get(i);var decoded=player.items.f(Long.valueOf(i+1));var original=manager.d(ItemConfig.class,e.config()).w(manager);
                var nativeItem=original.a(ctx,e.stockId(),new ConfigReference<ItemConfig>(e.config()));
                if(decoded==null||decoded.getClass()!=nativeItem.getClass()||decoded.getCount()!=(e.stackable()?17:1)
                    ||decoded.VC()!=e.permanent()||decoded.Cs()!=(e.locked()||e.permanent())||!decoded.Vx().getName().equals(e.config())
                    ||decoded.w(manager)!=original)throw new AssertionError("Stock item mismatch: "+e.config());
                if(e.variant()==ItemCatalog.Variant.SEED_ITEM&&(!(nativeItem instanceof com.threerings.projectx.item.data.SeedItem)
                    ||seed(nativeItem)!=e.seed()||seed(decoded)!=e.seed()))throw new AssertionError("Seed item metadata mismatch: "+e.config());
                if(e.variant()==ItemCatalog.Variant.ITEM&&nativeItem instanceof com.threerings.projectx.item.data.SeedItem)
                    throw new AssertionError("Missing seed item variant metadata: "+e.config());
                if(e.slot()!=WorldProfile.Slot.OTHER)for(int slot=0;slot<16;slot++)
                    if(e.slot().accepts(slot)&&!original.h(slot,false))
                        throw new AssertionError("Equipment slot mismatch: "+e.config()+" slot="+slot);
            }
        }
        // Compose cosmetic categories together, including a translated-color costume.
        var slots=List.of(WorldProfile.Slot.HELM,WorldProfile.Slot.ARMOR,WorldProfile.Slot.SHIELD,WorldProfile.Slot.SWORD,
            WorldProfile.Slot.HELM_COSTUME,WorldProfile.Slot.ARMOR_COSTUME,WorldProfile.Slot.SHIELD_COSTUME,WorldProfile.Slot.TRINKET);
        var equipped=new ArrayList<WorldProfile.Item>();
        for(var slot:slots) {var e=inventory.stream().filter(x->x.slot()==slot && (slot!=WorldProfile.Slot.ARMOR_COSTUME || x.config().equals("Costume/Armor/Bug/Hexoskeleton, Cool"))).findFirst().orElseThrow();
            equipped.add(new WorldProfile.Item(equipped.size()+1,slot,e.config(),null,0,slot.initialIndex(),true,e.bindOnEquip(),false,1,e.permanent()));}
        var profile=new WorldProfile(10,1,1,0,0,1,equipped);var player=readSelected(knight,profile,catalog);
        if(!player.n(manager).equals(WorldCompatibility.reference(StarterCatalog.equipped(knight,profile,catalog))))
            throw new AssertionError("Stock costume/trinket composition differs:\nstock="+player.n(manager)+"\nserver="+StarterCatalog.equipped(knight,profile,catalog));
        if(!player.a(manager,true,true,-1,true,false,Boolean.TRUE).equals(
                WorldCompatibility.reference(StarterCatalog.town(knight,profile,catalog))))
            throw new AssertionError("Peaceful costume/shield composition differs from stock town builder");
        // Read the rendered actor too: this exercises TransNormal fields on the wire.
        var codec=new org.tortodrone.protocol.SessionCodec(catalog);var model=StarterCatalog.equipped(knight,profile,catalog);
        var state=new org.tortodrone.world.ActorState(10,1001,knight.name(),257,100,0,-8,0,0,0,
            new StarterCatalog.Model("Character/PC/Default",Map.of("Model",model,"Height",model.arguments().get("Height"))));
        var bytes=new java.io.ByteArrayOutputStream();
        for(byte[] frame:List.of(org.tortodrone.protocol.HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"),
            org.tortodrone.protocol.HandshakeCodec.authenticationResponse((short)2,true,"synthetic","Alice"),
            codec.player((short)3,1001,1,"Alice"),codec.selected(1001,knight,profile),
            codec.sceneDelta(1001,0,0,101,List.of(state),List.of(),new int[0])))bytes.write(frame,4,frame.length-4);
        var in=new com.threerings.io.v(new java.io.ByteArrayInputStream(bytes.toByteArray()));in.qe();in.qe();in.qe();in.qe();
        var delta=(com.threerings.tudey.dobj.SceneDeltaEvent)((com.threerings.presents.net.EventNotification)in.qe()).yj();
        var actor=(com.threerings.projectx.data.actor.Knight)delta.aej()[0];
        if(!actor.Mv().kW().get("Model").equals(player.n(manager)))throw new AssertionError("Serialized costume actor model differs from stock builder");
        System.out.println("PASS: stock reader validates "+all.size()+" selected item class/count/binding/definition references and costume/trinket model precedence");
    }
    static com.threerings.projectx.data.PlayerObject readSelected(Knight knight,WorldProfile profile,ItemCatalog catalog)throws Exception {
        var codec=new org.tortodrone.protocol.SessionCodec(catalog);var bytes=new java.io.ByteArrayOutputStream();
        for(byte[] frame:List.of(org.tortodrone.protocol.HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"),
            org.tortodrone.protocol.HandshakeCodec.authenticationResponse((short)2,true,"synthetic","Alice"),
            codec.player((short)3,1001,1,"Alice"),codec.selected(1001,knight,profile)))bytes.write(frame,4,frame.length-4);
        var in=new com.threerings.io.v(new java.io.ByteArrayInputStream(bytes.toByteArray()));in.qe();in.qe();
        var player=(com.threerings.projectx.data.PlayerObject)((com.threerings.presents.net.ObjectResponse)in.qe()).yk();
        var selected=(com.threerings.presents.dobj.CompoundEvent)((com.threerings.presents.net.EventNotification)in.qe()).yj();
        for(var event:selected.xE())event.b(player);if(in.available()!=0)throw new AssertionError("Trailing item frame bytes");return player;
    }
    private static long seed(com.threerings.projectx.item.data.Item item)throws Exception {
        var field=com.threerings.projectx.item.data.SeedItem.class.getDeclaredField("_seed");field.setAccessible(true);return field.getLong(item);
    }
    static void wireRace(ItemCatalog catalog,ConfigManager manager)throws Exception {
        var knight=new Knight(1,"Race-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,1);
        var items=List.of(new WorldProfile.Item(1,WorldProfile.Slot.HELM,knight.helm(),null,0,0),
            new WorldProfile.Item(2,WorldProfile.Slot.ARMOR,knight.armor(),null,0,1),
            new WorldProfile.Item(3,WorldProfile.Slot.SWORD,"Weapon/Sword/Proto Sword",null,0,6),
            new WorldProfile.Item(4,WorldProfile.Slot.GUN,"Weapon/Handgun/Proto Gun",null,0,7),
            new WorldProfile.Item(5,WorldProfile.Slot.SHIELD,"Gear/Shield/Proto Shield",null,0,2));
        var before=new WorldProfile(10,1,1,0,0,1,items);var next=new ArrayList<>(items);
        next.set(2,new WorldProfile.Item(3,WorldProfile.Slot.SWORD,"Weapon/Sword/Proto Sword",null,0,null));
        next.add(new WorldProfile.Item(6,WorldProfile.Slot.SWORD,"Weapon/Sword/Calibur",null,0,6,true,true,false));
        next.add(new WorldProfile.Item(7,WorldProfile.Slot.SWORD,"Weapon/Sword/Calibur",null,0,null,false,true,false));
        var after=new WorldProfile(10,1,1,0,0,3,next);var codec=new org.tortodrone.protocol.SessionCodec(catalog);
        var bytes=new java.io.ByteArrayOutputStream();
        for(byte[] frame:List.of(org.tortodrone.protocol.HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"),
            org.tortodrone.protocol.HandshakeCodec.authenticationResponse((short)2,true,"synthetic","Alice"),
            codec.player((short)3,1001,1,"Alice"),codec.selected(1001,knight,before),codec.itemChanges(1001,knight,before,after)))bytes.write(frame,4,frame.length-4);
        var in=new com.threerings.io.v(new java.io.ByteArrayInputStream(bytes.toByteArray()));in.qe();in.qe();
        var player=(com.threerings.projectx.data.PlayerObject)((com.threerings.presents.net.ObjectResponse)in.qe()).yk();
        var selected=(com.threerings.presents.dobj.CompoundEvent)((com.threerings.presents.net.EventNotification)in.qe()).yj();
        for(var event:selected.xE())event.b(player);
        var changes=(com.threerings.presents.dobj.CompoundEvent)((com.threerings.presents.net.EventNotification)in.qe()).yj();
        int added=0,updated=0,loadouts=0,equipment=0;for(var event:changes.xE()) {
            if(event instanceof com.threerings.presents.dobj.EntryAddedEvent)added++;
            if(event instanceof com.threerings.presents.dobj.EntryUpdatedEvent update) {
                if(update.getName().equals("loadouts"))loadouts++;else updated++;
            }
            if(event instanceof com.threerings.presents.dobj.ElementUpdatedEvent update) {
                if(!update.getName().equals("equipment")||update.pN()!=6||update.xY()!=6)
                    throw new AssertionError("Wrong equipment slot/value event");
                equipment++;
            }
            if(!event.b(player))throw new AssertionError("Stock rejected item change");
        }
        if(added!=2||updated!=1||player.items.size()!=7||player.equipment[6]!=6||in.available()!=0)
            throw new AssertionError("Concurrent grant/equip skipped item entry events");
        if(loadouts!=1||equipment!=1||player.loadouts.f("").equipment[6]!=6)
            throw new AssertionError("Open loadout menu receives no current-loadout entry update");
        if(!player.n(manager).equals(WorldCompatibility.reference(StarterCatalog.equipped(knight,after,catalog))))
            throw new AssertionError("Stock held model differs after concurrent grant/equip");
        System.out.println("PASS: stock reader applies coalesced grant/equip with two additions, starter displacement and correct held model");
    }
}
