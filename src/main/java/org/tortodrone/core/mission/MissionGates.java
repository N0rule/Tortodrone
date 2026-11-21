package org.tortodrone.core.mission;

import java.util.*;

/** Imported stock {@code MissionGateSummary} data: floor order and the display values the
 * 2026 client reads from the gate DSet. A gate definition is not a playable route until every
 * floor scene, exit and entrance is present in the bundled content. */
public final class MissionGates {
    public static final Set<String> LEVEL_TYPES=Set.of("LEVEL","TERMINAL","LOBBY","SUBTOWN","CORE","SPECIAL");
    public static final int MAX_FLOORS=64;

    /** One single-wedge wheel. Multi-wedge (time-rotating) wheels are not imported. */
    public record Wedge(int scene,String level,String name,String icon,String description,String type,boolean restricted,
            int skip,float velocity,long start,float length) {
        public Wedge {
            if(scene<0)throw new IllegalArgumentException("Invalid mission gate scene");
            text(level,"mission gate level");
            text(name,"mission gate wedge name");text(icon,"mission gate wedge icon");text(description,"mission gate wedge description");
            if(!LEVEL_TYPES.contains(type))throw new IllegalArgumentException("Invalid mission gate level type");
            if(skip<0||skip>255)throw new IllegalArgumentException("Invalid mission gate skip");
            if(!Float.isFinite(velocity))throw new IllegalArgumentException("Invalid mission gate velocity");
            if(!Float.isFinite(length)||length<=0)throw new IllegalArgumentException("Invalid mission gate length");
        }
    }
    public record Gate(String missionId,int gateId,String icon,List<Integer> colors,String description,
            int sourceId,int destinationId,int priority,int tokens,int flags,List<Wedge> wedges) {
        public Gate {
            text(missionId,"mission gate id");text(icon,"mission gate icon");text(description,"mission gate description");
            if(missionId.length()>128||gateId<0)throw new IllegalArgumentException("Invalid mission gate identity");
            colors=List.copyOf(colors);if(colors.isEmpty()||colors.size()>8)throw new IllegalArgumentException("Invalid mission gate colors");
            wedges=List.copyOf(wedges);if(wedges.isEmpty()||wedges.size()>MAX_FLOORS)throw new IllegalArgumentException("Invalid mission gate floors");
            for(var wedge:wedges)Objects.requireNonNull(wedge);
        }
        public String name(){return "%mission:m."+missionId;}
    }

    private static final MissionGates EMPTY=new MissionGates(Map.of());
    private final Map<String,Gate> gates;
    private MissionGates(Map<String,Gate> gates){this.gates=Map.copyOf(gates);}
    public static MissionGates empty(){return EMPTY;}
    public static MissionGates of(Map<String,Gate> gates){
        var copy=new LinkedHashMap<String,Gate>();for(var entry:gates.entrySet()){
            var gate=Objects.requireNonNull(entry.getValue());
            if(!gate.missionId().equals(entry.getKey()))throw new IllegalArgumentException("Mission gate key does not match its mission");
            if(copy.putIfAbsent(entry.getKey(),gate)!=null)throw new IllegalArgumentException("Duplicate mission gate "+entry.getKey());
        }
        return copy.isEmpty()?EMPTY:new MissionGates(copy);
    }
    public Gate gate(String missionId){return gates.get(missionId);}
    public Collection<Gate> gates(){return gates.values();}
    private static void text(String value,String what){
        if(value==null||value.isBlank()||value.length()>512||value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid "+what);
    }
}
