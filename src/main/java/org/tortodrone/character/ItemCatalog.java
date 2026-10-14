package org.tortodrone.character;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Offline references/metadata, never executable client classes or game assets. */
public final class ItemCatalog {
    public static final int INVENTORY_LIMIT = 256, GRANT_LIMIT = 20;
    public enum PlayerColor { VALUE }
    /** Stock Item subclasses which carry state beyond the common Item fields. */
    public enum Variant { ITEM, LEVEL_ITEM, SEED_ITEM, UPGRADE_ITEM }
    public record Entry(String config, int stockId, String type, String nameKey, int rarity,
            boolean stackable, WorldProfile.Slot slot, boolean bindOnEquip, boolean permanent,
            boolean locked, Variant variant, Long seed, Map<String,Object> modelArgs) {
        public Entry(String config,int stockId,String type,String nameKey,int rarity,boolean stackable,WorldProfile.Slot slot,
                boolean bindOnEquip,boolean permanent,Map<String,Object> modelArgs) {
            this(config,stockId,type,nameKey,rarity,stackable,slot,bindOnEquip,permanent,stockLocked(type,permanent),Variant.ITEM,null,modelArgs);
        }
        public Entry(String config,int stockId,String type,String nameKey,int rarity,boolean stackable,WorldProfile.Slot slot,boolean bindOnEquip,Map<String,Object> modelArgs) {
            this(config,stockId,type,nameKey,rarity,stackable,slot,bindOnEquip,false,stockLocked(type,false),Variant.ITEM,null,modelArgs);
        }
        public Entry {
            Objects.requireNonNull(variant); modelArgs = Map.copyOf(modelArgs);
            if ((variant==Variant.SEED_ITEM)!=(seed!=null)) throw new IllegalArgumentException("Seed item metadata mismatch");
            if (permanent && !locked) throw new IllegalArgumentException("Permanent stock items are locked");
        }
        public String displayName() { return config.substring(config.lastIndexOf('/')+1); }
        public String category() { return type; }
        public String unavailableReason() {
            if (slot == null) return "No verified inventory slot";
            if (variant == Variant.UPGRADE_ITEM) return "Upgrade item records are unsupported";
            if (type.equals("Artifact") && (variant != Variant.SEED_ITEM || seed == null)) return "Artifact requires a verified seed item record";
            if (slot != WorldProfile.Slot.OTHER && stackable) return "Stackable equipment is unsupported";
            if (slot != WorldProfile.Slot.OTHER && slot != WorldProfile.Slot.TRINKET && modelArgs.isEmpty()) return "Equipment has no representable model arguments";
            return "Available";
        }
        public boolean grantable() { return slot != null && variant!=Variant.UPGRADE_ITEM
            && (!type.equals("Artifact") || variant==Variant.SEED_ITEM&&seed!=null)
            && (slot==WorldProfile.Slot.OTHER || !stackable && (slot==WorldProfile.Slot.TRINKET || !modelArgs.isEmpty())); }
        public Map<String,Object> arguments(int color) { return resolve(modelArgs,color); }
    }
    private final Map<String,Entry> entries;
    private final List<Entry> sorted;
    private final Map<String,String> aliases;
    private final Map<Integer,List<Entry>> ids;
    public ItemCatalog(Collection<Entry> entries) {
        var map=new TreeMap<String,Entry>();
        for(var e:entries) if(e.config().isBlank() || map.put(e.config().toLowerCase(Locale.ROOT),e)!=null)
            throw new IllegalArgumentException("Duplicate or empty item reference");
        this.entries=Map.copyOf(map);
        this.sorted=map.values().stream().sorted(Comparator.comparing(Entry::displayName,String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::config)).toList();
        var bases=new TreeMap<String,List<Entry>>();
        for(var e:entries) bases.computeIfAbsent(aliasBase(e),ignored->new ArrayList<>()).add(e);
        var derived=new TreeMap<String,String>();
        bases.forEach((base,members)->{ for(var e:members) derived.put(e.config(),members.size()==1?base:base+"@"+e.stockId()+"-"+digest(e.config()).substring(0,12)); });
        if(new HashSet<>(derived.values()).size()!=derived.size())throw new IllegalArgumentException("Derived item alias collision; canonical references remain required");
        this.aliases=Map.copyOf(derived);
        var byId=new HashMap<Integer,List<Entry>>();
        for(var e:entries)byId.computeIfAbsent(e.stockId(),ignored->new ArrayList<>()).add(e);
        byId.replaceAll((id,list)->List.copyOf(list));this.ids=Map.copyOf(byId);
    }
    public static ItemCatalog starter() { return new ItemCatalog(StarterCatalog.catalogEntries()); }
    public String alias(Entry entry) { return aliases.get(entry.config()); }
    private static String aliasBase(Entry entry) { return prefix(entry.category())+":"+slug(entry.displayName()); }
    private static String prefix(String type) {
        return switch(type) {
            case "Sword" -> "sw";
            case "Gun", "Handgun", "SwingingHandgun" -> "gun";
            case "Bomb", "BlastNetworkBomb" -> "bomb";
            case "Helm" -> "helm";
            case "Armor" -> "armor";
            case "Accessory", "AccessoryTicket" -> "acc";
            case "Material" -> "mat";
            case "Recipe" -> "recipe";
            default -> slug(type);
        };
    }
    private static String slug(String value) { return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("^-|-$",""); }
    private static String digest(String path) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(path.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public List<Entry> entries() { return sorted; }
    public List<Entry> search(String query) {
        String key=query.toLowerCase(Locale.ROOT);
        return entries().stream().filter(e->Integer.toString(e.stockId()).equals(key) || e.config().toLowerCase(Locale.ROOT).contains(key)
            || e.nameKey().toLowerCase(Locale.ROOT).contains(key) || e.displayName().toLowerCase(Locale.ROOT).contains(key)
            || alias(e).contains(key) || e.category().toLowerCase(Locale.ROOT).contains(key)).toList();
    }
    public record Page(List<Entry> entries,int total,int number,int size) { public Page { entries=List.copyOf(entries); } }
    public Page page(String query,String category,int number,int size) {
        if(number<1 || size<1)throw new IllegalArgumentException("Page and size must be positive");
        var matches=search(query).stream().filter(e->category==null || e.category().equalsIgnoreCase(category)).toList();
        long start=(long)(number-1)*size;
        return new Page(start>=matches.size()?List.of():matches.subList((int)start,(int)Math.min(start+size,matches.size())),matches.size(),number,size);
    }
    /** Inspection resolves indexed entries too; grants use require. */
    public Entry inspect(String reference)throws IOException {
        String key=reference.strip().toLowerCase(Locale.ROOT);
        var exact=entries.get(key);if(exact!=null)return exact;
        var matches=entries().stream().filter(e->alias(e).equals(key)).toList();
        if(matches.isEmpty() && key.matches("[+-]?\\d+")) {
            int id;try{id=Integer.parseInt(key);}catch(NumberFormatException invalid){throw new IOException("Invalid catalogue ID");}
            matches=ids.getOrDefault(id,List.of());
        } else if(matches.isEmpty()) matches=entries().stream().filter(e->e.displayName().equalsIgnoreCase(key)).toList();
        if(matches.isEmpty())throw new IOException("Unknown item; use !items or items to find a catalogue ID or exact config reference");
        if(matches.size()>1)throw new IOException("Ambiguous item reference; choose "+String.join("; ",matches.stream().sorted(Comparator.comparing(Entry::config)).limit(5).map(e->alias(e)+" ("+e.config()+")").toList())+(matches.size()>5?"; "+matches.size()+" choices; use items <search> --page N":""));
        return matches.get(0);
    }
    public Entry require(String reference)throws IOException {
        if(reference.strip().matches("[+-]?0+"))throw new IOException("Catalogue ID 0 is reserved for base definitions; use an exact reference");
        var entry=inspect(reference);
        if(!entry.grantable())throw new IOException("This item type is indexed but not supported for grants yet: "+entry.unavailableReason());
        return entry;
    }
    public Entry find(String config) { return entries.get(config.toLowerCase(Locale.ROOT)); }
    public static void write(Path file,Collection<Entry> entries)throws IOException { ItemCatalogJson.write(file,entries); }
    public static ItemCatalog load(Path file)throws IOException { return ItemCatalogJson.load(file); }
    static boolean stockLocked(String type,boolean permanent) {
        return permanent||Set.of("Artifact","Mineral","Token","TokenArtifact").contains(type);
    }
    private static Map<String,Object> resolve(Map<String,Object> args,int color) {
        var result=new TreeMap<String,Object>();
        args.forEach((k,v)->result.put(k,v==PlayerColor.VALUE?color:v instanceof StarterCatalog.Model m
            ?new StarterCatalog.Model(m.name(),resolve(m.arguments(),color)):v));return Map.copyOf(result);
    }
}
