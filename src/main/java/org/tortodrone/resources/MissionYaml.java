package org.tortodrone.resources;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.MissionCatalog;
import org.tortodrone.character.StarterCatalog;
import org.tortodrone.world.MissionComlinks;
import org.tortodrone.world.MissionGates;
import org.tortodrone.world.MissionRun;
import org.tortodrone.world.SceneConversation;
import static org.tortodrone.resources.Yaml.*;

/** Validated reader for the imported stock mission catalogue. No gameplay is implied. */
public final class MissionYaml {
    private MissionYaml() { }
    private static final Set<String> ROW_FIELDS=Set.of("name","type","deployment","support","priority","showMissionsOnComplete",
        "prestige","briefing","cardImage","missionProps","rewards","requirement","repeatable","rescueCamp","retired","test",
        "inTest","rank","subrank","needMission","daily","onceOnly","restrictions","group","tier");

    public record Definitions(MissionCatalog missions,MissionComlinks comlinks,MissionGates gates) { }

    /** Layout marker: absent or "1" is the flat v1 tree, "2" is per-mission folders. */
    public static int format(Path content)throws IOException {
        Path marker=content.resolve("format.txt");
        if(Files.notExists(marker))return 1;
        ContentFiles.regular(marker);
        if(Files.size(marker)>3)throw new IOException("Invalid content format marker: "+marker);
        String text=Files.readString(marker);
        String value=text.endsWith("\r\n")?text.substring(0,text.length()-2):text.endsWith("\n")?text.substring(0,text.length()-1):null;
        if(!"1".equals(value)&&!"2".equals(value))throw new IOException(marker+": expected exactly one line containing 1 or 2");
        return value.equals("1")?1:2;
    }

    public static MissionCatalog load(Path directory)throws IOException{return readV1(directory).missions();}

    /** Read the layout the marker selects; a missing marker stays v1 for existing deployments. */
    public static Definitions definitions(Path content)throws IOException {
        ContentFiles.safe(content);
        return format(content)==2?readV2(content):readV1(content.resolve("missions"));
    }

    private static Definitions readV1(Path directory)throws IOException {
        if(!Files.exists(directory))return empty();
        ContentFiles.safe(directory);if(!Files.isDirectory(directory))throw new IOException("Mission catalogue is not a directory: "+directory);
        var b=new Building();
        try(var paths=Files.list(directory)) {
            var files=new ArrayList<Path>();
            for(Path entry:paths.sorted().toList()) {
                if(Files.isDirectory(entry,LinkOption.NOFOLLOW_LINKS))
                    throw new IOException(directory+": v2 mission folders require content/format.txt 2: "+entry.getFileName());
                files.add(entry);
            }
            if(files.size()>16)throw new IOException("Too many mission files: "+directory);
            for(Path file:files) {
                if(!file.getFileName().toString().matches("[a-z][a-z0-9_-]*\\.yml"))throw new IOException("Invalid mission file name: "+file);
                var root=read(file);var sections=root.keySet();
                if(sections.size()!=1)throw new IOException(file+": expected one of missions, groups, properties, grants, comlinks or routes");
                merge(b,sections.iterator().next(),root,file);
            }
        }
        return finish(directory,b);
    }

    private static Definitions readV2(Path content)throws IOException {
        var b=new Building();
        Path shared=content.resolve("shared/missions");
        for(String name:List.of("groups.yml","properties.yml")) {
            Path file=shared.resolve(name);
            if(Files.notExists(file))continue;
            var root=read(file);var sections=root.keySet();
            if(sections.size()!=1)throw new IOException(file+": expected one of groups or properties");
            String section=sections.iterator().next();
            if(!section.equals("groups")&&!section.equals("properties"))throw new IOException(file+": expected one of groups or properties");
            merge(b,section,root,file);
        }
        Path directory=content.resolve("missions");
        if(Files.notExists(directory))return finish(directory,b);
        ContentFiles.safe(directory);if(!Files.isDirectory(directory))throw new IOException("Mission catalogue is not a directory: "+directory);
        try(var paths=Files.list(directory)) {
            for(Path entry:paths.sorted().toList()) {
                if(Files.isRegularFile(entry,LinkOption.NOFOLLOW_LINKS))
                    throw new IOException(directory+": mixed v1 flat mission files and v2 mission folders: "+entry.getFileName());
                if(!Files.isDirectory(entry,LinkOption.NOFOLLOW_LINKS))throw new IOException(directory+": invalid mission entry: "+entry.getFileName());
                String id=entry.getFileName().toString();
                if(!id.matches("[a-z][a-z0-9_]{0,127}"))throw new IOException(directory+": invalid mission folder name "+id);
                missionFolder(entry,id,b);
            }
        }
        return finish(directory,b);
    }

