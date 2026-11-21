package org.tortodrone.core.town;

import java.util.Set;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.KnightProgress;
import org.tortodrone.core.character.Currency;

/**
 * Retail Supply Depot rank availability.
 *
 * <p>Official stock is shown progressively: uncommon (1-star) gear appears at 3-1,
 * 2-star at 4-1, 3-star at 5-2, 4-star at 6-2 and 5-star at 8-2. Currency,
 * materials, tickets, accessories and keys have no deposit rank requirement.
 * Sprite eggs follow the client's own sprite-rank requirement: the first sprite
 * rank that needs knight rank ({@code ItemCodes.bvQ}) is 3-1.
 *
 * <p>The depot catalogue stays shared and administrator-managed. This gate is
 * evaluated per session from the resolved item definition, never from a native
 * path or display name, because abstract and concrete items can share a name.
 */
public final class DepotGate {
    private static final Set<String> WEAPONS=Set.of("Sword","Gun","Handgun","SwingingHandgun","Bomb","BlastNetworkBomb");
    private static final Set<String> ARMOR=Set.of("Helm","Armor","Shield","LockdownShield");
    /** Minimum sold sprite rank; level 4 is the first sprite rank with a knight-rank requirement. */
    public static final Rank SPRITE_MINIMUM=new Rank(3,1);
    private DepotGate() { }

    /** A saved mission rank. Compared lexicographically, never as a single number. */
    public record Rank(int rank,int subrank) implements Comparable<Rank> {
        public Rank {
            if(rank<1||rank>10||subrank<1||subrank>4)throw new IllegalArgumentException("Invalid mission rank");
        }
        @Override public int compareTo(Rank other) {
            return rank!=other.rank?Integer.compare(rank,other.rank):Integer.compare(subrank,other.subrank);
        }
        @Override public String toString() { return rank+"-"+subrank; }
    }

    /** Minimum mission rank that advertises this item; null means every rank. */
    public static Rank required(ItemCatalog.Entry entry) {
        if(entry==null)return null;
        String type=entry.type();
        if(WEAPONS.contains(type)||ARMOR.contains(type))return gear(entry.rarity());
        if(type.equals("SpriteEgg"))return SPRITE_MINIMUM;
        return null;
    }

    /** Gear tier by rarity: 1-star 3-1, 2-star 4-1, 3-star 5-2, 4-star 6-2, 5-star 8-2. */
    private static Rank gear(int rarity) {
        if(rarity<=1)return new Rank(3,1);
        if(rarity==2)return new Rank(4,1);
        if(rarity==3)return new Rank(5,2);
        if(rarity==4)return new Rank(6,2);
        return new Rank(8,2);
    }

    /** True when this knight may see and buy the resolved item in the depot. */
    public static boolean unlocked(KnightProgress progress,ItemCatalog.Entry entry) {
        Rank minimum=required(entry);
        return minimum==null||new Rank(progress.rank(),progress.subrank()).compareTo(minimum)>=0;
    }
}
