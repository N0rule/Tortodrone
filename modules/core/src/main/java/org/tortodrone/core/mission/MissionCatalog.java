package org.tortodrone.core.mission;

import java.util.*;
import org.tortodrone.core.character.KnightProgress;
import org.tortodrone.core.character.OpeningMission;

/** Validated mission catalogue imported from the stock client's own exported configs.
 * Definitions do not imply dungeon, conversation or reward gameplay support. */
public final class MissionCatalog {
    public static final Set<String> TYPES=Set.of("rank","prestige","expansion","hidden");
    public static final Set<String> DEPLOYMENTS=Set.of("PRODUCTION","PREVIEW","DEV","TESTING","TEST","NONE");
    public static final Set<String> PRESTIGE=Set.of("LOW","AVG","HIGH","MAX");
    public static final Set<String> DIFFICULTIES=Set.of("EASY","MEDIUM","HARD");
    public static final Set<String> RESTRICTIONS=Set.of("rank_range","rank","event","mission","schedule");

    public record Reward(String difficulty,String config,Map<String,Object> arguments) {
        public Reward {Objects.requireNonNull(difficulty);Objects.requireNonNull(config);arguments=arguments==null?Map.of():Collections.unmodifiableMap(new LinkedHashMap<>(arguments));}
    }
    public record Ingredient(String item,int count) {public Ingredient {Objects.requireNonNull(item);if(count<1)throw new IllegalArgumentException("Ingredient count");}}
    public record Choice(int rarity,int choose,List<String> recipes) {public Choice {if(rarity< -1||choose<1)throw new IllegalArgumentException("Invalid mission choice");recipes=List.copyOf(recipes);}}
    public record Requirement(String type,boolean hasObjective,boolean dissolveToLab,String conversation,String model,Map<String,Object> modelArguments,String music,List<Ingredient> ingredients,List<Choice> choices) {
        public Requirement {
            Objects.requireNonNull(type);conversation=conversation==null?"":conversation;model=model==null?"":model;music=music==null?"":music;
            modelArguments=modelArguments==null?Map.of():Collections.unmodifiableMap(new LinkedHashMap<>(modelArguments));
            ingredients=List.copyOf(ingredients);choices=List.copyOf(choices);
        }
    }
    public record Restriction(String type,int rank,int subrank,int maxRank,int maxSubrank,String event,String missionId,Long refTime,String interval) {
        public Restriction {Objects.requireNonNull(type);event=event==null?"":event;missionId=missionId==null?"":missionId;interval=interval==null?"":interval;}
    }
    public record Entry(String id,String name,String type,String deployment,String briefing,String cardImage,int priority,
            boolean showMissionsOnComplete,String prestige,String missionProps,List<Reward> rewards,Requirement requirement,
            int rank,int subrank,boolean repeatable,boolean rescueCamp,boolean retired,String test,boolean inTest,
            String needMission,List<Restriction> restrictions,String group,String tier,boolean daily,boolean onceOnly,boolean support) {
        public Entry {
            Objects.requireNonNull(id);Objects.requireNonNull(name);Objects.requireNonNull(type);Objects.requireNonNull(deployment);
            briefing=briefing==null?"":briefing;cardImage=cardImage==null?"":cardImage;missionProps=missionProps==null?"":missionProps;
            rewards=List.copyOf(rewards);Objects.requireNonNull(requirement);test=test==null?"":test;needMission=needMission==null?"":needMission;
            restrictions=List.copyOf(restrictions);group=group==null?"":group;tier=tier==null?"":tier;
        }
        public boolean rankMission(){return type.equals("rank");}
        public boolean prestigeMission(){return type.equals("prestige");}
        public boolean enabled(){return !deployment.equals("NONE")&&!deployment.equals("TESTING")&&!deployment.equals("TEST");}
        public boolean gate(){return requirement.type().equals("gate")||requirement.type().equals("gate_conversation");}
        public boolean exchange(){return requirement.type().equals("exchange")||requirement.type().equals("choose_exchange");}
        public boolean conversation(){return requirement.type().equals("conversation")||requirement.type().equals("gate_conversation");}
    }
    public record Group(String id,String name,String cardImage,String deployment,boolean purchase) {
        public Group {Objects.requireNonNull(id);Objects.requireNonNull(name);cardImage=cardImage==null?"":cardImage;Objects.requireNonNull(deployment);}
    }
    public record Property(String name,int crownCost,int prestigeLow,int prestigeAvg,int prestigeHigh,int prestigeMax) {
        public Property {Objects.requireNonNull(name);if(prestigeLow<0||prestigeAvg<prestigeLow||prestigeHigh<prestigeAvg||prestigeMax<prestigeHigh||crownCost<0)throw new IllegalArgumentException("Invalid mission property range: "+name);}
    }
    /** One concrete item a mission pays; variants name the stock client's own variant configs. */
    public record Grant(String config,int count,boolean locked,List<String> variants) {
        public Grant {
            Objects.requireNonNull(config);if(config.isBlank()||config.length()>512)throw new IllegalArgumentException("Invalid mission grant config");
            if(count<1||count>9999)throw new IllegalArgumentException("Invalid mission grant count: "+config);
            variants=List.copyOf(variants);
            for(String variant:variants)if(variant==null||variant.isBlank()||variant.length()>512)throw new IllegalArgumentException("Invalid mission grant variant: "+config);
        }
        public Grant(String config,int count){this(config,count,true,List.of());}
    }