    private static void missionFolder(Path folder,String id,Building b)throws IOException {
        Path missionFile=null,grantsFile=null,comlinkFile=null,routeFile=null;
        try(var paths=Files.list(folder)) {
            for(Path entry:paths.sorted().toList()) {
                String name=entry.getFileName().toString();
                if(!Files.isRegularFile(entry,LinkOption.NOFOLLOW_LINKS))throw new IOException(folder+": unknown mission folder entry "+name);
                switch(name) {
                    case "mission.yml" -> missionFile=entry;
                    case "grants.yml" -> grantsFile=entry;
                    case "comlink.yml" -> comlinkFile=entry;
                    case "route.yml" -> routeFile=entry;
                    case "objectives.yml" -> throw new IOException(folder+": objectives.yml is not supported until the objective subsystem exists");
                    default -> throw new IOException(folder+": unknown mission file "+name);
                }
            }
        }
        if(missionFile==null)throw new IOException(folder+": missing mission.yml");
        merge(b,"missions",single(missionFile,"missions",id),missionFile);
        if(grantsFile!=null)merge(b,"grants",single(grantsFile,"grants",id),grantsFile);
        if(comlinkFile!=null)merge(b,"comlinks",single(comlinkFile,"comlinks",id),comlinkFile);
        if(routeFile!=null)merge(b,"routes",single(routeFile,"routes",id),routeFile);
    }

    /** One section holding exactly one row, whose key is the folder's mission id. */
    private static Map<String,Object> single(Path file,String section,String id)throws IOException {
        var root=read(file);var sections=root.keySet();
        if(sections.size()!=1||!sections.contains(section))throw new IOException(file+": expected one "+section+" section");
        var rows=map(root.get(section),file+" "+section);
        if(rows.size()!=1)throw new IOException(file+": expected exactly one "+section+" row");
        if(!rows.keySet().iterator().next().equals(id))throw new IOException(file+": mission id does not match folder "+id);
        return root;
    }

    private record Building(LinkedHashMap<String,MissionCatalog.Entry> missions,LinkedHashMap<String,MissionCatalog.Group> groups,
            LinkedHashMap<String,MissionCatalog.Property> properties,LinkedHashMap<String,List<MissionCatalog.Grant>> grants,
            LinkedHashMap<String,SceneConversation> comlinks,LinkedHashMap<String,MissionGates.Gate> gates) {
        Building() {
            this(new LinkedHashMap<>(),new LinkedHashMap<>(),new LinkedHashMap<>(),new LinkedHashMap<>(),new LinkedHashMap<>(),new LinkedHashMap<>());
        }
    }

    private static Definitions empty(){return new Definitions(MissionCatalog.empty(),MissionComlinks.empty(),MissionGates.empty());}

