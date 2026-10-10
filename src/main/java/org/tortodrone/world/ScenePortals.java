package org.tortodrone.world;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Server-only portal facts tied to the exact archived layout. No client coordinates are used. */
public record ScenePortals(Map<String,Entrance> entrances,List<Trigger> triggers) {
    // Verified Character/PC/Default circle, collision flags 4; portal sensor mask is 65535.
    public static final float PAWN_RADIUS=.333333f;
    public static final ScenePortals EMPTY=new ScenePortals(Map.of(),List.of());
    public ScenePortals { entrances=Map.copyOf(entrances);triggers=List.copyOf(triggers); }
    public record Entrance(int id,float x,float y,float rotation) { }
    /** Current verified portals are axis-aligned IntersectionStart rectangles with zero expansion. */
    public record Trigger(int id,int destination,String entrance,float minX,float minY,float maxX,float maxY) {
        public boolean intersects(float x,float y) {
            if(!Float.isFinite(x)||!Float.isFinite(y))return false;
            float dx=x-Math.clamp(x,minX,maxX),dy=y-Math.clamp(y,minY,maxY);
            return dx*dx+dy*dy<=PAWN_RADIUS*PAWN_RADIUS;
        }
    }
    public static ScenePortals load(Path folder,String layoutDigest,CollisionMap terrain)throws IOException {
        Path file=folder.resolve("portals.properties");
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return EMPTY;
        if(!Files.isRegularFile(file)||!file.toRealPath().getParent().equals(folder.toRealPath())||Files.size(file)>16384)
            throw new IOException("Invalid portal metadata file");
        var p=new Properties();try(var in=Files.newInputStream(file)){p.load(in);}
        if(!"1".equals(required(p,"format"))||!layoutDigest.equalsIgnoreCase(required(p,"layout-sha256")))
            throw new IOException("Portal metadata does not match its scene layout");
        var entrances=new LinkedHashMap<String,Entrance>();var ids=new HashSet<Integer>();
        for(String tag:list(p,"entrances")) {
            if(!tag.matches("[A-Za-z0-9_-]{1,128}"))throw new IOException("Invalid entrance tag");
            String prefix="entrance."+tag+".";
            int id=positive(p,prefix+"id");float x=coordinate(p,prefix+"x"),y=coordinate(p,prefix+"y");
            float rotation=coordinate(p,prefix+"rotation");
            if(!ids.add(id)||!terrain.walkable(x,y)||Math.abs(rotation)>Math.PI)
                throw new IOException("Invalid, duplicated or unwalkable portal entrance");
            entrances.put(tag,new Entrance(id,x,y,rotation));
        }
        var triggers=new ArrayList<Trigger>();ids.clear();
        for(String key:list(p,"triggers")) {
            int id;try{id=Integer.parseInt(key);}catch(NumberFormatException e){throw new IOException("Invalid portal entry ID",e);}
            if(id<=0||!ids.add(id))throw new IOException("Invalid or duplicated portal entry ID");
            String prefix="trigger."+id+".";
            int destination=positive(p,prefix+"scene");String tag=required(p,prefix+"entrance");
            float minX=coordinate(p,prefix+"min-x"),minY=coordinate(p,prefix+"min-y");
            float maxX=coordinate(p,prefix+"max-x"),maxY=coordinate(p,prefix+"max-y");
            if(minX>=maxX||minY>=maxY||maxX-minX>1000||maxY-minY>1000)
                throw new IOException("Invalid portal trigger rectangle");
            triggers.add(new Trigger(id,destination,tag,minX,minY,maxX,maxY));
        }
        return new ScenePortals(entrances,triggers);
    }
    private static List<String> list(Properties p,String key)throws IOException {
        String value=p.getProperty(key,"").trim();
        if(value.isEmpty())return List.of();
        var values=Arrays.asList(value.split(",",-1));
        if(values.size()>128||new HashSet<>(values).size()!=values.size())throw new IOException("Invalid portal metadata list");
        return values;
    }
    private static String required(Properties p,String key)throws IOException {
        String value=p.getProperty(key,"").trim();
        if(value.isEmpty()||value.length()>256)throw new IOException("Missing or invalid portal property: "+key);return value;
    }
    private static int positive(Properties p,String key)throws IOException {
        try {int value=Integer.parseInt(required(p,key));if(value<=0)throw new NumberFormatException();return value;}
        catch(NumberFormatException e){throw new IOException("Invalid portal ID: "+key,e);}
    }
    private static float coordinate(Properties p,String key)throws IOException {
        try {float value=Float.parseFloat(required(p,key));if(!Float.isFinite(value)||Math.abs(value)>10000)throw new NumberFormatException();return value;}
        catch(NumberFormatException e){throw new IOException("Invalid portal coordinate: "+key,e);}
    }
}
