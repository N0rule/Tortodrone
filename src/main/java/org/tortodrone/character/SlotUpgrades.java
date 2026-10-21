package org.tortodrone.character;

/** Verified stock ItemConfig.Upgrade values; a native deadline marks an activated upgrade. */
public final class SlotUpgrades {
    private SlotUpgrades() { }
    /**
     * One configured upgrade. Slot upgrades add capacity, a heat bonus doubles heat gain and a
     * PvP bonus adds Krogmo tokens; the stock client caps the token total at six.
     */
    public record Definition(int days,int weapons,int trinkets,boolean heatBonus,int pvpTokens) { }
    public static Definition find(String config) {
        return switch(config) {
            case "Upgrade/Weapon Slot" -> new Definition(30,1,0,false,0);
            case "Upgrade/Trinket Slot" -> new Definition(30,0,1,false,0);
            case "Upgrade/Trinket Slot (2 days)" -> new Definition(2,0,1,false,0);
            // Upgrade/Parts/Heat Bonus is the two-day base; the shipped player items are the
            // named day variants below. Coin Bonus is one day and one Krogmo token.
            case "Upgrade/Heat Bonus" -> new Definition(2,0,0,true,0);
            case "Upgrade/Heat Bonus (1 day)" -> new Definition(1,0,0,true,0);
            case "Upgrade/Heat Bonus (7 days)" -> new Definition(7,0,0,true,0);
            case "Upgrade/Heat Bonus (30 days)" -> new Definition(30,0,0,true,0);
            case "Upgrade/Coin Bonus" -> new Definition(1,0,0,false,1);
            default -> null;
        };
    }
    public static int weapons(WorldProfile profile){return 2+bonus(profile,true);}
    public static int trinkets(WorldProfile profile){return bonus(profile,false);}
    /** A running heat amplifier doubles heat gained from every source. */
    public static boolean heatBonusActive(WorldProfile profile){
        if(profile==null)return false;long now=System.currentTimeMillis();
        return profile.items().stream().anyMatch(item->{var def=find(item.config());return def!=null&&def.heatBonus()&&item.expiresAt()!=null&&!item.expired(now);});
    }
    /** Running Krogmo Coin Boosters sum, as the stock client displays them, capped at six. */
    public static int pvpTokenBonus(WorldProfile profile){
        if(profile==null)return 0;long now=System.currentTimeMillis();int total=0;
        for(var item:profile.items()){var def=find(item.config());if(def!=null&&def.pvpTokens()>0&&item.expiresAt()!=null&&!item.expired(now))total+=def.pvpTokens();}
        return Math.min(6,total);
    }
    private static int bonus(WorldProfile profile,boolean weapons) {
        if(profile==null)return 0; // Scene movement can exist before equipment is attached.
        int count=0;long now=System.currentTimeMillis();
        for(var item:profile.items()) {
            var def=find(item.config());
            if(def!=null&&item.expiresAt()!=null&&!item.expired(now))count+=weapons?def.weapons():def.trinkets();
        }
        return Math.min(2,count);
    }
    public static boolean unlocked(WorldProfile profile,int slot) {
        return slot>=6&&slot<=9?slot<6+weapons(profile):slot==10||slot==11?slot<10+trinkets(profile):true;
    }
}
