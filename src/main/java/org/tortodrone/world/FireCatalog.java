package org.tortodrone.world;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.resources.Json;

/** Numeric/reference-only stock action graphs for player weapon attacks. */
public final class FireCatalog {
    private static final int MAX_BYTES = 64 * 1024 * 1024, MAX_ENTRIES = 100_000, MAX_NODES = 500_000;
    public enum Kind { COMPOUND, CONDITIONAL, MULTI_PRESPAWNED, RANDOM_EQUAL, SPAWN_ACTOR, SPAWN_FACING, RANDOM_TRANSLATED, FIRE_EFFECT, EMIT_PROJECTILE, DAMAGE_REGION, SWEEP_DAMAGE, IMPULSE_REGION, DESTROY_SOURCE, UNSUPPORTED }
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
            float maxStep, int flags, float offsetX, float offsetY, float height, Action detonation, OrbitSpec orbit,int collisionMask,Object collisionShape,List<ActorTimer> timers,Action shutdown) {
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
            if ((nativeType.equals("ORBITAL_BULLET") || nativeType.equals("COMPOUND_ORBITAL_BULLET")) != (orbit != null))
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
    public static String modelJson(StarterCatalog.Model value)throws IOException{return Json.stringify(model(value,0,new int[]{0}),128);}
    public static StarterCatalog.Model modelJson(String value)throws IOException{return readModel(Json.parse(value,128),0,new int[]{0});}
    public Action detonation(ActorSpec actor){return actor.detonation()!=null?actor.detonation():detonations.get(actor.config());}
    private static void indexDetonations(Action action,Map<StarterCatalog.Model,Action> result){
        if(action==null)return;
        if(action.actor()!=null){if(action.actor().detonation()!=null){result.putIfAbsent(action.actor().config(),action.actor().detonation());indexDetonations(action.actor().detonation(),result);}
            indexDetonations(action.actor().shutdown(),result);
            for(var timer:action.actor().timers()){indexDetonations(timer.action(),result);indexDetonations(timer.endAction(),result);}}
        for(var child:action.children())indexDetonations(child,result);
    }

    public static void write(Path file, Collection<AttackFire> entries, Map<String,Integer> unsupported) throws IOException {
        if (entries.size() > MAX_ENTRIES || unsupported.size() > 4096) throw new IOException("Fire catalogue exceeds limits");
        var rows = new ArrayList<Object>(); int[] nodes = {0};
        for (AttackFire e : entries.stream().sorted(Comparator.comparing(AttackFire::weaponConfig).thenComparingInt(AttackFire::activity)).toList()) {
            var row = new LinkedHashMap<String,Object>(); row.put("weapon", e.weaponConfig()); row.put("activity", e.activity());
            row.put("attack", model(e.attack(), 0, nodes)); row.put("fireSpeed",e.fireSpeed()); var refs = new ArrayList<Object>();
            for (FireRef f : e.fireRefs()) {
                var ref = new LinkedHashMap<String,Object>(); ref.put("config",model(f.config(),0,nodes)); ref.put("delay",f.delay()); ref.put("refractory",f.refractory()); ref.put("advance",f.advance()); ref.put("maxLoop",f.maxLoop());
                ref.put("successIncrement",f.successIncrement()); ref.put("incrementReduction",f.incrementReduction()); ref.put("action",action(f.action(),0,nodes)); ref.put("endAction",actionOrNull(f.endAction(),0,nodes)); refs.add(ref);
            }
            row.put("fireRefs", refs); rows.add(row);
        }
        var root = new LinkedHashMap<String,Object>(); root.put("format","tortodrone-fires"); root.put("version",4); root.put("entries",rows);
        root.put("unsupported",new TreeMap<>(unsupported));
        Files.createDirectories(file.toAbsolutePath().normalize().getParent());
        Files.writeString(file, Json.stringify(root,128), StandardOpenOption.CREATE_NEW);
    }
    public static FireCatalog load(Path file) throws IOException {
        if (Files.size(file) > MAX_BYTES) throw new IOException("Fire catalogue exceeds size limit");
        try {
            var root = object(Json.parse(Files.readString(file),128), Set.of("format","version","entries","unsupported"));
            int version=integer(root.get("version")); boolean legacy=version==1;
            if (!"tortodrone-fires".equals(root.get("format")) || version < 1 || version > 4) throw new IOException("Unsupported fire catalogue");
            if (!(root.get("entries") instanceof List<?> rows) || rows.size() > MAX_ENTRIES) throw new IOException("Invalid fire entries");
            var entries = new ArrayList<AttackFire>(); int[] nodes = {0};
            for (Object value : rows) {
                var row = object(value, Set.of("weapon","activity","attack","fireSpeed","fireRefs"));
                if (!(row.get("fireRefs") instanceof List<?> refs) || refs.size() > 1024) throw new IOException("Invalid fire references");
                var fireRefs = new ArrayList<FireRef>();
                for (Object refValue : refs) {
                    var ref = object(refValue, Set.of("config","delay","refractory","advance","maxLoop","successIncrement","incrementReduction","action","endAction"));
                    fireRefs.add(new FireRef(readModel(ref.get("config"),0,nodes),integer(ref.get("delay")),integer(ref.get("refractory")),bool(ref.get("advance")),integer(ref.get("maxLoop")),
                        integer(ref.get("successIncrement")),integer(ref.get("incrementReduction")),readAction(ref.get("action"),0,nodes,legacy),readActionOrNull(ref.get("endAction"),0,nodes,legacy)));
                }
                entries.add(new AttackFire(text(row.get("weapon"),512),integer(row.get("activity")),readModel(row.get("attack"),0,nodes),decimal(row.get("fireSpeed")),fireRefs));
            }
            var unsupported = new TreeMap<String,Integer>();
            if (!(root.get("unsupported") instanceof Map<?,?> u) || u.size() > 4096) throw new IOException("Invalid unsupported action report");
            for (var e : u.entrySet()) { String k=text(e.getKey(),256); int n=integer(e.getValue()); if(n<0||n>MAX_NODES||unsupported.put(k,n)!=null)throw new IOException("Invalid unsupported count"); }
            return new FireCatalog(entries, unsupported);
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid fire catalogue", invalid); }
    }

    private static Object action(Action a,int depth,int[] n)throws IOException{
        node(depth,n); var m=new LinkedHashMap<String,Object>();m.put("kind",a.kind().name());m.put("config",a.config()==null?null:model(a.config(),depth+1,n));m.put("actor",a.actor()==null?null:actor(a.actor(),depth+1,n));
        m.put("targetType",a.targetType());m.put("facingType",a.facingType());m.put("collisionSourceType",a.collisionSourceType());m.put("x",a.x());m.put("y",a.y());m.put("rotation",a.rotation());m.put("rotationVariance",a.rotationVariance());m.put("relative",a.relative());m.put("count",a.count());m.put("stepSize",a.stepSize());m.put("steps",a.steps());m.put("collisionMask",a.collisionMask());m.put("stopOnFailure",a.stopOnFailure());
        var children=new ArrayList<Object>();for(Action child:a.children())children.add(action(child,depth+1,n));m.put("children",children);m.put("unsupportedType",a.unsupportedType());m.put("parameter",a.parameter());m.put("values",a.values());return m;
    }
    public static String actionJson(Action action)throws IOException{return Json.stringify(action(action,0,new int[]{0}),128);}
    public static Action actionJson(String json)throws IOException{return readAction(Json.parse(json,128),0,new int[]{0},false);}
    private static Object orbit(OrbitSpec o)throws IOException{if(o==null)return null;var m=new LinkedHashMap<String,Object>();m.put("radius",o.radius());m.put("angularSpeed",o.angularSpeed());m.put("startingRotation",o.startingRotation());m.put("flightSpeed",o.flightSpeed());m.put("outDuration",o.outDuration());m.put("lifespan",o.lifespan());m.put("follow",o.follow());m.put("alternateSpin",o.alternateSpin());m.put("secondOrbitRadius",o.secondOrbitRadius());m.put("secondSpeed",o.secondSpeed());m.put("secondStartingRotation",o.secondStartingRotation());return m;}
    private static Object actor(ActorSpec a,int depth,int[] n)throws IOException{node(depth,n);var m=new LinkedHashMap<String,Object>();m.put("nativeType",a.nativeType());m.put("config",model(a.config(),depth+1,n));m.put("fuse",a.fuse());m.put("speed",a.speed());m.put("maxStep",a.maxStep());m.put("flags",a.flags());m.put("offsetX",a.offsetX());m.put("offsetY",a.offsetY());m.put("height",a.height());m.put("detonation",actionOrNull(a.detonation(),depth+1,n));m.put("orbit",orbit(a.orbit()));m.put("collisionMask",a.collisionMask());m.put("collisionShape",a.collisionShape()==null?null:value(a.collisionShape(),depth+1,n));var timers=new ArrayList<Object>();for(var timer:a.timers()){var t=new LinkedHashMap<String,Object>();t.put("delay",timer.delay());t.put("interval",timer.interval());t.put("limit",timer.limit());t.put("action",action(timer.action(),depth+1,n));t.put("endAction",actionOrNull(timer.endAction(),depth+1,n));timers.add(t);}m.put("timers",timers);m.put("shutdown",actionOrNull(a.shutdown(),depth+1,n));return m;}
    private static Object actionOrNull(Action a,int d,int[] n)throws IOException{return a==null?null:action(a,d,n);}
    private static Object model(StarterCatalog.Model m,int depth,int[] n)throws IOException{node(depth,n);return Map.of("name",m.name(),"arguments",values(m.arguments(),depth+1,n));}
    private static Map<String,Object> values(Map<String,Object> args,int d,int[] n)throws IOException {
        if(d>96||args.size()>4096)throw new IOException("Fire model arguments exceed limits ("+args.size()+" keys, depth "+d+")");
        var out=new TreeMap<String,Object>();for(var e:args.entrySet()){node(d,n);out.put(text(e.getKey(),1024),value(e.getValue(),d+1,n));}return out;
    }
    private static Object value(Object v,int d,int[] n)throws IOException {
        if(v==NullValue.INSTANCE)return Map.of("type","null");if(v instanceof String||v instanceof Integer||v instanceof Boolean)return v;
        if(v instanceof Long l)return Map.of("type","long","value",l);if(v instanceof BigDecimal b)return Map.of("type","decimal","value",b);
        if(v instanceof Float f&&Float.isFinite(f))return Map.of("type","float","value",f);
        if(v instanceof StarterCatalog.Model m)return Map.of("type","model","value",model(m,d+1,n));
        if(v instanceof StarterCatalog.Colorization c)return Map.of("type","colorization","value",c.value());
        if(v instanceof StarterCatalog.TransColorization c)return Map.of("type","transColorization","class",c.clazz(),"value",c.value());
        if(v instanceof EnumValue e)return Map.of("type","enum","class",e.type(),"name",e.name(),"ordinal",e.ordinal());
        if(v instanceof DataValue o){var fields=new ArrayList<Object>();for(FieldValue f:o.fields())fields.add(Map.of("name",f.name(),"fieldType",f.type(),"value",value(f.value(),d+1,n)));return Map.of("type","object","class",o.type(),"fields",fields);}
        if(v instanceof ListValue l)return Map.of("type","list","component",l.componentType(),"values",list(l.values(),d+1,n));
        if(v instanceof MapValue m)return Map.of("type","map","values",values(m.values(),d+1,n));
        throw new IOException("Unsupported stock model argument type");
    }
    private static List<Object> list(List<Object> values,int d,int[] n)throws IOException {
        if(values.size()>10_000)throw new IOException("Fire list exceeds limits");
        var out=new ArrayList<Object>();for(Object v:values){node(d,n);out.add(value(v,d+1,n));}return out;
    }
    private static Action readAction(Object v,int d,int[] n,boolean legacy)throws IOException{node(d,n);if(v==null)return null;v=defaults(v,legacy,Map.of("parameter","","values",List.of()));var m=object(v,Set.of("kind","config","actor","targetType","facingType","collisionSourceType","x","y","rotation","rotationVariance","relative","count","stepSize","steps","collisionMask","stopOnFailure","children","unsupportedType","parameter","values"));if(!(m.get("children") instanceof List<?> list)||list.size()>10_000||!(m.get("values") instanceof List<?> rawValues)||rawValues.size()>10_000)throw new IOException("Invalid action children or argument values");var children=new ArrayList<Action>();for(Object c:list)children.add(readAction(c,d+1,n,legacy));var values=new ArrayList<String>();for(Object item:rawValues)values.add(plainText(item,65536));return new Action(Kind.valueOf(text(m.get("kind"),64)),readModelOrNull(m.get("config"),d+1,n),readActorOrNull(m.get("actor"),d+1,n,legacy),plainText(m.get("targetType"),128),plainText(m.get("facingType"),128),plainText(m.get("collisionSourceType"),128),decimal(m.get("x")),decimal(m.get("y")),decimal(m.get("rotation")),decimal(m.get("rotationVariance")),bool(m.get("relative")),integer(m.get("count")),decimal(m.get("stepSize")),integer(m.get("steps")),integer(m.get("collisionMask")),bool(m.get("stopOnFailure")),children,plainText(m.get("unsupportedType"),256),plainText(m.get("parameter"),1024),values);}
    private static Action readActionOrNull(Object v,int d,int[] n,boolean legacy)throws IOException{return v==null?null:readAction(v,d,n,legacy);}
    private static ActorSpec readActorOrNull(Object v,int d,int[] n,boolean legacy)throws IOException{if(v==null)return null;node(d,n);v=defaults(v,legacy,Collections.singletonMap("orbit",null));var extra=new HashMap<String,Object>();extra.put("collisionMask",43);extra.put("collisionShape",null);extra.put("timers",List.of());extra.put("shutdown",null);v=defaults(v,true,extra);var m=object(v,Set.of("nativeType","config","fuse","speed","maxStep","flags","offsetX","offsetY","height","detonation","orbit","collisionMask","collisionShape","timers","shutdown"));return new ActorSpec(text(m.get("nativeType"),128),readModel(m.get("config"),d+1,n),integer(m.get("fuse")),decimal(m.get("speed")),decimal(m.get("maxStep")),integer(m.get("flags")),decimal(m.get("offsetX")),decimal(m.get("offsetY")),decimal(m.get("height")),readActionOrNull(m.get("detonation"),d+1,n,legacy),readOrbit(m.get("orbit")),integer(m.get("collisionMask")),m.get("collisionShape")==null?null:readValue(m.get("collisionShape"),d+1,n),readTimers(m.get("timers"),d+1,n,legacy),readActionOrNull(m.get("shutdown"),d+1,n,legacy));}
    private static List<ActorTimer> readTimers(Object v,int d,int[] n,boolean legacy)throws IOException {
        if(!(v instanceof List<?> rows)||rows.size()>64)throw new IOException("Invalid actor timers");
        var result=new ArrayList<ActorTimer>();for(Object row:rows){node(d,n);var m=object(row,Set.of("delay","interval","limit","action","endAction"));result.add(new ActorTimer(integer(m.get("delay")),integer(m.get("interval")),integer(m.get("limit")),readAction(m.get("action"),d+1,n,legacy),readActionOrNull(m.get("endAction"),d+1,n,legacy)));}return result;
    }
    private static OrbitSpec readOrbit(Object v)throws IOException{if(v==null)return null;var m=object(v,Set.of("radius","angularSpeed","startingRotation","flightSpeed","outDuration","lifespan","follow","alternateSpin","secondOrbitRadius","secondSpeed","secondStartingRotation"));boolean compound=m.get("secondOrbitRadius")!=null;Float radius=compound?decimal(m.get("secondOrbitRadius")):null;Float speed=compound?decimal(m.get("secondSpeed")):null;Float rotation=compound?decimal(m.get("secondStartingRotation")):null;if(!compound&&(m.get("secondSpeed")!=null||m.get("secondStartingRotation")!=null))throw new IOException("Incomplete compound orbit metadata");return new OrbitSpec(decimal(m.get("radius")),decimal(m.get("angularSpeed")),decimal(m.get("startingRotation")),decimal(m.get("flightSpeed")),integer(m.get("outDuration")),integer(m.get("lifespan")),text(m.get("follow"),128),bool(m.get("alternateSpin")),radius,speed,rotation);}
    private static StarterCatalog.Model readModelOrNull(Object v,int d,int[] n)throws IOException{return v==null?null:readModel(v,d,n);}
    private static StarterCatalog.Model readModel(Object v,int d,int[] n) throws IOException {node(d,n);var m=object(v,Set.of("name","arguments"));if(!(m.get("arguments") instanceof Map<?,?> args)||args.size()>4096)throw new IOException("Invalid fire arguments");var out=new TreeMap<String,Object>();for(var e:args.entrySet())out.put(text(e.getKey(),1024),readValue(e.getValue(),d+1,n));return new StarterCatalog.Model(text(m.get("name"),1024),out);}
    private static Object readValue(Object v,int d,int[] n)throws IOException {
        node(d,n);if(v instanceof String)return plainText(v,65536);if(v instanceof Boolean)return v;
        if(v instanceof BigDecimal b){try{return b.intValueExact();}catch(ArithmeticException ignored){try{return b.longValueExact();}catch(ArithmeticException noLong){return b;}}}
        if(v instanceof Map<?,?>){String type=text(((Map<?,?>)v).get("type"),32);return switch(type){
            case "null"->{object(v,Set.of("type"));yield NullValue.INSTANCE;}
            case "float"->{var f=object(v,Set.of("type","value"));yield decimal(f.get("value"));}
            case "long"->{var f=object(v,Set.of("type","value"));yield longInteger(f.get("value"));}
            case "decimal"->{var f=object(v,Set.of("type","value"));if(!(f.get("value") instanceof BigDecimal b))throw new IOException("Invalid decimal");yield b;}
            case "model"->{var f=object(v,Set.of("type","value"));yield readModel(f.get("value"),d+1,n);}
            case "colorization"->{var f=object(v,Set.of("type","value"));yield new StarterCatalog.Colorization(integer(f.get("value")));}
            case "transColorization"->{var f=object(v,Set.of("type","class","value"));yield new StarterCatalog.TransColorization(integer(f.get("class")),integer(f.get("value")));}
            case "enum"->{var f=object(v,Set.of("type","class","name","ordinal"));yield new EnumValue(text(f.get("class"),512),text(f.get("name"),256),integer(f.get("ordinal")));}
            case "object"->{var f=object(v,Set.of("type","class","fields"));if(!(f.get("fields") instanceof List<?> fields)||fields.size()>4096)throw new IOException("Invalid streamed fields");var result=new ArrayList<FieldValue>();for(Object field:fields){var fm=object(field,Set.of("name","fieldType","value"));result.add(new FieldValue(text(fm.get("name"),512),text(fm.get("fieldType"),512),readValue(fm.get("value"),d+1,n)));}yield new DataValue(text(f.get("class"),512),result);}
            case "list"->{var f=object(v,Set.of("type","component","values"));if(!(f.get("values") instanceof List<?> l)||l.size()>10_000)throw new IOException("Invalid argument list");var vals=new ArrayList<Object>();for(Object item:l)vals.add(readValue(item,d+1,n));yield new ListValue(text(f.get("component"),512),vals);}
            case "map"->{var f=object(v,Set.of("type","values"));yield new MapValue(readValues(f.get("values"),d+1,n));}
            default->throw new IOException("Unknown fire model value");};}
        throw new IOException("Invalid fire model value");
    }
    private static Map<String,Object> readValues(Object v,int d,int[] n) throws IOException {
        if(!(v instanceof Map<?,?> map)||map.size()>4096)throw new IOException("Invalid argument map");
        var out=new TreeMap<String,Object>();for(var e:map.entrySet())out.put(text(e.getKey(),1024),readValue(e.getValue(),d+1,n));return out;
    }
    private static void node(int d,int[] n)throws IOException{if(d>96||++n[0]>MAX_NODES)throw new IOException("Fire graph exceeds limits");}
    private static Object defaults(Object value,boolean legacy,Map<String,Object> defaults){
        if(!legacy||!(value instanceof Map<?,?> map))return value;
        var copy=new LinkedHashMap<Object,Object>(map);for(var entry:defaults.entrySet())if(!copy.containsKey(entry.getKey()))copy.put(entry.getKey(),entry.getValue());return copy;
    }
    private static Map<?,?> object(Object v,Set<String> fields)throws IOException{if(!(v instanceof Map<?,?>m)||!m.keySet().equals(fields))throw new IOException("Invalid fire object fields");return m;}
    private static String text(Object v,int max)throws IOException{if(!(v instanceof String s)||s.isBlank()||s.length()>max||s.chars().anyMatch(c->c<32))throw new IOException("Invalid fire text");return s;}
    private static String plainText(Object v,int max)throws IOException{if(!(v instanceof String s)||s.length()>max||s.chars().anyMatch(c->c<32))throw new IOException("Invalid fire text");return s;}
    private static int integer(Object v)throws IOException{if(!(v instanceof BigDecimal n))throw new IOException("Invalid fire integer");try{return n.intValueExact();}catch(ArithmeticException e){throw new IOException("Invalid fire integer",e);}}
    private static long longInteger(Object v)throws IOException{if(!(v instanceof BigDecimal n))throw new IOException("Invalid fire integer");try{return n.longValueExact();}catch(ArithmeticException e){throw new IOException("Invalid fire integer",e);}}
    private static float decimal(Object v)throws IOException{if(v instanceof BigDecimal n){float f=n.floatValue();if(Float.isFinite(f))return f;}if(v instanceof Float f&&Float.isFinite(f))return f;throw new IOException("Invalid fire number");}
    private static boolean bool(Object v)throws IOException{if(v instanceof Boolean b)return b;throw new IOException("Invalid fire boolean");}
    private static void time(int n){if(n<0||n>600_000)throw new IllegalArgumentException("Invalid fire time");}
    private static void finite(float n){if(!Float.isFinite(n)||Math.abs(n)>1_000_000)throw new IllegalArgumentException("Invalid fire number");}
    private static String text(String s,int max){if(s==null||s.isBlank()||s.length()>max||s.chars().anyMatch(c->c<32))throw new IllegalArgumentException("Invalid fire text");return s;}
    private static String plainText(String s,int max){if(s==null||s.length()>max||s.chars().anyMatch(c->c<32))throw new IllegalArgumentException("Invalid fire text");return s;}
    private static String key(String weapon,int activity){text(weapon,512);if(!activityValid(activity))throw new IllegalArgumentException("Invalid activity");return weapon.toLowerCase(Locale.ROOT)+"\0"+activity;}
    private static boolean activityValid(int a){return a==284||a==287||a>=289&&a<=291||a>=292&&a<324;}
}
