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
    private static final Set<String> NESTED_ACTORS = new HashSet<>();
    private static int actionCount, projectileCount;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: FireCatalogExport game-directory fresh-output-directory");
        Path game = Path.of(args[0]).toRealPath(), out = Path.of(args[1]).toAbsolutePath().normalize();
        Path ancestor = out; while (!Files.exists(ancestor)) ancestor = ancestor.getParent();
        if (ancestor.toRealPath().startsWith(game) || Files.exists(out)) throw new IOException("Choose a fresh output directory outside the installation");
        var resource = Class.forName("com.threerings.b.f").getConstructor(String.class).newInstance("rsrc/");
        resource.getClass().getMethod("ga", String.class).invoke(resource, game.resolve("rsrc").toString());
        var manager = (ConfigManager)ConfigManager.class.getConstructor(Class.forName("com.threerings.b.f"), com.threerings.util.R.class, String.class)
            .newInstance(resource, null, "config/");
        manager.init();

        var entries = new ArrayList<AttackFire>();
        for (ItemConfig config : manager.bd(ItemConfig.class).kK()) {
            ItemConfig.Original item = config.w(manager);
            if (!playerDefinition(config.getConfigId(), item)) continue;
            if (item instanceof ItemConfig.Weapon weapon) addWeapon(manager, config.getName(), weapon, entries);
            else if (item instanceof ItemConfig.Shield shield) addShield(manager, config.getName(), shield, entries);
        }
        Files.createDirectories(out);
        FireCatalog.write(out.resolve("fires.json"), entries, UNSUPPORTED);
        var loaded = FireCatalog.load(out.resolve("fires.json"));
        System.out.printf("PASS: exported %d weapon/shield attack entries; %d fire refs; %d projectile actor emissions; %d unsupported action types%n",
            loaded.entries().size(), loaded.entries().stream().mapToInt(e -> e.fireRefs().size()).sum(), projectileCount, UNSUPPORTED.size());
        UNSUPPORTED.forEach((type,count) -> System.out.println("UNSUPPORTED\t" + type + "\t" + count));
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
    }
    private static void addAttack(ConfigManager manager, String weapon, int activity,
            ConfigReference<AttackConfig> reference, List<AttackFire> entries) throws Exception {
        if (reference == null) return;
        AttackConfig.Original attack = AttackConfig.b(manager, reference);
        if (attack == null) throw new IOException("Unresolved stock attack " + reference.getName() + " for " + weapon);
        StarterCatalog.Model attackModel = model(reference);
        var refs = new ArrayList<FireRef>();
        if (attack.fireRefs != null) for (FireActionConfig.FireActionReference fireRef : attack.fireRefs) {
            if (fireRef == null || fireRef.ref == null) {
                UNSUPPORTED.merge("null-fire-reference",1,Integer::sum);
                continue;
            }
            FireActionConfig fire = manager.a(FireActionConfig.class, fireRef.ref);
            if (fire == null) throw new IOException("Unresolved fire action " + fireRef.ref.getName());
            Action action = readAction(manager, fire.action);
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

    private static Action readAction(ConfigManager manager, ActionConfig action) throws Exception {
        if (action == null) return null;
        actionCount++;
        if (action instanceof DungeonActionConfig.FireAction fireAction) {
            FireActionConfig fire = fireAction.fireAction == null ? null : manager.a(FireActionConfig.class, fireAction.fireAction);
            if (fire == null) throw new IOException("Unresolved nested fire action " + fireAction.fireAction);
            String name = fireAction.fireAction.getName();
            if (!NESTED_FIRES.add(name)) { UNSUPPORTED.merge("nested-fire-cycle",1,Integer::sum); return unsupported(action); }
            try { return readAction(manager, fire.action); } finally { NESTED_FIRES.remove(name); }
        }
        if (action instanceof DungeonActionConfig.FirePrefired firePrefired) {
            return new Action(Kind.FIRE_EFFECT, model(firePrefired.effect), null, target(firePrefired.location), "", "",
                0,0,0,0,false,0,0,0,0,false,List.of(),"");
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
            return new Action(kind,null,spec,target(spawn.location),facing,collision,x,y,rotation,rotationVariance,relative,count,step,steps,mask,false,List.of(),"");
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
        return new Action(kind,null,spec,target(spawn.location),"","",x,y,spawn.rotation,spawn.rotationVariance,
            spawn.relative,0,0,0,0,false,List.of(),"",parameter,values);
    }
    private static Action noop() {
        return new Action(Kind.COMPOUND,null,null,"","","",0,0,0,0,false,0,0,0,0,false,List.of(),"");
    }
    private static Action unsupported(ActionConfig action) throws IOException {
        String type=action.getClass().getName();
        UNSUPPORTED.merge(type,1,Integer::sum);
        return new Action(Kind.UNSUPPORTED,null,null,"","","",0,0,0,0,false,0,0,0,0,false,List.of(),type);
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
        else if(actor instanceof DungeonActorConfig.Bullet bullet){type="BULLET";fuse=bullet.fuse;speed=bullet.speed;ox=bullet.offset==null?0:bullet.offset.x;oy=bullet.offset==null?0:bullet.offset.y;height=bullet.height;if(NESTED_ACTORS.add(refName)){try{detonation=readAction(manager,bullet.detonationAction);}finally{NESTED_ACTORS.remove(refName);}}else UNSUPPORTED.merge("recursive-actor-detonation",1,Integer::sum);}
        else if(actor instanceof DungeonActorConfig.Bomb bomb){type="BOMB";fuse=bomb.fuse;if(NESTED_ACTORS.add(refName)){try{detonation=readAction(manager,bomb.detonationAction);}finally{NESTED_ACTORS.remove(refName);}}else UNSUPPORTED.merge("recursive-actor-detonation",1,Integer::sum);}
        else {type=actor.getClass().getName();UNSUPPORTED.merge("actor:"+type,1,Integer::sum);}
        com.threerings.tudey.data.actor.Actor nativeActor=actor.a(reference,0,0,new Vector2f(),0f);
        nativeActor.init(manager);
        int flags=intField(nativeActor,"_flags");
        if(nativeActor instanceof com.threerings.tudey.data.actor.Mobile)maxStep=floatField(nativeActor,"_maxStep");
        return new ActorSpec(type,model(reference),fuse,speed,maxStep,flags,ox,oy,height,detonation,orbit);
    }
    private static boolean isProjectile(String type) { return type.equals("BULLET") || type.equals("BOMB") || type.equals("ORBITAL_BULLET") || type.equals("COMPOUND_ORBITAL_BULLET"); }
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

    private static StarterCatalog.Model model(ConfigReference<?> reference) throws IOException {
        if (reference == null) return null;
        return new StarterCatalog.Model(reference.getName(),arguments(reference.kW(),0,new IdentityHashMap<>(),new int[]{0}));
    }
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
