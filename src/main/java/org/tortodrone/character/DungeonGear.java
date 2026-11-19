package org.tortodrone.character;

/** Native dungeon gear metadata that is not represented by the readable item bindings. */
public final class DungeonGear {
    private DungeonGear() { }
    /** The native Vitapod tier from its name; zero for non-Vitapod dungeon gear. */
    public static int vitapodTier(String config) {
        String prefix="Gear/Dungeon/Vitapod/Health +";
        if(config==null||!config.startsWith(prefix))return 0;
        try {
            String suffix=config.substring(prefix.length());
            if(suffix.isEmpty()||suffix.length()>2||suffix.chars().anyMatch(c->c<'0'||c>'9'))return 0;
            int tier=Integer.parseInt(suffix);
            return tier>=1&&tier<=30?tier:0;
        }catch(NumberFormatException invalid){return 0;}
    }
    /** Each native Vitapod capsule contributes 40 maximum health. */
    public static int healthBonus(String config) { return vitapodTier(config)*40; }
}