    private static void merge(Building b,String section,Map<String,Object> root,Path file)throws IOException {
        switch(section) {
            case "missions" -> {
                if(!b.missions().isEmpty()&&file.getFileName().toString().equals("groups.yml"))throw new IOException(file+": duplicated mission section");
                for(var row:map(root.get("missions"),file+" missions").entrySet()) {
                    String id=row.getKey();if(!id.matches("[a-z][a-z0-9_]{0,127}"))throw new IOException(file+": invalid mission id "+id);
                    var entry=mission(id,map(row.getValue(),file+" mission "+id),file+" mission "+id);
                    if(b.missions().putIfAbsent(id,entry)!=null)throw new IOException(file+": duplicate mission id "+id);
                }
            }
            case "groups" -> {
                for(var row:map(root.get("groups"),file+" groups").entrySet()) {
                    var group=group(row.getKey(),map(row.getValue(),file+" group "+row.getKey()),file+" group "+row.getKey());
                    if(b.groups().putIfAbsent(group.id(),group)!=null)throw new IOException(file+": duplicate mission group "+group.id());
                }
            }
            case "properties" -> {
                for(var row:map(root.get("properties"),file+" properties").entrySet()) {
                    var property=property(row.getKey(),map(row.getValue(),file+" property "+row.getKey()),file+" property "+row.getKey());
                    if(b.properties().putIfAbsent(property.name(),property)!=null)throw new IOException(file+": duplicate mission properties "+property.name());
                }
            }
            case "grants" -> {
                for(var row:map(root.get("grants"),file+" grants").entrySet()) {
                    String id=row.getKey();if(!id.matches("[a-z][a-z0-9_]{0,127}"))throw new IOException(file+": invalid grant mission id "+id);
                    if(b.grants().putIfAbsent(id,grants(row.getValue(),file+" grants "+id))!=null)throw new IOException(file+": duplicate grant mission "+id);
                }
            }
            case "comlinks" -> {
                for(var row:map(root.get("comlinks"),file+" comlinks").entrySet()) {
                    String id=row.getKey();if(!id.matches("[a-z][a-z0-9_]{0,127}"))throw new IOException(file+": invalid comlink mission id "+id);
                    if(b.comlinks().putIfAbsent(id,comlink(id,map(row.getValue(),file+" comlink "+id),file+" comlink "+id))!=null)throw new IOException(file+": duplicate comlink mission "+id);
                }
            }
            case "routes" -> {
                for(var row:map(root.get("routes"),file+" routes").entrySet()) {
                    String id=row.getKey();if(!id.matches("[a-z][a-z0-9_]{0,127}"))throw new IOException(file+": invalid route mission id "+id);
                    if(b.gates().putIfAbsent(id,gate(id,map(row.getValue(),file+" route "+id),file+" route "+id))!=null)throw new IOException(file+": duplicate route mission "+id);
                }
            }
            default -> throw new IOException(file+": unknown section "+section);
        }
    }

    private static Definitions finish(Path directory,Building b)throws IOException {
        MissionCatalog catalog;
        try{catalog=MissionCatalog.of(b.missions(),b.groups(),b.properties(),b.grants());}catch(IllegalArgumentException invalid){throw new IOException(directory+": "+invalid.getMessage(),invalid);}
        for(String id:b.comlinks().keySet()) {
            var mission=catalog.mission(id);
            if(mission==null)throw new IOException(directory+": comlink for missing mission "+id);
            if(!mission.requirement().type().equals("conversation")&&!mission.requirement().type().equals("gate_conversation"))throw new IOException(directory+": comlink mission is not a conversation: "+id);
        }
        for(String id:b.gates().keySet()) {
            var mission=catalog.mission(id);
            if(mission==null)throw new IOException(directory+": route for missing mission "+id);
            if(!mission.requirement().type().equals("gate")&&!mission.requirement().type().equals("gate_conversation"))throw new IOException(directory+": route mission is not a gate: "+id);
        }
        try{return new Definitions(catalog,new MissionComlinks(b.comlinks()),MissionGates.of(b.gates()));}catch(IllegalArgumentException invalid){throw new IOException(directory+": "+invalid.getMessage(),invalid);}
    }

