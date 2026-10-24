package org.tortodrone.web;

import java.io.IOException;
import java.util.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.character.*;
import org.tortodrone.server.ServerControl;
import org.tortodrone.town.*;

/** Read-only presentation facts. Mutable state continues to belong to domain services. */
final class AdminViews {
    public record Item(String config,String name,String category,String subcategory,String type,int rarity,
            boolean grantable,String reason,boolean stackable,WorldProfile.Slot slot,boolean permanent,
            boolean locked,int levels,boolean progression,List<String> variants,Map<String,String> properties,Map<String,Float> stats,Map<String,Object> variantDisplay,List<Integer> compatibleSlots) { }
    public record ItemPage(List<Item> entries,int total,int number,int size,List<String> categories) { }
    public record KnightRow(String username,int accountId,boolean gameMaster,int id,String name,boolean online) { }
    public record SpriteView(TownState.Sprite state,boolean selected,Boolean active,boolean online,
            int displayedLevel,int tier,int iconTier,int skillPoints,List<Boolean> unlocked,List<Boolean> ultimate,
            TownCatalog.Sprite definition,List<TownCatalog.Perk> perkCatalogue,TownState.Harness harness,LiveSprite live,Object display,Map<String,String> perkNames,int spentPoints,int availablePoints) { }
    public record LiveSprite(boolean following,int shieldHealth,int shieldMaximum,List<Double> cooldownSeconds) { }
    public record KnightView(String name,String username,boolean gameMaster,String guild,boolean online,
            WorldProfile profile,List<Item> definitions,List<SpriteView> sprites,List<Integer> unlockedSlots,boolean equipmentEditable) { }
    public record Accessory(String config,String name,String location,Set<String> blocks,String iconConfig) { }
    private final AccountStore accounts;
    private final ServerControl control;
    private final Map<String,Float> stats;
    private final Map<String,Integer> shieldHealth;
    private final org.tortodrone.resources.PresentationCatalog presentation;
    AdminViews(AccountStore accounts,ServerControl control,org.tortodrone.resources.ContentRegistry content){
        this.accounts=accounts;this.control=control;presentation=content==null?org.tortodrone.resources.PresentationCatalog.empty():content.presentation();
        shieldHealth=content==null?Map.of():content.weapons().shields().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(org.tortodrone.world.WeaponCatalog.Shield::config,org.tortodrone.world.WeaponCatalog.Shield::health));
        stats=content==null||content.scenes().scenes().isEmpty()?Map.of():content.scenes().scenes().getFirst().content().stats();
    }

    Item item(String config)throws IOException{return item(accounts.catalog().inspect(config));}
    List<Item> definitions(String configs) {
        var names=configs.lines().distinct().toList();
        if(names.size()>100)throw new IllegalArgumentException("At most 100 definitions per request");
        return names.stream().map(accounts.catalog()::find).filter(Objects::nonNull).map(this::item).toList();
    }
    private Item item(ItemCatalog.Entry e) {
        var catalog=accounts.catalog();var rules=accounts.townRules();var gear=rules.gear().gear(e.config());
        return new Item(e.config(),catalog.displayName(e),catalog.category(e),catalog.subcategory(e),e.type(),e.rarity(),
            e.grantable(),e.unavailableReason(),e.stackable(),e.slot(),e.permanent(),e.locked(),
            gear==null?1:Math.max(1,gear.levels()),gear!=null,rules.availableVariants(TownCatalog.itemGroup(e)),rules.item(e.config()),stats(e,ItemModifiers.NONE),variantDisplay(rules.availableVariants(TownCatalog.itemGroup(e))),java.util.stream.IntStream.range(0,16).filter(i->e.slot()!=null&&e.slot().accepts(i)).boxed().toList());
    }
    ItemPage items(AdminService.Input p) {
        int number=p.optional("page").isEmpty()?1:p.integer("page"),size=p.optional("size").isEmpty()?48:p.integer("size");
        if(size>100||number<1||number>100000)throw new IllegalArgumentException("Invalid catalogue page");
        if(!p.optional("slot").isEmpty()) {
            int slot=p.integer("slot");if(slot<0||slot>15)throw new IllegalArgumentException("Invalid equipment slot");
            var catalog=accounts.catalog();String search=p.optional("search").toLowerCase(Locale.ROOT),category=p.optional("category");
            var entries=catalog.entries().stream().filter(e->e.slot()!=null&&e.slot().accepts(slot))
                .filter(e->category.isEmpty()||catalog.category(e).equals(category)).filter(e->(catalog.displayName(e)+" "+e.config()).toLowerCase(Locale.ROOT).contains(search)).sorted(Comparator.comparing(catalog::displayName)).toList();
            return new ItemPage(entries.stream().skip((long)(number-1)*size).limit(size).map(this::item).toList(),entries.size(),number,size,catalog.entries().stream().map(catalog::category).distinct().sorted().toList());
        }
        var result=accounts.catalog().page(p.optional("search"),p.optional("category").isEmpty()?null:p.optional("category"),number,size);
        return new ItemPage(result.entries().stream().map(this::item).toList(),result.total(),number,size,
            accounts.catalog().entries().stream().map(accounts.catalog()::category).distinct().sorted().toList());
    }
    List<KnightRow> knights()throws IOException {
        var online=control.sessions().snapshots();var result=new ArrayList<KnightRow>();
        for(var a:accounts.list())for(var k:accounts.knights(a.username()))result.add(new KnightRow(a.username(),a.id(),a.gameMaster(),k.id(),k.name(),
            online.stream().anyMatch(s->Objects.equals(s.accountId(),a.id())&&Objects.equals(s.rosterId(),k.id()))));
        return List.copyOf(result);
    }
    Object state(String username,String name)throws IOException {
        var state=accounts.knightState(username,name);
        var session=control.sessions().snapshots().stream().filter(s->Objects.equals(s.accountId(),state.accountId())&&Objects.equals(s.knightId(),state.worldId())).findFirst().orElse(null);
        var companion=session==null?null:control.companion(session.id(),state.accountId(),state.worldId());
        var pet=companion==null?null:companion.deployed();
        var result=new LinkedHashMap<String,Object>();result.put("state",state);result.put("online",session!=null);result.put("equipmentEditable",!accounts.gameAttached(state.accountId()));
        result.put("deployed",pet==null?null:pet.sprite().id());result.put("available",session==null||companion!=null);
        result.put("live",pet==null?null:new LiveSprite(pet.follow(),pet.shieldHealth(),pet.shieldMaximum(),pet.nextSkill().stream().map(t->Math.max(0d,(t-companion.observedAt())/1000d)).toList()));
        return result;
    }
    KnightView knight(String username,String name)throws IOException {
        var p=accounts.worldProfile(username,name);boolean gm=accounts.isGameMaster(p.accountId());
        var session=control.sessions().snapshots().stream().filter(s->Objects.equals(s.accountId(),p.accountId())&&Objects.equals(s.knightId(),p.worldId())).findFirst().orElse(null);
        boolean online=session!=null;
        var companion=session==null?null:control.companion(session.id(),p.accountId(),p.worldId());
        var definitions=p.items().stream().map(i->accounts.catalog().find(i.config())).filter(Objects::nonNull).distinct().map(this::item).toList();
        var rules=accounts.townRules();
        var sprites=p.town().sprites().stream().map(s->{
            boolean selected=p.town().equippedSprite()==s.id();
            // SessionCodec and the scene simulation resolve the implicit base harness this way.
            var harness=p.town().harnesses().stream().filter(h->h.id()==s.harness()).findFirst().orElse(new TownState.Harness(0,"Iron Harness"));
            // Native BattleSprite.ad selects icon1 below 14, icon2 below 49, then icon3.
            Boolean active=!online?false:companion==null?null:companion.deployed()!=null&&companion.deployed().sprite().id()==s.id();
            var pet=Boolean.TRUE.equals(active)?companion.deployed():null;
            var live=pet==null?null:new LiveSprite(pet.follow(),pet.shieldHealth(),pet.shieldMaximum(),pet.nextSkill().stream().map(t->Math.max(0d,(t-companion.observedAt())/1000d)).toList());
            return new SpriteView(s,selected,active,online,s.level()+1,SpriteRules.tier(s.level()),s.level()<14?1:s.level()<49?2:3,SpriteRules.points(s.level()),
                java.util.stream.IntStream.range(0,3).mapToObj(i->SpriteRules.unlocked(s.level(),i)).toList(),
                java.util.stream.IntStream.range(0,3).mapToObj(i->SpriteRules.ultimate(s.level(),i)).toList(),rules.sprite(s.config()),rules.perks(),harness,live,presentation.sprites().getOrDefault(s.config(),Map.of()),rules.perks().stream().collect(java.util.stream.Collectors.toMap(TownCatalog.Perk::name,perk->presentation.perk(perk.name()))),s.powers().stream().mapToInt(Integer::intValue).sum(),Math.max(0,SpriteRules.points(s.level())-s.powers().stream().mapToInt(Integer::intValue).sum()));
        }).toList();
        return new KnightView(name,username,gm,"No guild",online,p,definitions,sprites,java.util.stream.IntStream.range(0,16).filter(i->SlotUpgrades.unlocked(p,i)).boxed().toList(),!accounts.gameAttached(p.accountId()));
    }
    List<Accessory> accessories(String config) throws IOException {
        var item=accounts.catalog().inspect(config);boolean helm=item.slot()==WorldProfile.Slot.HELM||item.slot()==WorldProfile.Slot.HELM_COSTUME;
        if(!helm&&item.slot()!=WorldProfile.Slot.ARMOR&&item.slot()!=WorldProfile.Slot.ARMOR_COSTUME)return List.of();
        var rules=accounts.townRules();var result=new TreeMap<String,Accessory>();
        for(var e:accounts.catalog().entries()) {
            String ref=rules.item(e.config()).get("accessory");var a=ref==null?null:rules.accessory(ref);
            if(a==null||a.remove()||rules.accessoryModel(ref)==null||a.location().startsWith(helm?"ARMOR_":"HELM_")
                    ||a.blocks().stream().anyMatch(rules.blockers(config)::contains))continue;
            result.putIfAbsent(ref,new Accessory(ref,accounts.catalog().displayName(e),a.location(),a.blocks(),e.config()));
        }
        return List.copyOf(result.values());
    }
    Object validateModifiers(AdminService.Input p)throws IOException {
        var e=accounts.catalog().inspect(p.text("config"));
        var variants=p.optional("variants").isBlank()?List.<String>of():Arrays.stream(p.text("variants").split("\n")).toList();
        var modifiers=new ItemModifiers(p.integer("level"),Float.parseFloat(p.text("heat")),variants);
        modifiers.validate(e,accounts.townRules());
        var compatible=new ArrayList<String>();
        for(String candidate:accounts.townRules().availableVariants(TownCatalog.itemGroup(e))) {
            if(variants.contains(candidate)){compatible.add(candidate);continue;}
            var proposed=new ArrayList<>(variants);proposed.add(candidate);
            try{accounts.townRules().validateVariants(TownCatalog.itemGroup(e),proposed);compatible.add(candidate);}
            catch(IOException invalid){/* Candidate conflicts with the authoritative family/count rules. */}
        }
        return Map.of("valid",true,"displayedLevel",modifiers.level()+1,"heat",modifiers.heat(),"stats",stats(e,modifiers),"compatibleVariants",compatible,"effects",effects(e,modifiers),"properties",properties(e,modifiers));
    }
    private Map<String,Float> stats(ItemCatalog.Entry e,ItemModifiers modifiers) {
        String prefix="gear."+e.config()+".";
        Float table=stats.get(prefix+"table");
        String level=(table==null?prefix:"gear.table."+Math.round(table)+".")+"levels."+modifiers.level()+".";
        var result=new TreeMap<String,Float>();
        var item=new WorldProfile.Item(0,e.slot()==null?WorldProfile.Slot.OTHER:e.slot(),e.config(),null,0,null,false,false,false,1,false,modifiers.level(),modifiers.heat(),
            modifiers.variants().stream().map(v->new StarterCatalog.Model(v,Map.of())).toList());
        for(String key:List.of("health-bonus","shield-health","defense.normal","defense.piercing","defense.elemental","defense.shadow")) {
            Float value=stats.get(level+key);
            if(value!=null){float calculated=key.startsWith("defense.")?org.tortodrone.world.GearStats.defense(stats,item,key.substring(8),accounts.townRules()):value;
                if(key.equals("health-bonus"))calculated+=accounts.townRules().weaponBonuses(item,"").health();
                if(calculated!=0)result.put(key,calculated);
            }
        }
        if(e.slot()==WorldProfile.Slot.SHIELD&&shieldHealth.getOrDefault(e.config(),0)>0)result.put("shield-health",shieldHealth.get(e.config()).floatValue());
        var nativeStats=presentation.level(e.config(),modifiers.level()).get("stats");
        if(nativeStats instanceof Map<?,?> values)values.forEach((key,value)->{if(value instanceof Number n&&n.floatValue()!=0)result.put(key.toString(),n.floatValue());});
        Float attack=result.remove("attack-modifier");
        if(result.containsKey("attack-speed")) {
            String weapon=switch(e.slot()){case GUN->"HANDGUN";case SWORD->"SWORD";case BOMB->"BOMB";case null,default->"NONE";};
            float bonus=(attack==null?0:attack)+accounts.townRules().weaponBonuses(item,weapon).attack();
            // Native ItemValueKey.SPEED.capped: 0.04 per rank, six ranks in either direction.
            result.put("attack-speed",result.get("attack-speed")*(1+Math.clamp(bonus,-0.24f,0.24f)));
        }
        return Map.copyOf(result);
    }
    public record Property(String label,float value,Float meter,String note) { }
    private List<Property> properties(ItemCatalog.Entry e,ItemModifiers modifiers) {
        var out=new ArrayList<Property>();
        for(var entry:new TreeMap<>(stats(e,modifiers)).entrySet()) {
            String key=entry.getKey(),label;Float meter=null;String note="";float value=entry.getValue();
            if(key.startsWith("damage.")||key.startsWith("defense.")) {
                String type=switch(key.substring(key.indexOf('.')+1)){case "normal"->"Normal";case "piercing"->"Piercing";case "elemental"->"Elemental";case "shadow"->"Shadow";case "unblockable"->"Unblockable";default->"Other";};
                label=type+(key.startsWith("damage.")?" damage":" defense");
                if(key.startsWith("damage.")){meter=Math.clamp((value*0.41666666f+14)/164,0,1);note="Native tooltip rating at depth 30; actual hit damage depends on the target.";}
            } else if(key.equals("attack-speed")){label="Attack speed";meter=Math.clamp((value*15+14)/164,0,1);note="Native tooltip speed rating, including item variants.";}
            else if(key.equals("health-bonus")){label="Health bonus";}
            else if(key.equals("shield-health")){label="Shield health";}
            else continue;
            out.add(new Property(label,value,meter,note));
        }
        return List.copyOf(out);
    }
    private Map<String,Object> variantDisplay(List<String> variants){var result=new TreeMap<String,Object>();for(String v:variants)result.put(v,presentation.variant(v));return Map.copyOf(result);}
    private List<Object> effects(ItemCatalog.Entry e,ItemModifiers modifiers){
        var out=new ArrayList<Object>();
        for(String v:modifiers.variants()){var effect=new TreeMap<String,Object>(presentation.variant(v));effect.put("source","Unique Variant");effect.put("config",v);out.add(effect);}
        var base=presentation.level(e.config(),modifiers.level()).get("effects");
        if(base instanceof List<?> values)for(Object label:values)out.add(Map.of("label",label,"source","Equipment bonus"));
        return List.copyOf(out);
    }
}
