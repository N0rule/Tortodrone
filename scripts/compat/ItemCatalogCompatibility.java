import java.nio.file.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.projectx.item.config.*;
import org.tortodrone.character.*;

/** Exhaustive stock metadata and equipment-model check, using unchanged installed classes. */
public final class ItemCatalogCompatibility {
    public static void main(String[] args)throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        var resource=Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga",String.class).invoke(resource,args[0]+"/rsrc");
        var manager=(ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"),com.threerings.util.R.class,String.class).newInstance(resource,null,"config/");manager.init();
        var catalog=ItemCatalog.load(Path.of(args[1]));int checked=0;
        for(var entry:catalog.entries()) {
            var config=manager.d(ItemConfig.class,entry.config());var original=config.w(manager);
            if(config.getConfigId()!=entry.stockId()||!original.getClass().getSimpleName().equals(entry.type())
                ||original.rarity!=entry.rarity()||original.stackable!=entry.stackable())throw new AssertionError("Metadata mismatch: "+entry.config());
            if(!entry.grantable())continue;
            for(int color:List.of(256,263,511)) {
                var expected=new ArgumentMap();original.a(color,expected);
                var actual=entry.arguments(color);
                var stock=ItemCatalogExport.convert(expected,expected);
                if(original instanceof ItemConfig.Weapon w){var copy=new TreeMap<>(stock);copy.put("Weapon Idle",w.UU());stock=Map.copyOf(copy);}
                if(!actual.equals(stock))throw new AssertionError("Equipment model mismatch: "+entry.config()+" color="+color+"\nactual="+actual+"\nstock="+stock);
            }
            checked++;
        }
        for(var e:ItemCatalog.starter().entries()) {
            var exported=catalog.require(e.config());
            if(!e.arguments(263).equals(exported.arguments(263)))throw new AssertionError("Starter fallback differs: "+e.config());
        }
        wireRace(catalog,manager);
        System.out.println("PASS: "+catalog.entries().size()+" stock item metadata records and "+checked+" equipment models across three colors, including starter fallback");
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
        var after=new WorldProfile(10,1,1,0,0,3,next);var codec=new org.tortodrone.protocol.SessionCodec();
        var bytes=new java.io.ByteArrayOutputStream();
        for(byte[] frame:List.of(org.tortodrone.protocol.HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"),
            org.tortodrone.protocol.HandshakeCodec.authenticationResponse((short)2,true,"synthetic","Alice"),
            codec.player((short)3,1001,1,"Alice"),codec.selected(1001,knight,before),codec.itemChanges(1001,knight,before,after)))bytes.write(frame,4,frame.length-4);
        var in=new com.threerings.io.v(new java.io.ByteArrayInputStream(bytes.toByteArray()));in.qe();in.qe();
        var player=(com.threerings.projectx.data.PlayerObject)((com.threerings.presents.net.ObjectResponse)in.qe()).yk();
        var selected=(com.threerings.presents.dobj.CompoundEvent)((com.threerings.presents.net.EventNotification)in.qe()).yj();
        for(var event:selected.xE())event.b(player);
        var changes=(com.threerings.presents.dobj.CompoundEvent)((com.threerings.presents.net.EventNotification)in.qe()).yj();
        int added=0,updated=0;for(var event:changes.xE()) {
            if(event instanceof com.threerings.presents.dobj.EntryAddedEvent)added++;
            if(event instanceof com.threerings.presents.dobj.EntryUpdatedEvent)updated++;
            if(!event.b(player))throw new AssertionError("Stock rejected item change");
        }
        if(added!=2||updated!=1||player.items.size()!=7||player.equipment[6]!=6||in.available()!=0)
            throw new AssertionError("Concurrent grant/equip skipped item entry events");
        if(!player.n(manager).equals(WorldCompatibility.reference(StarterCatalog.equipped(knight,after,catalog))))
            throw new AssertionError("Stock held model differs after concurrent grant/equip");
        System.out.println("PASS: stock reader applies coalesced grant/equip with two additions, starter displacement and correct held model");
    }
}
