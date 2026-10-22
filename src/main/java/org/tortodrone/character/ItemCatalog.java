package org.tortodrone.character;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Offline references/metadata, never executable client classes or game assets. */
public final class ItemCatalog {
    public static final int GRANT_LIMIT = 20;
    public enum PlayerColor { VALUE }
    /** Stock Item subclasses which carry state beyond the common Item fields. */
    public enum Variant { ITEM, LEVEL_ITEM, SEED_ITEM, UPGRADE_ITEM, FURNITURE_ITEM }
    public record Entry(String config, int stockId, String type, String nameKey, int rarity,
            boolean stackable, WorldProfile.Slot slot, boolean bindOnEquip, boolean permanent,
            boolean locked, Variant variant, Long seed, Map<String,Object> modelArgs, int expireDays,int auctionGroup) {
        public Entry(String config,int stockId,String type,String nameKey,int rarity,boolean stackable,WorldProfile.Slot slot,boolean bindOnEquip,boolean permanent,boolean locked,Variant variant,Long seed,Map<String,Object> modelArgs,int expireDays){this(config,stockId,type,nameKey,rarity,stackable,slot,bindOnEquip,permanent,locked,variant,seed,modelArgs,expireDays,-1);}
        public Entry(String config,int stockId,String type,String nameKey,int rarity,boolean stackable,WorldProfile.Slot slot,boolean bindOnEquip,boolean permanent,boolean locked,Variant variant,Long seed,Map<String,Object> modelArgs) {
            this(config,stockId,type,nameKey,rarity,stackable,slot,bindOnEquip,permanent,locked,variant,seed,modelArgs,0);
        }
        public Entry(String config,int stockId,String type,String nameKey,int rarity,boolean stackable,WorldProfile.Slot slot,
                boolean bindOnEquip,boolean permanent,Map<String,Object> modelArgs) {
            this(config,stockId,type,nameKey,rarity,stackable,slot,bindOnEquip,permanent,stockLocked(type,permanent),Variant.ITEM,null,modelArgs);
        }
        public Entry(String config,int stockId,String type,String nameKey,int rarity,boolean stackable,WorldProfile.Slot slot,boolean bindOnEquip,Map<String,Object> modelArgs) {
            this(config,stockId,type,nameKey,rarity,stackable,slot,bindOnEquip,false,stockLocked(type,false),Variant.ITEM,null,modelArgs);
        }
        public Entry {
            Objects.requireNonNull(variant); modelArgs = Map.copyOf(modelArgs);
            if(auctionGroup< -1||auctionGroup>=29)throw new IllegalArgumentException("Invalid native auction group");
            if(expireDays<0||expireDays>3650||expireDays>0&&(permanent||stackable))throw new IllegalArgumentException("Invalid expiring item metadata");
            if ((variant==Variant.SEED_ITEM)!=(seed!=null)) throw new IllegalArgumentException("Seed item metadata mismatch");
            if (permanent && !locked) throw new IllegalArgumentException("Permanent stock items are locked");
        }
        public String displayName() { return config.substring(config.lastIndexOf('/')+1); }
        public String category() { return type; }
        public String unavailableReason() {
            if (slot == null) return "No verified inventory slot";
            if (variant == Variant.UPGRADE_ITEM && (!type.equals("Upgrade") || slot != WorldProfile.Slot.OTHER)) return "Upgrade requires its native inventory representation";
            if (variant == Variant.FURNITURE_ITEM && (slot != WorldProfile.Slot.OTHER || !Set.of("Prop","Actor","SpecialProp").contains(type))) return "Furniture requires its native inventory representation";
            if (type.equals("Artifact") && (variant != Variant.SEED_ITEM || seed == null)) return "Artifact requires a verified seed item record";
            if (slot != WorldProfile.Slot.OTHER && stackable) return "Stackable equipment is unsupported";
            if (slot != WorldProfile.Slot.OTHER && slot != WorldProfile.Slot.TRINKET && modelArgs.isEmpty()) return "Equipment has no representable model arguments";
            return "Available";
        }
        public boolean grantable() { return slot != null && (variant!=Variant.UPGRADE_ITEM || type.equals("Upgrade") && slot==WorldProfile.Slot.OTHER)
            && (variant!=Variant.FURNITURE_ITEM || slot==WorldProfile.Slot.OTHER&&Set.of("Prop","Actor","SpecialProp").contains(type))
            && (!type.equals("Artifact") || variant==Variant.SEED_ITEM&&seed!=null)
            && (slot==WorldProfile.Slot.OTHER || !stackable && (slot==WorldProfile.Slot.TRINKET || !modelArgs.isEmpty())); }
        public Map<String,Object> arguments(int color) { return resolve(modelArgs,color); }
    }
    private final Map<String,Entry> entries;
    private final List<Entry> sorted;
    private final Map<String,String> aliases;
    private final Map<Integer,List<Entry>> ids;
    public record Labels(String category,String subcategory) { }
    private final Map<String,Labels> labels;
    private final Map<String,String> displayNames;
    private final Map<String,String> referenceIds;
    private final Map<String,String> configsByReference;
    public ItemCatalog(Collection<Entry> entries) {this(entries,Map.of());}
    private ItemCatalog(Collection<Entry> entries,Map<String,String> displayNames) {this(entries,displayNames,Map.of(),Map.of());}
    private ItemCatalog(Collection<Entry> entries,Map<String,String> displayNames,Map<String,String> referenceIds,Map<String,Labels> labels) {
        this.labels=Map.copyOf(labels);
        this.referenceIds=Map.copyOf(referenceIds);
        var reverseReferences=new TreeMap<String,String>();referenceIds.forEach((config,id)->reverseReferences.put(id,config));this.configsByReference=Map.copyOf(reverseReferences);
        this.displayNames=Map.copyOf(displayNames);
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
    /** Administrator discovery only; never serialized as a client display-name override. */
    public String displayName(Entry entry){return displayNames.getOrDefault(entry.config(),entry.displayName());}
    public ItemCatalog withDisplayNames(Map<String,String> names)throws IOException {
        for(var e:names.entrySet())if(find(e.getKey())==null||!find(e.getKey()).config().equals(e.getKey())||e.getValue().isBlank()||e.getValue().length()>512)throw new IOException("Invalid native item discovery name: "+e.getKey());
        return new ItemCatalog(entries(),names,referenceIds,labels);
    }
    public String category(Entry entry){var value=labels.get(entry.config());return value==null?entry.category():value.category();}
    public String subcategory(Entry entry){var value=labels.get(entry.config());return value==null?"":value.subcategory();}
    public boolean hasLabels(Entry entry){return labels.containsKey(entry.config());}
    public ItemCatalog withLabels(Map<String,Labels> values)throws IOException {
        for(var e:values.entrySet())if(find(e.getKey())==null||!find(e.getKey()).config().equals(e.getKey())||e.getValue().category()==null||e.getValue().category().isBlank()||e.getValue().category().length()>512||e.getValue().subcategory()==null||e.getValue().subcategory().length()>512)throw new IOException("Invalid item category labels: "+e.getKey());
        return new ItemCatalog(entries(),displayNames,referenceIds,values);
    }
    /** Explicit administrator identities; native paths and existing aliases remain accepted. */
    public ItemCatalog withReferenceIds(Map<String,String> ids)throws IOException {
        var reverse=new TreeMap<String,String>();
        for(var e:ids.entrySet()) {
            if(find(e.getKey())==null || !find(e.getKey()).config().equals(e.getKey()) || !e.getValue().matches("[a-z][a-z0-9_]{0,159}"))throw new IOException("Invalid item ID binding: "+e);
            var nativeItem=find(e.getValue());if(nativeItem!=null&&!nativeItem.config().equals(e.getKey()))throw new IOException("Ambiguous item ID/native reference: "+e.getValue());
            if(reverse.putIfAbsent(e.getValue(),e.getKey())!=null)throw new IOException("Duplicate item ID: "+e.getValue());
        }
        return new ItemCatalog(entries(),displayNames,ids,labels);
    }
    public String referenceId(Entry entry){return referenceIds.getOrDefault(entry.config(),alias(entry));}
    /** Static content accepts native paths, explicit IDs and exact historic aliases, never localized labels. */
    public String resolveConfig(String reference)throws IOException {
        String key=reference.strip().toLowerCase(Locale.ROOT);
        var nativeItem=entries.get(key);if(nativeItem!=null)return nativeItem.config();
        String config=configsByReference.get(key);if(config!=null)return config;
        var matches=entries().stream().filter(e->alias(e).equals(key)||aliases.get(e.config()).equals(key)).toList();
        if(matches.size()==1)return matches.getFirst().config();
        throw new IOException("Unknown or ambiguous item reference: "+reference+"; use an explicit item ID or native config");
    }
    public static ItemCatalog starter() { return new ItemCatalog(StarterCatalog.catalogEntries()); }
    public String alias(Entry entry) { return referenceIds.getOrDefault(entry.config(),aliases.get(entry.config())); }
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
            || displayName(e).toLowerCase(Locale.ROOT).contains(key) || e.nameKey().toLowerCase(Locale.ROOT).contains(key) || e.displayName().toLowerCase(Locale.ROOT).contains(key)
            || referenceId(e).contains(key) || alias(e).contains(key) || e.type().toLowerCase(Locale.ROOT).contains(key) || category(e).toLowerCase(Locale.ROOT).contains(key) || subcategory(e).toLowerCase(Locale.ROOT).contains(key)).toList();
    }
    public record Page(List<Entry> entries,int total,int number,int size) { public Page { entries=List.copyOf(entries); } }
    public Page page(String query,String category,int number,int size) {
        if(number<1 || size<1)throw new IllegalArgumentException("Page and size must be positive");
        var matches=search(query).stream().filter(e->category==null || category(e).equalsIgnoreCase(category) || e.category().equalsIgnoreCase(category)).toList();
        long start=(long)(number-1)*size;
        return new Page(start>=matches.size()?List.of():matches.subList((int)start,(int)Math.min(start+size,matches.size())),matches.size(),number,size);
    }
    /** Inspection resolves indexed entries too; grants use require. */
    public Entry inspect(String reference)throws IOException {
        String key=reference.strip().toLowerCase(Locale.ROOT);
        var exact=entries.get(key);if(exact!=null)return exact;
        String config=configsByReference.get(key);if(config!=null)return find(config);
        var matches=entries().stream().filter(e->alias(e).equals(key)||aliases.get(e.config()).equals(key)).toList();
        if(matches.isEmpty() && key.matches("[+-]?\\d+")) {
            int id;try{id=Integer.parseInt(key);}catch(NumberFormatException invalid){throw new IOException("Invalid catalogue ID");}
            matches=ids.getOrDefault(id,List.of());
        } else if(matches.isEmpty()) matches=entries().stream().filter(e->e.displayName().equalsIgnoreCase(key)||displayName(e).equalsIgnoreCase(key)).toList();
        if(matches.isEmpty())throw new IOException("Unknown item; use 'items' to find a catalogue ID or exact config reference");
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
    public static ItemCatalog load(Path file)throws IOException { return Files.isDirectory(file)?org.tortodrone.resources.ItemYaml.load(file):ItemCatalogJson.load(file); }
    public static ItemCatalog fromData(Object data)throws IOException {return ItemCatalogJson.load(data); }
    static boolean stockLocked(String type,boolean permanent) {
        return permanent||Set.of("Artifact","Mineral","Token","TokenArtifact").contains(type);
    }
    private static Map<String,Object> resolve(Map<String,Object> args,int color) {
        var result=new TreeMap<String,Object>();
        args.forEach((k,v)->result.put(k,v==PlayerColor.VALUE?color:v instanceof StarterCatalog.Model m
            ?new StarterCatalog.Model(m.name(),resolve(m.arguments(),color)):v));return Map.copyOf(result);
    }
}
