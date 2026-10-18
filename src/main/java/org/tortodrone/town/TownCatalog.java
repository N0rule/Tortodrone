package org.tortodrone.town;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.resources.Json;

/** Immutable rules extracted from reference inputs; no executable client code or assets. */
public final class TownCatalog {
    public record Ingredient(String kind,String item,int count,int rarity,String group,int level,String upgrade,boolean keepHeat,List<String> options) { }
    public record Recipe(String config,String item,int count,boolean locked,List<Integer> crowns,List<Boolean> useOrbs,
            String variantTable,List<Ingredient> ingredients,boolean oneTime,int maxHours) {
        public int cost(int rarity){return rarity>=0&&rarity<crowns.size()?crowns.get(rarity):0;}
        public boolean orbs(int rarity){return rarity>=0&&rarity<useOrbs.size()&&useOrbs.get(rarity);}
    }
    public record Skill(List<String> attacks,boolean follow,List<Integer> recharge,List<Integer> minimum) { }
    public record Sprite(String config,List<String> preferredFood,String restrictedDamage,List<Skill> skills) {
        public Sprite(String config,List<String> preferredFood,String restrictedDamage){this(config,preferredFood,restrictedDamage,List.of());}
    }
    public record Accessory(String config,String location,Set<String> blocks,boolean remove,boolean destroy) { }
    public record Perk(String name,int tier,String variant,String previous) { }
    public record Shop(String catalog,int level,String preparer,String cycle,boolean perKnight){public static final Shop EMPTY=new Shop("",10,"BASIC","CONSTANT",false);}
    public record Bonus(String weapon,int health,float charge,float speed,float attack) { }
    public record Good(String item,int count,int price,int energy,int limit,String token,int tokenCount) { }
    public record DepotGood(Good good,boolean featured,int originalPrice,boolean sale) {
        public DepotGood(Good good,boolean featured){this(good,featured,0,false);}
    }
    public record DepotOffers(Map<Integer,DepotGood> goods) { public DepotOffers{goods=Map.copyOf(goods);} }
    public record Catalog(String name,String kind,List<String> includes,List<Good> goods,int min,int max,float chance) { }
    public record Station(String actor,String kind,String type,String group,String name,String title,String model,String description,List<String> recipes,Shop shop) {
        public Station(String actor,String kind,String type,String group,String name,String title,String model,String description,List<String> recipes){this(actor,kind,type,group,name,title,model,description,recipes,Shop.EMPTY);}
    }
    private final Map<String,Recipe> recipes;
    private final Map<String,Map<String,String>> items;
    private final Map<String,Sprite> sprites;
    private final List<Perk> perks;
    private final Map<String,Station> stations;
    private Map<String,Map<String,Object>> variantTables=Map.of();
    private GearRules gear=GearRules.empty();
    public GearRules gear(){return gear;}
    public static String itemGroup(org.tortodrone.character.ItemCatalog.Entry item){return switch(item.type()){case "Sword"->"SWORD";case "Gun","Handgun","SwingingHandgun"->"HANDGUN";case "Bomb","BlastNetworkBomb"->"BOMB";case "HelmCostume"->"COSTUME_HELM";case "ArmorCostume"->"COSTUME_ARMOR";case "ShieldCostume"->"COSTUME_SHIELD";case "Material","Craft"->"CRAFT";default->item.type().toUpperCase(Locale.ROOT);};}
    public List<String> availableVariants(String group){
        if(!Set.of("SWORD","HANDGUN","BOMB","HELM","ARMOR","SHIELD","TRINKET").contains(group))return List.of();
        var result=new TreeSet<String>();collectVariants(variantTables.get("Shop/Variant 3"),group,result,0);return List.copyOf(result);
    }
    private void collectVariants(Map<String,Object> node,String group,Set<String> result,int depth){
        if(node==null||depth>32)return;String kind=string(node,"kind");if(kind.equals("GroupEntry")&&!group.equals(string(node,"group")))return;
        if(kind.equals("VariantEntry")){result.add(string(node,"variant"));return;}
        var entries=list(node,"entries");if((kind.equals("TableEntry")||kind.equals("Derived"))&&entries.isEmpty())collectVariants(variantTables.get(string(node,"table")),group,result,depth+1);
        for(var e:entries)collectVariants(object(object(e).get("node")),group,result,depth+1);
    }
    public void validateVariants(String group,List<String> selected)throws IOException{
        if(selected.size()>3||new HashSet<>(selected).size()!=selected.size()||!availableVariants(group).containsAll(selected))throw new IOException("Choose up to three distinct UVs valid for this item");
        validateVariantFamilies(variantTables.get("Shop/Variant 3"),group,new HashSet<>(selected),0);
    }
    private void validateVariantFamilies(Map<String,Object> node,String group,Set<String> selected,int depth)throws IOException{
        if(node==null||depth>32)return;String kind=string(node,"kind");if(kind.equals("GroupEntry")&&!group.equals(string(node,"group")))return;
        var entries=list(node,"entries");if((kind.equals("TableEntry")||kind.equals("Derived"))&&entries.isEmpty())validateVariantFamilies(variantTables.get(string(node,"table")),group,selected,depth+1);
        for(var e:entries){var child=object(object(e).get("node"));if(Boolean.TRUE.equals(node.get("exclude"))){var family=new HashSet<String>();collectVariants(child,group,family,depth+1);family.retainAll(selected);if(family.size()>1)throw new IOException("Choose only one UV from each variant family");}validateVariantFamilies(child,group,selected,depth+1);}
    }
    private Map<String,Accessory> accessories=Map.of();
    private Map<String,Set<String>> blockers=Map.of();
    private Map<String,String> harnesses=Map.of();
    private final Map<String,Float> harnessRecharge=new HashMap<>();
    public float harnessRecharge(String name){return harnessRecharge.getOrDefault(name,1f);}
    private Map<String,Catalog> catalogs=Map.of();
    private Map<String,Bonus> bonuses=Map.of();
    public Bonus equipmentBonuses(org.tortodrone.character.WorldProfile profile,String weapon){
        var refs=new ArrayList<String>();for(var item:profile.items())if(item.equippedSlot()!=null&&Set.of(0,1,2,5,10,11,12).contains(item.equippedSlot()))for(var variant:item.variants())refs.add(variant.name());
        var sprite=profile.town().sprites().stream().filter(s->s.id()==profile.town().equippedSprite()).findFirst().orElse(null);if(sprite!=null&&sprite.perk()!=null&&sprite.perk()>=0&&sprite.perk()<perks.size())refs.add("Sprite/"+perks.get(sprite.perk()).variant());
        return bonus(refs,weapon);
    }
    public Bonus weaponBonuses(org.tortodrone.character.WorldProfile.Item item,String weapon){return bonus(item==null?List.of():item.variants().stream().map(org.tortodrone.character.StarterCatalog.Model::name).toList(),weapon);}
    private Bonus bonus(List<String> refs,String weapon){int health=0;float charge=0,speed=0,attack=0;for(String ref:refs){var b=bonuses.get(ref);if(b==null||!b.weapon().isEmpty()&&!b.weapon().equals(weapon))continue;health+=b.health();charge+=b.charge();speed+=b.speed();attack+=b.attack();}return new Bonus("",health,charge,speed,attack);}

