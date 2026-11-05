package org.tortodrone.resources;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.world.*;
import org.tortodrone.support.ContentFixture;
import static org.tortodrone.resources.Yaml.*;
import static org.tortodrone.support.TestWire.check;

/** Whole-catalog equivalence, effective legacy edits, strict diagnostics and stable reference identities. */
public final class ReadableItemsTest {
    public static void main(String[] args)throws Exception {
        Path temp=Files.createTempDirectory("readable-items-");
        try {
            var source=ItemYaml.read(Path.of("content/items"),1200);var items=source.items();
            check(items.entries().size()==9355&&source.weapons().weapons().size()==386&&source.weapons().shields().size()==123,"all indexed records retained");
            check(items.resolveConfig("wrench_wand").equals("Weapon/Sword/Wrench Wand"),"friendly IDs resolve exact native paths");
            check(items.resolveConfig("sw:wrench-wand").equals(items.resolveConfig("wrench_wand")),"historic aliases still resolve");
            check(items.inspect("wrench_wand").stockId()==420056402,"original stock ID retained");
            check(items.require("wrench_wand").config().equals(items.require("Weapon/Sword/Wrench Wand").config()),"grants and saved native inventories share the same entry");
            check(items.alias(items.inspect("wrench_wand")).equals("wrench_wand"),"administrator discovery uses persistent IDs");
            var renamed=items.withDisplayNames(Map.of("Weapon/Sword/Wrench Wand","Different label"));
            check(renamed.resolveConfig("wrench_wand").equals(items.resolveConfig("wrench_wand")),"label edits never change identity");
            var classified=items.withLabels(Map.of("Weapon/Sword/Wrench Wand",new ItemCatalog.Labels("Custom category","Custom group")));
            check(classified.category(classified.inspect("wrench_wand")).equals("Custom category")&&classified.search("Custom group").size()==1,"administrator categories/subcategories are retained and searchable");
            ReadableItemExport.write(new ItemYaml.Definitions(classified,source.weapons(),source.equipment()),temp.resolve("classified"));
            var exportedLabels=ItemYaml.read(temp.resolve("classified/items"),1200).items();check(exportedLabels.category(exportedLabels.inspect("wrench_wand")).equals("Custom category"),"export preserves administrator categories");
            identities();
            // Exporting a complete immutable snapshot twice produces exactly the same files.
            ReadableItemExport.write(source,temp.resolve("a"));ReadableItemExport.write(source,temp.resolve("b"));
            equalTrees(temp.resolve("a"),temp.resolve("b"));ItemMigration.equivalent(source,ItemYaml.read(temp.resolve("a/items"),1200));
            invalidContent(temp.resolve("a"),source);
            Path old=temp.resolve("legacy");legacy(old,source);
            var effective=LegacyItemYaml.read(old.resolve("content/items"),1200);
            check(effective.weapons().find("Weapon/Sword/Wrench Wand").chargeTime()==3123,"fixture has an administrator timing edit");
            var fingerprint=ContentManager.inputs(old);Path candidate=temp.resolve("candidate");ItemMigration.migrate(old,candidate);
            var migrated=ItemYaml.read(candidate.resolve("content/items"),1200);ItemMigration.equivalent(effective,migrated);behavior(effective,migrated);
            check(ContentManager.inputs(old).equals(fingerprint),"source content is never overwritten");
            check(Files.readString(old.resolve("data/tortodrone.db")).equals("owner inventory sentinel")&&!Files.exists(candidate.resolve("data")),"migration never copies or opens player data");
            refused(()->ItemMigration.migrate(old,candidate),"fresh candidate");
            Path lockFile=old.resolve("data/server.lock");Files.writeString(lockFile,"");
            try(var channel=FileChannel.open(lockFile,StandardOpenOption.WRITE);var lock=channel.lock()) {refused(()->ItemMigration.migrate(old,temp.resolve("locked")),"running");}
            // A second migration preserves all current readable edits and IDs byte for byte.
            Path weapons=candidate.resolve("content/items/weapons.yml");var yaml=read(weapons);var wrench=map(map(yaml.get("items"),"items").get("wrench_wand"),"wrench");wrench.put("name","My Wrench");wrench.put("category","My swords");write(weapons,yaml);
            Path round=temp.resolve("roundtrip");ItemMigration.migrate(candidate,round);equalTrees(candidate.resolve("content"),round.resolve("content"));
            System.out.println("PASS: 9355 item identities, 386 weapons, 123 shields; deterministic export; legacy/customized roundtrip; native/Town equivalence; strict fields/ranges/order; immutable IDs and offline data safety");
        } finally {ContentFixture.delete(temp);}
    }
    private static void behavior(ItemYaml.Definitions old,ItemYaml.Definitions migrated) {
        for(var definitions:List.of(old,migrated)) {
            String config="Weapon/Sword/Wrench Wand";
            var controller=new KnightCombat(definitions.weapons());controller.equipment(profile(config,WorldProfile.Slot.SWORD));controller.reset(1000);
            controller.input(4,1000,1000,0,0,true);check(controller.activity()==292,"first named swing maps to native first activity");
            controller.input(4,1433,1433,0,0,true);check(controller.activity()==293,"second swing rearms at 433 ms");
            controller.advance(1533,(float)Math.PI/2);var impulse=controller.state().impulse();
            check(impulse.duration()==150&&Math.abs(impulse.y()-1.25f)<.00001f&&Math.abs(impulse.fraction(1608)-.75f)<.00001f,"second swing preserves rotated impulse and native eased integration");
            controller.input(4,1866,1866,0,0,true);check(controller.activity()==294,"third named swing maps to native third activity");
            controller.advance(1966,0);check(controller.state().impulse().x()==2,"third swing preserves movement");
            controller.advance(2743,0);check(controller.activity()==0,"third action clears at its unchanged deadline");
            controller.reset(5000);controller.input(4,5000,5000,0,0,true);controller.input(4,5000,5433,0,0,true);
            check(controller.activity()==KnightCombat.CHARGE,"held input enters charge at rearm");
            controller.input(0,5000,5433+3123,0,0,true);check(controller.activity()==KnightCombat.CHARGED,"customized full charge duration takes effect");
            controller.reset(10000);controller.input(4,10000,10000,0,0,true);controller.input(4,10000,10433,0,0,true);controller.input(0,10000,10434,0,0,true);
            check(controller.activity()==293,"early sword release uses next normal swing, never the incomplete bomb-style action");
            controller.equipment(profile("Weapon/Bomb/Ash of Agni",WorldProfile.Slot.BOMB));controller.reset(20000);controller.input(4,20000,20000,0,0,true);controller.input(0,20000,20001,0,0,true);
            check(controller.activity()==KnightCombat.INCOMPLETE,"early charge-only bomb release retains incomplete action");
        }
    }
    private static WorldProfile profile(String config,WorldProfile.Slot slot) {
        return new WorldProfile(10,1,1,0,0,1,List.of(new WorldProfile.Item(1,slot,config,null,0,6,true)));
    }
    private static void identities()throws Exception {
        var a=new ItemCatalog.Entry("Unusual/First/Shared Name",12,"Craft","",-1,true,WorldProfile.Slot.OTHER,false,Map.of());
        var b=new ItemCatalog.Entry("A wholly different native identity/Shared Name",12,"Craft","",-1,true,WorldProfile.Slot.OTHER,false,Map.of());
        var unicode=new ItemCatalog.Entry("世界/工具",-9,"Craft","",-1,true,WorldProfile.Slot.OTHER,false,Map.of());
        var catalog=new ItemCatalog(List.of(a,b,unicode));var ids=ItemIds.assign(catalog);var bound=catalog.withReferenceIds(ids);
        check(new HashSet<>(ids.values()).size()==3&&ids.equals(ItemIds.assign(new ItemCatalog(List.of(unicode,b,a)))),"collision/Unicode IDs are deterministic independent of order");
        check(!ids.get(a.config()).equals(ids.get(b.config()))&&bound.inspect(ids.get(a.config())).equals(a),"same names and stock IDs retain distinct paths");
        refused(()->bound.inspect("Shared Name"),"Ambiguous");refused(()->bound.resolveConfig("Shared Name"),"ambiguous");
        refused(()->catalog.withReferenceIds(Map.of(a.config(),"duplicate",b.config(),"duplicate")),"Duplicate");
        var extended=new ArrayList<>(bound.entries());extended.add(new ItemCatalog.Entry("New branch/Other",9,"Craft","",-1,true,WorldProfile.Slot.OTHER,false,Map.of()));
        var extendedIds=ItemIds.assign(new ItemCatalog(extended).withReferenceIds(ids));check(extendedIds.get(a.config()).equals(ids.get(a.config())),"persisted IDs survive catalog extension");
        check(bound.withDisplayNames(Map.of(a.config(),"Renamed",b.config(),"Renamed")).resolveConfig(ids.get(a.config())).equals(a.config()),"duplicate display labels do not alter IDs");
    }
    private static void invalidContent(Path content,ItemYaml.Definitions baseline)throws Exception {
        Path file=content.resolve("items/weapons.yml");String original=Files.readString(file);var yaml=read(file);var rows=map(yaml.get("items"),"items");var wrench=map(rows.get("wrench_wand"),"wrench");var combat=map(wrench.get("combat"),"combat");var combo=map(combat.get("combo"),"combo");var attacks=map(combo.get("attacks"),"attacks");
        var second=map(attacks.get("second_swing"),"second");var timing=map(second.get("timing"),"timing");timing.put("nextAttackAfterMs",-1);write(file,yaml);
        refused(()->ItemYaml.read(content.resolve("items"),1200),"wrench_wand → combat → combo → attacks → second_swing → timing → nextAttackAfterMs");
        timing.put("nextAttackAfterMs",433);timing.put("typo",12);write(file,yaml);refused(()->ItemYaml.read(content.resolve("items"),1200),"typo");timing.remove("typo");
        second.put("step",1);write(file,yaml);refused(()->ItemYaml.read(content.resolve("items"),1200),"duplicate position");second.put("step",2);
        var reordered=new LinkedHashMap<String,Object>();reordered.put("third_swing",attacks.get("third_swing"));reordered.put("first_swing",attacks.get("first_swing"));reordered.put("second_swing",second);combo.put("attacks",reordered);write(file,yaml);
        ItemMigration.equivalent(baseline,ItemYaml.read(content.resolve("items"),1200));
        wrench.put("stockId",1);write(file,yaml);refused(()->ItemYaml.read(content.resolve("items"),1200),"stockId");wrench.remove("stockId");write(file,yaml);
        Path misc=content.resolve("items/misc.yml");var other=read(misc);map(other.get("items"),"items").put("wrench_wand",wrench);write(misc,other);refused(()->ItemYaml.read(content.resolve("items"),1200),"duplicate item ID");
        map(other.get("items"),"items").remove("wrench_wand");write(misc,other);
        rows.put("made_up_item",Map.of("name","Unknown","category","Sword"));write(file,yaml);refused(()->ItemYaml.read(content.resolve("items"),1200),"unknown item ID");Files.writeString(file,original);
        Path gear=content.resolve("items/gear.yml");var gearYaml=read(gear);var proto=map(map(gearYaml.get("items"),"items").get("proto_shield"),"shield");map(proto.get("shield"),"shield").put("defendingSpeedAdjustment",-.25);write(gear,gearYaml);
        var adjusted=ItemYaml.read(content.resolve("items"),1200);var shield=adjusted.items().inspect("proto_shield");
        var controller=new KnightCombat(adjusted.weapons(),adjusted.equipment());controller.equipment(new WorldProfile(10,1,1,0,0,1,List.of(new WorldProfile.Item(1,WorldProfile.Slot.SHIELD,shield.config(),null,0,2,true))));controller.reset(1000);controller.input(64,1000,1000,0,0,true);
        check(controller.movementScale()==.75f,"shield movement edit drives actual equipped defending speed with the full equipment catalog");
        proto.put("modifiers",Map.of("SWORD",Map.of("defendingSpeed",0)));write(gear,gearYaml);adjusted=ItemYaml.read(content.resolve("items"),1200);check(adjusted.equipment().find(shield.config()).forClass(EquipmentCatalog.WeaponClass.SWORD).defendingSpeed()==0,"explicit zero class override is retained");
        ReadableItemExport.write(adjusted,content.resolve("override-export"));ItemMigration.equivalent(adjusted,ItemYaml.read(content.resolve("override-export/items"),1200));
        var town=new LinkedHashMap<String,Object>();town.put("catalogs",new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("config","shop","goods",List.of(new LinkedHashMap<>(Map.of("item","missing_item"))))))));
        refused(()->ItemReferences.town("catalogs",list(town.get("catalogs"),"catalogs"),baseline.items(),false,"shops.yml"),"missing item");
    }
    /** Independent old-schema encoding of all supported fields, plus real customized sparse overrides. */
    private static void legacy(Path deployment,ItemYaml.Definitions source)throws Exception {
        ContentFixture.copy(Path.of("content"),deployment.resolve("content"));ContentFixture.copy(Path.of("config"),deployment.resolve("config"));
        Path content=deployment.resolve("content"),internal=content.resolve("_internal");
        var townItems=source.items();for(String name:TownYaml.SECTIONS.keySet()) {Path file=content.resolve("town/"+name+".yml");var root=read(file);for(var e:root.entrySet())ItemReferences.town(e.getKey(),list(e.getValue(),"rows"),townItems,false,file.toString());write(file,root);}
        Path shared=content.resolve("shared/combat.yml");var root=read(shared);var props=map(root.get("properties"),"properties");props.put("gear",ItemReferences.gear(map(props.get("gear"),"gear"),townItems,false,"gear"));write(shared,root);
        Files.delete(internal.resolve("item-bindings.json"));ItemCatalog.write(deployment.resolve("native-items.json"),source.items().entries());var raw=ItemYaml.json(deployment.resolve("native-items.json"));
        var definitions=new TreeMap<String,Object>();var arguments=new TreeMap<String,Object>();var names=new TreeMap<String,Object>();
        for(Object o:list(raw.get("items"),"items")) {var row=new LinkedHashMap<>(map(o,"item"));String config=text(row.remove("config"),"config");arguments.put(config,row.remove("modelArguments"));definitions.put(config,row);names.put(config,source.items().displayName(source.items().find(config)));}
        for(var w:source.weapons().weapons())map(definitions.get(w.config()),"item").put("combat",weapon(w));
        for(var s:source.weapons().shields()) {var row=new LinkedHashMap<String,Object>();row.put("health",s.health());row.put("regenTimeMs",s.regenTime());row.put("regenDelayMs",s.regenDelay());row.put("defendingSpeedModifier",s.defendingSpeed());row.put("bashHealthFraction",s.bashHealthPct());row.put("breakDelayMs",s.breakDelay());if(s.bash()!=null)row.put("bash",attack(s.bash()));map(definitions.get(s.config()),"item").put("shield",row);}
        for(var e:source.equipment().entries()) {var mods=new TreeMap<String,Object>();for(var c:e.byWeaponClass().entrySet()){var v=c.getValue();mods.put(c.getKey().name(),Map.of("chargeTime",v.chargeTime(),"speed",v.speed(),"attackSpeed",v.attackSpeed(),"defendingSpeed",v.defendingSpeed()));}map(definitions.get(e.config()),"item").put("modifiers",mods);}
        for(String family:ItemYaml.CATEGORIES)write(content.resolve("items/"+family+".yml"),Map.of("items",Map.of()));
        var custom=weapon(source.weapons().find("Weapon/Sword/Wrench Wand"));custom.put("chargeTimeMs",3123);write(content.resolve("items/weapons.yml"),Map.of("items",Map.of("Weapon/Sword/Wrench Wand",Map.of("combat",custom,"rarity",4))));
        Files.writeString(internal.resolve("item-definitions.json"),Json.stringify(definitions));Files.writeString(internal.resolve("item-arguments.json"),Json.stringify(arguments));Files.writeString(internal.resolve("item-names.json"),Json.stringify(names));
        Files.createDirectories(deployment.resolve("data"));Files.writeString(deployment.resolve("data/tortodrone.db"),"owner inventory sentinel");
    }
    private static Map<String,Object> weapon(WeaponCatalog.Weapon w) {
        var m=new LinkedHashMap<String,Object>();m.put("chargeTimeMs",w.chargeTime());m.put("maxChain",w.maxChain());m.put("chainCooldownMs",w.chainCooldown());m.put("cycle",w.cycle());m.put("blended",w.blended());m.put("attackMovementSpeed",w.attackingSpeed());m.put("chargeMovementSpeed",w.chargingSpeed());m.put("attacks",w.attacks().stream().map(ReadableItemsTest::attack).toList());if(w.incomplete()!=null)m.put("incomplete",attack(w.incomplete()));if(w.charged()!=null)m.put("charged",attack(w.charged()));if(w.cooldown()!=null)m.put("cooldown",attack(w.cooldown()));return m;
    }
    private static Map<String,Object> attack(WeaponCatalog.Attack a) {return Map.of("landMs",a.land(),"rearmMs",a.rearm(),"clearMs",a.clear(),"blended",a.blended(),"impulseDelayMs",a.impulseDelay(),"impulseDurationMs",a.impulseDuration(),"impulseX",a.impulseX(),"impulseY",a.impulseY());}
    private static void equalTrees(Path a,Path b)throws IOException {try(var files=Files.walk(a)){for(Path f:files.filter(Files::isRegularFile).toList())check(Files.mismatch(f,b.resolve(a.relativize(f)))==-1,"deterministic bytes: "+a.relativize(f));}}
    private static void write(Path p,Map<String,Object> m)throws IOException {Files.deleteIfExists(p);Yaml.write(p,m);}
    private interface Work{void run()throws Exception;}
    private static void refused(Work work,String message)throws Exception {try{work.run();throw new AssertionError("Expected refusal: "+message);}catch(IOException expected){check(expected.getMessage().contains(message),"actionable diagnostic: "+expected);}}
}
