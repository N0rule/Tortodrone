package org.tortodrone.character;

import java.util.*;

/** Verified stock AnimatedAction confetti references and KnightAdvancer timing. */
public final class Confetti {
    public static final int LAND_MILLIS=300;
    private static final Set<String> CONFIGS=Set.of("Usable/Confetti","Usable/Confetti, Bubbles",
        "Usable/Confetti, Dark Harvest","Usable/Confetti, Equinox","Usable/Confetti, Fall Equinox",
        "Usable/Confetti, Flare Popper","Usable/Confetti, Frenzy","Usable/Confetti, Leaf",
        "Usable/Confetti, Rage","Usable/Confetti, Silver","Usable/Confetti, Slime",
        "Usable/Confetti, Storm","Usable/Confetti, Summer","Usable/Confetti, Winter");
    private Confetti() { }
    public static boolean supports(String config){return CONFIGS.contains(config);}
    public static int duration(String config,int started){
        if(!supports(config))throw new IllegalArgumentException("Unsupported confetti");
        int clear=switch(config){
            case "Usable/Confetti, Dark Harvest" -> new Random(started).nextDouble()*2<1?1600:3500;
            case "Usable/Confetti, Frenzy","Usable/Confetti, Rage","Usable/Confetti, Storm" -> 3100;
            default -> 1600;
        };
        // AnimatedAction.fn seeds Random with activityStarted and uses weighted reservoir
        // selection. Dark Harvest has equal trick/treat weights in that order.
        return LAND_MILLIS+clear;
    }
}
