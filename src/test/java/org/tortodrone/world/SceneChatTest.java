package org.tortodrone.world;

import java.io.IOException;
import java.util.*;
import static org.tortodrone.support.TestWire.check;

public final class SceneChatTest {
    public static void main(String[] args)throws Exception {
        var router=new SceneChat();var a=new ArrayList<SceneChat.Message>();var b=new ArrayList<SceneChat.Message>();var isolated=new ArrayList<SceneChat.Message>();
        var budget=new SceneChat.RateLimit();
        var alice=router.join(10,1001,"Alice-Knight",budget,a::add);
        var bob=router.join(10,1002,"Bob-Knight",new SceneChat.RateLimit(),b::add);
        var other=router.join(11,1003,"Other-Knight",new SceneChat.RateLimit(),isolated::add);
        var failed=router.join(10,1004,"Failed-Knight",new SceneChat.RateLimit(),message->{throw new IOException("Fixture queue failure");});
        check(router.speak(alice,"  hello 🌍  ",(byte)0),"normal scene chat accepted");
        check(a.size()==1&&b.equals(a)&&isolated.isEmpty()&&a.get(0).speaker().equals("Alice-Knight")&&a.get(0).text().equals("hello 🌍"),"authoritative sender, sender echo and instance isolation");
        check(router.speak(bob,"waves",(byte)2)&&a.get(1).mode()==2,"verified emote mode retained; failed recipient does not block peers");
        bob.close();router.speak(alice,"after departure",(byte)0);
        check(b.size()==2&&a.size()==3,"departed recipients stop receiving");
        var replacement=router.join(11,1001,"Alice-Knight",budget,isolated::add);
        alice.close();refuse(()->router.speak(alice,"stale",(byte)0));
        check(router.speak(replacement,"new instance",(byte)0)&&a.size()==3&&isolated.size()==2&&isolated.get(0).equals(isolated.get(1)),"late cleanup cannot remove replacement membership or route to old scene");
        refuse(()->router.speak(replacement,"spoof broadcast",(byte)4));
        refuse(()->router.speak(replacement,"x".repeat(257),(byte)0));
        refuse(()->SceneChat.validate("bad\ntext"));refuse(()->SceneChat.validate("\ud800"));refuse(()->SceneChat.validate("  "));
        check(SceneChat.validate("🌍".repeat(256)).codePointCount(0,512)==256,"Unicode limit counts characters rather than UTF-16 units");
        var rate=new SceneChat.RateLimit(0);
        for(int i=0;i<5;i++)check(rate.allow(0),"bounded initial burst");
        check(!rate.allow(0)&&!rate.allow(999_999_999)&&rate.allow(1_000_000_000),"rate limit replenishes at one message per second");
        check(!rate.allow(-1),"clock reversal cannot grant tokens");
        replacement.close();other.close();failed.close();
        System.out.println("PASS: scene chat identity, echo/modes, instance isolation, departure/re-entry, failed peers, Unicode bounds and persistent rate budget");
    }
    private static void refuse(Action action)throws Exception{try{action.run();throw new AssertionError("Invalid chat accepted");}catch(IOException expected){}}
    @FunctionalInterface interface Action{void run()throws Exception;}
}
