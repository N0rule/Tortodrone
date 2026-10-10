package org.tortodrone.world;

import java.util.HashSet;
import static org.tortodrone.support.TestWire.check;

public final class PlayerEmotesTest {
    public static void main(String[] args) {
        String[] names = { "Rude","Come","Gaze","Salute","Wink","Congratulate","Tired",
            "Follow","Crazy","Hello","Frown","Thank","Look","Fail","Poke","Helpme",
            "Lonely","Bow","Smile","Shrug","Smell","Wave","Ready","Joy","Whine","Love",
            "Sad","Fallback","Flex","Herp","Tickle","Wait","Laugh","Derp","Sit","Amaze",
            "Yes","Flirt","Cry","Sniff","Cheer","Grin","Bored","Yawn","Clap","Hug",
            "Thirsty","Belch","No","Sleepy","Happy","Beg","Wince","Attack","Point",
            "Hungry","Burp","Sigh","Sleep","Kidding","Stare","Impatient","Dance","Sorry",
            "Epicfail","Giggle","Kiss","Goodbye" };
        check(PlayerEmotes.all().size()==68,"installed catalog size");
        var found=new HashSet<String>();
        for(var name:names) {
            var emote=PlayerEmotes.find(name);
            check(emote!=null,"catalog contains "+name);
            check(found.add(emote.name()),"unique catalog name "+name);
        }
        check(found.size()==PlayerEmotes.all().size(),"catalog has no unverified extra names");
        for(int i=0;i<names.length;i++)check(PlayerEmotes.find(names[i])!=null,"catalog entry "+i);
        check(PlayerEmotes.find("sit")==null&&PlayerEmotes.find(null)==null,"exact name lookup");

        var attack=PlayerEmotes.find("Attack");
        check(attack.activity()==9&&attack.duration()==700&&attack.allowsMovement()
            &&!attack.targetable()&&attack.balloonless()&&attack.spawnsTransient()
            &&attack.defaultKey()==71,"stock Attack descriptor");
        var hello=PlayerEmotes.find("Hello");
        check(hello.activity()==17&&hello.duration()==1667&&hello.allowsMovement()
            &&hello.targetable()&&!hello.balloonless()&&!hello.spawnsTransient(),"stock Hello descriptor");
        var sit=PlayerEmotes.find("Sit");
        check(sit.activity()==26&&sit.duration()==0&&sit.endActivity()==46&&sit.endDuration()==1333
            &&!sit.allowsMovement()&&!sit.targetable()&&sit.balloonless(),"stock Sit descriptor");
        var sleep=PlayerEmotes.find("Sleep");
        check(sleep.activity()==27&&sleep.duration()==0&&sleep.endActivity()==0
            &&sleep.endDuration()==0&&!sleep.allowsMovement(),"stock Sleep descriptor");
        check(PlayerEmotes.find("Wave").activity()==28&&PlayerEmotes.find("Wave").duration()==1667,
            "last native animation index");
        check(PlayerEmotes.find("Gaze").activity()==0&&!PlayerEmotes.find("Gaze").animated(),
            "chat-only emote has no actor activity");
        check(PlayerEmotes.all().stream().filter(PlayerEmotes.Descriptor::animated).count()==20,
            "exactly twenty stock animation activities");
        System.out.println("PASS: all 68 stock emote descriptors and native animation mappings");
    }
}
