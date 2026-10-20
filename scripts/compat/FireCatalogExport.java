import java.io.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import com.threerings.config.*;
import com.threerings.projectx.dungeon.config.*;
import com.threerings.projectx.item.config.ItemConfig;
import com.threerings.math.Vector2f;
import com.threerings.tudey.config.*;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.world.FireCatalog;
import org.tortodrone.world.FireCatalog.*;

/** Exports numeric stock attack/fire-action references; never copies client assets or code. */
public final class FireCatalogExport {
    private static final Map<String,Integer> UNSUPPORTED = new TreeMap<>();
    private static final Set<String> NESTED_FIRES = new HashSet<>();
    private static final Set<ConfigReference<ActorConfig>> NESTED_ACTORS = new HashSet<>();
    private static int actionCount, projectileCount;
    private static int damageRarity;

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 4 || args.length==4&&!args[3].equals("--vortex")) throw new IllegalArgumentException("Usage: FireCatalogExport game-directory fresh-output-directory [existing-catalogue [--vortex]]");
        Path game = Path.of(args[0]).toRealPath(), out = Path.of(args[1]).toAbsolutePath().normalize();
        Path ancestor = out; while (!Files.exists(ancestor)) ancestor = ancestor.getParent();
        if (ancestor.toRealPath().startsWith(game) || Files.exists(out)) throw new IOException("Choose a fresh output directory outside the installation");
        var resource = Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga", String.class).invoke(resource, game.resolve("rsrc").toString());
        var manager = (ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"), com.threerings.util.R.class, String.class)
            .newInstance(resource, null, "config/");
        manager.init();

        var entries = new ArrayList<AttackFire>();
        var previous=args.length>=3?FireCatalog.load(Path.of(args[2])):null;
        boolean vortex=args.length==4;
        for (ItemConfig config : manager.bd(ItemConfig.class).kK()) {
            ItemConfig.Original item = config.w(manager);
            if (!playerDefinition(config.getConfigId(), item)) continue;
            damageRarity=item.rarity;
            if(previous!=null){
                if(item instanceof ItemConfig.Weapon weapon){
                    var charged=previous.find(config.getName(),290);
                    if(charged!=null&&(vortex?Set.of("Weapon/Bomb/Graviton Vortex","Weapon/Bomb/Electron Vortex","Weapon/Bomb/Celestial Vortex").contains(config.getName()):charged.fireRefs().stream().anyMatch(ref->fragmentation(ref.action()))))addAttack(manager,config.getName(),290,weapon.chargedAttack,entries);
                }
                continue;
            }
            if (item instanceof ItemConfig.Weapon weapon) addWeapon(manager, config.getName(), weapon, entries);
            else if (item instanceof ItemConfig.Shield shield) addShield(manager, config.getName(), shield, entries);
        }
        var repaired=new HashSet<String>();
        if(previous!=null){
            entries.forEach(entry->repaired.add(entry.weaponConfig()));
            var replacements=new HashMap<String,AttackFire>();for(var entry:entries){
                var old=previous.find(entry.weaponConfig(),entry.activity());
                var refs=new ArrayList<FireRef>();
                for(int i=0;i<entry.fireRefs().size();i++){
                    var ref=entry.fireRefs().get(i);var oldRef=old.fireRefs().get(i);
                    refs.add(new FireRef(ref.config(),ref.delay(),ref.refractory(),ref.advance(),ref.maxLoop(),ref.successIncrement(),ref.incrementReduction(),preserveHeadings(ref.action(),oldRef.action()),ref.endAction()));
                }
                replacements.put(entry.weaponConfig(),new AttackFire(entry.weaponConfig(),entry.activity(),entry.attack(),entry.fireSpeed(),refs));
            }
            entries=new ArrayList<>(previous.entries().stream().map(entry->entry.activity()==290?replacements.getOrDefault(entry.weaponConfig(),entry):entry).toList());
            UNSUPPORTED.putAll(previous.unsupportedTypes());
        }
        Files.createDirectories(out);
        FireCatalog.write(out.resolve("fires.json"), entries, UNSUPPORTED);
        if(previous!=null){
            // Preserve raw rows outside this repair, including their original numeric precision.
            @SuppressWarnings("unchecked") var document=(Map<String,Object>)org.tortodrone.resources.Json.parse(Files.readString(Path.of(args[2])),128);
            @SuppressWarnings("unchecked") var generated=(Map<String,Object>)org.tortodrone.resources.Json.parse(Files.readString(out.resolve("fires.json")),128);
            @SuppressWarnings("unchecked") var generatedRows=(List<Map<String,Object>>)generated.get("entries");
            var rows=new HashMap<String,Map<String,Object>>();for(var row:generatedRows)if(((Number)row.get("activity")).intValue()==290&&repaired.contains(row.get("weapon")))rows.put((String)row.get("weapon"),row);
            @SuppressWarnings("unchecked") var existingRows=(List<Map<String,Object>>)document.get("entries");
            document.put("entries",existingRows.stream().map(row->((Number)row.get("activity")).intValue()==290?rows.getOrDefault(row.get("weapon"),row):row).toList());
            @SuppressWarnings("unchecked") var unsupported=(Map<String,Object>)document.get("unsupported");
            var actual=new TreeMap<String,Integer>();countUnsupported(document.get("entries"),actual);
            unsupported.keySet().removeIf(type->type.startsWith("com.")&&!actual.containsKey(type));unsupported.putAll(actual);
            Files.writeString(out.resolve("fires.json"),org.tortodrone.resources.Json.stringify(document,128)+"\n");
        }
        var loaded = FireCatalog.load(out.resolve("fires.json"));
        System.out.printf("PASS: exported %d weapon/shield attack entries; %d fire refs; %d projectile actor emissions; %d unsupported action types%n",
            loaded.entries().size(), loaded.entries().stream().mapToInt(e -> e.fireRefs().size()).sum(), projectileCount, loaded.unsupportedTypes().size());
        loaded.unsupportedTypes().forEach((type,count) -> System.out.println("UNSUPPORTED\t" + type + "\t" + count));
    }

    private static void countUnsupported(Object value,Map<String,Integer> counts){
        if(value instanceof Map<?,?> map){
            if("UNSUPPORTED".equals(map.get("kind")))counts.merge((String)map.get("unsupportedType"),1,Integer::sum);
            map.values().forEach(child->countUnsupported(child,counts));
        }else if(value instanceof List<?> list)list.forEach(child->countUnsupported(child,counts));
    }

    /** Retain previously prepared headings; the retail export strips these server fields. */
    private static Action preserveHeadings(Action fresh,Action old){
        if(fresh==null||old==null||old.kind()==Kind.UNSUPPORTED||fresh.kind()!=old.kind())return fresh;
        var children=new ArrayList<Action>();for(int i=0;i<fresh.children().size();i++)children.add(preserveHeadings(fresh.children().get(i),i<old.children().size()?old.children().get(i):null));
        var actor=fresh.actor();if(actor!=null&&old.actor()!=null){
            var timers=new ArrayList<ActorTimer>();for(int i=0;i<actor.timers().size();i++){var t=actor.timers().get(i);timers.add(new ActorTimer(t.delay(),t.interval(),t.limit(),preserveHeadings(t.action(),i<old.actor().timers().size()?old.actor().timers().get(i).action():null),t.endAction()));}
            actor=new ActorSpec(actor.nativeType(),actor.config(),actor.fuse(),actor.speed(),actor.maxStep(),actor.flags(),actor.offsetX(),actor.offsetY(),actor.height(),actor.detonation(),actor.orbit(),actor.collisionMask(),actor.collisionShape(),timers,actor.shutdown());
        }
        boolean repairedFan=fresh.actor()!=null&&fresh.actor().config().name().equals("Bomb/Parts/Shard/Shard Base (Distance Check)")&&fresh.relative()&&!old.relative()&&old.rotation()==0;
        return new Action(fresh.kind(),fresh.config(),actor,fresh.targetType(),fresh.facingType(),fresh.collisionSourceType(),fresh.x(),fresh.y(),repairedFan?fresh.rotation():old.rotation(),old.rotationVariance(),repairedFan||old.relative(),fresh.count(),fresh.stepSize(),fresh.steps(),fresh.collisionMask(),fresh.stopOnFailure(),children,fresh.unsupportedType(),fresh.parameter(),fresh.values());
    }

    private static boolean fragmentation(Action action){
        return action!=null&&(action.actor()!=null&&action.actor().config().name().startsWith("Bomb/Parts/Shard/Bomb (Timer)")||action.children().stream().anyMatch(FireCatalogExport::fragmentation));
    }

    private static void addWeapon(ConfigManager manager, String name, ItemConfig.Weapon weapon, List<AttackFire> entries) throws Exception {
        for (int i=0; i<weapon.attacks.length; i++) {
            AttackConfig.AttackReference r = weapon.attacks[i];
            addAttack(manager, name, 292+i, r == null ? null : r.ref, entries);
        }
        addAttack(manager,name,289,weapon.incompleteChargedAttack,entries);
        addAttack(manager,name,290,weapon.chargedAttack,entries);
        addAttack(manager,name,291,weapon.cooldownAttack,entries);
    }
    private static void addShield(ConfigManager manager, String name, ItemConfig.Shield shield, List<AttackFire> entries) throws Exception {
        addAttack(manager,name,287,shield.bash,entries);
        ActionConfig defense=shield.action;
        if(defense instanceof DungeonActionConfig.FireAction fire&&fire.fireAction==null){
            // Reconstructed missing binding to the surviving stock push-back base;
            // its own rarity/intersection/cooldown guards remain authoritative.
            var restored=new DungeonActionConfig.FireAction();restored.fireAction=new ConfigReference<>("Item/Gear/Shield/PushBack (Base)");defense=restored;
        }
        if(defense!=null)entries.add(new AttackFire(name,284,new StarterCatalog.Model("ShieldDefense",Map.of("rarity",shield.rarity)),1,List.of(
            new FireRef(new StarterCatalog.Model("ShieldDefense",Map.of()),shield.actionDelay,0,false,0,0,0,readAction(manager,defense),null))));
    }
    static void addAttack(ConfigManager manager, String weapon, int activity,
            ConfigReference<AttackConfig> reference, List<AttackFire> entries) throws Exception {
        if (reference == null) return;
        AttackConfig.Original attack = AttackConfig.b(manager, reference);
        if (attack == null) throw new IOException("Unresolved stock attack " + reference.getName() + " for " + weapon);
        StarterCatalog.Model attackModel = model(reference);
        if(weapon.startsWith("Sprite/")){var metadata=new TreeMap<>(attackModel.arguments());metadata.put("land",attack.Qh());metadata.put("clear",attack.Qf());attackModel=new StarterCatalog.Model(attackModel.name(),metadata);}
        var refs = new ArrayList<FireRef>();
        if(attack instanceof AttackConfig.ShieldBash bash&&bash.handler instanceof DungeonHandlerConfig.OneTimeIntersection handler
            &&handler.action instanceof DungeonActionConfig.DamageTargetFromPoint hit){
            // Capture-calibrated archive reconstruction: 0.60 ahead, radius 0.67,
            // armed at 550/attackSpeed ms; one hit per target for this bash.
            var args=new TreeMap<String,Object>();args.put("damage",resolvedDamage(manager,hit.damage));
            var shape=new com.threerings.tudey.shape.config.ShapeConfig.Circle();shape.radius=.67f;args.put("shape",argumentValue(shape));
            args.put("impulse",argumentValue(hit.impulseDealt));args.put("impulse-origin",argumentValue(new Vector2f(hit.impulseTranslation.x-.6f,hit.impulseTranslation.y)));
            Action sweep=new Action(Kind.SWEEP_DAMAGE,new StarterCatalog.Model("ShieldBash",args),null,"Source","","",.6f,0,0,0,true,0,0,0,handler.mask,false,List.of(),"");
            refs.add(new FireRef(new StarterCatalog.Model("ShieldBash",Map.of()),Math.max(0,550-attack.Qh()),50,false,0,0,0,sweep,null));
        }
        if (attack.fireRefs != null) for (FireActionConfig.FireActionReference fireRef : attack.fireRefs) {
            if (fireRef == null || fireRef.ref == null) {
                UNSUPPORTED.merge("null-fire-reference",1,Integer::sum);
                continue;
            }
            FireActionConfig fire = manager.a(FireActionConfig.class, fireRef.ref);
            if (fire == null) throw new IOException("Unresolved fire action " + fireRef.ref.getName());
            Action action;
            if(fire.action instanceof DungeonActionConfig.SpawnPrespawned spawn&&spawn.actor!=null
                &&(spawn.actor.getName().equals("Bullet/Sword/Burst Bullet")||spawn.actor.getName().equals("Bullet/Sword/Burst Bullet (Obsidian)"))){
                // Stock charge parameters address pre-strip fire-ref indices.
                // The official captures retain the weapon's Burst Color/Damage
                // on its carrier and trails; bind the surviving attack arguments.
                var bound=(DungeonActionConfig.SpawnPrespawned)spawn.clone();
                bound.actor=new ConfigReference<>(spawn.actor.getName());bound.actor.kW().putAll(spawn.actor.kW());
                if(reference.kW().containsKey("Burst Color"))bound.actor.kW().put("Color",reference.kW().get("Burst Color"));
                if(reference.kW().containsKey("Burst Damage"))bound.actor.kW().put("Damage",reference.kW().get("Burst Damage"));
                action=readPrespawned(manager,bound);
            }else action = readFireAction(manager, fireRef.ref, fire);
            if (action == null) throw new IOException("Stock fire reference has no action: " + fireRef.ref.getName());
            int loop = fire instanceof FireActionConfig.Looped l ? l.maxLoop : 0;
            int success = fire instanceof FireActionConfig.IncreasingLooped l ? l.successIncrement : 0;
            int reduction = fire instanceof FireActionConfig.IncreasingLooped l ? l.incrementReduction : 0;
            Action endAction = fire instanceof FireActionConfig.Looped l ? readAction(manager,l.endAction) : null;
            refs.add(new FireRef(model(fireRef.ref),fire.delay,fire.refractory,fire.advance,loop,success,reduction,action,endAction));
        }
        if (!Float.isFinite(attack.fireSpeed) || attack.fireSpeed <= 0f)
            throw new IOException("Invalid stock fireSpeed for " + weapon + " activity " + activity);
        entries.add(new AttackFire(weapon,activity,attackModel,attack.fireSpeed,refs));
    }

    static Action readAction(ConfigManager manager, ActionConfig action) throws Exception {
        if (action == null) return null;
        actionCount++;
        if(action instanceof ActionConfig.Delayed delayed){
            return new Action(Kind.DELAYED,new StarterCatalog.Model("Delayed",Map.of("delay",delayed.delay,"variance",delayed.variance)),null,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(delayed.action==null?noop():readAction(manager,delayed.action)),"");
        }
        if(action.getClass().getSimpleName().equals("DestroyActor"))return destroySource();
        if(action instanceof ActionConfig.Fail)return new Action(Kind.FAIL,null,null,"","","",0,0,0,0,false,0,0,0,0,false,List.of(),"");
        if(action instanceof DungeonActionConfig.ApplyStatus status){
            if(status.condition==null)return noop();
            var values=new TreeMap<String,Object>();values.put("reference",model(status.condition));
            if(status.condition!=null){var resolved=manager.a(StatusConditionConfig.class,status.condition);if(resolved!=null)values.put("data",FireCatalog.modelJson(SceneStatusExport.statusData(resolved)).arguments().get("data"));}
            return new Action(Kind.APPLY_STATUS,new StarterCatalog.Model("Status",values),null,target(status.target),"","",0,0,0,0,true,0,0,0,0,false,List.of(),"");
        }
        if(action instanceof DungeonActionConfig.StartSpriteShield shield){
            var scale=manager.a(DepthScaleConfig.class,shield.healthScale);var values=new TreeMap<String,Object>();values.put("health",scale==null?0:scale.a(manager,0,0,com.threerings.projectx.dungeon.data.DungeonCodes.Difficulty.MEDIUM));values.put("power",shield.power);values.put("defense",argumentValue(shield.defense));
            return new Action(Kind.START_SPRITE_SHIELD,new StarterCatalog.Model("SpriteShield",values),null,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(),"");
        }
        if(action instanceof DungeonActionConfig.EndSpriteShield)return new Action(Kind.END_SPRITE_SHIELD,null,null,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(),"");
        if(action instanceof ActionConfig.Conditional conditional){
            return new Action(Kind.CONDITIONAL,new StarterCatalog.Model("Condition",Map.of("condition",argumentValue(conditional.condition))),null,"Source","","",0,0,0,0,true,0,0,0,0,false,
                List.of(conditional.action==null?noop():readAction(manager,conditional.action),conditional.elseAction==null?noop():readAction(manager,conditional.elseAction)),"");
        }
        if (action instanceof DungeonActionConfig.FireAction fireAction) {
            FireActionConfig fire = fireAction.fireAction == null ? null : manager.a(FireActionConfig.class, fireAction.fireAction);
            if (fire == null) throw new IOException("Unresolved nested fire action " + fireAction.fireAction);
            String name = fireAction.fireAction.getName();
            if (!NESTED_FIRES.add(name)) { UNSUPPORTED.merge("nested-fire-cycle",1,Integer::sum); return unsupported(action); }
            try { return readFireAction(manager, fireAction.fireAction, fire); } finally { NESTED_FIRES.remove(name); }
        }
        if (action instanceof DungeonActionConfig.FirePrefired firePrefired) {
            return new Action(Kind.FIRE_EFFECT, model(firePrefired.effect), null, target(firePrefired.location), "", "",
                0,0,0,0,false,0,0,0,0,false,List.of(),"");
        }
        if(action instanceof DungeonActionConfig.DamageTargetFromPoint damage){
            var data=new TreeMap<String,Object>();data.put("damage",resolvedDamage(manager,damage.damage));data.put("region",argumentValue(new RegionConfig.Default()));data.put("impulse",argumentValue(damage.impulseDealt));
            return new Action(Kind.DAMAGE_REGION,new StarterCatalog.Model("DamageTarget",data),null,"Source","","",0,0,0,0,true,0,0,0,damage.collisionMask,false,List.of(),"");
        }
        if(action instanceof DungeonActionConfig.DamageRegionFromPoint damage){
            var data=new TreeMap<String,Object>();data.put("damage",resolvedDamage(manager,damage.damage));
            data.put("region",argumentValue(damage.region));
            data.put("impulse",argumentValue(damage.impulseDealt));data.put("impulse-origin",argumentValue(damage.impulseTranslation));
            return new Action(Kind.DAMAGE_REGION,new StarterCatalog.Model("DamageRegionFromPoint",data),null,
                target(damage.location),"","",damage.translation.x,damage.translation.y,0,0,true,0,0,0,
                damage.collisionMask,false,List.of(),"");
        }
        if(action instanceof DungeonActionConfig.DamageRegion damage){
            var data=new TreeMap<String,Object>();data.put("damage",resolvedDamage(manager,damage.damage));data.put("region",argumentValue(damage.region));
            return new Action(Kind.DAMAGE_REGION,new StarterCatalog.Model("DamageRegion",data),null,"Source","","",0,0,0,0,true,0,0,0,0,false,List.of(),"");
        }
        if(action instanceof DungeonActionConfig.ImpartImpulse impulse){
            var data=new TreeMap<String,Object>();data.put("region",argumentValue(impulse.region));data.put("impulse",argumentValue(impulse.impulseDealt));data.put("impulse-origin",argumentValue(impulse.impulseTranslation));data.put("flags",impulse.flags);
            return new Action(Kind.IMPULSE_REGION,new StarterCatalog.Model("ImpartImpulse",data),null,target(impulse.location),"","",0,0,0,0,true,0,0,0,0,false,List.of(),"");
        }
        if (action instanceof DungeonActionConfig.SpawnTransformedPrespawnedArgModify modified)
            return readPrespawned(manager, modified);
        if (action instanceof DungeonActionConfig.MultiSpawnPrespawned multi) {
            var children = new ArrayList<Action>();
            if (multi.spawns != null) for (DungeonActionConfig.SpawnPrespawned spawn : multi.spawns) {
                Action converted = readPrespawned(manager, spawn); if (converted != null) children.add(converted);
            }
            return new Action(Kind.MULTI_PRESPAWNED,null,null,"","","",0,0,0,0,false,0,0,0,0,false,children,"");
        }
        if (action instanceof DungeonActionConfig.SpawnPrespawned spawn) return readPrespawned(manager, spawn);
        if (action instanceof ActionConfig.Compound compound) {
            var children = new ArrayList<Action>();
            if (compound.actions != null) for (ActionConfig child : compound.actions) {
                Action converted = readAction(manager,child); if(converted!=null)children.add(converted);
            }
            return new Action(Kind.COMPOUND,null,null,"","","",0,0,0,0,false,0,0,0,0,compound.stopOnFailure,children,"");
        }
        if (action instanceof ActionConfig.Random random) {
            // Equal positive weights retain the stock uniform draw (Wrench Wand's
            // two sounds and a null spawn); other weighted graphs stay explicit.
            if (random.actions != null && random.actions.length == 0) return noop();
            if (random.actions == null) return unsupported(action);
            if (random.actions.length == 1 && random.actions[0] != null
                    && Float.isFinite(random.actions[0].weight) && random.actions[0].weight > 0f)
                return readAction(manager, random.actions[0].action);
            var choices=new ArrayList<Action>();float weight=-1;
            for(var entry:random.actions){if(entry==null||!Float.isFinite(entry.weight)||entry.weight<=0f||weight>=0&&entry.weight!=weight)return unsupported(action);
                weight=entry.weight;var child=readAction(manager,entry.action);choices.add(child==null?noop():child);}
            return new Action(Kind.RANDOM_EQUAL,null,null,"","","",0,0,0,0,false,0,0,0,0,false,choices,"");
        }
        if (action instanceof ActionConfig.FireEffect effect) {
            return new Action(Kind.FIRE_EFFECT,model(effect.effect),null,target(effect.location),"","",0,0,0,0,false,0,0,0,0,false,List.of(),"");
        }
        if (action instanceof ActionConfig.SpawnActor spawn) {
            if (spawn.actor == null) return noop();
            ActorConfig config = manager.a(ActorConfig.class,spawn.actor);
            ActorConfig.Original actor = config == null ? null : config.ai(manager);
            if (actor == null) throw new IOException("Unresolved actor reference " + spawn.actor.getName());
            ActorSpec spec = actor(manager,spawn.actor,actor);
            Kind kind = isProjectile(spec.nativeType()) ? Kind.EMIT_PROJECTILE
                : action instanceof ActionConfig.SpawnRandomTranslatedActor ? Kind.RANDOM_TRANSLATED
                : action instanceof ActionConfig.SpawnFacingActor ? Kind.SPAWN_FACING : Kind.SPAWN_ACTOR;
            if(kind==Kind.EMIT_PROJECTILE) projectileCount++;
            float x=0,y=0,rotation=0,rotationVariance=0;boolean relative=true;
            if(action instanceof ActionConfig.SpawnRotatedActor rotated){rotation=rotated.rotation;rotationVariance=rotated.rotationVariance;relative=rotated.relative;}
            if(action instanceof ActionConfig.SpawnTransformedActor transformed&&transformed.translation!=null){x=transformed.translation.x;y=transformed.translation.y;}
            int count=0,steps=0,mask=0;float step=0;String collision="",facing="";
            if(action instanceof ActionConfig.SpawnRandomTranslatedActor random){count=random.count;steps=random.steps;step=random.stepSize;mask=random.collisionMask;collision=target(random.collisionSource);}
            if(action instanceof ActionConfig.SpawnFacingActor f)facing=target(f.facing);
            return new Action(kind,kind==Kind.EMIT_PROJECTILE?new StarterCatalog.Model("ServerSpawned",Map.of()):null,spec,target(spawn.location),facing,collision,x,y,rotation,rotationVariance,relative,count,step,steps,mask,false,List.of(),"");
        }
        return unsupported(action);
    }
    private static Action readPrespawned(ConfigManager manager, DungeonActionConfig.SpawnPrespawned spawn) throws Exception {
        // SpawnPrespawned.shouldPreExecute() is exactly actor != null in the stock archive.
        if (spawn == null || spawn.actor == null) return noop();
        ActorConfig config = manager.a(ActorConfig.class, spawn.actor);
        ActorConfig.Original original = config == null ? null : config.ai(manager);
        if (original == null) throw new IOException("Unresolved prespawned actor " + spawn.actor.getName());
        ActorSpec spec = actor(manager, spawn.actor, original);
        Kind kind = isProjectile(spec.nativeType()) ? Kind.EMIT_PROJECTILE : Kind.SPAWN_ACTOR;
        if (kind == Kind.EMIT_PROJECTILE) projectileCount++;
        float x=0,y=0;
        if (spawn instanceof DungeonActionConfig.SpawnTransformedPrespawned transformed && transformed.translation != null) {
            x=transformed.translation.x; y=transformed.translation.y;
        }
        String parameter = ""; List<String> values = List.of();
        if (spawn instanceof DungeonActionConfig.SpawnTransformedPrespawnedArgModify modified) {
            parameter = modified.parameter;
            values = modified.values == null ? List.of() : Arrays.asList(modified.values.clone());
        }
        return new Action(kind,new StarterCatalog.Model("Prespawned",Map.of()),spec,target(spawn.location),"","",x,y,spawn.rotation,spawn.rotationVariance,
            spawn.relative,0,0,0,0,false,List.of(),"",parameter,values);
    }
    private static Action noop() {
        return new Action(Kind.COMPOUND,null,null,"","","",0,0,0,0,false,0,0,0,0,false,List.of(),"");
    }
    static Action readFireAction(ConfigManager manager, ConfigReference<FireActionConfig> reference,
            FireActionConfig fire) throws Exception {
        ActionConfig raw=fire.action;
        if(reference.getName().equals("Item/Gear/Shield/PushBack (Base)")){
            // Authored fire_action.xml guards survive only as empty Tagged placeholders
            // in the installed binary. Restore this named graph, including cooldown.
            if(!(raw instanceof ActionConfig.Conditional first)||!(first.action instanceof ActionConfig.Conditional second))throw new IOException("Shield push-back graph changed");
            var outer=(ActionConfig.Conditional)first.clone();var inner=(ActionConfig.Conditional)second.clone();
            var rarity=new com.threerings.projectx.config.ProjectXConditionConfig.ShieldRarityEquipped();rarity.rarity=2;
            var intersection=new ConditionConfig.Intersecting();var region=new RegionConfig.Transformed();var circle=new com.threerings.tudey.shape.config.ShapeConfig.Circle();circle.radius=1.1f;region.shape=circle;
            intersection.first=region;var monsters=new RegionConfig.Default();var type=new TargetConfig.InstanceOf();type.logicClass="com.threerings.projectx.dungeon.server.logic.MonsterLogic";monsters.location=type;intersection.second=monsters;
            var all=new ConditionConfig.All();all.conditions=new ConditionConfig[]{rarity,intersection};outer.condition=all;
            var cooldown=new ConditionConfig.Cooldown();cooldown.time=1500;inner.condition=cooldown;outer.action=inner;raw=outer;
            if(!(inner.action instanceof ActionConfig.Compound original))throw new IOException("Shield push-back action changed");
            var compound=(ActionConfig.Compound)original.clone();compound.actions=original.actions.clone();
            var choice=(com.threerings.config.Parameter.Choice)fire.getParameter("Knock-Back Power");
            String selected=Objects.toString(reference.kW().get("Knock-Back Power"),choice.choice);ImpulseConfig.DamageImpulse impulse=null;
            for(var option:choice.options)if(option.name.equals(selected)){var field=option.getClass().getDeclaredField("_arguments");field.setAccessible(true);impulse=(ImpulseConfig.DamageImpulse)((Map<?,?>)field.get(option)).get("KB");}
            if(impulse==null)throw new IOException("Shield push-back power binding changed");
            for(int i=0;i<compound.actions.length;i++)if(compound.actions[i] instanceof DungeonActionConfig.ImpartImpulse push){
                var restored=(DungeonActionConfig.ImpartImpulse)push.clone();restored.impulseDealt=impulse;
                var area=new RegionConfig.Transformed();area.shape=new com.threerings.tudey.shape.config.ShapeConfig.Circle();restored.region=area;compound.actions[i]=restored;
            }
            inner.action=compound;
        }
        Action action = readAction(manager, raw);
        if(reference.getName().equals("Traps and Hazards/Trap, floor")){
            // Prepared Training content has depth zero: preserve the authored first
            // tier's damage graph. Its stripped UP guard is enforced by Trap runtime.
            if(!(fire.action instanceof ActionConfig.Compound compound)||compound.actions.length<1
                ||!(compound.actions[0] instanceof ActionConfig.Conditional conditional))throw new IOException("Floor trap graph changed");
            action=readAction(manager,conditional.action);
            var choice=(com.threerings.config.Parameter.Choice)fire.getParameter("Damage Size");
            for(var option:choice.options)if(option.name.equals(Objects.toString(reference.kW().get("Damage Size"),choice.choice))){
                var field=option.getClass().getDeclaredField("_arguments");field.setAccessible(true);
                Object shape=((Map<?,?>)field.get(option)).get("Damage Region");
                if(shape==null)throw new IOException("Floor trap shape missing");
                action=withShape(action,argumentValue(shape));
            }
        }
        if(reference.getName().equals("Base/Damage Origin Transformed")||reference.getName().equals("Base/Damage Region Transformed")){
            // The original base contains RegionConfig.Transformed -> Compound[Shape].
            // RegionConfig's server-only shape is stripped, but each attack's Shape
            // argument survives. Preserve that exact argument rather than the empty
            // Default region prototype returned by the client configuration manager.
            Object shape=reference.kW().get("Shape");
            if(action.kind()!=Kind.DAMAGE_REGION||shape==null)throw new IOException("Verified transformed damage action changed shape");
            var data=new TreeMap<>(action.config().arguments());data.put("shape",argumentValue(shape));
            action=new Action(action.kind(),new StarterCatalog.Model(action.config().name(),data),action.actor(),action.targetType(),
                action.facingType(),action.collisionSourceType(),action.x(),action.y(),action.rotation(),action.rotationVariance(),action.relative(),
                action.count(),action.stepSize(),action.steps(),action.collisionMask(),action.stopOnFailure(),action.children(),action.unsupportedType(),action.parameter(),action.values());
        }
        // SpawnRotatedActor.relative is stripped to the class default (false) in the
        // stock client. The original named base action explicitly has relative=true
        // in both the local original and 2026 fire_action.xml. Restore that one binding;
        // its absolute sibling and all other actions retain their authored semantics.
        if (reference.getName().equals("Base/Spawn Relative Rotated Actor")) {
            if (fire.action.getClass() != ActionConfig.SpawnRotatedActor.class
                    || action.kind() != Kind.EMIT_PROJECTILE || !action.targetType().equals("Source"))
                throw new IOException("Verified relative fire action changed shape");
            return new Action(action.kind(), action.config(), action.actor(), action.targetType(),
                action.facingType(), action.collisionSourceType(), action.x(), action.y(), action.rotation(),
                action.rotationVariance(), true, action.count(), action.stepSize(), action.steps(),
                action.collisionMask(), action.stopOnFailure(), action.children(), action.unsupportedType(),
                action.parameter(), action.values());
        }
        return action;
    }
    private static Action withShape(Action action,Object shape){
        var children=action.children().stream().map(a->withShape(a,shape)).toList();var config=action.config();
        if(action.kind()==Kind.DAMAGE_REGION||action.kind()==Kind.IMPULSE_REGION){var arguments=new TreeMap<>(config.arguments());arguments.put("shape",shape);config=new StarterCatalog.Model(config.name(),arguments);}
        return new Action(action.kind(),config,action.actor(),action.targetType(),action.facingType(),action.collisionSourceType(),action.x(),action.y(),action.rotation(),action.rotationVariance(),action.relative(),action.count(),action.stepSize(),action.steps(),action.collisionMask(),action.stopOnFailure(),children,action.unsupportedType(),action.parameter(),action.values());
    }
    private static Action unsupported(ActionConfig action) throws IOException {
        String type=action.getClass().getName();
        UNSUPPORTED.merge(type,1,Integer::sum);
        return new Action(Kind.UNSUPPORTED,null,null,"","","",0,0,0,0,false,0,0,0,0,false,List.of(),type);
    }
    private static Object resolvedDamage(ConfigManager manager,DamageConfig damage)throws Exception{
        if(damage instanceof DamageConfig.Compound compound){
            var fields=new ArrayList<FieldValue>();var children=new ArrayList<Object>();for(var child:compound.damages)children.add(resolvedDamage(manager,child));
            fields.add(new FieldValue("damages",DamageConfig[].class.getName(),new ListValue(DamageConfig.class.getName(),children)));
            return new DataValue(DamageConfig.Compound.class.getName(),fields);
        }
        if(damage instanceof DamageConfig.Status status&&status.statusCondition!=null){
            var fields=new ArrayList<FieldValue>(((DataValue)argumentValue(damage)).fields());
            var config=manager.a(StatusConditionConfig.class,status.statusCondition);
            if(config!=null)fields.add(new FieldValue("statusData","java.lang.Object",FireCatalog.modelJson(SceneStatusExport.statusData(config)).arguments().get("data")));
            return new DataValue(damage.getClass().getName(),fields);
        }
        if(!(damage instanceof DamageConfig.DepthScale scaled))return argumentValue(damage);
        var config=manager.a(DepthScaleConfig.class,scaled.depthScale);if(config==null)throw new IOException("Unresolved damage scale "+scaled.depthScale);
        float amount=config.a(manager,0,damageRarity,com.threerings.projectx.dungeon.data.DungeonCodes.Difficulty.MEDIUM);
        if(scaled instanceof DamageConfig.DepthScaleMulti multi){
            var split=manager.a(DepthScaleConfig.class,multi.splitDepth);
            float fraction=split==null?.5f:split.a(manager,0,damageRarity,com.threerings.projectx.dungeon.data.DungeonCodes.Difficulty.MEDIUM),offset=fraction>0&&fraction<1?.02f:0;
            return new DataValue(DamageConfig.Compound.class.getName(),List.of(new FieldValue("damages",DamageConfig[].class.getName(),new ListValue(DamageConfig.class.getName(),List.of(
                flatDamage(scaled,scaled.type,com.threerings.projectx.dungeon.util.DamageSummary.v(amount,Math.min(1,fraction+offset))),
                flatDamage(scaled,multi.type2,com.threerings.projectx.dungeon.util.DamageSummary.v(amount,Math.min(1,1-fraction+offset))))))));
        }
        return flatDamage(scaled,scaled.type,amount);
    }
    private static Object flatDamage(DamageConfig.DepthScale source,com.threerings.projectx.dungeon.util.DamageSummary.DamageType type,float amount)throws IOException{
        DamageConfig.Base result=switch(type){case NORMAL->new DamageConfig.Normal();case PIERCING->new DamageConfig.Piercing();case ELEMENTAL->new DamageConfig.Elemental();case SHADOW->new DamageConfig.Shadow();case UNBLOCKABLE->new DamageConfig.Unblockable();};
        result.amount=amount;result.flags=source.flags;result.shouldReact=source.shouldReact;result.majorReact=source.majorReact;result.limiter=source.limiter;
        return argumentValue(result);
    }
    private static ActorSpec actor(ConfigManager manager, ConfigReference<ActorConfig> reference, ActorConfig.Original actor) throws Exception {
        String type; int fuse=0; float speed=0,maxStep=-1,ox=0,oy=0,height=0;Action detonation=null; OrbitSpec orbit=null;
        String refName=reference.getName();
        if (isNamedSubtype(actor.getClass(), "com.threerings.projectx.dungeon.config.DungeonActorConfig$CompoundOrbitalBullet")) {
            type = "COMPOUND_ORBITAL_BULLET"; orbit = orbital(actor, true); fuse = orbit.lifespan(); speed = orbit.angularSpeed(); height = floatField(actor,"height");
        }
        else if (isNamedSubtype(actor.getClass(), "com.threerings.projectx.dungeon.config.DungeonActorConfig$OrbitalBullet")) {
            type = "ORBITAL_BULLET"; orbit = orbital(actor, false); fuse = orbit.lifespan(); speed = orbit.angularSpeed(); height = floatField(actor,"height");
        }
        else if(actor instanceof DungeonActorConfig.Orbital o){type="ORBITAL";fuse=600000;speed=o.speed;height=o.height;orbit=new OrbitSpec(o.orbitRadius,o.speed,o.startingRotation,0,0,600000,o.follow.name(),false,null,null,null);}
        else if(actor instanceof DungeonActorConfig.Laser laser){type="LASER";fuse=laser.duration;speed=laser.length;}
        else if(refName.startsWith("Bullet/Pet/")&&actor.getClass()==ActorConfig.Original.class){type="ACTOR";fuse=600000;}
        else if(actor instanceof DungeonActorConfig.Bullet bullet){type="BULLET";fuse=bullet.fuse;speed=bullet.speed;ox=bullet.offset==null?0:bullet.offset.x;oy=bullet.offset==null?0:bullet.offset.y;height=bullet.height;if(NESTED_ACTORS.add(reference)){try{detonation=readAction(manager,bullet.detonationAction);}finally{NESTED_ACTORS.remove(reference);}}else UNSUPPORTED.merge("recursive-actor-detonation",1,Integer::sum);}
        else if(actor instanceof DungeonActorConfig.Bomb bomb){type="BOMB";fuse=bomb.fuse;if(NESTED_ACTORS.add(reference)){try{detonation=readAction(manager,bomb.detonationAction);}finally{NESTED_ACTORS.remove(reference);}}else UNSUPPORTED.merge("recursive-actor-detonation",1,Integer::sum);}
        else {type=actor.getClass().getName();UNSUPPORTED.merge("actor:"+type,1,Integer::sum);}
        // The distance checker retains its planting action, but its server-only guard is
        // an empty Tagged placeholder in retail. Keep the retained payload on termination;
        // exact retail collision/planting gating still requires an official capture.
        if(refName.equals("Bomb/Parts/Shard/Shard Base (Distance Check)")&&actor instanceof DungeonActorConfig.Bullet bullet
            &&bullet.detonationAction instanceof ActionConfig.Conditional gate&&gate.condition instanceof ConditionConfig.Tagged tag
            &&tag.tag.isEmpty()&&gate.elseAction==null)detonation=readAction(manager,gate.action);
        com.threerings.tudey.data.actor.Actor nativeActor=actor.a(reference,0,0,new Vector2f(),0f);
        nativeActor.init(manager);
        int flags=intField(nativeActor,"_flags");
        if(nativeActor instanceof com.threerings.tudey.data.actor.Mobile)maxStep=floatField(nativeActor,"_maxStep");
        var timers=new ArrayList<ActorTimer>();var shutdown=new ArrayList<Action>();
        for(var handler:actor.handlers)if(handler instanceof HandlerConfig.Shutdown ended){
            Action action=readAction(manager,ended.action);if(action!=null)shutdown.add(action);
        }
        if(refName.equals("Bullet/Sword/Burst Bullet")||refName.equals("Bullet/Sword/Burst Bullet (Obsidian)")) {
            // The stock Timer retains the trail reference/arguments but strips its
            // interval and counter. Local official captures contain five trails
            // at +123,+224,+324,+424,+526 ms; the authored Burst Count is five.
            if(actor.handlers.length!=3||!(actor.handlers[1] instanceof HandlerConfig.Timer timer)
                ||!(timer.action instanceof ActionConfig.Conditional conditional)
                ||!(conditional.action instanceof ActionConfig.Compound compound)||compound.actions.length!=1
                ||!(compound.actions[0] instanceof ActionConfig.SpawnActor spawn)||spawn.actor==null)
                throw new IOException("Burst bullet timer layout changed");
            Object count=reference.kW().get("Burst Count");
            if(count!=null){int bursts=Integer.parseInt(count.toString());
                if(bursts<1||bursts>32)throw new IOException("Invalid burst count");
                // Retail's parameter paths still address stripped server nodes.
                // Captures show the carrier's Color/Damage/FX passed verbatim to
                // each trail, with Burst Scale renamed Scale. Resolve that child
                // afresh so it cannot retain the prototype's monster damage.
                var trail=new ActionConfig.SpawnActor();trail.location=spawn.location;
                trail.actor=new ConfigReference<>(spawn.actor.getName());trail.actor.kW().putAll(spawn.actor.kW());
                for(String key:List.of("Color","Damage","Extra FX","Ground Scar"))
                    if(reference.kW().containsKey(key))trail.actor.kW().put(key,reference.kW().get(key));
                if(reference.kW().containsKey("Burst Scale"))trail.actor.kW().put("Scale",reference.kW().get("Burst Scale"));
                timers.add(new ActorTimer(100,100,bursts,readAction(manager,trail),destroySource()));}
        } else if(refName.startsWith("Bullet/Handgun/Pulsar/")&&!refName.contains("(Charged)")) {
            for(var handler:actor.handlers)if(handler instanceof HandlerConfig.Timer timer
                &&timer.action instanceof ActionConfig.Compound compound
                &&Arrays.stream(compound.actions).anyMatch(a->a instanceof ActionConfig.SpawnActor spawn
                    &&spawn.actor!=null&&spawn.actor.getName().equals("Bullet/Handgun/Pulsar/Parts/Base Stage2"))) {
                // Maturation replaces the first stage; never repeat a replacement
                // every tick. Stage2's fuse, visuals and damage remain stock data.
                timers.add(new ActorTimer(1000,0,1,readAction(manager,timer.action),destroySource()));
            }
        }
        if(refName.startsWith("Bullet/Handgun/Parts/Alchemical/")&&refName.endsWith("/Secondary")
            &&detonation!=null&&detonation.kind()==Kind.COMPOUND){
            // The local official Voltech capture has twenty secondary -> tertiary
            // bounces in the parent's +90..180 degree sector, rather than the
            // stripped SpawnRotatedActor default of absolute east. This bounded
            // fan is a capture reconstruction; charged fan angles remain unknown.
            var children=new ArrayList<Action>();
            for(var child:detonation.children()){
                if(child.kind()==Kind.EMIT_PROJECTILE&&child.actor().config().name().endsWith("/Tirtiary"))
                    child=new Action(child.kind(),child.config(),child.actor(),child.targetType(),child.facingType(),child.collisionSourceType(),child.x(),child.y(),(float)(Math.PI*.75),(float)(Math.PI*.5),true,child.count(),child.stepSize(),child.steps(),child.collisionMask(),child.stopOnFailure(),child.children(),child.unsupportedType(),child.parameter(),child.values());
                children.add(child);
            }
            detonation=new Action(detonation.kind(),detonation.config(),detonation.actor(),detonation.targetType(),detonation.facingType(),detonation.collisionSourceType(),detonation.x(),detonation.y(),detonation.rotation(),detonation.rotationVariance(),detonation.relative(),detonation.count(),detonation.stepSize(),detonation.steps(),detonation.collisionMask(),detonation.stopOnFailure(),children,detonation.unsupportedType(),detonation.parameter(),detonation.values());
        }
        if(refName.startsWith("Bullet/Pet/")){
            if(actor instanceof DungeonActorConfig.Laser laser){
                var action=readAction(manager,laser.damageAction);int interval=Math.max(1,laser.damageInterval),limit=Math.max(1,Math.min(128,(fuse-laser.warmup+interval-1)/interval));
                timers.add(new ActorTimer(laser.warmup,interval,limit,action,null));
            }
            for(var handler:actor.handlers){
                if(handler instanceof HandlerConfig.Startup start){var a=readAction(manager,start.action);if(a!=null)timers.add(new ActorTimer(0,0,1,a,null));}
                if(handler instanceof HandlerConfig.Timer timer){
                    var a=readAction(manager,timer.action);if(a==null)continue;
                    int interval=Math.max(1,(int)(timer.interval*1000)),delay=Math.max(0,interval+(int)(timer.offset*1000));
                    if(a.kind()==Kind.DESTROY_SOURCE){fuse=Math.min(fuse,delay);continue;}
                    timers.add(new ActorTimer(delay,interval,Math.min(128,timer.limit==0?128:timer.limit),a,null));
                }
            }
        }
        boolean shardBomb=refName.startsWith("Bomb/Parts/Shard/Bomb (Timer)");
        if(shardBomb){
            // An explosive's retained graph is a single detonation, not a repeating pulse.
            // The client bomb wrapper retains its fuse even though Timer fields are stripped.
            Object visualFuse=actor.sprite.model==null?null:actor.sprite.model.kW().get("Fuse");
            if(!(visualFuse instanceof Float seconds)||!Float.isFinite(seconds)||seconds<=0)throw new IOException("Shard bomb has no visual fuse");
            for(var handler:actor.handlers)if(handler instanceof HandlerConfig.Timer timer)
                timers.add(new ActorTimer((int)(seconds*1000),0,1,shardFan(readAction(manager,bindShardBranches(timer.action,reference))),destroySource()));
        }
        if(refName.equals("Bomb/Parts/Shard/Shard Base (Timer)")){
            // Its signal trigger/timer is stripped. End the retained visual and damage
            // together at the authored model lifespan rather than leave an invisible bomb.
            Object lifespan=actor.sprite.model==null?null:actor.sprite.model.kW().get("Lifespan");
            if(!(lifespan instanceof Float seconds)||!Float.isFinite(seconds)||seconds<=0)throw new IOException("Shard has no visual lifespan");
            for(var handler:actor.handlers)if(handler instanceof HandlerConfig.Signal signal)
                timers.add(new ActorTimer((int)(seconds*1000),0,1,readAction(manager,signal.action),destroySource()));
        }
        if(!shardBomb&&!refName.equals("Bomb/Parts/Shard/Shard Base (Timer)")&&!refName.startsWith("Bullet/Pet/"))for(var handler:actor.handlers){
            // Piercing charged bullets exclude monsters from their blocking mask.
            // Their intersection handler, rather than detonation, deals the hits.
            if(handler instanceof HandlerConfig.BaseIntersection intersection){
                Action hit=intersectionDamage(readAction(manager,intersection.action));
                if(hit!=null){int interval=Math.max(16,(fuse+126)/127);
                    timers.add(new ActorTimer(0,interval,Math.min(128,1+(fuse+interval-1)/interval),hit,null));}
            }
            if(handler instanceof HandlerConfig.Timer timer&& !refName.equals("Bullet/Sword/Burst Bullet")
                && !refName.equals("Bullet/Sword/Burst Bullet (Obsidian)")&&!refName.startsWith("Bullet/Handgun/Pulsar/")){
                Action action=readAction(manager,timer.action);
                if(action!=null&&containsDamage(action)){
                    int interval=Math.max(1,(int)(timer.interval*1000)),delay=Math.max(0,interval+(int)(timer.offset*1000));
                    if(refName.equals("Bullet/Sword/Spur (Charged)")){
                        // Archived Spur/Winmillion combat description: the short
                        // piercing charge projectile can hit up to three times.
                        timers.add(new ActorTimer(0,Math.max(1,fuse/3),3,action,null));
                    }else if(type.equals("BULLET")&&speed>0&&(actor.collisionMask&8)==0&&delay>=fuse){
                        // Stripped timers can outlive their moving blade. Its
                        // surviving damage graph is a piercing contact attack.
                        int step=Math.max(16,(fuse+126)/127);
                        timers.add(new ActorTimer(0,step,Math.min(128,1+(fuse+step-1)/step),intersectionDamage(action),null));
                    }else timers.add(new ActorTimer(delay,interval,Math.min(128,timer.limit==0?128:timer.limit),action,null));
                }
            }
        }
        if(type.equals("ORBITAL")&&reference.kW().get("Duration") instanceof Number duration)fuse=Math.max(1,(int)(duration.floatValue()*1000));
        if(type.equals("ACTOR")&&!timers.isEmpty())fuse=timers.stream().filter(t->t.action().kind()!=Kind.COMPOUND||!t.action().children().isEmpty()).mapToInt(t->t.delay()+t.interval()*(t.limit()-1)+1).max().orElse(1);
        detonation=restoreDamageShape(manager,reference,detonation);
        return new ActorSpec(type,model(reference),fuse,speed,maxStep,flags,ox,oy,height,detonation,orbit,actor.collisionMask,argumentValue(actor.shape),timers,
            shutdown.isEmpty()?null:new Action(Kind.COMPOUND,null,null,"","","",0,0,0,0,false,0,0,0,0,false,shutdown,""));
    }
    /** Retail's Shard Damage path has an extra closing bracket on one branch. Bind
     *  the surviving shared arguments to every retained child instead of default damage. */
    private static ActionConfig bindShardBranches(ActionConfig raw,ConfigReference<ActorConfig> source){
        if(raw instanceof ActionConfig.Compound compound){var copy=(ActionConfig.Compound)compound.clone();copy.actions=Arrays.stream(compound.actions).map(a->bindShardBranches(a,source)).toArray(ActionConfig[]::new);return copy;}
        if(raw instanceof ActionConfig.Delayed delayed){var copy=(ActionConfig.Delayed)delayed.clone();copy.action=bindShardBranches(delayed.action,source);return copy;}
        if(raw instanceof ActionConfig.SpawnActor spawn&&spawn.actor!=null&&spawn.actor.getName().equals("Bomb/Parts/Shard/Shard Base (Distance Check)")){
            var copy=(ActionConfig.SpawnActor)spawn.clone();copy.actor=spawn.actor.kX();
            if(source.kW().containsKey("Shard Damage"))copy.actor.kW().put("Damage",source.kW().get("Shard Damage"));
            if(source.kW().containsKey("Shard Color"))copy.actor.kW().put("Color",source.kW().get("Shard Color"));
            return copy;
        }
        return raw;
    }

    /** The retained Rock Salt graph supplies the family's equally spaced relative fan.
     * Retail strips SpawnTransformedActor headings from later tier graphs; otherwise all
     * authored branches overlap at absolute east. Keep the authored branch count/timing. */
    private static Action shardFan(Action action){
        if(action==null||action.kind()!=Kind.COMPOUND)return action;
        var branches=action.children().stream().filter(a->a.kind()==Kind.DELAYED&&a.children().size()==1&&a.children().getFirst().actor()!=null&&a.children().getFirst().actor().config().name().equals("Bomb/Parts/Shard/Shard Base (Distance Check)")).toList();
        if(branches.size()<2||branches.stream().anyMatch(a->a.children().getFirst().rotation()!=0))return action;
        var children=new ArrayList<Action>();int index=0;
        for(var child:action.children()){
            if(branches.contains(child)){
                var a=child.children().getFirst();
                var spread=new Action(a.kind(),a.config(),a.actor(),a.targetType(),a.facingType(),a.collisionSourceType(),a.x(),a.y(),(float)(index++*2*Math.PI/branches.size()),a.rotationVariance(),true,a.count(),a.stepSize(),a.steps(),a.collisionMask(),a.stopOnFailure(),a.children(),a.unsupportedType(),a.parameter(),a.values());
                child=new Action(child.kind(),child.config(),child.actor(),child.targetType(),child.facingType(),child.collisionSourceType(),child.x(),child.y(),child.rotation(),child.rotationVariance(),child.relative(),child.count(),child.stepSize(),child.steps(),child.collisionMask(),child.stopOnFailure(),List.of(spread),child.unsupportedType(),child.parameter(),child.values());
            }
            children.add(child);
        }
        return new Action(action.kind(),action.config(),action.actor(),action.targetType(),action.facingType(),action.collisionSourceType(),action.x(),action.y(),action.rotation(),action.rotationVariance(),action.relative(),action.count(),action.stepSize(),action.steps(),action.collisionMask(),action.stopOnFailure(),children,action.unsupportedType(),action.parameter(),action.values());
    }

    private static Action destroySource(){return new Action(Kind.DESTROY_SOURCE,null,null,"Source","","",0,0,0,0,false,0,0,0,0,false,List.of(),"");}
    private static boolean containsDamage(Action action){return action.kind()==Kind.DAMAGE_REGION||action.children().stream().anyMatch(FireCatalogExport::containsDamage);}
    private static Action intersectionDamage(Action action){
        if(action==null)return null;
        if(action.kind()==Kind.DAMAGE_REGION)return new Action(Kind.SWEEP_DAMAGE,action.config(),null,action.targetType(),"","",action.x(),action.y(),0,0,true,0,0,0,action.collisionMask(),false,List.of(),"");
        if(action.kind()!=Kind.COMPOUND)return null;
        var children=new ArrayList<Action>();for(var child:action.children()){var hit=intersectionDamage(child);if(hit!=null)children.add(hit);}
        return children.isEmpty()?null:new Action(Kind.COMPOUND,null,null,"","","",0,0,0,0,false,0,0,0,0,false,children,"");
    }
    private static Action restoreDamageShape(ConfigManager manager,ConfigReference<ActorConfig> reference,Action action)throws Exception {
        if(action==null)return null;
        // Direct and choice arguments survive stripping even when the region they address
        // does not. Follow the actual derived bindings and restore only an
        // explicitly authored detonation circle radius, never a guessed area.
        var seen=new HashSet<String>();
        while(reference!=null&&seen.add(reference.getName())){
            var config=manager.a(ActorConfig.class,reference);if(config==null)break;
            for(var parameter:config.parameters)if(parameter instanceof com.threerings.config.Parameter.Direct direct){
                for(String path:direct.paths)action=restoreDetonationCircle(action,path,reference.kW().get(direct.name));
            }else if(parameter instanceof com.threerings.config.Parameter.Choice choice){
                for(var direct:choice.directs)for(String path:direct.paths)
                    if(path.matches("implementation\\.detonation_action(?:\\.actions\\[\\d+\\])?\\.region\\.shape\\.radius")){
                        String selected=Objects.toString(reference.kW().get(choice.name),choice.choice);
                        for(var option:choice.options)if(option.name.equals(selected)){
                            var field=option.getClass().getDeclaredField("_arguments");field.setAccessible(true);
                            Object value=((Map<?,?>)field.get(option)).get(direct.name);
                            action=restoreDetonationCircle(action,path,value);
                        }
                    }
            }
            reference=config.implementation instanceof ActorConfig.Derived derived?derived.actor:null;
        }
        return action;
    }
    private static Action restoreDetonationCircle(Action action,String path,Object value)throws Exception {
        if(!path.matches("implementation\\.detonation_action(?:\\.actions\\[\\d+\\])?\\.region\\.shape\\.radius")||!(value instanceof Number radius))return action;
        if(!Float.isFinite(radius.floatValue())||radius.floatValue()<=0)throw new IOException("Invalid detonation radius: "+path);
        var circle=new com.threerings.tudey.shape.config.ShapeConfig.Circle();circle.radius=radius.floatValue();
        Object shape=argumentValue(circle);
        var index=java.util.regex.Pattern.compile(".*\\.actions\\[(\\d+)\\].*").matcher(path);
        if(index.matches()){
            int at=Integer.parseInt(index.group(1));
            if(action.kind()!=Kind.COMPOUND||at>=action.children().size())throw new IOException("Detonation shape path changed: "+path);
            var children=new ArrayList<>(action.children());var child=children.get(at);
            if(child.kind()!=Kind.DAMAGE_REGION&&child.kind()!=Kind.IMPULSE_REGION)throw new IOException("Detonation circle has no matching region action: "+path);
            children.set(at,withShape(child,shape));
            return new Action(action.kind(),action.config(),action.actor(),action.targetType(),action.facingType(),action.collisionSourceType(),action.x(),action.y(),action.rotation(),action.rotationVariance(),action.relative(),action.count(),action.stepSize(),action.steps(),action.collisionMask(),action.stopOnFailure(),children,action.unsupportedType(),action.parameter(),action.values());
        }
        if(action.kind()!=Kind.DAMAGE_REGION&&action.kind()!=Kind.IMPULSE_REGION)throw new IOException("Detonation circle has no matching region action: "+path);
        return withShape(action,shape);
    }
    private static boolean isProjectile(String type) { return type.equals("ORBITAL") || type.equals("LASER") || type.equals("ACTOR") || type.equals("BULLET") || type.equals("BOMB") || type.equals("ORBITAL_BULLET") || type.equals("COMPOUND_ORBITAL_BULLET"); }
    private static boolean isNamedSubtype(Class<?> type,String name) { for(Class<?> c=type;c!=null;c=c.getSuperclass())if(c.getName().equals(name))return true;return false; }
    private static OrbitSpec orbital(Object config,boolean compound)throws IOException {
        Object follow=field(config,"follow");
        if(!(follow instanceof Enum<?> value))throw new IOException("Invalid stock orbital follow enum");
        return new OrbitSpec(floatField(config,"orbitRadius"),floatField(config,"speed"),floatField(config,"startingRotation"),
            floatField(config,"flightSpeed"),intField(config,"outDuration"),intField(config,"lifespan"),value.name(),
            (Boolean)readField(config,"alternateSpin"),compound?floatField(config,"secondOrbitRadius"):null,
            compound?floatField(config,"secondSpeed"):null,compound?floatField(config,"secondStartingRotation"):null);
    }
    private static String target(TargetConfig target) { return target==null?"":target.getClass().getSimpleName(); }

    static StarterCatalog.Model model(ConfigReference<?> reference) throws IOException {
        if (reference == null) return null;
        return new StarterCatalog.Model(reference.getName(),arguments(reference.kW(),0,new IdentityHashMap<>(),new int[]{0}));
    }
    static Object argumentValue(Object value)throws IOException{return argument(value,0,new IdentityHashMap<>(),new int[]{0});}
    private static Map<String,Object> arguments(Map<?,?> input,int depth,IdentityHashMap<Object,Boolean> visiting,int[] nodes)throws IOException {
        if(depth>12||input.size()>4096)throw new IOException("Stock fire arguments exceed bounds");
        var result=new TreeMap<String,Object>();
        for(var e:input.entrySet()) {
            if(!(e.getKey() instanceof String key)||key.length()>1024||key.chars().anyMatch(c->c<32))throw new IOException("Invalid stock argument key");
            result.put(key,argument(e.getValue(),depth+1,visiting,nodes));
        }
        return result;
    }
    private static Object argument(Object value,int depth,IdentityHashMap<Object,Boolean> visiting,int[] nodes)throws IOException {
        if(++nodes[0]>100_000||depth>16)throw new IOException("Stock fire argument graph exceeds bounds");
        if(value==null)return FireCatalog.NullValue.INSTANCE;
        if(value instanceof String||value instanceof Integer||value instanceof Boolean)return value;
        if(value instanceof Character c)return (int)c.charValue();
        if(value instanceof Float f&&Float.isFinite(f))return f;
        if(value instanceof Double d&&Double.isFinite(d))return BigDecimal.valueOf(d);
        if(value instanceof Long||value instanceof Short||value instanceof Byte)return ((Number)value).longValue();
        if(value instanceof BigDecimal b)return b;
        if(value instanceof Enum<?> e)return new FireCatalog.EnumValue(e.getDeclaringClass().getName(),e.name(),e.ordinal());
        if(value instanceof ConfigReference<?> ref)return model(ref);
        if(visiting.put(value,Boolean.TRUE)!=null)throw new IOException("Cyclic stock fire argument: "+value.getClass().getName());
        try {
            if(value instanceof Map<?,?> map)return new FireCatalog.MapValue(arguments(map,depth+1,visiting,nodes));
            if(value instanceof Iterable<?> iterable){var list=new ArrayList<Object>();for(Object item:iterable)list.add(argument(item,depth+1,visiting,nodes));return new FireCatalog.ListValue(value.getClass().getName(),list);}
            if(value.getClass().isArray()){int length=Array.getLength(value);if(length>10_000)throw new IOException("Oversized stock argument array");var list=new ArrayList<Object>();for(int i=0;i<length;i++)list.add(argument(Array.get(value,i),depth+1,visiting,nodes));return new FireCatalog.ListValue(value.getClass().getComponentType().getName(),list);}
            var fields=new ArrayList<FireCatalog.FieldValue>();
            var hierarchy=new ArrayList<Class<?>>();for(Class<?> c=value.getClass();c!=null&&c!=Object.class;c=c.getSuperclass())hierarchy.add(c);
            Collections.reverse(hierarchy);
            for(Class<?> c:hierarchy)for(Field field:c.getDeclaredFields())
                if(!Modifier.isStatic(field.getModifiers())&&!Modifier.isTransient(field.getModifiers())&&!field.isSynthetic()
                    && Arrays.stream(field.getAnnotations()).noneMatch(a->a.annotationType().getSimpleName().equals("NotStreamable"))) {
                    try { field.setAccessible(true); fields.add(new FireCatalog.FieldValue(field.getName(),field.getType().getName(),argument(field.get(value),depth+1,visiting,nodes))); }
                    catch(IllegalAccessException|RuntimeException inaccessible){throw new IOException("Unreadable stock argument field "+field,inaccessible);}
                }
            return new FireCatalog.DataValue(value.getClass().getName(),fields);
        } finally { visiting.remove(value); }
    }
    private static int intField(Object value,String name)throws IOException{Object field=field(value,name);if(field instanceof Number n)return n.intValue();throw new IOException("Missing stock actor integer "+name);}
    private static float floatField(Object value,String name)throws IOException{Object field=field(value,name);if(field instanceof Number n&&Float.isFinite(n.floatValue()))return n.floatValue();throw new IOException("Missing stock actor float "+name);}
    private static Object field(Object value,String name)throws IOException { return readField(value,name); }
    private static Object readField(Object value,String name)throws IOException {
        for(Class<?> type=value.getClass();type!=null;type=type.getSuperclass())try{Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(value);}catch(NoSuchFieldException absent){}catch(ReflectiveOperationException|RuntimeException failure){throw new IOException("Cannot read stock runtime field "+name,failure);}
        throw new IOException("Stock runtime field not found: "+name);
    }
    private static boolean playerDefinition(int id, ItemConfig.Original item) {
        boolean eligibleSlot = item instanceof ItemConfig.Weapon weapon
            && weapon.h(6, false)
            && (item instanceof ItemConfig.Sword || item instanceof ItemConfig.Handgun || item instanceof ItemConfig.Bomb)
            || item instanceof ItemConfig.Shield shield && shield.h(2, false);
        return id != 0 && item.name != null && !item.name.isBlank() && item.icon != null
            && item.expireDays == 0 && (item.expireEvent == null || item.expireEvent.isBlank()) && eligibleSlot;
    }
}
