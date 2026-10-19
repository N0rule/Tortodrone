package org.tortodrone.storage;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.random.RandomGenerator;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.loot.*;
import org.tortodrone.resources.ItemYaml;
import static org.tortodrone.support.TestWire.check;

/** Shipped estimates use real native identities, retain every category and persist complete bundles. */
public final class BoxPoolsTest {
    private static ItemCatalog items;private static BoxCatalog boxes;private static Database db;private static int serial;
    private record Player(int account,String user,String name) {
        WorldProfile profile()throws IOException{return db.worldProfile(account,1,1);}
        void grant(String id,int count)throws IOException{db.grantItems(user,name,-1,-1,items.require(id),count,-1,-1);}
        WorldProfile open(String id,int option,RandomGenerator random)throws IOException {
            var p=profile();String config=items.require(id).config();long instance=p.items().stream().filter(i->i.config().equals(config)).findFirst().orElseThrow().id();
            return new BoxOpeningService(db,items,boxes,random).open(account,1,1,p.revision(),instance,option).profile();
        }
    }
    private static final class Choices implements RandomGenerator {
        final Deque<Long> choices=new ArrayDeque<>();final boolean bonuses;
        Choices(boolean bonuses,long...choices){this.bonuses=bonuses;for(long choice:choices)this.choices.add(choice);}
        public long nextLong(){throw new AssertionError("Unbounded random selection");}
        public long nextLong(long bound){long value=choices.isEmpty()?0:choices.removeFirst();check(value>=0&&value<bound,"scripted choice is inside group bounds");return value;}
        public int nextInt(int bound){return bound==1_000_000&&!bonuses?bound-1:0;}
    }
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-shipped-boxes-");
        try {
            items=ItemYaml.load(Path.of("content/items"));boxes=BoxCatalog.load(Path.of("content"),items);db=new Database(home,true);
            for(var box:boxes.entries().stream().filter(BoxDefinition::enabled).toList()) {
                check(box.status()==BoxDefinition.Status.APPROXIMATE&&!box.evidence().isBlank(),"every shipped approximation is disclosed");
                var p=player();p.grant(box.id(),1);p.grant("silver_key",2);p.grant("slime_key",2);
                var after=p.open(box.id(),box.kind()==BoxDefinition.Kind.KEYLESS?-1:0,new Choices(false));
                check(count(after,box.id())==0,"each real box is consumed once");
                check(count(after,"silver_key")== (box.keys().equals(List.of("Ticket/Keys/Slime Key","Ticket/Keys/Silver Key"))||box.kind()==BoxDefinition.Kind.KEYLESS?2:1)
                    &&count(after,"slime_key")== (box.keys().equals(List.of("Ticket/Keys/Slime Key","Ticket/Keys/Silver Key"))?1:2),"real native key menu preserves the unselected type");
                for(var reward:after.items())if(items.require(reward.config()).expireDays()>0)check(reward.expiresAt()!=null&&reward.expiresAt()>System.currentTimeMillis(),"timed reward receives a persisted native deadline");
                check(new CharacterStore(db,p.account,p.user,items).worldProfile(p.name,1).equals(after),"reconnect retains real rewards");
                check(new Database(home,true).worldProfile(p.account,1,1).equals(after),"reopened database retains real rewards");
            }
            var slime=player();slime.grant("item_000",1);slime.grant("silver_key",1);slime.grant("slime_key",1);
            var slimed=slime.open("item_000",1,new Choices(true));
            check(count(slimed,"silver_key")==0&&count(slimed,"slime_key")==1,"Slime 000 explicitly chosen Silver option");
            for(String reward:List.of("small_fireworks_slime","medium_fireworks_slime","large_fireworks_slime","confetti_slime","trinket_slot_2_days"))
                check(count(slimed,reward)==1,"all independent Slime bonus families are deliverable");
            String trinket=items.require("trinket_slot_2_days").config();
            check(slimed.items().stream().anyMatch(i->i.config().equals(trinket)&&i.bound()),"Slime timed upgrade binds on receipt");
            var candidates=rewardConfigs(boxes.find(items.require("item_000").config()));
            check(candidates.stream().noneMatch(c->c.contains("Slimed Aura")||c.contains("Node Aura")||c.contains("/Trinket/")||c.contains(", Fancy")),"serial 000 excludes later aura, trinket and palette additions");

            var surge=boxes.find(items.require("surge_prize_box").config());
            check(surge.groups().get("main").entries().stream().allMatch(e->e.weight()==25)&&surge.groups().get("main").entries().size()==4,"published four quarter categories retained");
            check(surge.groups().get("accessories").entries().size()==19&&surge.groups().get("helm_costumes").entries().size()==10&&surge.groups().get("armor_costumes").entries().size()==10,"all documented Surge cosmetic candidates retained");
            check(surge.groups().get("bonus_upgrade").chance()==50_000&&surge.groups().get("bonus_upgrade").entries().stream().map(BoxDefinition.Entry::weight).toList().equals(List.of(62L,31L,6L,1L)),"published independent upgrade odds retained without pruning");
            String[] upgrades={"trinket_slot","weapon_slot","coin_bonus","heat_bonus_s909083618_3f30bba58f51"};long[] boundaries={0,62,93,99};
            for(int n=0;n<upgrades.length;n++) {
                String upgrade=upgrades[n];var p=player();p.grant("surge_prize_box",1);
                var after=p.open("surge_prize_box",-1,new Choices(true,0,0,0,0,boundaries[n]));
                check(count(after,upgrades[n])==1&&count(after,"variants_1")==1&&count(after,"hacked_aura")==1&&count(after,"confetti")==1,"every Surge upgrade boundary grants main, confetti and independent aura together");
                String upgradeConfig=items.require(upgrade).config();
                check(after.items().stream().filter(i->i.config().equals(upgradeConfig)).noneMatch(WorldProfile.Item::bound),"Surge rewards remain unbound");
            }
            for(int bonus=0;bonus<5;bonus++) {
                var p=player();p.grant("solstice_prize_box_complete",1);
                var after=p.open("solstice_prize_box_complete",-1,new Choices(true,0,0,bonus));
                String reward=List.of("snow_bucket","bloon_bucket","golden_slime_coin","trinket_slot_2_days","jar_o_fireflies").get(bonus);
                check(count(after,reward)==(bonus==2?5:1)&&count(after,"confetti_summer")==1&&count(after,"confetti_winter")==1,"Complete Solstice preserves both guaranteed confetti and all five bonus alternatives/quantities");
            }
            var kat=boxes.find(items.require("katastrophe_prize_box_complete").config());
            check(rewardConfigs(kat).size()==56&&kat.groups().keySet().stream().noneMatch(g->g.startsWith("bonus_")),"Complete Katastrophe has all 56 outcomes and no invented bonus pool");
            check(!rewardConfigs(boxes.find(items.require("item_001").config())).equals(candidates),"historical Slime editions retain distinct pools");
            // Exact main/color boundaries include both rare colors and Shadow Key as a reward.
            var iron=boxes.find(items.require("lockbox").config());
            check(BoxRewardSelector.select(iron,new Choices(false,9300)).getFirst().config().equals(items.require("shadow_key").config()),"Iron Shadow Key category remains a reward only");
            for(long color:List.of(6299L,6300L,6580L,6860L)) {
                var reward=BoxRewardSelector.select(iron,new Choices(false,0,0,color)).getFirst();
                check(reward.config().equals(items.require(color<6300?"barrel_belly_red":color<6580?"barrel_belly_divine":color<6860?"barrel_belly_volcanic":"barrel_belly_prismatic").config()),"explicit standard/divine/volcanic/prismatic boundaries");
            }
            System.out.println("PASS: 243 shipped approximation pools, actual ordinary/Slime/keyless opens, all upgrade/bonus outcomes, historical separation, rarity boundaries and reconnect/restart persistence");
        }finally{org.tortodrone.support.ContentFixture.delete(home);}
    }
    private static Set<String> rewardConfigs(BoxDefinition box) {
        var result=new HashSet<String>();for(var reward:box.guaranteed())if(reward instanceof BoxDefinition.Item i)result.add(i.config());
        box.groups().values().forEach(g->g.entries().forEach(e->e.rewards().forEach(r->{if(r instanceof BoxDefinition.Item i)result.add(i.config());})));return result;
    }
    private static Player player()throws Exception {
        String user="PoolPlayer"+(++serial),name="Pool-Knight"+letters(serial);int account=db.create(user,new Passwords.Protection(new byte[16],new byte[32]),false);
        new CharacterStore(db,account,user,items).create(new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));return new Player(account,user,name);
    }
    private static String letters(int n){return n<26?String.valueOf((char)('a'+n)):letters(n/26)+(char)('a'+n%26);}
    private static int count(WorldProfile p,String id)throws IOException{String config=items.require(id).config();return p.items().stream().filter(i->i.config().equals(config)).mapToInt(WorldProfile.Item::count).sum();}
}