    private static MissionGates.Gate gate(String id,Map<String,Object> row,String where)throws IOException {
        fields(row,where,"gateId","icon","colors","description","sourceId","destId","priority","tokens","flags","wheels");
        var colors=new ArrayList<Integer>();
        for(Object raw:list(row.get("colors"),where+" colors")) {
            if(colors.size()>=8)throw new IOException(where+": too many colors");
            colors.add(integer(raw,where+" color"));
        }
        var wedges=new ArrayList<MissionGates.Wedge>();
        for(Object rawWheel:list(row.get("wheels"),where+" wheels")) {
            var wheel=map(rawWheel,where+" wheel");
            fields(wheel,where+" wheel","skip","velocity","start","lengths","wedges");
            var lengths=list(wheel.get("lengths"),where+" lengths");
            if(lengths.size()!=1)throw new IOException(where+": only single-wedge wheels are imported");
            float length=decimal(lengths.getFirst(),where+" length");
            int skip=integer(wheel.getOrDefault("skip",0),where+" skip");
            float velocity=decimal(wheel.getOrDefault("velocity",0),where+" velocity");
            long start=longValue(wheel.getOrDefault("start",0),where+" start");
            for(Object rawWedge:list(wheel.get("wedges"),where+" wedges")) {
                if(wedges.size()>=MissionRun.MAX_FLOORS)throw new IOException(where+": too many floors");
                var wedge=map(rawWedge,where+" wedge");
                fields(wedge,where+" wedge","scene","level","name","icon","description","type","restricted");
                wedges.add(new MissionGates.Wedge(integer(wedge.get("scene"),where+" scene"),text(wedge.get("level"),where+" level"),
                    text(wedge.get("name"),where+" name"),text(wedge.get("icon"),where+" icon"),
                    text(wedge.get("description"),where+" description"),text(wedge.get("type"),where+" type"),
                    flag(wedge.get("restricted"),where+" restricted"),skip,velocity,start,length));
            }
        }
        return new MissionGates.Gate(id,integer(row.get("gateId"),where+" gateId"),text(row.get("icon"),where+" icon"),colors,
            text(row.get("description"),where+" description"),integer(row.get("sourceId"),where+" sourceId"),
            integer(row.get("destId"),where+" destId"),integer(row.get("priority"),where+" priority"),
            integer(row.get("tokens"),where+" tokens"),integer(row.get("flags"),where+" flags"),wedges);
    }

    private static SceneConversation comlink(String id,Map<String,Object> row,String where)throws IOException {
        fields(row,where,"name","initial","model","music","nodes");
        String name=text(row.get("name"),where+" name");bounded(name,where+" name");
        String initial=text(row.get("initial"),where+" initial");bounded(initial,where+" initial");
        StarterCatalog.Model model=model(row.get("model"),where+" model");
        StarterCatalog.Model music=model(row.get("music"),where+" music");
        var nodes=new LinkedHashMap<String,SceneConversation.Node>();
        for(var entry:map(row.get("nodes"),where+" nodes").entrySet()) {
            String nodeId=entry.getKey();bounded(nodeId,where+" node id");
            if(nodes.size()>=256)throw new IOException(where+": too many comlink nodes");
            var node=map(entry.getValue(),where+" node "+nodeId);
            fields(node,where+" node "+nodeId,"message","interface","animation","transient","responses");
            String message=text(node.get("message"),where+" "+nodeId+" message");bounded(message,where+" "+nodeId+" message");
            var responses=new ArrayList<SceneConversation.Response>();
            for(Object raw:list(node.get("responses"),where+" "+nodeId+" responses")) {
                if(responses.size()>=16)throw new IOException(where+": too many comlink responses");
                var response=map(raw,where+" "+nodeId+" response");
                fields(response,where+" "+nodeId+" response","text","destination","terminal");
                String text=text(response.get("text"),where+" "+nodeId+" response text");bounded(text,where+" "+nodeId+" response text");
                String destination=optionalText(response.get("destination"),where+" "+nodeId+" destination");
                if(!destination.isEmpty())bounded(destination,where+" "+nodeId+" destination");
                responses.add(new SceneConversation.Response(text,destination,"","",null,flag(response.getOrDefault("terminal",false),where+" "+nodeId+" terminal")));
            }
            if(responses.isEmpty())throw new IOException(where+": comlink node has no responses: "+nodeId);
            if(nodes.putIfAbsent(nodeId,new SceneConversation.Node(nodeId,message,responses,
                model(node.get("animation"),where+" "+nodeId+" animation"),model(node.get("transient"),where+" "+nodeId+" transient"),
                model(node.get("interface"),where+" "+nodeId+" interface")))!=null)throw new IOException(where+": duplicate comlink node "+nodeId);
        }
        if(nodes.isEmpty())throw new IOException(where+": comlink has no nodes");
        return MissionComlinks.conversation(id,name,model,music,initial,nodes);
    }

