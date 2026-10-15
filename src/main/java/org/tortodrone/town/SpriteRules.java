package org.tortodrone.town;

/** Archive/client sprite progression tables. Levels are zero based on the wire. */
public final class SpriteRules {
    private SpriteRules() { }
    private static final int[] POINTS={1,3,5,7,9,11,16,19,22,25,28,31,34,37,40,43,46,51,54,57,61,65,69,73,77,81,85};
    private static final int[] TIERS={0,4,14,29,49,74};
    private static final int[] HEAT={300,500,700,900,1100,1300,1500};
    private static final int[] UNLOCK={0,14,49},ULTIMATES={89,94,99};
    public static int points(int level){int points=0;for(int at:POINTS)if(at<=level)points++;return points;}
    public static int tier(int level){for(int i=TIERS.length-1;i>=0;i--)if(level>=TIERS[i])return i;return 0;}
    public static boolean unlocked(int level,int skill){return skill>=0&&skill<3&&level>=UNLOCK[skill];}
    public static boolean ultimate(int level,int skill){return unlocked(level,skill)&&level>=ULTIMATES[skill];}
    public static int requiredRank(int level){return switch(tier(level+1)){case 2->3;case 3->5;case 4->6;case 5->8;default->1;};}
    public static String levelItem(int level,java.util.List<String> food){
        String catalyst=switch(level+1){case 14->"Evo Catalyst";case 49->"Advanced Evo Catalyst";case 89,94,99->"Ultimate Evo Catalyst";default->null;};
        return catalyst!=null?"Rarity/Evo Catalyst/"+catalyst:food.isEmpty()?null:food.get(Math.min(Math.max(0,tier(level)-1),food.size()-1));
    }
    public static float feedGain(int rarity,boolean preferred){
        float rate=switch(Math.clamp(rarity,-5,5)){case -5->.1f;case -4->.15f;case -3->.2f;case -2->.25f;case -1->.5f;case 0->1f;case 1->2f;case 2->4f;case 3->8f;case 4->16f;default->32f;};
        return rate*(1f/75f)*(preferred?5f:1f);
    }
    public static long fedAt(Long lastFull,long now){return Math.max(lastFull==null?now-3600000:lastFull,now-3600000)+720000;}
    public static boolean appetite(Long lastFull,long now){return lastFull==null||now-lastFull>720000;}
    public static int heatScale(int level){int i=tier(level);int next=i+1<TIERS.length?TIERS[i+1]:99;float value=HEAT[i]+(float)(level-TIERS[i])/(next-TIERS[i])*(HEAT[i+1]-HEAT[i]);return 10*(int)Math.ceil(value);}
    public static float floorHeat(float heat,int level,float amount){return Math.clamp(heat+.2f*Math.min(1,amount/heatScale(level)),0,1);}
    public static boolean grantsPerk(int levelBefore){return levelBefore>=99||!java.util.stream.IntStream.of(POINTS).anyMatch(v->v==levelBefore+1)&&levelBefore+1!=14&&levelBefore+1!=49&&levelBefore+1!=89&&levelBefore+1!=94&&levelBefore+1!=99;}
}
