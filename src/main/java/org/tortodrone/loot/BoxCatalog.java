package org.tortodrone.loot;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.ItemCatalog;
import org.tortodrone.resources.*;
import static org.tortodrone.resources.Yaml.*;
import static org.tortodrone.loot.BoxDefinition.*;

/** Explicit native key bindings and fail-closed, readable loot definitions. */
public final class BoxCatalog {
    private final Map<String,BoxDefinition> boxes;
    private final Map<String,String> tables;
    private BoxCatalog(Map<String,BoxDefinition> boxes){this(boxes,Map.of());}
    private BoxCatalog(Map<String,BoxDefinition> boxes,Map<String,String> tables){this.boxes=Map.copyOf(boxes);this.tables=Map.copyOf(tables);}
    public static BoxCatalog empty(){return new BoxCatalog(Map.of());}
    public BoxDefinition find(String config){return boxes.get(config);}
    public String spawnTable(String config){return tables.get(config);}
    public Collection<BoxDefinition> entries(){return boxes.values();}
    public static BoxCatalog load(Path content,ItemCatalog items)throws IOException {
        Path file=content.resolve("_internal/box-bindings.json");
        if(!Files.exists(file)&&!Files.exists(content.resolve("loot")))return empty(); // legacy import boundary
        ContentFiles.regular(file);if(Files.size(file)>32_000_000)throw new IOException("Box bindings exceed 32 MB");
        var root=map(Json.parseCatalog(Files.readString(file)),file.toString());fields(root,file.toString(),"format","version","boxes");
        if(!"tortodrone-box-bindings".equals(root.get("format"))||integer(root.get("version"),"box bindings version")!=1)throw new IOException("Unsupported box bindings");
        var nativeBoxes=map(root.get("boxes"),"box bindings");var definitions=new HashMap<String,BoxDefinition>();var tables=new HashMap<String,String>();
        for(Path yaml:yamlFiles(content.resolve("loot/boxes"))) {
            var data=read(yaml);fields(data,yaml.toString(),"boxes");
            for(var row:map(data.get("boxes"),"boxes").entrySet()) {
                String id=row.getKey();String config=item(items,id).config();
                if(!items.inspect(config).type().equals("Lockbox"))throw new IOException("Not a Lockbox: "+id);
                var binding=map(nativeBoxes.get(id),"Missing native box binding: "+id);fields(binding,id,"keyOptions","spawnTable");
                if(binding.containsKey("spawnTable"))tables.put(config,text(binding.get("spawnTable"),id+" spawn table"));
                var nativeKeys=keys(binding.get("keyOptions"),items);
                var value=map(row.getValue(),id);fields(value,id,"kind","acceptedKeys","status","evidence","groups","guaranteed");
                Kind kind=enumeration(Kind.class,value.get("kind"),id);Status status=enumeration(Status.class,value.get("status"),id);
                var keys=keys(value.get("acceptedKeys"),items);
                if(!keys.equals(nativeKeys)||(kind==Kind.KEYLESS)!=keys.isEmpty())throw new IOException("Key options must match the stock client: "+id);
                String evidence=text(value.get("evidence"),id);if(evidence.isBlank()||evidence.length()>2048)throw new IOException("Box evidence is required: "+id);
                var groups=new LinkedHashMap<String,Group>();
                for(var g:map(value.getOrDefault("groups",Map.of()),id).entrySet()) {
                    String name=g.getKey();if(!name.matches("[a-z][a-z0-9_]{0,63}"))throw new IOException("Invalid reward group: "+name);
                    var m=map(g.getValue(),name);fields(m,name,"chance","rolls","entries");
                    int chance=chance(m.get("chance"));int rolls=integer(m.get("rolls"),name);if(rolls<1||rolls>100)throw new IOException("Invalid roll count");
                    var entries=new ArrayList<Entry>();long total=0;
                    for(var entry:list(m.get("entries"),name)) {
                        var e=map(entry,name);fields(e,name,"weight","rewards");
                        int weight=integer(e.get("weight"),name);if(weight<1)throw new IOException("Weights must be positive");
                        total+=weight;if(total>1_000_000_000_000L)throw new IOException("Weight sum exceeds limit");
                        entries.add(new Entry(weight,rewards(e.get("rewards"),items)));
                    }
                    if(entries.isEmpty())throw new IOException("Empty reward group: "+name);
                    groups.put(name,new Group(chance,rolls,entries,total));
                }
                var guaranteed=rewards(value.getOrDefault("guaranteed",List.of()),items);
                var box=new BoxDefinition(id,config,kind,keys,status,evidence,groups,guaranteed);
                if(box.enabled()&&!items.inspect(config).grantable())throw new IOException("Box is not grantable: "+id);
                if(box.enabled()&&(!groups.containsKey("main")||groups.get("main").chance()!=1_000_000))throw new IOException("Enabled box needs a guaranteed main roll: "+id);
                for(String group:groups.keySet())bounds(box,group,new HashSet<>());
                int maximum=bounds(box,guaranteed,new HashSet<>());
                if(groups.containsKey("main"))maximum+=bounds(box,"main",new HashSet<>());
                for(String group:groups.keySet())if(group.startsWith("bonus_"))maximum+=bounds(box,group,new HashSet<>());
                if(box.enabled()&&minimum(box,guaranteed)+minimum(box,"main")<1)throw new IOException("Main pool can yield no reward: "+id);
                if(maximum>9999)throw new IOException("Box reward expansion exceeds 9999 items: "+id);
                if(definitions.put(config,box)!=null)throw new IOException("Duplicate box definition: "+id);
            }
        }
        // Nested boxes stay unopened. Reject automatic reward cycles even across box definitions.
        for(var box:definitions.values())nested(box,definitions,new HashSet<>());
        return new BoxCatalog(definitions,tables);
    }
    private static List<Path> yamlFiles(Path directory)throws IOException {
        ContentFiles.safe(directory);if(!Files.isDirectory(directory))throw new IOException("Missing loot/boxes directory");
        var result=new ArrayList<Path>();
        try(var paths=Files.walk(directory,8)) {
            var iterator=paths.iterator();int visited=0;
            while(iterator.hasNext()) {
                var path=iterator.next();if(++visited>1024)throw new IOException("Too many box content entries");
                ContentFiles.safe(path);if(Files.isDirectory(path))continue;ContentFiles.regular(path);
                if(!path.toString().endsWith(".yml"))throw new IOException("Unexpected box content file: "+path);
                if(result.size()>=512)throw new IOException("Too many box definition files");result.add(path);
            }
        }result.sort(Comparator.naturalOrder());return result;
    }
    private static <E extends Enum<E>> E enumeration(Class<E> type,Object value,String at)throws IOException {
        try{return Enum.valueOf(type,text(value,at).toUpperCase(Locale.ROOT));}catch(IllegalArgumentException bad){throw new IOException("Invalid "+type.getSimpleName()+": "+at,bad);}
    }
    private static ItemCatalog.Entry item(ItemCatalog items,String id)throws IOException {
        if(!id.matches("[a-z][a-z0-9_]{0,159}"))throw new IOException("Loot requires a readable item ID: "+id);
        var entry=items.inspect(id);if(!items.referenceId(entry).equals(id))throw new IOException("Unknown readable item ID: "+id);return entry;
    }
    private static List<String> keys(Object value,ItemCatalog items)throws IOException {
        var keys=new ArrayList<String>();
        for(Object raw:list(value,"acceptedKeys")) {
            String id=text(raw,"key");var entry=item(items,id);
            if(!entry.type().equals("Ticket")||!Set.of("Ticket/Keys/Silver Key","Ticket/Keys/Slime Key").contains(entry.config())||keys.contains(entry.config()))throw new IOException("Invalid or duplicate box key: "+id);
            keys.add(entry.config());
        }return List.copyOf(keys);
    }
    private static int chance(Object value)throws IOException {
        try {
            String text=text(value,"chance");if(!text.endsWith("%"))throw new NumberFormatException();
            int result=new BigDecimal(text.substring(0,text.length()-1)).movePointRight(4).intValueExact();
            if(result<0||result>1_000_000)throw new NumberFormatException();return result;
        }catch(ArithmeticException|NumberFormatException bad){throw new IOException("Chance must be 0%..100%, with at most four decimal places",bad);}
    }
    private static List<Reward> rewards(Object value,ItemCatalog items)throws IOException {
        var result=new ArrayList<Reward>();
        for(Object raw:list(value,"rewards")) {
            var row=map(raw,"reward");
            if(row.containsKey("group")){fields(row,"group reference","group");result.add(new Reference(text(row.get("group"),"group")));}
            else {
                fields(row,"item reward","item","quantity","binding");var entry=item(items,text(row.get("item"),"reward item"));
                if(!entry.grantable())throw new IOException("Unsupported reward representation: "+entry.unavailableReason());
                if(entry.type().equals("GiftBox"))throw new IOException("A gift requires existing wrapped contents");
                int min,max;Object quantity=row.get("quantity");
                if(quantity instanceof Map<?,?>){var q=map(quantity,"quantity");fields(q,"quantity","min","max");min=integer(q.get("min"),"min");max=integer(q.get("max"),"max");}
                else min=max=integer(quantity,"quantity");
                if(min<1||max<min||max>9999)throw new IOException("Invalid reward quantity");
                var binding=enumeration(Binding.class,row.get("binding"),"reward binding");
                if(binding==Binding.UNBOUND&&entry.locked())throw new IOException("Native locked reward cannot be unbound");
                result.add(new Item(entry.config(),min,max,binding));
            }
        }return List.copyOf(result);
    }
    private static int bounds(BoxDefinition box,String name,Set<String> visiting)throws IOException {
        var group=box.groups().get(name);if(group==null||!visiting.add(name)||visiting.size()>16)throw new IOException("Missing, cyclic or excessively nested reward group: "+name);
        int max=0;
        for(var entry:group.entries()) {
            max=Math.max(max,bounds(box,entry.rewards(),visiting));
        }
        visiting.remove(name);if((long)max*group.rolls()>9999)throw new IOException("Reward group expansion exceeds limit");return max*group.rolls();
    }
    private static int bounds(BoxDefinition box,List<Reward> rewards,Set<String> visiting)throws IOException {
        int max=0;for(var r:rewards){max+=r instanceof Item i?i.maximum():bounds(box,((Reference)r).group(),visiting);if(max>9999)throw new IOException("Reward expansion exceeds limit");}return max;
    }
    private static int minimum(BoxDefinition box,String name) {
        var group=box.groups().get(name);if(group.chance()<1_000_000)return 0;
        return group.rolls()*group.entries().stream().mapToInt(e->minimum(box,e.rewards())).min().orElse(0);
    }
    private static int minimum(BoxDefinition box,List<Reward> rewards) {
        int minimum=0;for(var reward:rewards)minimum+=reward instanceof Item item?item.minimum():minimum(box,((Reference)reward).group());return minimum;
    }
    private static void nested(BoxDefinition box,Map<String,BoxDefinition> definitions,Set<String> visiting)throws IOException {
        if(!visiting.add(box.config())||visiting.size()>32)throw new IOException("Cyclic nested box rewards: "+box.id());
        var rewards=new ArrayList<>(box.guaranteed());box.groups().values().forEach(g->g.entries().forEach(e->rewards.addAll(e.rewards())));
        for(var reward:rewards)if(reward instanceof Item item&&definitions.containsKey(item.config()))nested(definitions.get(item.config()),definitions,visiting);
        visiting.remove(box.config());
    }
}
