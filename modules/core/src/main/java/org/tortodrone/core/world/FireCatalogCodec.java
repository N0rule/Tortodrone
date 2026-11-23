package org.tortodrone.core.world;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.Json;

/**
 * Bounded JSON codec for {@link FireCatalog}.
 *
 * <p>The runtime catalogue keeps only immutable attack/detonation indexes; this class owns the
 * prepared-artifact file format, the tagged JSON graph shape and every size/depth limit applied to
 * untrusted exported values. The shape is shared by prepared scene metadata and the catalogue
 * itself, so it stays in one place rather than in each consumer.
 */
public final class FireCatalogCodec {
    private static final int MAX_BYTES = 128 * 1024 * 1024, MAX_NODES = 2_000_000;

    private FireCatalogCodec() { }

    /** Shared bounded encoding for config references in prepared scene metadata. */
    public static String modelJson(StarterCatalog.Model value)throws IOException{return Json.stringify(model(value,0,new int[]{0}),128);}
    public static StarterCatalog.Model modelJson(String value)throws IOException{return readModel(Json.parse(value,128),0,new int[]{0});}

    public static void write(Path file, Collection<FireCatalog.AttackFire> entries, Map<String,Integer> unsupported) throws IOException {
        Files.createDirectories(file.toAbsolutePath().normalize().getParent());
        Files.writeString(file, document(entries, unsupported), StandardOpenOption.CREATE_NEW);
    }
    /** Same catalogue, gzip-compressed; prepared content keeps the large graphs compact. */
    public static void writeCompressed(Path file, Collection<FireCatalog.AttackFire> entries, Map<String,Integer> unsupported) throws IOException {
        Files.createDirectories(file.toAbsolutePath().normalize().getParent());
        try (var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(file, StandardOpenOption.CREATE_NEW))) {
            out.write(document(entries, unsupported).getBytes(StandardCharsets.UTF_8));
        }
    }
    private static String document(Collection<FireCatalog.AttackFire> entries, Map<String,Integer> unsupported) throws IOException {
        if (entries.size() > FireCatalog.MAX_ENTRIES || unsupported.size() > 4096) throw new IOException("Fire catalogue exceeds limits");
        var rows = new ArrayList<Object>(); int[] nodes = {0};
        for (FireCatalog.AttackFire e : entries.stream().sorted(Comparator.comparing(FireCatalog.AttackFire::weaponConfig).thenComparingInt(FireCatalog.AttackFire::activity)).toList()) {
            var row = new LinkedHashMap<String,Object>(); row.put("weapon", e.weaponConfig()); row.put("activity", e.activity());
            row.put("attack", model(e.attack(), 0, nodes)); row.put("fireSpeed",e.fireSpeed()); var refs = new ArrayList<Object>();
            for (FireCatalog.FireRef f : e.fireRefs()) {
                var ref = new LinkedHashMap<String,Object>(); ref.put("config",model(f.config(),0,nodes)); ref.put("delay",f.delay()); ref.put("refractory",f.refractory()); ref.put("advance",f.advance()); ref.put("maxLoop",f.maxLoop());
                ref.put("successIncrement",f.successIncrement()); ref.put("incrementReduction",f.incrementReduction()); ref.put("action",action(f.action(),0,nodes)); ref.put("endAction",actionOrNull(f.endAction(),0,nodes)); refs.add(ref);
            }
            row.put("fireRefs", refs); rows.add(row);
        }
        var root = new LinkedHashMap<String,Object>(); root.put("format","tortodrone-fires"); root.put("version",4); root.put("entries",rows);
        root.put("unsupported",new TreeMap<>(unsupported));
        return Json.stringify(root,128);
    }
    /** Read either a plain .json catalogue or its gzip-compressed .json.gz form. */
    private static String readCatalogue(Path file) throws IOException {
        if (Files.size(file) > MAX_BYTES) throw new IOException("Fire catalogue exceeds size limit");
        try (var raw = Files.newInputStream(file);
             var input = file.getFileName().toString().endsWith(".gz") ? new java.util.zip.GZIPInputStream(raw) : raw) {
            var bytes = new java.io.ByteArrayOutputStream();
            var buffer = new byte[8192]; int total = 0, count;
            while ((count = input.read(buffer)) >= 0) {
                total += count; if (total > MAX_BYTES) throw new IOException("Fire catalogue exceeds size limit");
                bytes.write(buffer, 0, count);
            }
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }
    public static FireCatalog load(Path file) throws IOException {
        try {
            var root = object(Json.parse(readCatalogue(file),128), Set.of("format","version","entries","unsupported"));
            int version=integer(root.get("version")); boolean legacy=version==1;
            if (!"tortodrone-fires".equals(root.get("format")) || version < 1 || version > 4) throw new IOException("Unsupported fire catalogue");
            if (!(root.get("entries") instanceof List<?> rows) || rows.size() > FireCatalog.MAX_ENTRIES) throw new IOException("Invalid fire entries");
            var entries = new ArrayList<FireCatalog.AttackFire>(); int[] nodes = {0};
            for (Object value : rows) {
                var row = object(value, Set.of("weapon","activity","attack","fireSpeed","fireRefs"));
                if (!(row.get("fireRefs") instanceof List<?> refs) || refs.size() > 1024) throw new IOException("Invalid fire references");
                var fireRefs = new ArrayList<FireCatalog.FireRef>();
                for (Object refValue : refs) {
                    var ref = object(refValue, Set.of("config","delay","refractory","advance","maxLoop","successIncrement","incrementReduction","action","endAction"));
                    fireRefs.add(new FireCatalog.FireRef(readModel(ref.get("config"),0,nodes),integer(ref.get("delay")),integer(ref.get("refractory")),bool(ref.get("advance")),integer(ref.get("maxLoop")),
                        integer(ref.get("successIncrement")),integer(ref.get("incrementReduction")),readAction(ref.get("action"),0,nodes,legacy),readActionOrNull(ref.get("endAction"),0,nodes,legacy)));
                }
                entries.add(new FireCatalog.AttackFire(text(row.get("weapon"),512),integer(row.get("activity")),readModel(row.get("attack"),0,nodes),decimal(row.get("fireSpeed")),fireRefs));
            }
            var unsupported = new TreeMap<String,Integer>();
            if (!(root.get("unsupported") instanceof Map<?,?> u) || u.size() > 4096) throw new IOException("Invalid unsupported action report");
            for (var e : u.entrySet()) { String k=text(e.getKey(),256); int n=integer(e.getValue()); if(n<0||n>MAX_NODES||unsupported.put(k,n)!=null)throw new IOException("Invalid unsupported count"); }
            return new FireCatalog(entries, unsupported);
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid fire catalogue", invalid); }
    }

    private static Object action(FireCatalog.Action a,int depth,int[] n)throws IOException{
        node(depth,n); var m=new LinkedHashMap<String,Object>();m.put("kind",a.kind().name());m.put("config",a.config()==null?null:model(a.config(),depth+1,n));m.put("actor",a.actor()==null?null:actor(a.actor(),depth+1,n));
        m.put("targetType",a.targetType());m.put("facingType",a.facingType());m.put("collisionSourceType",a.collisionSourceType());m.put("x",a.x());m.put("y",a.y());m.put("rotation",a.rotation());m.put("rotationVariance",a.rotationVariance());m.put("relative",a.relative());m.put("count",a.count());m.put("stepSize",a.stepSize());m.put("steps",a.steps());m.put("collisionMask",a.collisionMask());m.put("stopOnFailure",a.stopOnFailure());
        var children=new ArrayList<Object>();for(FireCatalog.Action child:a.children())children.add(action(child,depth+1,n));m.put("children",children);m.put("unsupportedType",a.unsupportedType());m.put("parameter",a.parameter());m.put("values",a.values());return m;
    }
    public static String actionJson(FireCatalog.Action action)throws IOException{return Json.stringify(action(action,0,new int[]{0}),128);}
    public static FireCatalog.Action actionJson(String json)throws IOException{return readAction(Json.parse(json,128),0,new int[]{0},false);}
    /** One streamed native value in the same tagged JSON shape used inside action graphs. */
    public static String valueJson(Object value)throws IOException{return Json.stringify(value(value,0,new int[]{0}),128);}
    public static Object valueJson(String json)throws IOException{return readValue(Json.parse(json,128),0,new int[]{0});}
    private static Object orbit(FireCatalog.OrbitSpec o)throws IOException{if(o==null)return null;var m=new LinkedHashMap<String,Object>();m.put("radius",o.radius());m.put("angularSpeed",o.angularSpeed());m.put("startingRotation",o.startingRotation());m.put("flightSpeed",o.flightSpeed());m.put("outDuration",o.outDuration());m.put("lifespan",o.lifespan());m.put("follow",o.follow());m.put("alternateSpin",o.alternateSpin());m.put("secondOrbitRadius",o.secondOrbitRadius());m.put("secondSpeed",o.secondSpeed());m.put("secondStartingRotation",o.secondStartingRotation());return m;}
    private static Object actor(FireCatalog.ActorSpec a,int depth,int[] n)throws IOException{node(depth,n);var m=new LinkedHashMap<String,Object>();m.put("nativeType",a.nativeType());m.put("config",model(a.config(),depth+1,n));m.put("fuse",a.fuse());m.put("speed",a.speed());m.put("maxStep",a.maxStep());m.put("flags",a.flags());m.put("offsetX",a.offsetX());m.put("offsetY",a.offsetY());m.put("height",a.height());m.put("detonation",actionOrNull(a.detonation(),depth+1,n));m.put("orbit",orbit(a.orbit()));m.put("collisionMask",a.collisionMask());m.put("collisionShape",a.collisionShape()==null?null:value(a.collisionShape(),depth+1,n));var timers=new ArrayList<Object>();for(var timer:a.timers()){var t=new LinkedHashMap<String,Object>();t.put("delay",timer.delay());t.put("interval",timer.interval());t.put("limit",timer.limit());t.put("action",action(timer.action(),depth+1,n));t.put("endAction",actionOrNull(timer.endAction(),depth+1,n));timers.add(t);}m.put("timers",timers);m.put("shutdown",actionOrNull(a.shutdown(),depth+1,n));
        if(a.startup()!=null)m.put("startup",actionOrNull(a.startup(),depth+1,n));
        if(a.attachment()!=null)m.put("attachment",actor(a.attachment(),depth+1,n));
        if(a.activation()!=null)m.put("activation",actionOrNull(a.activation(),depth+1,n));
        if(a.activatesAttachments())m.put("activatesAttachments",true);return m;}
    private static Object actionOrNull(FireCatalog.Action a,int d,int[] n)throws IOException{return a==null?null:action(a,d,n);}
    private static Object model(StarterCatalog.Model m,int depth,int[] n)throws IOException{node(depth,n);return Map.of("name",m.name(),"arguments",values(m.arguments(),depth+1,n));}
    private static Map<String,Object> values(Map<String,Object> args,int d,int[] n)throws IOException {
        if(d>96||args.size()>4096)throw new IOException("Fire model arguments exceed limits ("+args.size()+" keys, depth "+d+")");
        var out=new TreeMap<String,Object>();for(var e:args.entrySet()){node(d,n);out.put(text(e.getKey(),1024),value(e.getValue(),d+1,n));}return out;
    }
    private static Object value(Object v,int d,int[] n)throws IOException {
        if(v==FireCatalog.NullValue.INSTANCE)return Map.of("type","null");if(v instanceof String||v instanceof Integer||v instanceof Boolean)return v;
        if(v instanceof Long l)return Map.of("type","long","value",l);if(v instanceof BigDecimal b)return Map.of("type","decimal","value",b);
        if(v instanceof Float f&&Float.isFinite(f))return Map.of("type","float","value",f);
        if(v instanceof StarterCatalog.Model m)return Map.of("type","model","value",model(m,d+1,n));
        if(v instanceof StarterCatalog.Colorization c)return Map.of("type","colorization","value",c.value());
        if(v instanceof StarterCatalog.TransColorization c)return Map.of("type","transColorization","class",c.clazz(),"value",c.value());
        if(v instanceof FireCatalog.EnumValue e)return Map.of("type","enum","class",e.type(),"name",e.name(),"ordinal",e.ordinal());
        if(v instanceof FireCatalog.DataValue o){var fields=new ArrayList<Object>();for(FireCatalog.FieldValue f:o.fields())fields.add(Map.of("name",f.name(),"fieldType",f.type(),"value",value(f.value(),d+1,n)));return Map.of("type","object","class",o.type(),"fields",fields);}
        if(v instanceof FireCatalog.ListValue l)return Map.of("type","list","component",l.componentType(),"values",list(l.values(),d+1,n));
        if(v instanceof FireCatalog.MapValue m)return Map.of("type","map","values",values(m.values(),d+1,n));
        throw new IOException("Unsupported stock model argument type");
    }
    private static List<Object> list(List<Object> values,int d,int[] n)throws IOException {
        if(values.size()>10_000)throw new IOException("Fire list exceeds limits");
        var out=new ArrayList<Object>();for(Object v:values){node(d,n);out.add(value(v,d+1,n));}return out;
    }
    private static FireCatalog.Action readAction(Object v,int d,int[] n,boolean legacy)throws IOException{node(d,n);if(v==null)return null;v=defaults(v,legacy,Map.of("parameter","","values",List.of()));var m=object(v,Set.of("kind","config","actor","targetType","facingType","collisionSourceType","x","y","rotation","rotationVariance","relative","count","stepSize","steps","collisionMask","stopOnFailure","children","unsupportedType","parameter","values"));if(!(m.get("children") instanceof List<?> list)||list.size()>10_000||!(m.get("values") instanceof List<?> rawValues)||rawValues.size()>10_000)throw new IOException("Invalid action children or argument values");var children=new ArrayList<FireCatalog.Action>();for(Object c:list)children.add(readAction(c,d+1,n,legacy));var values=new ArrayList<String>();for(Object item:rawValues)values.add(plainText(item,65536));return new FireCatalog.Action(FireCatalog.Kind.valueOf(text(m.get("kind"),64)),readModelOrNull(m.get("config"),d+1,n),readActorOrNull(m.get("actor"),d+1,n,legacy),plainText(m.get("targetType"),128),plainText(m.get("facingType"),128),plainText(m.get("collisionSourceType"),128),decimal(m.get("x")),decimal(m.get("y")),decimal(m.get("rotation")),decimal(m.get("rotationVariance")),bool(m.get("relative")),integer(m.get("count")),decimal(m.get("stepSize")),integer(m.get("steps")),integer(m.get("collisionMask")),bool(m.get("stopOnFailure")),children,plainText(m.get("unsupportedType"),256),plainText(m.get("parameter"),1024),values);}
    private static FireCatalog.Action readActionOrNull(Object v,int d,int[] n,boolean legacy)throws IOException{return v==null?null:readAction(v,d,n,legacy);}
    private static FireCatalog.ActorSpec readActorOrNull(Object v,int d,int[] n,boolean legacy)throws IOException{if(v==null)return null;node(d,n);v=defaults(v,legacy,Collections.singletonMap("orbit",null));var extra=new HashMap<String,Object>();extra.put("collisionMask",43);extra.put("collisionShape",null);extra.put("timers",List.of());extra.put("shutdown",null);extra.put("startup",null);extra.put("attachment",null);extra.put("activation",null);extra.put("activatesAttachments",false);v=defaults(v,true,extra);var m=object(v,Set.of("nativeType","config","fuse","speed","maxStep","flags","offsetX","offsetY","height","detonation","orbit","collisionMask","collisionShape","timers","shutdown","startup","attachment","activation","activatesAttachments"));return new FireCatalog.ActorSpec(text(m.get("nativeType"),128),readModel(m.get("config"),d+1,n),integer(m.get("fuse")),decimal(m.get("speed")),decimal(m.get("maxStep")),integer(m.get("flags")),decimal(m.get("offsetX")),decimal(m.get("offsetY")),decimal(m.get("height")),readActionOrNull(m.get("detonation"),d+1,n,legacy),readOrbit(m.get("orbit")),integer(m.get("collisionMask")),m.get("collisionShape")==null?null:readValue(m.get("collisionShape"),d+1,n),readTimers(m.get("timers"),d+1,n,legacy),readActionOrNull(m.get("shutdown"),d+1,n,legacy),readActionOrNull(m.get("startup"),d+1,n,legacy),readActorOrNull(m.get("attachment"),d+1,n,legacy),readActionOrNull(m.get("activation"),d+1,n,legacy),bool(m.get("activatesAttachments")));}
    private static List<FireCatalog.ActorTimer> readTimers(Object v,int d,int[] n,boolean legacy)throws IOException {
        if(!(v instanceof List<?> rows)||rows.size()>64)throw new IOException("Invalid actor timers");
        var result=new ArrayList<FireCatalog.ActorTimer>();for(Object row:rows){node(d,n);var m=object(row,Set.of("delay","interval","limit","action","endAction"));result.add(new FireCatalog.ActorTimer(integer(m.get("delay")),integer(m.get("interval")),integer(m.get("limit")),readAction(m.get("action"),d+1,n,legacy),readActionOrNull(m.get("endAction"),d+1,n,legacy)));}return result;
    }
    private static FireCatalog.OrbitSpec readOrbit(Object v)throws IOException{if(v==null)return null;var m=object(v,Set.of("radius","angularSpeed","startingRotation","flightSpeed","outDuration","lifespan","follow","alternateSpin","secondOrbitRadius","secondSpeed","secondStartingRotation"));boolean compound=m.get("secondOrbitRadius")!=null;Float radius=compound?decimal(m.get("secondOrbitRadius")):null;Float speed=compound?decimal(m.get("secondSpeed")):null;Float rotation=compound?decimal(m.get("secondStartingRotation")):null;if(!compound&&(m.get("secondSpeed")!=null||m.get("secondStartingRotation")!=null))throw new IOException("Incomplete compound orbit metadata");return new FireCatalog.OrbitSpec(decimal(m.get("radius")),decimal(m.get("angularSpeed")),decimal(m.get("startingRotation")),decimal(m.get("flightSpeed")),integer(m.get("outDuration")),integer(m.get("lifespan")),text(m.get("follow"),128),bool(m.get("alternateSpin")),radius,speed,rotation);}
    private static StarterCatalog.Model readModelOrNull(Object v,int d,int[] n)throws IOException{return v==null?null:readModel(v,d,n);}
    private static StarterCatalog.Model readModel(Object v,int d,int[] n) throws IOException {node(d,n);var m=object(v,Set.of("name","arguments"));if(!(m.get("arguments") instanceof Map<?,?> args)||args.size()>4096)throw new IOException("Invalid fire arguments");var out=new TreeMap<String,Object>();for(var e:args.entrySet())out.put(text(e.getKey(),1024),readValue(e.getValue(),d+1,n));return new StarterCatalog.Model(text(m.get("name"),1024),out);}
    private static Object readValue(Object v,int d,int[] n)throws IOException {
        node(d,n);if(v instanceof String)return plainText(v,65536);if(v instanceof Boolean)return v;
        if(v instanceof BigDecimal b){try{return b.intValueExact();}catch(ArithmeticException ignored){try{return b.longValueExact();}catch(ArithmeticException noLong){return b;}}}
        if(v instanceof Map<?,?>){String type=text(((Map<?,?>)v).get("type"),32);return switch(type){
            case "null"->{object(v,Set.of("type"));yield FireCatalog.NullValue.INSTANCE;}
            case "float"->{var f=object(v,Set.of("type","value"));yield decimal(f.get("value"));}
            case "long"->{var f=object(v,Set.of("type","value"));yield longInteger(f.get("value"));}
            case "decimal"->{var f=object(v,Set.of("type","value"));if(!(f.get("value") instanceof BigDecimal b))throw new IOException("Invalid decimal");yield b;}
            case "model"->{var f=object(v,Set.of("type","value"));yield readModel(f.get("value"),d+1,n);}
            case "colorization"->{var f=object(v,Set.of("type","value"));yield new StarterCatalog.Colorization(integer(f.get("value")));}
            case "transColorization"->{var f=object(v,Set.of("type","class","value"));yield new StarterCatalog.TransColorization(integer(f.get("class")),integer(f.get("value")));}
            case "enum"->{var f=object(v,Set.of("type","class","name","ordinal"));yield new FireCatalog.EnumValue(text(f.get("class"),512),text(f.get("name"),256),integer(f.get("ordinal")));}
            case "object"->{var f=object(v,Set.of("type","class","fields"));if(!(f.get("fields") instanceof List<?> fields)||fields.size()>4096)throw new IOException("Invalid streamed fields");var result=new ArrayList<FireCatalog.FieldValue>();for(Object field:fields){var fm=object(field,Set.of("name","fieldType","value"));result.add(new FireCatalog.FieldValue(text(fm.get("name"),512),text(fm.get("fieldType"),512),readValue(fm.get("value"),d+1,n)));}yield new FireCatalog.DataValue(text(f.get("class"),512),result);}
            case "list"->{var f=object(v,Set.of("type","component","values"));if(!(f.get("values") instanceof List<?> l)||l.size()>10_000)throw new IOException("Invalid argument list");var vals=new ArrayList<Object>();for(Object item:l)vals.add(readValue(item,d+1,n));String component=text(f.get("component"),512);
                // Older native exports mistook iterable TagConfig for an array of
                // TagConfig, although its iterator yields effective String tags.
                if(component.equals("com.threerings.tudey.config.TagConfig")){
                    if(vals.stream().anyMatch(item->!(item instanceof String)))throw new IOException("Invalid legacy tag configuration");
                    yield new FireCatalog.DataValue(component,List.of(new FireCatalog.FieldValue("tags","[Ljava.lang.String;",new FireCatalog.ListValue("java.lang.String",vals)),new FireCatalog.FieldValue("derived",component,FireCatalog.NullValue.INSTANCE)));
                }
                yield new FireCatalog.ListValue(component,vals);}
            case "map"->{var f=object(v,Set.of("type","values"));yield new FireCatalog.MapValue(readValues(f.get("values"),d+1,n));}
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
}
