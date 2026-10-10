package org.tortodrone.world;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Verified installed-client emote configuration used by the server's actor predictor. */
public final class PlayerEmotes {
    public static final int FIRST_ACTIVITY = 9;
    public static final int ANIMATED_COUNT = 20;

    public record Descriptor(String name, int activity, int duration, int endActivity,
            int endDuration, boolean allowsMovement, boolean targetable, boolean balloonless,
            boolean spawnsTransient, int defaultKey) {
        public boolean animated() { return activity != 0; }
        /** Sit has no chat format; Herp shares the stock derp command's localization. */
        public String messageKey(boolean targeted) {
            if(name.equals("Sit"))return null;
            return "m."+(name.equals("Herp")?"derp":name.toLowerCase(java.util.Locale.ROOT))+"_format"+
                (targeted&&targetable?"_targeted":"");
        }
    }

    private static final List<Descriptor> ALL = List.of(
        e("Rude",0,0,0,true,true,false,false,0),
        e("Come",0,0,0,true,false,true,false,75),
        e("Gaze",0,0,0,true,false,false,false,0),
        e("Salute",24,2000,0,true,true,true,false,0),
        e("Wink",0,0,0,true,true,false,false,0),
        e("Congratulate",0,0,0,true,true,false,false,0),
        e("Tired",0,0,0,true,true,false,false,0),
        e("Follow",15,700,0,true,false,true,true,79),
        e("Crazy",0,0,0,true,true,false,false,0),
        e("Hello",17,1667,0,true,true,false,false,0),
        e("Frown",0,0,0,true,true,false,false,0),
        e("Thank",0,0,0,true,true,false,false,0),
        e("Look",20,1667,0,true,true,false,false,0),
        e("Fail",13,2222,0,true,true,false,false,0),
        e("Poke",22,1667,0,true,true,true,false,0),
        e("Helpme",18,700,0,true,false,true,true,82),
        e("Lonely",0,0,0,true,false,false,false,0),
        e("Bow",0,0,0,true,true,false,false,0),
        e("Smile",0,0,0,true,true,false,false,0),
        e("Shrug",25,2222,0,true,true,false,false,0),
        e("Smell",0,0,0,true,true,false,false,0),
        e("Wave",28,1667,0,true,true,true,false,0),
        e("Ready",23,2000,0,true,false,true,false,80),
        e("Joy",0,0,0,true,true,false,false,0),
        e("Whine",0,0,0,true,true,false,false,0),
        e("Love",0,0,0,true,true,false,false,0),
        e("Sad",0,0,0,true,true,false,false,0),
        e("Fallback",14,700,0,true,false,true,true,72),
        e("Flex",0,0,0,true,false,false,false,0),
        e("Herp",0,0,0,true,false,false,false,0),
        e("Tickle",0,0,0,true,true,false,false,0),
        e("Wait",0,0,0,true,false,true,false,81),
        e("Laugh",19,1667,0,true,true,false,false,0),
        e("Derp",0,0,0,true,false,false,false,0),
        e("Sit",26,0,46,false,false,true,false,0,1333),
        e("Amaze",0,0,0,true,true,false,false,0),
        e("Yes",0,0,0,true,true,false,false,0),
        e("Flirt",0,0,0,true,true,false,false,0),
        e("Cry",0,0,0,true,true,false,false,0),
        e("Sniff",0,0,0,true,true,false,false,0),
        e("Cheer",10,1667,0,true,true,false,false,0),
        e("Grin",0,0,0,true,true,false,false,0),
        e("Bored",0,0,0,true,true,false,false,0),
        e("Yawn",0,0,0,true,false,false,false,0),
        e("Clap",11,1667,0,true,true,false,false,0),
        e("Hug",0,0,0,true,true,false,false,0),
        e("Thirsty",0,0,0,true,false,false,false,0),
        e("Belch",0,0,0,true,false,false,false,0),
        e("No",0,0,0,true,true,false,false,0),
        e("Sleepy",0,0,0,true,true,false,false,0),
        e("Happy",0,0,0,true,true,false,false,0),
        e("Beg",0,0,0,true,true,false,false,0),
        e("Wince",0,0,0,true,true,false,false,0),
        e("Attack",9,700,0,true,false,true,true,71),
        e("Point",21,1667,0,true,true,false,false,0),
        e("Hungry",0,0,0,true,false,false,false,0),
        e("Burp",0,0,0,true,false,false,false,0),
        e("Sigh",0,0,0,true,true,false,false,0),
        e("Sleep",27,0,0,false,false,false,false,0),
        e("Kidding",0,0,0,true,true,false,false,0),
        e("Stare",0,0,0,true,true,false,false,0),
        e("Impatient",0,0,0,true,true,false,false,0),
        e("Dance",0,0,0,true,true,false,false,0),
        e("Sorry",0,0,0,true,true,false,false,0),
        e("Epicfail",12,2222,0,true,true,false,false,0),
        e("Giggle",0,0,0,true,true,false,false,0),
        e("Kiss",0,0,0,true,true,false,false,0),
        e("Goodbye",16,1667,0,true,true,false,false,0)
    );
    private static final Map<String, Descriptor> BY_NAME = ALL.stream().collect(
        Collectors.toUnmodifiableMap(Descriptor::name, Function.identity()));
    private static final Map<Integer, Descriptor> BY_ACTIVITY = ALL.stream()
        .filter(Descriptor::animated)
        .collect(Collectors.toUnmodifiableMap(Descriptor::activity, Function.identity()));

    private PlayerEmotes() { }

    /** Names are case-sensitive and match the installed EmoteConfig names. */
    public static Descriptor find(String exactName) {
        return exactName==null?null:BY_NAME.get(exactName);
    }

    public static List<Descriptor> all() { return ALL; }

    static Descriptor byActivity(int activity) { return BY_ACTIVITY.get(activity); }

    static boolean isCatalogued(Descriptor descriptor) {
        return descriptor!=null&&descriptor.name()!=null&&BY_NAME.get(descriptor.name())==descriptor;
    }

    private static Descriptor e(String name, int activity, int duration, int endActivity,
            boolean allowsMovement, boolean targetable, boolean balloonless,
            boolean spawnsTransient, int defaultKey) {
        return e(name,activity,duration,endActivity,allowsMovement,targetable,balloonless,
            spawnsTransient,defaultKey,0);
    }

    private static Descriptor e(String name, int activity, int duration, int endActivity,
            boolean allowsMovement, boolean targetable, boolean balloonless,
            boolean spawnsTransient, int defaultKey, int endDuration) {
        return new Descriptor(name,activity,duration,endActivity,endDuration,allowsMovement,
            targetable,balloonless,spawnsTransient,defaultKey);
    }
}
