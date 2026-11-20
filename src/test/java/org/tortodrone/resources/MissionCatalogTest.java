package org.tortodrone.resources;

import java.nio.file.*;
import java.util.*;
import org.tortodrone.character.MissionCatalog;
import static org.tortodrone.support.TestWire.check;

/** The complete imported stock mission catalogue and its validation boundaries. */
public final class MissionCatalogTest {
    private MissionCatalogTest() { }
    public static void main(String[] args)throws Exception {
        var baseline=ContentManager.load(Path.of("."));
        var missions=baseline.missions();
        check(missions.size()==273,"all stock missions are imported");
        check(missions.missions("rank").size()==108&&missions.missions("prestige").size()==147
            &&missions.missions("expansion").size()==3&&missions.missions("hidden").size()==15,"stock implementation split is retained");
        check(missions.groups().size()==1&&missions.group("operation_crimson_hammer").purchase()
            &&missions.group("operation_crimson_hammer").name().equals("Operation Crimson Hammer"),"Crimson Hammer group is imported with purchase access");
        check(missions.properties().size()==27&&missions.property("Rank/Rank 1").prestigeAvg()==10
            &&missions.property("Rank/Rank 10").prestigeMax()==200,"prestige/crown properties are retained");

        var chasm=missions.mission("crossing_chasm");
        check(chasm.name().equals("Rank/01/1.2/Crossing the Chasm")&&chasm.rank()==1&&chasm.subrank()==2
            &&!chasm.repeatable()&&chasm.rescueCamp()&&chasm.test().equals("old_experience"),"rank pair and rescue test fields match the stock card");
        check(chasm.gate()&&chasm.rewards().size()==1&&chasm.rewards().getFirst().config().equals("Mission/Rank/01/1.2/Crossing the Chasm (Easy)")
            &&chasm.rewards().getFirst().difficulty().equals("EASY"),"gate reward reference is retained verbatim");

        var alchemy=missions.mission("alchemy_beginners");
        check(alchemy.conversation()&&alchemy.requirement().model().equals("character/npc/crew/model_bosco.dat")
            &&alchemy.requirement().conversation().equals("Mission/Rank/Alchemy for Beginners A")
            &&alchemy.needMission().equals("mission_sprite_3"),"conversation mission keeps its NPC, conversation and prerequisite");
        check(alchemy.requirement().modelArguments().isEmpty(),"absent native model arguments stay absent");

        var prestige=missions.mission("p_anniversary_shock_r4");
        check(prestige.rewards().size()==3&&prestige.rewards().get(1).difficulty().equals("MEDIUM")
            &&prestige.rewards().get(2).difficulty().equals("HARD"),"prestige difficulty rewards are retained");
        var compound=prestige.rewards().getFirst().arguments().get("Entries");
        check(compound instanceof Map<?,?> entries&&entries.get("entries") instanceof List<?> list&&list.size()==2,
            "compound spawn-table reward arguments are retained as native data");
        check(prestige.restrictions().stream().anyMatch(r->r.type().equals("event")&&r.event().equals("Anniversary"))
            &&prestige.restrictions().stream().anyMatch(r->r.type().equals("rank_range")&&r.rank()==4&&r.maxRank()==6),
            "event and rank restrictions are retained");

        var expansion=missions.mission("operation_crimson_hammer_1");
        check(expansion.type().equals("expansion")&&expansion.group().equals("operation_crimson_hammer")
            &&expansion.prestige().equals("MAX")&&expansion.rewards().getFirst().config().contains("Easy"),"expansion card keeps its group and reward");
        check(!missions.mission("lab_1").missionProps().isEmpty()&&!missions.mission("lab_1").restrictions().isEmpty()
            &&missions.mission("lab_1").requirement().hasObjective()==false,"hidden laboratory card keeps its restriction and objective");

        check(missions.rankBoard(2,2).stream().map(MissionCatalog.Entry::id).toList().equals(
            List.of("mission_sprite_1","skies_under_cradle","mission_sprite_2","scarlet_fortress","mission_sprite_3","monstrous_research","alchemy_beginners","time_to_crafty")),
            "rank board uses descending priority and a stable tie break");
        check(missions.availableRankCards(2,2,Set.of("mission_sprite_1","mission_sprite_2")).stream().map(MissionCatalog.Entry::id)
            .noneMatch(Set.of("mission_sprite_1","mission_sprite_2")::contains),"completed cards leave the board");
        check(missions.availableRankCards(2,2,Set.of()).stream().map(MissionCatalog.Entry::id).noneMatch("alchemy_beginners"::equals),
            "an unmet needMission prerequisite withholds its card");

        Path dir=Files.createTempDirectory("tortodrone-mission-test-");
        try {
            write(dir,"rank.yml","""
                missions:
                  first:
                    name: Rank/01/1.1/First
                    type: rank
                    rank: 1
                    subrank: 1
                    needMission: second
                  second:
                    name: Rank/01/1.1/Second
                    type: rank
                    rank: 1
                    subrank: 1
                """);
            var small=MissionYaml.load(dir);
            check(small.size()==2&&small.availableRankCards(1,1,Set.of()).stream().map(MissionCatalog.Entry::id).toList().equals(List.of("second"))
                &&small.availableRankCards(1,1,Set.of("second")).stream().map(MissionCatalog.Entry::id).toList().equals(List.of("first")),"needMission gates cards until its prerequisite is complete");
            check(MissionYaml.load(dir.resolve("absent")).size()==0,"a deployment without missions keeps an empty catalogue");
            write(dir,"duplicate.yml","""
                missions:
                  first:
                    name: Rank/01/1.1/Again
                    type: rank
                    rank: 1
                    subrank: 1
                """);
            fails(()->MissionYaml.load(dir),"duplicate mission id");
            Files.delete(dir.resolve("duplicate.yml"));
            write(dir,"properties.yml","""
                properties:
                  Rank/Rank 1:
                    name: Rank/Rank 1
                    prestigeLow: 5
                    prestigeAvg: 4
                    prestigeHigh: 15
                    prestigeMax: 20
                """);
            fails(()->MissionYaml.load(dir),"Invalid mission property range");
            Files.delete(dir.resolve("properties.yml"));
            write(dir,"broken.yml","""
                missions:
                  third:
                    name: Rank/01/1.1/Third
                    type: rank
                    rank: 1
                    subrank: 1
                    needMission: missing
                """);
            fails(()->MissionYaml.load(dir),"missing mission");
            Files.delete(dir.resolve("broken.yml"));
            write(dir,"broken.yml","""
                missions:
                  third:
                    name: Rank/01/1.1/Third
                    type: rank
                    rank: 11
                    subrank: 1
                """);
            fails(()->MissionYaml.load(dir),"invalid rank pair");
            Files.delete(dir.resolve("broken.yml"));
            write(dir,"broken.yml","""
                missions:
                  third:
                    name: Rank/01/1.1/Third
                    type: rank
                    rank: 1
                    subrank: 1
                    invented: true
                """);
            fails(()->MissionYaml.load(dir),"unknown field");
            Files.delete(dir.resolve("broken.yml"));
            write(dir,"broken.yml","""
                missions:
                  third:
                    name: Rank/01/1.1/Third
                    type: prestige
                    restrictions:
                      - type: schedule
                        schedule:
                          refTime: 1
                          interval: every day
                """);
            fails(()->MissionYaml.load(dir),"invalid schedule interval");
            Files.delete(dir.resolve("broken.yml"));
            write(dir,"broken.yml","""
                groups:
                  wrong:
                    name: Wrong
                    id: other
                """);
            fails(()->MissionYaml.load(dir),"key/id mismatch");
        }finally{delete(dir);}
        System.out.println("PASS: 273 stock missions, groups, properties, board ordering, prerequisites and bounded rejection");
    }
    private static void write(Path dir,String name,String text)throws Exception {Files.writeString(dir.resolve(name),text);}
    private static void fails(Action action,String message)throws Exception {
        try{action.run();throw new AssertionError("Invalid mission catalogue accepted: "+message);}
        catch(java.io.IOException expected){check(expected.getMessage()!=null&&expected.getMessage().toLowerCase(Locale.ROOT).contains(message.toLowerCase(Locale.ROOT)),"actionable error: "+expected);}
    }
    private static void delete(Path root)throws Exception {try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    private interface Action{void run()throws Exception;}
}
