package org.tortodrone.character;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.resources.Json;

/** Human-readable item metadata with explicit types for stock model arguments. */
final class ItemCatalogJson {
    private static final Set<String> FIELDS = Set.of("config", "stockId", "type", "nameKey", "rarity", "stackable", "slot", "bindOnEquip", "permanent", "modelArguments");
    static void write(Path file, Collection<ItemCatalog.Entry> entries) throws IOException {
        if (entries.size()>50_000) throw new IOException("Item catalogue exceeds limits");
        try (var out=Files.newBufferedWriter(file, StandardOpenOption.CREATE_NEW)) {
            out.write("{\n  \"format\": \"tortodrone-items\",\n  \"version\": 1,\n  \"items\": [\n");
            boolean comma=false;
            for (var entry:entries.stream().sorted(Comparator.comparing(ItemCatalog.Entry::config)).toList()) {
                var row=new LinkedHashMap<String,Object>();
                row.put("config",entry.config()); row.put("stockId",entry.stockId()); row.put("type",entry.type());
                row.put("nameKey",entry.nameKey()); row.put("rarity",entry.rarity()); row.put("stackable",entry.stackable());
                row.put("slot",entry.slot()==null?null:entry.slot().name()); row.put("bindOnEquip",entry.bindOnEquip());
                row.put("permanent",entry.permanent()); row.put("modelArguments",encode(entry.modelArgs(),0,new int[]{0}));
                if (comma) out.write(",\n"); comma=true; out.write("    "+Json.stringify(row));
            }
            out.write("\n  ]\n}\n");
        }
    }
    static ItemCatalog load(Path file) throws IOException {
        if (Files.size(file)>32_000_000) throw new IOException("Item catalogue exceeds size limit");
        try {
            var root=object(Json.parse(Files.readString(file)),Set.of("format","version","items"));
            if (!"tortodrone-items".equals(root.get("format")) || integer(root.get("version"))!=1)
                throw new IOException("Unsupported JSON item catalogue");
            if (!(root.get("items") instanceof List<?> rows)) throw new IOException("Invalid item list");
            var entries=new ArrayList<ItemCatalog.Entry>();
            for (Object value:rows) {
                var row=object(value,FIELDS);
                String config=text(row.get("config"),512),type=text(row.get("type"),512),name=text(row.get("nameKey"),1024);
                int rarity=integer(row.get("rarity")); if (rarity < -1 || rarity > 10) throw new IOException("Invalid item rarity");
                WorldProfile.Slot slot=row.get("slot")==null?null:WorldProfile.Slot.valueOf(text(row.get("slot"),32));
                entries.add(new ItemCatalog.Entry(config,integer(row.get("stockId")),type,name,rarity,
                    bool(row.get("stackable")),slot,bool(row.get("bindOnEquip")),bool(row.get("permanent")),
                    decode(row.get("modelArguments"),0,new int[]{0})));
            }
            return new ItemCatalog(entries);
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid JSON item catalogue",invalid); }
    }
    private static Map<String,Object> encode(Map<String,Object> args,int depth,int[] nodes) throws IOException {
        bounds(args.size(),depth); var result=new TreeMap<String,Object>();
        for (var entry:args.entrySet()) {
            if (++nodes[0]>128) throw new IOException("Too many item arguments");
            Object value=entry.getValue(), encoded;
            if (value instanceof String || value instanceof Integer || value instanceof Boolean) encoded=value;
            else if (value instanceof StarterCatalog.Model model) encoded=Map.of("type","model","name",model.name(),"arguments",encode(model.arguments(),depth+1,nodes));
            else if (value instanceof StarterCatalog.Colorization c) encoded=Map.of("type","colorization","value",c.value());
            else if (value instanceof StarterCatalog.TransColorization c) encoded=Map.of("type","transColorization","class",c.clazz(),"value",c.value());
            else if (value==ItemCatalog.PlayerColor.VALUE) encoded=Map.of("type","playerColor");
            else if (value instanceof Float f && Float.isFinite(f)) encoded=Map.of("type","float","value",f);
            else throw new IOException("Unsupported item model argument");
            result.put(entry.getKey(),encoded);
        }
        return result;
    }
    private static Map<String,Object> decode(Object value,int depth,int[] nodes) throws IOException {
        if (!(value instanceof Map<?,?> args)) throw new IOException("Invalid item arguments");
        bounds(args.size(),depth); var result=new TreeMap<String,Object>();
        for (var entry:args.entrySet()) {
            if (++nodes[0]>128) throw new IOException("Too many item arguments");
            String key=text(entry.getKey(),1024); Object v=entry.getValue(), decoded;
            if (v instanceof String) decoded=text(v,65536);
            else if (v instanceof Boolean) decoded=v;
            else if (v instanceof BigDecimal) decoded=integer(v);
            else if (v instanceof Map<?,?> typed) {
                String type=text(typed.get("type"),32);
                decoded=switch (type) {
                    case "model" -> { var m=object(v,Set.of("type","name","arguments")); yield new StarterCatalog.Model(text(m.get("name"),1024),decode(m.get("arguments"),depth+1,nodes)); }
                    case "playerColor" -> { object(v,Set.of("type")); yield ItemCatalog.PlayerColor.VALUE; }
                    case "colorization" -> { var m=object(v,Set.of("type","value")); yield new StarterCatalog.Colorization(integer(m.get("value"))); }
                    case "transColorization" -> { var m=object(v,Set.of("type","class","value")); yield new StarterCatalog.TransColorization(integer(m.get("class")),integer(m.get("value"))); }
                    case "float" -> {
                        var m=object(v,Set.of("type","value"));
                        if (!(m.get("value") instanceof BigDecimal number)) throw new IOException("Invalid model float");
                        float f=number.floatValue(); if (!Float.isFinite(f)) throw new IOException("Invalid model float"); yield f;
                    }
                    default -> throw new IOException("Unknown item argument type");
                };
            } else throw new IOException("Invalid item model value");
            result.put(key,decoded);
        }
        return Map.copyOf(result);
    }
    private static void bounds(int count,int depth) throws IOException { if (count>64 || depth>8) throw new IOException("Item arguments exceed limits"); }
    private static Map<?,?> object(Object value,Set<String> fields) throws IOException {
        if (!(value instanceof Map<?,?> map) || !map.keySet().equals(fields)) throw new IOException("Missing or unknown JSON item fields");
        return map;
    }
    private static String text(Object value,int max) throws IOException {
        if (!(value instanceof String s) || s.length()>max || s.chars().anyMatch(c->c<32)) throw new IOException("Invalid item text");
        return s;
    }
    private static boolean bool(Object value) throws IOException { if (!(value instanceof Boolean b)) throw new IOException("Invalid item flag"); return b; }
    private static int integer(Object value) throws IOException {
        if (!(value instanceof BigDecimal n)) throw new IOException("Invalid item integer");
        try { return n.intValueExact(); } catch (ArithmeticException invalid) { throw new IOException("Invalid item integer",invalid); }
    }
}