    private static StarterCatalog.Model model(Object raw,String where)throws IOException {
        if(raw==null)return null;
        if(raw instanceof String text) {
            String name=text(text,where);bounded(name,where);return name.isBlank()?null:new StarterCatalog.Model(name,Map.of());
        }
        var row=map(raw,where);fields(row,where,"name","arguments");
        String name=text(row.get("name"),where+" name");bounded(name,where+" name");
        return new StarterCatalog.Model(name,row.containsKey("arguments")?arguments(row.get("arguments"),where+" arguments"):Map.of());
    }

    private static MissionCatalog.Entry mission(String id,Map<String,Object> row,String where)throws IOException {
        fields(row,where,ROW_FIELDS.toArray(String[]::new));
        String name=text(row.get("name"),where+" name");bounded(name,where+" name");
        String type=text(row.get("type"),where+" type");if(!MissionCatalog.TYPES.contains(type))throw new IOException(where+": unsupported mission type "+type);
        String deployment=text(row.getOrDefault("deployment","DEV"),where+" deployment");
        if(!MissionCatalog.DEPLOYMENTS.contains(deployment))throw new IOException(where+": unsupported deployment "+deployment);
        boolean support=flag(row.getOrDefault("support",false),where+" support");
        int priority=integer(row.getOrDefault("priority",0),where+" priority");if(priority<0||priority>1000)throw new IOException(where+": invalid priority");
        boolean show=flag(row.getOrDefault("showMissionsOnComplete",true),where+" showMissionsOnComplete");
        String prestige=text(row.getOrDefault("prestige","AVG"),where+" prestige");
        if(!MissionCatalog.PRESTIGE.contains(prestige))throw new IOException(where+": unsupported prestige "+prestige);
        String briefing=optionalText(row.get("briefing"),where+" briefing"),cardImage=optionalText(row.get("cardImage"),where+" cardImage");
        String missionProps=optionalText(row.get("missionProps"),where+" missionProps");
        int rank=0,subrank=0;boolean repeatable=false,rescueCamp=false,retired=false,inTest=false,daily=false,onceOnly=false;
        String test="",needMission="",group="",tier="";
        if(type.equals("rank")) {
            rank=integer(row.get("rank"),where+" rank");subrank=integer(row.get("subrank"),where+" subrank");
            if(rank<1||rank>10||subrank<1||subrank>4)throw new IOException(where+": invalid rank pair "+rank+"-"+subrank);
            repeatable=flag(row.getOrDefault("repeatable",true),where+" repeatable");
            rescueCamp=flag(row.getOrDefault("rescueCamp",false),where+" rescueCamp");
            retired=flag(row.getOrDefault("retired",false),where+" retired");
            inTest=flag(row.getOrDefault("inTest",true),where+" inTest");
            test=optionalText(row.get("test"),where+" test");needMission=optionalText(row.get("needMission"),where+" needMission");
        } else if(type.equals("prestige")) {
            daily=flag(row.getOrDefault("daily",true),where+" daily");
            onceOnly=flag(row.getOrDefault("onceOnly",false),where+" onceOnly");
        } else if(type.equals("expansion")) {
            group=optionalText(row.get("group"),where+" group");tier=optionalText(row.get("tier"),where+" tier");
        }
        var requirement=requirement(map(row.getOrDefault("requirement",Map.of("type","gate")),where+" requirement"),where+" requirement");
        var rewards=rewards(row.get("rewards"),where+" rewards");
        var restrictions=restrictions(row.get("restrictions"),where+" restrictions");
        if(type.equals("rank")&&(row.containsKey("daily")||row.containsKey("onceOnly")||row.containsKey("restrictions")))throw new IOException(where+": rank missions do not accept prestige restriction fields");
        if(type.equals("prestige")&&(row.containsKey("rank")||row.containsKey("subrank")||row.containsKey("needMission")))throw new IOException(where+": prestige missions do not accept rank fields");
        if(type.equals("hidden")&&(row.containsKey("rank")||row.containsKey("subrank")||row.containsKey("group")))throw new IOException(where+": hidden missions do not accept rank or group fields");
        return new MissionCatalog.Entry(id,name,type,deployment,briefing,cardImage,priority,show,prestige,missionProps,rewards,requirement,
            rank,subrank,repeatable,rescueCamp,retired,test,inTest,needMission,restrictions,group,tier,daily,onceOnly,support);
    }

