package org.tortodrone.core.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;

/**
 * Portal-metadata ingestion for {@link ScenePortals}.
 *
 * <p>The runtime value keeps only the immutable entrance/trigger tables and the intersection
 * kernel. This class owns the exported properties format, entrance clearance checks, trigger
 * shape parsing and the convex-polygon rules; it refuses metadata whose layout hash does not
 * match the archived scene.
 */
public final class ScenePortalCodec {
    private ScenePortalCodec() { }

    public static ScenePortals load(Path folder,String layoutDigest,CollisionMap terrain)throws IOException {
        Path file=folder.resolve("portals.properties");
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return ScenePortals.EMPTY;
        if(!Files.isRegularFile(file)||!file.toRealPath().getParent().equals(folder.toRealPath())||Files.size(file)>16384)
            throw new IOException("Invalid portal metadata file");
        var p=new Properties();try(var in=Files.newInputStream(file)){p.load(in);}
        return load(p,layoutDigest,terrain);
    }
    public static ScenePortals load(Properties p,String layoutDigest,MapCollision collision,int mask)throws IOException {
        String format=required(p,"format");
        if(!(format.equals("1")||format.equals("2"))||!layoutDigest.equalsIgnoreCase(required(p,"layout-sha256")))
            throw new IOException("Portal metadata does not match its scene layout");
        var entrances=new LinkedHashMap<String,ScenePortals.Entrance>();var ids=new HashSet<Integer>();
        for(String tag:list(p,"entrances")) {
            if(!tag.matches("[A-Za-z0-9_-]{1,128}"))throw new IOException("Invalid entrance tag");
            String prefix="entrance."+tag+".";
            int id=positive(p,prefix+"id");float x=coordinate(p,prefix+"x"),y=coordinate(p,prefix+"y");
            float rotation=coordinate(p,prefix+"rotation");
            if(!ids.add(id)||!collision.clear(mask,x,y,ScenePortals.PAWN_RADIUS)||Math.abs(rotation)>Math.PI)
                throw new IOException("Invalid, duplicated or unwalkable portal entrance");
            entrances.put(tag,new ScenePortals.Entrance(id,x,y,rotation));
        }
        var triggers=new ArrayList<ScenePortals.Trigger>();ids.clear();
        for(String key:list(p,"triggers")) {
            int id;try{id=Integer.parseInt(key);}catch(NumberFormatException e){throw new IOException("Invalid portal entry ID",e);}
            if(id<=0||!ids.add(id))throw new IOException("Invalid or duplicated portal entry ID");
            String prefix="trigger."+id+".";
            int destination=positive(p,prefix+"scene");String tag=required(p,prefix+"entrance");
            String shape=p.getProperty(prefix+"shape","").trim();
            if(shape.isEmpty())shape="rectangle";
            if(shape.equals("polygon")) {
                if(!format.equals("2"))throw new IOException("Polygon triggers require metadata format 2");
                List<ScenePortals.Vertex> vertices=parseVertices(requiredLong(p,prefix+"vertices",4096));validatePolygon(vertices);
                triggers.add(new ScenePortals.Trigger(id,destination,tag,vertices.stream().map(ScenePortals.Vertex::x).min(Float::compare).orElseThrow(),vertices.stream().map(ScenePortals.Vertex::y).min(Float::compare).orElseThrow(),vertices.stream().map(ScenePortals.Vertex::x).max(Float::compare).orElseThrow(),vertices.stream().map(ScenePortals.Vertex::y).max(Float::compare).orElseThrow(),vertices));
            } else if(shape.equals("rectangle")) {
                float x=coordinate(p,prefix+"x"),y=coordinate(p,prefix+"y"),w=coordinate(p,prefix+"width"),h=coordinate(p,prefix+"height");
                if(!(w>0)||!(h>0))throw new IOException("Invalid portal rectangle");
                triggers.add(new ScenePortals.Trigger(id,destination,tag,x-w/2,y-h/2,x+w/2,y+h/2));
            } else throw new IOException("Unknown portal trigger shape: "+shape);
        }
        return new ScenePortals(entrances,triggers);
    }
    public static ScenePortals load(Properties p,String layoutDigest,CollisionMap terrain)throws IOException {
        String format=required(p,"format");
        if(!(format.equals("1")||format.equals("2"))||!layoutDigest.equalsIgnoreCase(required(p,"layout-sha256")))
            throw new IOException("Portal metadata does not match its scene layout");
        var entrances=new LinkedHashMap<String,ScenePortals.Entrance>();var ids=new HashSet<Integer>();
        for(String tag:list(p,"entrances")) {
            if(!tag.matches("[A-Za-z0-9_-]{1,128}"))throw new IOException("Invalid entrance tag");
            String prefix="entrance."+tag+".";
            int id=positive(p,prefix+"id");float x=coordinate(p,prefix+"x"),y=coordinate(p,prefix+"y");
            float rotation=coordinate(p,prefix+"rotation");
            if(!ids.add(id)||!terrain.walkable(x,y)||Math.abs(rotation)>Math.PI)
                throw new IOException("Invalid, duplicated or unwalkable portal entrance");
            entrances.put(tag,new ScenePortals.Entrance(id,x,y,rotation));
        }
        var triggers=new ArrayList<ScenePortals.Trigger>();ids.clear();
        for(String key:list(p,"triggers")) {
            int id;try{id=Integer.parseInt(key);}catch(NumberFormatException e){throw new IOException("Invalid portal entry ID",e);}
            if(id<=0||!ids.add(id))throw new IOException("Invalid or duplicated portal entry ID");
            String prefix="trigger."+id+".";
            int destination=positive(p,prefix+"scene");String tag=required(p,prefix+"entrance");
            String shape=p.getProperty(prefix+"shape","").trim();
            if(shape.isEmpty())shape="rectangle";
            if(shape.equals("polygon")) {
                if(!format.equals("2"))throw new IOException("Polygon triggers require metadata format 2");
                List<ScenePortals.Vertex> vertices=parseVertices(requiredLong(p,prefix+"vertices",4096));validatePolygon(vertices);
                float minX=vertices.stream().map(ScenePortals.Vertex::x).min(Float::compare).orElseThrow();
                float minY=vertices.stream().map(ScenePortals.Vertex::y).min(Float::compare).orElseThrow();
                float maxX=vertices.stream().map(ScenePortals.Vertex::x).max(Float::compare).orElseThrow();
                float maxY=vertices.stream().map(ScenePortals.Vertex::y).max(Float::compare).orElseThrow();
                triggers.add(new ScenePortals.Trigger(id,destination,tag,minX,minY,maxX,maxY,vertices));
            } else if(shape.equals("rectangle")) {
                float minX=coordinate(p,prefix+"min-x"),minY=coordinate(p,prefix+"min-y");
                float maxX=coordinate(p,prefix+"max-x"),maxY=coordinate(p,prefix+"max-y");
                if(minX>=maxX||minY>=maxY||maxX-minX>1000||maxY-minY>1000)
                    throw new IOException("Invalid portal trigger rectangle");
                triggers.add(new ScenePortals.Trigger(id,destination,tag,minX,minY,maxX,maxY));
            } else throw new IOException("Invalid portal trigger shape");
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
    private static String requiredLong(Properties p,String key,int maxLength)throws IOException {
        String value=p.getProperty(key,"").trim();
        if(value.isEmpty()||value.length()>maxLength)throw new IOException("Missing or invalid portal property: "+key);return value;
    }
    private static int positive(Properties p,String key)throws IOException {
        try {int value=Integer.parseInt(required(p,key));if(value<=0)throw new NumberFormatException();return value;}
        catch(NumberFormatException e){throw new IOException("Invalid portal ID: "+key,e);}
    }
    private static float coordinate(Properties p,String key)throws IOException {
        try {float value=Float.parseFloat(required(p,key));if(!Float.isFinite(value)||Math.abs(value)>10000)throw new NumberFormatException();return value;}
        catch(NumberFormatException e){throw new IOException("Invalid portal coordinate: "+key,e);}
    }
    private static List<ScenePortals.Vertex> parseVertices(String value)throws IOException {
        if(value.length()>4096)throw new IOException("Portal polygon metadata is too long");
        String[] points=value.split(";",-1);if(points.length<3||points.length>32)throw new IOException("Invalid portal polygon vertex count");
        var result=new ArrayList<ScenePortals.Vertex>(points.length);
        for(String point:points) {
            String[] xy=point.trim().split(",",-1);if(xy.length!=2)throw new IOException("Invalid portal polygon vertex");
            result.add(new ScenePortals.Vertex(parseCoordinate(xy[0]),parseCoordinate(xy[1])));
        }
        return List.copyOf(result);
    }
    private static float parseCoordinate(String raw)throws IOException {
        try {float value=Float.parseFloat(raw.trim());if(!Float.isFinite(value)||Math.abs(value)>10000)throw new NumberFormatException();return value;}
        catch(NumberFormatException e){throw new IOException("Invalid portal polygon coordinate",e);}
    }
    static void validatePolygon(List<ScenePortals.Vertex> points)throws IOException {
        if(points.size()<3||points.size()>32)throw new IOException("Invalid portal polygon vertex count");
        float minX=Float.POSITIVE_INFINITY,minY=Float.POSITIVE_INFINITY,maxX=Float.NEGATIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY;
        for(ScenePortals.Vertex v:points) { if(!Float.isFinite(v.x())||!Float.isFinite(v.y())||Math.abs(v.x())>10000||Math.abs(v.y())>10000)throw new IOException("Invalid portal polygon coordinate");
            minX=Math.min(minX,v.x());minY=Math.min(minY,v.y());maxX=Math.max(maxX,v.x());maxY=Math.max(maxY,v.y()); }
        if(maxX-minX>1000||maxY-minY>1000)throw new IOException("Portal polygon is too large");
        for(int i=0;i<points.size();i++)for(int j=i+1;j<points.size();j++) {
            ScenePortals.Vertex a=points.get(i),b=points.get(j);
            if(a.x()==b.x()&&a.y()==b.y())throw new IOException("Duplicate portal polygon vertex");
        }
        for(int i=0;i<points.size();i++) {
            ScenePortals.Vertex a=points.get(i),b=points.get((i+1)%points.size());float side=0;
            for(int j=0;j<points.size();j++) {
                if(j==i||j==(i+1)%points.size())continue;
                ScenePortals.Vertex c=points.get(j);float cross=(b.x()-a.x())*(c.y()-a.y())-(b.y()-a.y())*(c.x()-a.x());
                if(cross==0)throw new IOException("Collinear portal polygon vertices");
                if(side==0)side=Math.signum(cross);else if(Math.signum(cross)!=side)throw new IOException("Portal polygon is not strictly convex");
            }
        }
    }}
