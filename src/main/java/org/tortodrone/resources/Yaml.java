package org.tortodrone.resources;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Bounded data-only YAML. No object tags, merges, aliases or duplicate keys. */
public final class Yaml {
    private Yaml() { }
    public static Map<String,Object> read(Path path) throws IOException {
        ContentFiles.regular(path);
        if(Files.size(path)>32_000_000)throw new IOException("YAML exceeds 32 MB: "+path);
        var options=new LoaderOptions();options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);options.setNestingDepthLimit(32);options.setCodePointLimit(32_000_000);
        try(var reader=Files.newBufferedReader(path)) {
            var parser=new org.yaml.snakeyaml.Yaml(new SafeConstructor(options));
            var node=parser.compose(reader);checkNode(node,0,new int[1]);
            // Construction uses the same bounded, immutable text read, avoiding a second file read.
            return map(normalize(new DataConstructor(options).construct(node),0,new int[1]),path.toString());
        } catch(RuntimeException|IOException invalid) {throw new IOException(path+": "+invalid.getMessage(),invalid);}
    }
    private static final class DataConstructor extends SafeConstructor {
        DataConstructor(LoaderOptions options){super(options);setAllowDuplicateKeys(false);}
        Object construct(org.yaml.snakeyaml.nodes.Node node){return constructObject(node);}
    }
    private static void checkNode(org.yaml.snakeyaml.nodes.Node node,int depth,int[] count)throws IOException {
        if(node==null)throw new IOException("Expected a YAML document");
        if(depth>32||++count[0]>2_000_000)throw new IOException("YAML structure exceeds limits");
        if(node.getAnchor()!=null)throw new IOException("YAML anchors/aliases are unsupported");
        if(node instanceof org.yaml.snakeyaml.nodes.MappingNode m)for(var pair:m.getValue()) {
            if(pair.getKeyNode().getTag().equals(org.yaml.snakeyaml.nodes.Tag.MERGE))throw new IOException("YAML merge keys are unsupported");
            checkNode(pair.getKeyNode(),depth+1,count);checkNode(pair.getValueNode(),depth+1,count);
        } else if(node instanceof org.yaml.snakeyaml.nodes.SequenceNode s)for(var value:s.getValue())checkNode(value,depth+1,count);
    }
    private static Object normalize(Object value,int depth,int[] count) throws IOException {
        if(depth>32||++count[0]>2_000_000)throw new IOException("YAML structure exceeds limits");
        if(value instanceof Map<?,?> source) {
            var out=new LinkedHashMap<String,Object>();
            for(var e:source.entrySet()) {
                if(!(e.getKey() instanceof String key)||key.equals("<<"))throw new IOException("YAML keys must be strings; merge keys are unsupported");
                out.put(key,normalize(e.getValue(),depth+1,count));
            }
            return out;
        }
        if(value instanceof List<?> source){var out=new ArrayList<Object>();for(var e:source)out.add(normalize(e,depth+1,count));return out;}
        if(value==null||value instanceof String||value instanceof Boolean||value instanceof Integer||value instanceof Long)return value;
        if(value instanceof Number number){var n=new BigDecimal(number.toString());
            try{return n.intValueExact();}catch(ArithmeticException fractional){ }
            try{return n.longValueExact();}catch(ArithmeticException fractional){ }
            if(!Double.isFinite(n.doubleValue()))throw new IOException("Non-finite YAML number");return n.doubleValue();}
        throw new IOException("Unsupported YAML scalar: "+value);
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> map(Object value,String where)throws IOException {
        if(!(value instanceof Map<?,?>))throw new IOException(where+": expected a mapping");return (Map<String,Object>)value;
    }
    public static List<?> list(Object value,String where)throws IOException {
        if(!(value instanceof List<?> list))throw new IOException(where+": expected a list");return list;
    }
    public static void fields(Map<String,Object> map,String where,String... allowed)throws IOException {
        var names=Set.of(allowed);for(String key:map.keySet())if(!names.contains(key))throw new IOException(where+": unknown field '"+key+"'");
    }
    public static String text(Object value,String where)throws IOException {
        if(!(value instanceof String text))throw new IOException(where+": expected text");return text;
    }
    public static int integer(Object value,String where)throws IOException {
        try {if(!(value instanceof Number))throw new ArithmeticException();return new BigDecimal(value.toString()).intValueExact();}
        catch(ArithmeticException invalid){throw new IOException(where+": expected a 32-bit integer");}
    }
    public static float decimal(Object value,String where)throws IOException {
        if(!(value instanceof Number n)||!Float.isFinite(n.floatValue()))throw new IOException(where+": expected a finite number");return n.floatValue();
    }
    public static boolean flag(Object value,String where)throws IOException {
        if(!(value instanceof Boolean b))throw new IOException(where+": expected true or false");return b;
    }
    public static void write(Path path,Map<String,Object> data)throws IOException {
        var options=new org.yaml.snakeyaml.DumperOptions();options.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);options.setWidth(120);options.setSplitLines(false);
        Files.createDirectories(path.toAbsolutePath().getParent());
        try(var writer=Files.newBufferedWriter(path,StandardOpenOption.CREATE_NEW)){new org.yaml.snakeyaml.Yaml(options).dump(normalize(data,0,new int[1]),writer);}
    }
}