    private static MissionCatalog.Requirement requirement(Map<String,Object> row,String where)throws IOException {
        String type=text(row.get("type"),where+" type");
        boolean objective=true,dissolve=false;String conversation="",model="",music="";Map<String,Object> arguments=Map.of();
        var ingredients=new ArrayList<MissionCatalog.Ingredient>();var choices=new ArrayList<MissionCatalog.Choice>();
        switch(type) {
            case "gate" -> fields(row,where,"type","hasObjective","dissolveToLab");
            case "gate_conversation" -> fields(row,where,"type","hasObjective","dissolveToLab","conversation");
            case "conversation" -> fields(row,where,"type","conversation");
            case "exchange" -> fields(row,where,"type","ingredients");
            case "choose_exchange" -> fields(row,where,"type","ingredients","choose");
            default -> throw new IOException(where+": unsupported requirement "+type);
        }
        if(row.containsKey("hasObjective"))objective=flag(row.get("hasObjective"),where+" hasObjective");
        if(row.containsKey("dissolveToLab"))dissolve=flag(row.get("dissolveToLab"),where+" dissolveToLab");
        if(row.containsKey("ingredients"))for(Object raw:list(row.get("ingredients"),where+" ingredients")) {
            if(ingredients.size()>=64)throw new IOException(where+": too many ingredients");
            var item=map(raw,where+" ingredient");fields(item,where+" ingredient","item","count");
            ingredients.add(new MissionCatalog.Ingredient(text(item.get("item"),where+" ingredient item"),integer(item.getOrDefault("count",1),where+" ingredient count")));
        }
        if(row.containsKey("choose"))for(Object raw:list(row.get("choose"),where+" choose")) {
            if(choices.size()>=64)throw new IOException(where+": too many choices");
            var choice=map(raw,where+" choice");fields(choice,where+" choice","rarity","choose","recipes");
            var recipes=new ArrayList<String>();
            for(Object recipe:list(choice.get("recipes"),where+" recipes")) {
                if(recipes.size()>=64)throw new IOException(where+": too many recipes");
                var ref=map(recipe,where+" recipe");fields(ref,where+" recipe","name","arguments");
                recipes.add(text(ref.get("name"),where+" recipe name"));
            }
            choices.add(new MissionCatalog.Choice(integer(choice.getOrDefault("rarity",-1),where+" rarity"),integer(choice.getOrDefault("choose",1),where+" choose"),recipes));
        }
        if(row.containsKey("conversation")) {
            var data=map(row.get("conversation"),where+" conversation");fields(data,where+" conversation","name","conversation","model","music");
            conversation=optionalText(data.get("conversation"),where+" conversation id");
            if(conversation.isEmpty())conversation=optionalText(data.get("name"),where+" conversation name");
            if(data.containsKey("music")) {
                Object raw=data.get("music");
                music=raw instanceof Map<?,?>?optionalText(map(raw,where+" music").get("name"),where+" music name"):optionalText(raw,where+" music");
            }
            if(data.containsKey("model")) {
                var fixed=map(data.get("model"),where+" model");fields(fixed,where+" model","name","arguments");
                model=text(fixed.get("name"),where+" model name");
                if(fixed.containsKey("arguments"))arguments=arguments(fixed.get("arguments"),where+" model arguments");
            }
        }
        if(type.equals("conversation")&&conversation.isEmpty())throw new IOException(where+": conversation requirement has no conversation");
        return new MissionCatalog.Requirement(type,objective,dissolve,conversation,model,arguments,music,ingredients,choices);
    }

