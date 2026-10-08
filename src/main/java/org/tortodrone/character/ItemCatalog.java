package org.tortodrone.character;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Offline references/metadata, never executable client classes or game assets. */
public final class ItemCatalog {
    public static final int INVENTORY_LIMIT = 256, GRANT_LIMIT = 20;
    public enum PlayerColor { VALUE }
    public record Entry(String config, int stockId, String type, String nameKey, int rarity,
            boolean stackable, WorldProfile.Slot slot, boolean bindOnEquip, Map<String,Object> modelArgs) {
        public Entry { modelArgs = Map.copyOf(modelArgs); }
        public boolean grantable() { return slot != null && !modelArgs.isEmpty() && !stackable; }
        public Map<String,Object> arguments(int color) { return resolve(modelArgs,color); }
    }
    private final Map<String,Entry> entries;
    public ItemCatalog(Collection<Entry> entries) {
        var map=new TreeMap<String,Entry>();
        for(var e:entries) if(e.config().isBlank() || map.put(e.config().toLowerCase(Locale.ROOT),e)!=null)
            throw new IllegalArgumentException("Duplicate or empty item reference");
        this.entries=Map.copyOf(map);
    }
    public static ItemCatalog starter() { return new ItemCatalog(StarterCatalog.catalogEntries()); }
    public static ItemCatalog deployment(Path data)throws IOException {
        Path file=data.toAbsolutePath().normalize().getParent().resolve("items/catalog.tsv");
        return Files.exists(file)?load(file):starter();
    }
    public List<Entry> entries() { return entries.values().stream().sorted(Comparator.comparing(Entry::config)).toList(); }
    public List<Entry> search(String query) {
        String key=query.toLowerCase(Locale.ROOT);
        return entries().stream().filter(e->e.config().toLowerCase(Locale.ROOT).contains(key)
            ||e.nameKey().toLowerCase(Locale.ROOT).contains(key)).toList();
    }
    public Entry require(String config)throws IOException {
        var entry=entries.get(config.toLowerCase(Locale.ROOT));
        if(entry==null)throw new IOException("Unknown item; use !items or items to find an exact config reference");
        if(!entry.grantable())throw new IOException("This item type is indexed but not supported for grants yet");
        return entry;
    }
    public Entry find(String config) { return entries.get(config.toLowerCase(Locale.ROOT)); }
    public static void write(Path file,Collection<Entry> entries)throws IOException {
        try(var out=Files.newBufferedWriter(file,StandardOpenOption.CREATE_NEW)) {
            out.write("# Tortodrone item catalogue v1\n# config\tstock-id\ttype\tname-key\trarity\tstackable\tslot\tbind-on-equip\tmodel-arguments-base64\n");
            for(var e:entries.stream().sorted(Comparator.comparing(Entry::config)).toList()) {
                for(String value:List.of(e.config(),e.type(),e.nameKey()))
                    if(value.chars().anyMatch(c->c<32))throw new IOException("Invalid catalogue text");
                var bytes=new ByteArrayOutputStream();try(var data=new DataOutputStream(bytes)){writeMap(data,e.modelArgs(),0);}
                out.write(String.join("\t",e.config(),""+e.stockId(),e.type(),e.nameKey(),""+e.rarity(),""+e.stackable(),
                    e.slot()==null?"":e.slot().name(),""+e.bindOnEquip(),Base64.getEncoder().encodeToString(bytes.toByteArray()))+"\n");
            }
        }
    }
    public static ItemCatalog load(Path file)throws IOException {
        if(Files.size(file)>32_000_000)throw new IOException("Item catalogue exceeds size limit");
        var entries=new ArrayList<Entry>();
        try(var reader=Files.newBufferedReader(file)) {
            if(!"# Tortodrone item catalogue v1".equals(reader.readLine()))throw new IOException("Unsupported item catalogue");
            for(String line;(line=reader.readLine())!=null;) {
                if(line.isBlank()||line.startsWith("#"))continue;
                if(line.length()>65536||entries.size()>=50000)throw new IOException("Item catalogue exceeds limits");
                var f=line.split("\t",-1);if(f.length!=9)throw new IOException("Malformed item catalogue row");
                if(!Set.of("true","false").contains(f[5])||!Set.of("true","false").contains(f[7]))throw new IOException("Invalid item flags");
                try(var in=new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(f[8])))) {
                    var args=readMap(in,0,new int[]{0});if(in.read()!=-1)throw new IOException("Trailing item arguments");
                    var e=new Entry(f[0],Integer.parseInt(f[1]),f[2],f[3],Integer.parseInt(f[4]),Boolean.parseBoolean(f[5]),
                        f[6].isEmpty()?null:WorldProfile.Slot.valueOf(f[6]),Boolean.parseBoolean(f[7]),args);
                    if(e.config().length()>512||e.rarity()<-1||e.rarity()>10)throw new IOException("Invalid item metadata");
                    entries.add(e);
                }
            }
            return new ItemCatalog(entries);
        }catch(IllegalArgumentException invalid){throw new IOException("Invalid item catalogue",invalid);}
    }
    private static Map<String,Object> resolve(Map<String,Object> args,int color) {
        var result=new TreeMap<String,Object>();
        args.forEach((k,v)->result.put(k,v==PlayerColor.VALUE?color:v instanceof StarterCatalog.Model m
            ?new StarterCatalog.Model(m.name(),resolve(m.arguments(),color)):v));return Map.copyOf(result);
    }
    private static void writeMap(DataOutputStream out,Map<String,Object> map,int depth)throws IOException {
        if(depth>8||map.size()>64)throw new IOException("Item arguments exceed limits");out.writeInt(map.size());
        for(var e:new TreeMap<>(map).entrySet()) {
            out.writeUTF(e.getKey());Object v=e.getValue();
            if(v instanceof String s){out.writeByte(1);out.writeUTF(s);}
            else if(v instanceof Integer n){out.writeByte(2);out.writeInt(n);}
            else if(v instanceof Boolean b){out.writeByte(3);out.writeBoolean(b);}
            else if(v instanceof StarterCatalog.Colorization c){out.writeByte(4);out.writeInt(c.value());}
            else if(v instanceof StarterCatalog.Model m){out.writeByte(5);out.writeUTF(m.name());writeMap(out,m.arguments(),depth+1);}
            else if(v==PlayerColor.VALUE){out.writeByte(6);}
            else throw new IOException("Unsupported item model argument");
        }
    }
    private static Map<String,Object> readMap(DataInputStream in,int depth,int[] nodes)throws IOException {
        int n=in.readInt();if(depth>8||n<0||n>64)throw new IOException("Invalid item argument bounds");
        var map=new TreeMap<String,Object>();for(int i=0;i<n;i++) {
            if(++nodes[0]>128)throw new IOException("Too many item arguments");String key=in.readUTF();
            Object value=switch(in.readUnsignedByte()) {
                case 1->in.readUTF();case 2->in.readInt();case 3->in.readBoolean();case 4->new StarterCatalog.Colorization(in.readInt());
                case 5->new StarterCatalog.Model(in.readUTF(),readMap(in,depth+1,nodes));case 6->PlayerColor.VALUE;
                default->throw new IOException("Unknown item argument tag");
            };if(map.put(key,value)!=null)throw new IOException("Duplicate item argument");
        }return Map.copyOf(map);
    }
}