    private Map<String,org.tortodrone.character.StarterCatalog.Model> accessoryModels=Map.of();
    private Map<String,String> accessoryParameters=Map.of();
    public org.tortodrone.character.StarterCatalog.Model accessoryModel(String name){return accessoryModels.get(name);}
    public String accessoryParameter(String name){return accessoryParameters.get(name);}
    public int price(String name,int rarity,boolean buy){String table=item(name).getOrDefault(buy?"buyPrices":"sellPrices","");if(table.isEmpty())return 0;String[] rows=table.split(",");int index=rows.length==1?0:rarity;return index>=0&&index<rows.length?Integer.parseInt(rows[index]):0;}
    public long shopCycle(Shop shop){
        var now=java.time.ZonedDateTime.now();return switch(shop.cycle()){
            case "DAY","INSTANCE"->now.toLocalDate().plusDays(1).atStartOfDay(now.getZone()).toInstant().toEpochMilli();
            case "WEEK"->now.toLocalDate().with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.SUNDAY)).atStartOfDay(now.getZone()).toInstant().toEpochMilli();
            case "MONTH"->now.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(now.getZone()).toInstant().toEpochMilli();
            case "YEAR"->now.toLocalDate().withDayOfYear(1).plusYears(1).atStartOfDay(now.getZone()).toInstant().toEpochMilli();default->0;
        };
    }
    public List<Good> goods(Shop shop,long cycle,int world){var result=new ArrayList<Good>();goods(shop.catalog(),new Random(cycle+(shop.perKnight()?world:0)),result,0);return List.copyOf(result);}
    private void goods(String name,Random random,List<Good> output,int depth){
        if(depth>16)return;var catalog=catalogs.get(name);if(catalog==null||catalog.kind().equals("Optional")&&random.nextFloat()>=catalog.chance())return;
        var goods=new ArrayList<Good>();for(String include:catalog.includes())goods(include,random,goods,depth+1);goods.addAll(catalog.goods());
        if(catalog.kind().equals("Subset")){if(catalog.max()<catalog.min())return;int count=Math.min(goods.size(),random.nextInt(catalog.max()-catalog.min()+1)+catalog.min()+1);for(int i=0;i<count;i++)output.add(goods.remove(random.nextInt(goods.size())));}else output.addAll(goods);
    }
    public Accessory accessory(String name){return accessories.get(name);}
    public Set<String> blockers(String name){return blockers.getOrDefault(name,Set.of());}
    public String harnessDamage(String name){return harnesses.get(name);}
    public String accessoryTicket(String accessory){var grants=items.entrySet().stream().filter(e->accessory.equals(e.getValue().get("accessory"))).map(Map.Entry::getKey).filter(k->!k.contains("/Parts/")).sorted().toList();return grants.size()==1?grants.getFirst():null;}
    public List<org.tortodrone.character.StarterCatalog.Model> roll(String table,String group)throws IOException{return roll(table,group,List.of());}
    /** Retained UVs exclude their entire weighted branch, including other tiers of that UV. */
    public List<org.tortodrone.character.StarterCatalog.Model> roll(String table,String group,List<org.tortodrone.character.StarterCatalog.Model> retained)throws IOException{
        var result=new ArrayList<org.tortodrone.character.StarterCatalog.Model>();var excluded=retained.stream().map(org.tortodrone.character.StarterCatalog.Model::name).collect(java.util.stream.Collectors.toSet());
        if(!table.isEmpty()){
            var root=variantTables.get(table);if(root==null)throw new IOException("Missing variant table: "+table);
            int limit=integer(root,"maxResults")-retained.size();if(limit>0){rollNode(root,group,result,excluded,0);trim(result,limit);}
        }
        result.addAll(retained);return List.copyOf(result);
    }
    private static void trim(List<?> values,int maximum){while(values.size()>maximum)values.remove(java.util.concurrent.ThreadLocalRandom.current().nextInt(values.size()));}
    private void rollNode(Map<String,Object> node,String group,List<org.tortodrone.character.StarterCatalog.Model> result,Set<String> excluded,int depth)throws IOException{
        if(depth>32)throw new IOException("Variant table recursion limit");String kind=string(node,"kind");var entries=list(node,"entries");var random=java.util.concurrent.ThreadLocalRandom.current();
        switch(kind){
            case "VariantEntry"->{String name=string(node,"variant");if(!name.isEmpty())result.add(new org.tortodrone.character.StarterCatalog.Model(name,Map.of()));}
            case "TableEntry","Derived"->{
                Map<String,Object> root=entries.isEmpty()?variantTables.get(string(node,"table")):object(object(entries.getFirst()).get("node"));
                if(root==null)throw new IOException("Missing variant table: "+string(node,"table"));rollNode(root,group,result,excluded,depth+1);
            }
            case "GroupEntry"->{if(group.equals(string(node,"group"))&&!entries.isEmpty())rollNode(object(object(entries.getFirst()).get("node")),group,result,excluded,depth+1);}
            case "OptionalEntry"->{if(random.nextFloat()<((Number)node.get("probability")).floatValue()&&!entries.isEmpty())rollNode(object(object(entries.getFirst()).get("node")),group,result,excluded,depth+1);}
            case "RandomEntry","SelectionEntry","RandomSelectionEntry"->{
                int selections=kind.equals("RandomEntry")?1:kind.equals("SelectionEntry")?integer(node,"selections"):weighted(list(node,"selectionWeights").stream().mapToDouble(v->((Number)v).doubleValue()).toArray());
                double[] weights=new double[entries.size()];for(int i=0;i<weights.length;i++){var entry=object(entries.get(i));weights[i]=Boolean.TRUE.equals(node.get("exclude"))&&containsVariant(object(entry.get("node")),group,excluded,depth+1)?0:((Number)entry.get("weight")).doubleValue();}
                for(int i=0;i<selections;i++){int selected=weighted(weights);if(selected<0)break;rollNode(object(object(entries.get(selected)).get("node")),group,result,excluded,depth+1);weights[selected]=0;}
            }
            case "CompoundEntry"->{for(var e:entries)rollNode(object(object(e).get("node")),group,result,excluded,depth+1);}
            case "Original","VariantTableConfig"->{var local=new ArrayList<org.tortodrone.character.StarterCatalog.Model>();for(var e:entries)rollNode(object(object(e).get("node")),group,local,excluded,depth+1);trim(local,integer(node,"maxResults"));result.addAll(local);}
            default->throw new IOException("Unsupported variant table node: "+kind);
        }
    }
    private boolean containsVariant(Map<String,Object> node,String group,Set<String> variants,int depth)throws IOException{
        if(depth>32)throw new IOException("Variant table recursion limit");String kind=string(node,"kind");if(kind.equals("VariantEntry"))return variants.contains(string(node,"variant"));
        if(kind.equals("GroupEntry")&&!group.equals(string(node,"group")))return false;
        var entries=list(node,"entries");if((kind.equals("TableEntry")||kind.equals("Derived"))&&entries.isEmpty()){var root=variantTables.get(string(node,"table"));return root!=null&&containsVariant(root,group,variants,depth+1);}
        for(var entry:entries)if(containsVariant(object(object(entry).get("node")),group,variants,depth+1))return true;return false;
    }
    private static int weighted(double[] weights){double total=0;for(double weight:weights)total+=Math.max(0,weight);if(total<=0)return -1;double pick=java.util.concurrent.ThreadLocalRandom.current().nextDouble(total);for(int i=0;i<weights.length;i++){pick-=Math.max(0,weights[i]);if(pick<0)return i;}return -1;}
    private TownCatalog(Map<String,Recipe> recipes,Map<String,Map<String,String>> items,Map<String,Sprite> sprites,List<Perk> perks,Map<String,Station> stations){
        this.recipes=Map.copyOf(recipes);this.items=Map.copyOf(items);this.sprites=Map.copyOf(sprites);this.perks=List.copyOf(perks);this.stations=Map.copyOf(stations);
    }
    public static TownCatalog empty(){return new TownCatalog(Map.of(),Map.of(),Map.of(),List.of(),Map.of());}
    public Recipe recipe(String name){return recipes.get(name);}
    public Collection<Recipe> recipes(){return recipes.values();}
    public Map<String,String> item(String name){return items.getOrDefault(name,Map.of());}
    public Sprite sprite(String name){return sprites.get(name);}
    public List<Perk> perks(){return perks;}
    public Station station(String actor){return stations.get(actor);}
    public Collection<Station> stations(){return stations.values();}
    public static TownCatalog load(Path path)throws IOException{
        if(Files.size(path)>8_000_000)throw new IOException("Town catalogue too large");
        return fromData(Json.parse(Files.readString(path)));
    }
    public static TownCatalog fromData(Object data)throws IOException{
        try{
            var root=object(data);if(integer(root,"format")!=1)throw new IOException("Unsupported Town catalogue");
            var recipes=new LinkedHashMap<String,Recipe>();var items=new HashMap<String,Map<String,String>>();var sprites=new HashMap<String,Sprite>();var perks=new ArrayList<Perk>();var stations=new HashMap<String,Station>();
            for(var value:list(root,"recipes")){
                var r=object(value);var ingredients=new ArrayList<Ingredient>();
                for(var iv:list(r,"ingredients")){var i=object(iv);ingredients.add(new Ingredient(string(i,"kind"),string(i,"item"),integer(i,"count"),integer(i,"rarity"),string(i,"group"),integer(i,"level"),string(i,"upgrade"),bool(i,"keepHeat"),strings(i,"options")));}
                var recipe=new Recipe(string(r,"config"),string(r,"item"),integer(r,"count"),bool(r,"locked"),list(r,"crowns").stream().map(v->((Number)v).intValue()).toList(),list(r,"useOrbs").stream().map(v->(Boolean)v).toList(),string(r,"variantTable"),List.copyOf(ingredients),bool(r,"oneTime"),integer(r,"maxHours"));
                if(recipe.count()<1||recipe.count()>9999||recipe.ingredients().stream().anyMatch(i->i.count()<1||i.count()>9999)||recipes.put(recipe.config(),recipe)!=null)throw new IOException("Invalid Town recipe");
            }
            for(var value:list(root,"items")){var i=object(value);var fields=new HashMap<String,String>();i.forEach((k,v)->fields.put(k,(String)v));items.put(string(i,"config"),Map.copyOf(fields));}
            for(var value:list(root,"sprites")){var s=object(value);var skills=new ArrayList<Skill>();for(var raw:(List<?>)s.getOrDefault("skills",List.of())){var skill=object(raw);skills.add(new Skill(strings(skill,"attacks"),bool(skill,"follow"),list(skill,"recharge").stream().map(v->((Number)v).intValue()).toList(),list(skill,"minimum").stream().map(v->((Number)v).intValue()).toList()));}sprites.put(string(s,"config"),new Sprite(string(s,"config"),strings(s,"preferredFood"),(String)s.getOrDefault("restrictDamageType","UNBLOCKABLE"),List.copyOf(skills)));}
            for(var value:list(root,"perks")){var p=object(value);perks.add(new Perk(string(p,"name"),integer(p,"tier"),string(p,"variant"),string(p,"previous")));}
            for(var value:list(root,"stations")){var s=object(value);var station=new Station(string(s,"actor"),string(s,"kind"),string(s,"type"),string(s,"group"),string(s,"name"),string(s,"title"),string(s,"model"),string(s,"description"),strings(s,"recipes"),new Shop((String)s.getOrDefault("catalog",""),((Number)s.getOrDefault("level",10)).intValue(),(String)s.getOrDefault("preparer","BASIC"),(String)s.getOrDefault("cycle","CONSTANT"),(Boolean)s.getOrDefault("perKnight",false)));stations.put(station.actor(),station);}
            var result=new TownCatalog(recipes,items,sprites,perks,stations);result.gear=GearRules.load(root);
            var tables=new HashMap<String,Map<String,Object>>();for(var value:list(root,"variantTables")){var t=object(value);tables.put(string(t,"config"),object(t.get("root")));}result.variantTables=Map.copyOf(tables);
            var accessories=new HashMap<String,Accessory>();for(var value:list(root,"accessories")){var v=object(value);accessories.put(string(v,"config"),new Accessory(string(v,"config"),string(v,"location"),Set.copyOf(strings(v,"blocks")),bool(v,"remove"),bool(v,"destroy")));}result.accessories=Map.copyOf(accessories);
            var blockers=new HashMap<String,Set<String>>();for(var value:list(root,"blockers")){var v=object(value);blockers.put(string(v,"config"),Set.copyOf(strings(v,"slots")));}result.blockers=Map.copyOf(blockers);
            var harnesses=new HashMap<String,String>();for(var value:list(root,"harnesses")){var v=object(value);harnesses.put(string(v,"config"),string(v,"damageType"));result.harnessRecharge.put(string(v,"config"),((Number)v.getOrDefault("rechargeModifier",1)).floatValue());}result.harnesses=Map.copyOf(harnesses);
            var catalogs=new HashMap<String,Catalog>();for(var value:list(root,"catalogs")){var v=object(value);var goods=new ArrayList<Good>();for(var raw:list(v,"goods")){var g=object(raw);goods.add(new Good(string(g,"item"),integer(g,"count"),integer(g,"price"),integer(g,"energy"),integer(g,"limit"),string(g,"token"),integer(g,"tokenCount")));}catalogs.put(string(v,"config"),new Catalog(string(v,"config"),string(v,"kind"),strings(v,"includes"),List.copyOf(goods),integer(v,"min"),integer(v,"max"),((Number)v.get("chance")).floatValue()));}result.catalogs=Map.copyOf(catalogs);
            var bonuses=new HashMap<String,Bonus>();for(var value:list(root,"bonuses")){var v=object(value);bonuses.put(string(v,"config"),new Bonus(string(v,"weapon"),integer(v,"health"),((Number)v.get("charge")).floatValue(),((Number)v.get("speed")).floatValue(),((Number)v.get("attack")).floatValue()));}result.bonuses=Map.copyOf(bonuses);
            var models=new HashMap<String,org.tortodrone.character.StarterCatalog.Model>();var parameters=new HashMap<String,String>();for(var value:list(root,"accessories")){var v=object(value);if(v.containsKey("model")){models.put(string(v,"config"),org.tortodrone.world.FireCatalog.modelJson(string(v,"model")));parameters.put(string(v,"config"),string(v,"parameter"));}}result.accessoryModels=Map.copyOf(models);result.accessoryParameters=Map.copyOf(parameters);
            return result;
        }catch(IllegalArgumentException|ClassCastException|NullPointerException bad){throw new IOException("Invalid Town catalogue",bad);}
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){return (Map<String,Object>)value;}
    private static List<?> list(Map<String,Object> object,String key){return (List<?>)object.get(key);}
    private static List<String> strings(Map<String,Object> object,String key){return list(object,key).stream().map(v->(String)v).toList();}
    private static String string(Map<String,Object> object,String key){return (String)object.get(key);}
    private static int integer(Map<String,Object> object,String key){return ((Number)object.get(key)).intValue();}
    private static boolean bool(Map<String,Object> object,String key){return (Boolean)object.get(key);}
}
