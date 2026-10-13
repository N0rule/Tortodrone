package org.tortodrone.world;

/** Native carried/thrown lift fields, independent of the knight's weapon state. */
public record SceneLiftState(float z,int faction,int[] excluded,Point dropPoint) {
    public record Point(float x,float y) { }
    public SceneLiftState(float z,int faction,int[] excluded){this(z,faction,excluded,null);}
    public SceneLiftState { excluded=excluded==null?null:excluded.clone(); }
    @Override public int[] excluded(){return excluded==null?null:excluded.clone();}
    @Override public boolean equals(Object other){return other instanceof SceneLiftState value
        &&Float.compare(z,value.z)==0&&faction==value.faction&&java.util.Arrays.equals(excluded,value.excluded)
        &&java.util.Objects.equals(dropPoint,value.dropPoint);}
    @Override public int hashCode(){return java.util.Objects.hash(z,faction,java.util.Arrays.hashCode(excluded),dropPoint);}
}
