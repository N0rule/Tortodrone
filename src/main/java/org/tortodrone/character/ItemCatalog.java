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
        public boolean grantable() { return slot != null && variant!=Variant.UPGRADE_ITEM
            && (!type.equals("Artifact") || variant==Variant.SEED_ITEM&&seed!=null)
            && (slot==WorldProfile.Slot.OTHER || !stackable && (slot==WorldProfile.Slot.TRINKET || !modelArgs.isEmpty())); }
        public Map<String,Object> arguments(int color) { return resolve(modelArgs,color); }
    }
    private final Map<String,Entry> entries;
    private final Map<Integer,List<Entry>> ids;
    public ItemCatalog(Collection<Entry> entries) {
        var map=new TreeMap<String,Entry>();
        for(var e:entries) if(e.config().isBlank() || map.put(e.config().toLowerCase(Locale.ROOT),e)!=null)
            throw new IllegalArgumentException("Duplicate or empty item reference");
        this.entries=Map.copyOf(map);
        var byId=new HashMap<Integer,List<Entry>>();
        for(var e:entries)byId.computeIfAbsent(e.stockId(),ignored->new ArrayList<>()).add(e);
        byId.replaceAll((id,list)->List.copyOf(list));this.ids=Map.copyOf(byId);
    }
    public static ItemCatalog starter() { return new ItemCatalog(StarterCatalog.catalogEntries()); }
    public static ItemCatalog deployment(Path data)throws IOException {
        Path file=source(data);return file==null?starter():load(file);
    }
    public static Path source(Path data) {
        Path deployment=data.toAbsolutePath().normalize().getParent();
        Path custom=deployment.resolve("items/catalog.json"),defaults=deployment.resolve("defaults/items/catalog.json");
        return Files.exists(custom)?custom:Files.exists(defaults)?defaults:null;
    }
    public List<Entry> entries() { return entries.values().stream().sorted(Comparator.comparing(Entry::config)).toList(); }
    public List<Entry> search(String query) {
        String key=query.toLowerCase(Locale.ROOT);
        return entries().stream().filter(e->Integer.toString(e.stockId()).equals(key) || e.config().toLowerCase(Locale.ROOT).contains(key)
            ||e.nameKey().toLowerCase(Locale.ROOT).contains(key)).toList();
    }
    public Entry require(String config)throws IOException {
        Entry entry;
        if(config.matches("[+-]?\\d+")) {
            int id;try{id=Integer.parseInt(config);}catch(NumberFormatException invalid){throw new IOException("Invalid catalogue ID");}
            if(id==0)throw new IOException("Catalogue ID 0 is reserved for base definitions; use an exact reference");
            var matches=ids.getOrDefault(id,List.of());
            if(matches.size()>1)throw new IOException("Ambiguous catalogue ID; use an exact reference");
            entry=matches.isEmpty()?null:matches.get(0);
        }else entry=entries.get(config.toLowerCase(Locale.ROOT));
        if(entry==null)throw new IOException("Unknown item; use !items or items to find a catalogue ID or exact config reference");
        if(!entry.grantable())throw new IOException("This item type is indexed but not supported for grants yet");
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
