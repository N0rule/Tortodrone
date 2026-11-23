package org.tortodrone.core.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.tortodrone.core.character.StarterCatalog;

/** Numeric/reference-only stock action graphs for player weapon attacks. */
public final class FireCatalog {
    static final int MAX_ENTRIES = 100_000;
    public enum Kind { COMPOUND, CONDITIONAL, DELAYED, MULTI_PRESPAWNED, RANDOM_EQUAL, RANDOM_WEIGHTED, SPAWN_ACTOR, SPAWN_PICKUP, SPAWN_SCENE_ACTOR, SPAWN_FACING, RANDOM_TRANSLATED, FIRE_EFFECT, EMIT_PROJECTILE, DAMAGE_REGION, SWEEP_DAMAGE, IMPULSE_REGION, DESTROY_SOURCE, HEAL, CLEAR_STATUS, APPLY_STATUS, START_SPRITE_SHIELD, END_SPRITE_SHIELD, FAIL, UNSUPPORTED }
    public enum NullValue { INSTANCE }
    public record EnumValue(String type, String name, int ordinal) {
        public EnumValue(String type,String name){this(type,name,-1);}
        public EnumValue { text(type,512); text(name,256); if(ordinal < -1 || ordinal > 1_000_000)throw new IllegalArgumentException("Invalid enum ordinal"); }
    }
    public record FieldValue(String name,String type,Object value) {
        public FieldValue { text(name,512); text(type,512); }
    }
    public record DataValue(String type, List<FieldValue> fields) {
        public DataValue(String type,Map<String,Object> fields){this(type,fields.entrySet().stream().map(e->new FieldValue(e.getKey(),"java.lang.Object",e.getValue())).toList());}
        public DataValue { text(type,512); fields=List.copyOf(fields); if(fields.size()>4096)throw new IllegalArgumentException("Too many streamed fields"); }
    }
    public record ListValue(String componentType, List<Object> values) { public ListValue { text(componentType,512); values=List.copyOf(values); } }
    public record MapValue(Map<String,Object> values) { public MapValue { values=Map.copyOf(values); } }
    public record OrbitSpec(float radius, float angularSpeed, float startingRotation, float flightSpeed,
            int outDuration, int lifespan, String follow, boolean alternateSpin,
            Float secondOrbitRadius, Float secondSpeed, Float secondStartingRotation) {
        public OrbitSpec {
            finite(radius); finite(angularSpeed); finite(startingRotation); finite(flightSpeed);
            time(outDuration); time(lifespan); text(follow, 128);
            boolean compound = secondOrbitRadius != null || secondSpeed != null || secondStartingRotation != null;
            if (compound && (secondOrbitRadius == null || secondSpeed == null || secondStartingRotation == null))
                throw new IllegalArgumentException("Incomplete compound orbit metadata");
            if (compound) { finite(secondOrbitRadius); finite(secondSpeed); finite(secondStartingRotation); }
        }
        public boolean compound() { return secondOrbitRadius != null; }
    }
    public record ActorTimer(int delay,int interval,int limit,Action action,Action endAction) {
        public ActorTimer { time(delay);time(interval);if(limit<1||limit>128||interval==0&&limit!=1)throw new IllegalArgumentException("Invalid actor timer limit");Objects.requireNonNull(action); }
    }
    public record ActorSpec(String nativeType, StarterCatalog.Model config, int fuse, float speed,
            float maxStep, int flags, float offsetX, float offsetY, float height, Action detonation, OrbitSpec orbit,int collisionMask,Object collisionShape,List<ActorTimer> timers,Action shutdown,
            Action startup, ActorSpec attachment, Action activation, boolean activatesAttachments) {
        public ActorSpec(String nativeType,StarterCatalog.Model config,int fuse,float speed,float maxStep,int flags,float offsetX,float offsetY,float height,Action detonation,OrbitSpec orbit,int collisionMask,Object collisionShape,List<ActorTimer> timers,Action shutdown){
            this(nativeType,config,fuse,speed,maxStep,flags,offsetX,offsetY,height,detonation,orbit,collisionMask,collisionShape,timers,shutdown,null,null,null,false);
        }
        public ActorSpec(String nativeType,StarterCatalog.Model config,int fuse,float speed,float maxStep,int flags,float offsetX,float offsetY,float height,Action detonation,OrbitSpec orbit,int collisionMask,Object collisionShape,List<ActorTimer> timers){
            this(nativeType,config,fuse,speed,maxStep,flags,offsetX,offsetY,height,detonation,orbit,collisionMask,collisionShape,timers,null);
        }
        public ActorSpec(String nativeType,StarterCatalog.Model config,int fuse,float speed,float maxStep,int flags,float offsetX,float offsetY,float height,Action detonation,OrbitSpec orbit,int collisionMask,Object collisionShape){
            this(nativeType,config,fuse,speed,maxStep,flags,offsetX,offsetY,height,detonation,orbit,collisionMask,collisionShape,List.of());
        }
        public ActorSpec(String nativeType, StarterCatalog.Model config, int fuse, float speed,float maxStep,int flags,float offsetX,float offsetY,float height,Action detonation,OrbitSpec orbit){
            this(nativeType,config,fuse,speed,maxStep,flags,offsetX,offsetY,height,detonation,orbit,43,null);
        }
        public ActorSpec(String nativeType, StarterCatalog.Model config, int fuse, float speed,
                float maxStep, int flags, float offsetX, float offsetY, float height, Action detonation) {
            this(nativeType, config, fuse, speed, maxStep, flags, offsetX, offsetY, height, detonation, null);
        }
        public ActorSpec {
            text(nativeType, 128); Objects.requireNonNull(config); time(fuse); finite(speed); finite(maxStep);
            finite(offsetX); finite(offsetY); finite(height);
            timers=List.copyOf(timers);if(timers.size()>64)throw new IllegalArgumentException("Too many actor timers");
            if ((nativeType.equals("ORBITAL") || nativeType.equals("ORBITAL_BULLET") || nativeType.equals("COMPOUND_ORBITAL_BULLET")) != (orbit != null))
                throw new IllegalArgumentException("Orbital native actor metadata mismatch");
            if (orbit != null && orbit.compound() != nativeType.equals("COMPOUND_ORBITAL_BULLET"))
                throw new IllegalArgumentException("Compound orbital metadata mismatch");
        }
    }
    public record Action(Kind kind, StarterCatalog.Model config, ActorSpec actor, String targetType,
            String facingType, String collisionSourceType,
            float x, float y, float rotation, float rotationVariance, boolean relative, int count, float stepSize, int steps,
            int collisionMask, boolean stopOnFailure, List<Action> children, String unsupportedType,
            String parameter, List<String> values) {
        public Action(Kind kind, StarterCatalog.Model config, ActorSpec actor, String targetType,
                String facingType, String collisionSourceType, float x, float y, float rotation, float rotationVariance,
                boolean relative, int count, float stepSize, int steps, int collisionMask, boolean stopOnFailure,
                List<Action> children, String unsupportedType) {
            this(kind, config, actor, targetType, facingType, collisionSourceType, x, y, rotation, rotationVariance,
                relative, count, stepSize, steps, collisionMask, stopOnFailure, children, unsupportedType, "", List.of());
        }
        public Action {
            Objects.requireNonNull(kind); targetType = targetType == null ? "" : plainText(targetType, 128);
            facingType = facingType == null ? "" : plainText(facingType, 128);
            collisionSourceType = collisionSourceType == null ? "" : plainText(collisionSourceType, 128);
            finite(x); finite(y); finite(rotation); finite(rotationVariance); finite(stepSize);
            if (count < 0 || count > 10_000 || steps < 0 || steps > 10_000) throw new IllegalArgumentException("Invalid action count");
            children = List.copyOf(children);
            if (children.size() > 10_000) throw new IllegalArgumentException("Too many action children");
            unsupportedType = unsupportedType == null ? "" : plainText(unsupportedType, 256);
            parameter = parameter == null ? "" : plainText(parameter, 1024);
            values = List.copyOf(values);
            if (values.size() > 10_000 || values.stream().anyMatch(value -> value == null || value.length() > 65536 || value.chars().anyMatch(c -> c < 32)))
                throw new IllegalArgumentException("Invalid action argument values");
            if (kind == Kind.EMIT_PROJECTILE && actor == null) throw new IllegalArgumentException("Projectile action has no actor");
            if (kind == Kind.SPAWN_SCENE_ACTOR && (config == null || !config.name().equals("SceneActorTemplate")
                || !config.arguments().keySet().equals(Set.of("template")) || !(config.arguments().get("template") instanceof String id)
                || !id.matches("spawn_[0-9]{1,3}") || !targetType.equals("Source") || actor != null || !children.isEmpty()
                || !facingType.isEmpty() || !collisionSourceType.isEmpty() || x!=0 || y!=0 || rotation!=0 || rotationVariance!=0
                || count!=0 || steps!=0 || stepSize!=0 || collisionMask!=0 || !parameter.isEmpty() || !values.isEmpty()))
                throw new IllegalArgumentException("Invalid deferred scene actor spawn");
            if (kind == Kind.UNSUPPORTED && unsupportedType.isBlank()) throw new IllegalArgumentException("Unsupported action has no type");
        }
    }
    public record FireRef(StarterCatalog.Model config, int delay, int refractory, boolean advance, int maxLoop,
            int successIncrement, int incrementReduction, Action action, Action endAction) {
        public FireRef { Objects.requireNonNull(config); time(delay); time(refractory); if (maxLoop < 0 || maxLoop > 10_000) throw new IllegalArgumentException("Invalid fire loop"); Objects.requireNonNull(action); }
    }
    public record AttackFire(String weaponConfig, int activity, StarterCatalog.Model attack, float fireSpeed,
            List<FireRef> fireRefs) {
        public AttackFire { text(weaponConfig, 512); if (!activityValid(activity)) throw new IllegalArgumentException("Invalid weapon activity"); Objects.requireNonNull(attack); if (!Float.isFinite(fireSpeed)||fireSpeed<=0||fireSpeed>100)throw new IllegalArgumentException("Invalid attack fire speed"); fireRefs = List.copyOf(fireRefs); }
    }