    private final Map<String,Entry> missions;
    private final Map<String,Group> groups;
    private final Map<String,Property> properties;
    private final Map<String,List<Grant>> grants;
    private MissionCatalog(Map<String,Entry> missions,Map<String,Group> groups,Map<String,Property> properties,Map<String,List<Grant>> grants){
        this.missions=Collections.unmodifiableMap(new TreeMap<>(missions));
        this.groups=Collections.unmodifiableMap(new TreeMap<>(groups));
        this.properties=Collections.unmodifiableMap(new TreeMap<>(properties));
        this.grants=Collections.unmodifiableMap(new TreeMap<>(grants));
    }
    private MissionCatalog(){this(Map.of(),Map.of(),Map.of(),Map.of());}
    public static MissionCatalog empty(){return new MissionCatalog();}
    public static MissionCatalog of(Map<String,Entry> missions,Map<String,Group> groups,Map<String,Property> properties){
        return of(missions,groups,properties,Map.of());
    }
    public static MissionCatalog of(Map<String,Entry> missions,Map<String,Group> groups,Map<String,Property> properties,Map<String,List<Grant>> grants){
        var checkedMissions=new TreeMap<>(missions);
        for(var entry:checkedMissions.entrySet())if(!entry.getKey().equals(entry.getValue().id()))throw new IllegalArgumentException("Mission key/id mismatch: "+entry.getKey());
        var checkedGroups=new TreeMap<>(groups);
        var checkedProperties=new TreeMap<>(properties);
        var checkedGrants=new TreeMap<String,List<Grant>>();
        for(var entry:grants.entrySet()){if(!checkedMissions.containsKey(entry.getKey()))throw new IllegalArgumentException("Grants reference missing mission: "+entry.getKey());checkedGrants.put(entry.getKey(),List.copyOf(entry.getValue()));}
        for(var entry:checkedMissions.values()){
            if(!entry.missionProps().isEmpty()&&!checkedProperties.containsKey(entry.missionProps()))throw new IllegalArgumentException("Mission "+entry.id()+" references missing properties: "+entry.missionProps());
            if(!entry.needMission().isEmpty()&&!checkedMissions.containsKey(entry.needMission()))throw new IllegalArgumentException("Mission "+entry.id()+" requires missing mission: "+entry.needMission());
            if(!entry.group().isEmpty()&&!checkedGroups.containsKey(entry.group()))throw new IllegalArgumentException("Mission "+entry.id()+" references missing group: "+entry.group());
            for(var restriction:entry.restrictions())if(restriction.type().equals("mission")&&!checkedMissions.containsKey(restriction.missionId()))throw new IllegalArgumentException("Mission "+entry.id()+" restricts on missing mission: "+restriction.missionId());
            if(entry.rankMission()&&!entry.rewards().isEmpty()&&!checkedGrants.containsKey(entry.id()))throw new IllegalArgumentException("Mission "+entry.id()+" has rewards but no concrete grants");
        }
        return new MissionCatalog(checkedMissions,checkedGroups,checkedProperties,checkedGrants);
    }
    private static boolean propertyBand(Property property,String prestige){
        return switch(prestige){case "LOW"->property.prestigeLow()>=0;case "AVG"->property.prestigeAvg()>=0;case "HIGH"->property.prestigeHigh()>=0;case "MAX"->property.prestigeMax()>=0;default->false;};
    }
    public Entry mission(String id){return missions.get(id);}
    public List<Entry> missions(){return List.copyOf(missions.values());}
    public List<Entry> missions(String type){return missions.values().stream().filter(m->m.type().equals(type)).toList();}
    public Group group(String id){return groups.get(id);}
    public List<Group> groups(){return List.copyOf(groups.values());}
    public Property property(String config){return properties.get(config);}
    public List<Property> properties(){return List.copyOf(properties.values());}
    public List<Grant> grants(String id){return grants.getOrDefault(id,List.of());}
    public int size(){return missions.size();}
    /** Official mission-board order: descending priority, then stable id. */
    public List<Entry> rankBoard(int rank,int subrank){
        return missions.values().stream().filter(Entry::rankMission).filter(m->m.rank()==rank&&m.subrank()==subrank)
            .sorted(Comparator.comparingInt(Entry::priority).reversed().thenComparing(Entry::id)).toList();
    }
    /** Completed cards are removed; needMission prerequisites must be complete. */
    public List<Entry> availableRankCards(int rank,int subrank,Set<String> completed){
        return rankBoard(rank,subrank).stream().filter(m->!completed.contains(m.id()))
            .filter(m->m.needMission().isEmpty()||completed.contains(m.needMission())).toList();
    }
    /** Whether the stock client indexes and draws this card for a knight without mission tests.
     * Production deployment and the mission's A/B branch decide this, not gameplay support. */
    public static boolean drawnByClient(Entry entry){
        return entry.rankMission()&&entry.deployment().equals("PRODUCTION")&&!entry.support()&&!entry.retired()
            &&(entry.test().isEmpty()||!entry.inTest());
    }
    private static int comparison(int rank,int subrank,int otherRank,int otherSubrank){
        return rank!=otherRank?Integer.compare(rank,otherRank):Integer.compare(subrank,otherSubrank);
    }
    /** Catalogue start rule: drawn card, reached rank, completed needMission, and the same
     * opening-route checkpoint the verified Rescue Camp chain requires. */
    public boolean eligible(String id,KnightProgress progress,Set<String> completed){
        Entry entry=missions.get(id);
        if(entry==null||!drawnByClient(entry)||completed.contains(id))return false;
        if(comparison(entry.rank(),entry.subrank(),progress.rank(),progress.subrank())>0)return false;
        if(!entry.needMission().isEmpty()&&!completed.contains(entry.needMission()))return false;
        var opening=OpeningMission.byId(id);
        return opening==null||progress.openingStage()==opening.stage();
    }
    /** Prestige paid by the mission's own property band (archive `prestigeFor`). */
    public int prestige(String id){
        Entry entry=missions.get(id);
        if(entry==null||!entry.rankMission())return 0;
        Property property=properties.get(entry.missionProps());
        if(property==null)return 0;
        return switch(entry.prestige()){case "LOW"->property.prestigeLow();case "AVG"->property.prestigeAvg();case "HIGH"->property.prestigeHigh();case "MAX"->property.prestigeMax();default->0;};
    }
    /** Archive `MissionManager.promotionFor`: the lowest production rank pair at or above the
     * knight's own that still holds an unfinished card on this knight's A/B branch. */
    public int[] promotionFor(KnightProgress progress,Set<String> completed){
        int[] best=null;
        for(Entry entry:missions.values()){
            if(!entry.rankMission()||!entry.deployment().equals("PRODUCTION")||entry.support()||entry.rank()>10)continue;
            if(!entry.test().isEmpty()&&entry.inTest)continue;
            if(comparison(entry.rank(),entry.subrank(),progress.rank(),progress.subrank())<0||completed.contains(entry.id()))continue;
            if(best==null||comparison(entry.rank(),entry.subrank(),best[0],best[1])<0)best=new int[]{entry.rank(),entry.subrank()};
        }
        return best!=null&&comparison(best[0],best[1],progress.rank(),progress.subrank())>0?best:null;
    }
}