    private static List<MissionCatalog.Reward> rewards(Object raw,String where)throws IOException {
        var result=new ArrayList<MissionCatalog.Reward>();
        if(raw==null)return result;
        for(Object value:list(raw,where)) {
            if(result.size()>=64)throw new IOException(where+": too many rewards");
            var row=map(value,where+" entry");fields(row,where+" entry","difficulty","rewards");
            String difficulty=text(row.getOrDefault("difficulty","EASY"),where+" difficulty");
            if(!MissionCatalog.DIFFICULTIES.contains(difficulty))throw new IOException(where+": unsupported difficulty "+difficulty);
            var reference=map(row.get("rewards"),where+" reference");fields(reference,where+" reference","name","arguments");
            result.add(new MissionCatalog.Reward(difficulty,text(reference.get("name"),where+" reward name"),
                reference.containsKey("arguments")?arguments(reference.get("arguments"),where+" reward arguments"):Map.of()));
        }
        return result;
    }

    private static List<MissionCatalog.Grant> grants(Object raw,String where)throws IOException {
        var result=new ArrayList<MissionCatalog.Grant>();
        if(raw==null)return result;
        for(Object value:list(raw,where)) {
            if(result.size()>=64)throw new IOException(where+": too many grants");
            var row=map(value,where+" entry");fields(row,where+" entry","config","count","locked","variants");
            var variants=new ArrayList<String>();
            for(Object variant:list(row.getOrDefault("variants",List.of()),where+" variants")) {
                if(variants.size()>=16)throw new IOException(where+": too many grant variants");
                variants.add(text(variant,where+" variant"));
            }
            try{result.add(new MissionCatalog.Grant(text(row.get("config"),where+" config"),
                integer(row.getOrDefault("count",1),where+" count"),flag(row.getOrDefault("locked",true),where+" locked"),variants));}
            catch(IllegalArgumentException invalid){throw new IOException(where+": "+invalid.getMessage(),invalid);}
        }
        return result;
    }

    private static List<MissionCatalog.Restriction> restrictions(Object raw,String where)throws IOException {
        var result=new ArrayList<MissionCatalog.Restriction>();
        if(raw==null)return result;
        for(Object value:list(raw,where)) {
            if(result.size()>=64)throw new IOException(where+": too many restrictions");
            var row=map(value,where+" entry");String type=text(row.get("type"),where+" type");
            if(!MissionCatalog.RESTRICTIONS.contains(type))throw new IOException(where+": unsupported restriction "+type);
            int rank=0,subrank=0,maxRank=0,maxSubrank=0;String event="",missionId="",interval="";Long refTime=null;
            switch(type) {
                case "rank_range" -> {fields(row,where+" rank range","type","rank","subrank","maxRank","maxSubrank");rank=integer(row.get("rank"),where+" rank");maxRank=integer(row.get("maxRank"),where+" maxRank");if(row.containsKey("subrank"))subrank=integer(row.get("subrank"),where+" subrank");if(row.containsKey("maxSubrank"))maxSubrank=integer(row.get("maxSubrank"),where+" maxSubrank");}
                case "rank" -> {fields(row,where+" rank","type","rank","subrank");rank=integer(row.get("rank"),where+" rank");subrank=integer(row.get("subrank"),where+" subrank");}
                case "event" -> {fields(row,where+" event","type","event");event=text(row.get("event"),where+" event");}
                case "mission" -> {fields(row,where+" mission","type","missionId");missionId=text(row.get("missionId"),where+" missionId");}
                case "schedule" -> {fields(row,where+" schedule","type","schedule");var schedule=map(row.get("schedule"),where+" schedule");fields(schedule,where+" schedule","refTime","interval");refTime=longValue(schedule.get("refTime"),where+" refTime");interval=text(schedule.get("interval"),where+" interval");if(!interval.matches("\\d{1,6}[dhm]"))throw new IOException(where+": invalid schedule interval");}
            }
            result.add(new MissionCatalog.Restriction(type,rank,subrank,maxRank,maxSubrank,event,missionId,refTime,interval));
        }
        return result;
    }

