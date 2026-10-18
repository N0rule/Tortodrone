package org.tortodrone.loot;

import java.util.*;

/** Validated server content; configs are resolved from readable IDs at startup. */
public record BoxDefinition(String id,String config,Kind kind,List<String> keys,Status status,
        String evidence,Map<String,Group> groups,List<Reward> guaranteed) {
    public enum Kind { KEYED, KEYLESS }
    public enum Status { VERIFIED, APPROXIMATE, CUSTOM, INCOMPLETE, UNSUPPORTED, UNRESEARCHED }
    public enum Binding { BOUND, UNBOUND, CATALOG }
    public sealed interface Reward permits Item,Reference { }
    public record Item(String config,int minimum,int maximum,Binding binding) implements Reward { }
    public record Reference(String group) implements Reward { }
    /** Chance is in millionths; weights describe only the alternatives within this group. */
    public record Group(int chance,int rolls,List<Entry> entries,long weight) {
        public Group { entries=List.copyOf(entries); }
    }
    public record Entry(long weight,List<Reward> rewards) {
        public Entry { rewards=List.copyOf(rewards); }
    }
    public BoxDefinition {
        keys=List.copyOf(keys);groups=Collections.unmodifiableMap(new LinkedHashMap<>(groups));guaranteed=List.copyOf(guaranteed);
    }
    public boolean enabled(){return status==Status.VERIFIED||status==Status.APPROXIMATE||status==Status.CUSTOM;}
}
