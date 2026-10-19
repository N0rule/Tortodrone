package org.tortodrone.character;

/** Verified stock ItemConfig.Upgrade values; a native deadline marks an activated upgrade. */
public final class SlotUpgrades {
    private SlotUpgrades() { }
    public record Definition(int days,int weapons,int trinkets) { }
    public static Definition find(String config) {
        return switch(config) {
            case "Upgrade/Weapon Slot" -> new Definition(30,1,0);
            case "Upgrade/Trinket Slot" -> new Definition(30,0,1);
            case "Upgrade/Trinket Slot (2 days)" -> new Definition(2,0,1);
            default -> null;
        };
    }
    public static int weapons(WorldProfile profile){return 2+bonus(profile,true);}
    public static int trinkets(WorldProfile profile){return bonus(profile,false);}
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