    private static MissionCatalog.Group group(String id,Map<String,Object> row,String where)throws IOException {
        fields(row,where,"name","id","deployment","purchase","cardImage");
        String name=text(row.get("name"),where+" name");String declared=text(row.get("id"),where+" id");
        if(!id.equals(declared))throw new IOException(where+": key/id mismatch");
        String deployment=text(row.getOrDefault("deployment","DEV"),where+" deployment");
        if(!MissionCatalog.DEPLOYMENTS.contains(deployment))throw new IOException(where+": unsupported deployment "+deployment);
        return new MissionCatalog.Group(id,name,optionalText(row.get("cardImage"),where+" cardImage"),deployment,flag(row.getOrDefault("purchase",false),where+" purchase"));
    }

    private static MissionCatalog.Property property(String name,Map<String,Object> row,String where)throws IOException {
        fields(row,where,"name","crownCost","prestigeLow","prestigeAvg","prestigeHigh","prestigeMax");
        String declared=text(row.get("name"),where+" name");if(!name.equals(declared))throw new IOException(where+": key/name mismatch");
        try{
            return new MissionCatalog.Property(name,integer(row.getOrDefault("crownCost",0),where+" crownCost"),
                integer(row.getOrDefault("prestigeLow",0),where+" prestigeLow"),integer(row.getOrDefault("prestigeAvg",0),where+" prestigeAvg"),
                integer(row.getOrDefault("prestigeHigh",0),where+" prestigeHigh"),integer(row.getOrDefault("prestigeMax",0),where+" prestigeMax"));
        }catch(IllegalArgumentException invalid){throw new IOException(where+": "+invalid.getMessage(),invalid);}
    }

    private static String optionalText(Object value,String where)throws IOException {
        if(value==null)return "";String text=text(value,where);bounded(text,where);return text;
    }
    private static void bounded(String value,String where)throws IOException {if(value.length()>512)throw new IOException(where+": text exceeds 512 characters");}
    private static long longValue(Object value,String where)throws IOException {
        if(!(value instanceof Number number))throw new IOException(where+": expected an integer");
        if(number instanceof Integer||number instanceof Long)return number.longValue();
        throw new IOException(where+": expected an integer");
    }
    /** Preserve native action arguments without inventing typed fields. */
    private static Map<String,Object> arguments(Object raw,String where)throws IOException {
        var result=new LinkedHashMap<String,Object>();
        var root=map(raw,where);if(root.size()>64)throw new IOException(where+": too many arguments");
        for(var entry:root.entrySet()){bounded(entry.getKey(),where+" key");result.put(entry.getKey(),actionValue(entry.getValue(),where,0));}
        return result;
    }
    private static Object actionValue(Object value,String where,int depth)throws IOException {
        if(depth>8)throw new IOException(where+": argument nesting exceeds limit");
        if(value instanceof Map<?,?> raw) {
            var result=new LinkedHashMap<String,Object>();
            for(var entry:map(value,where).entrySet()){bounded(entry.getKey(),where+" key");result.put(entry.getKey(),actionValue(entry.getValue(),where,depth+1));}
            return result;
        }
        if(value instanceof List<?> raw) {
            if(raw.size()>128)throw new IOException(where+": argument list exceeds limit");
            var result=new ArrayList<Object>();for(Object item:raw)result.add(actionValue(item,where,depth+1));return result;
        }
        if(value instanceof String text){bounded(text,where);return text;}
        if(value instanceof Integer||value instanceof Long||value instanceof Boolean||value instanceof Float||value instanceof Double)return value;
        throw new IOException(where+": unsupported argument value");
    }
}