    private final Map<String, AttackFire> attacks;
    private final Map<String, Integer> unsupported;
    private final Map<StarterCatalog.Model,Action> detonations;
    public FireCatalog(Collection<AttackFire> entries, Map<String, Integer> unsupported) {
        if (entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many fire entries");
        var map = new TreeMap<String, AttackFire>();
        for (AttackFire entry : entries) if (map.put(key(entry.weaponConfig(), entry.activity()), entry) != null)
            throw new IllegalArgumentException("Duplicate fire entry");
        this.attacks = Map.copyOf(map); this.unsupported = Map.copyOf(unsupported);
        var actions=new HashMap<StarterCatalog.Model,Action>();
        for(var attack:entries)for(var ref:attack.fireRefs()){indexDetonations(ref.action(),actions);indexDetonations(ref.endAction(),actions);}
        detonations=Map.copyOf(actions);
    }
    public static FireCatalog empty() { return new FireCatalog(List.of(), Map.of()); }
    public AttackFire find(String weaponConfig, int activity) { return attacks.get(key(weaponConfig, activity)); }
    public List<AttackFire> entries() { return attacks.values().stream().sorted(Comparator.comparing(AttackFire::weaponConfig).thenComparingInt(AttackFire::activity)).toList(); }
    public Map<String,Integer> unsupportedTypes() { return unsupported; }
    /** Shared bounded encoding for config references in prepared scene metadata. */
    public static String modelJson(StarterCatalog.Model value)throws IOException{return FireCatalogCodec.modelJson(value);}
    public static StarterCatalog.Model modelJson(String value)throws IOException{return FireCatalogCodec.modelJson(value);}
    public Action detonation(ActorSpec actor){return actor.detonation()!=null?actor.detonation():detonations.get(actor.config());}
    private static void indexDetonations(Action action,Map<StarterCatalog.Model,Action> result){
        if(action==null)return;
        if(action.actor()!=null){if(action.actor().detonation()!=null){result.putIfAbsent(action.actor().config(),action.actor().detonation());indexDetonations(action.actor().detonation(),result);}
            indexDetonations(action.actor().shutdown(),result);
            for(var timer:action.actor().timers()){indexDetonations(timer.action(),result);indexDetonations(timer.endAction(),result);}}
        for(var child:action.children())indexDetonations(child,result);
    }

    /** Write the prepared catalogue; the codec owns ordering, versioning and limits. */
    public static void write(Path file, Collection<AttackFire> entries, Map<String,Integer> unsupported) throws IOException {
        FireCatalogCodec.write(file, entries, unsupported);
    }

    /** Read a prepared catalogue, including the bounded legacy version-1 shape. */
    public static FireCatalog load(Path file) throws IOException {
        return FireCatalogCodec.load(file);
    }

    public static String actionJson(Action action)throws IOException{return FireCatalogCodec.actionJson(action);}
    public static Action actionJson(String json)throws IOException{return FireCatalogCodec.actionJson(json);}
    /** One streamed native value in the same tagged JSON shape used inside action graphs. */
    public static String valueJson(Object value)throws IOException{return FireCatalogCodec.valueJson(value);}
    public static Object valueJson(String json)throws IOException{return FireCatalogCodec.valueJson(json);}

    private static void time(int n){if(n<0||n>600_000)throw new IllegalArgumentException("Invalid fire time");}
    private static void finite(float n){if(!Float.isFinite(n)||Math.abs(n)>1_000_000)throw new IllegalArgumentException("Invalid fire number");}
    private static String text(String s,int max){if(s==null||s.isBlank()||s.length()>max||s.chars().anyMatch(c->c<32))throw new IllegalArgumentException("Invalid fire text");return s;}
    private static String plainText(String s,int max){if(s==null||s.length()>max||s.chars().anyMatch(c->c<32))throw new IllegalArgumentException("Invalid fire text");return s;}
    private static String key(String weapon,int activity){text(weapon,512);if(!activityValid(activity))throw new IllegalArgumentException("Invalid activity");return weapon.toLowerCase(Locale.ROOT)+"\0"+activity;}
    private static boolean activityValid(int a){return a==284||a==287||a>=289&&a<=291||a>=292&&a<324;}
}
