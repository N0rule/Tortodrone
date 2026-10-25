package org.tortodrone.world;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Immutable native static-collision metadata for projectile profiles. */
public final class SceneCollision {
    private static final SceneCollision EMPTY = new SceneCollision(Map.of(), Set.of(), List.of(),false);
    private static final int MAX_BYTES = 4 * 1024 * 1024, MAX_TILES = 1_000_000, MAX_FLOORS=1_000_000, MAX_SHAPES = 100_000;
    private final Map<Long,Integer> tiles;
    private final Set<Long> floorCells;
    private final List<Obstacle> obstacles;
    private final boolean present;

    private SceneCollision(Map<Long,Integer> tiles, Set<Long> floorCells, List<Obstacle> obstacles,boolean present) {
        this.tiles = Map.copyOf(tiles);
        this.floorCells=Set.copyOf(floorCells);
        this.obstacles = List.copyOf(obstacles);
        this.present=present;
    }

    public static SceneCollision empty() { return EMPTY; }
    /** Minimum separating translation for the circular stock pawn; compounds use the deepest contact. */
    public record Penetration(float x,float y) {
        public static final Penetration NONE=new Penetration(0,0);
        public boolean present(){return x!=0||y!=0;}
        public Penetration deepest(Penetration other){return other.x*other.x+other.y*other.y>x*x+y*y?other:this;}
    }
    /** Immutable compiled shape; scene entities retain a posed copy until their pose changes. */
    public static final class PawnShape {
        private final List<Obstacle> parts;
        private PawnShape(List<Geom> geometry){parts=geometry.stream().map(g->new Obstacle(0,g)).toList();}
        public PawnShape at(float x,float y,float rotation){
            float c=(float)Math.cos(rotation),s=(float)Math.sin(rotation);
            return new PawnShape(parts.stream().map(part->transform(part.geometry,x,y,c,s,1)).toList());
        }
        public Penetration penetration(float x,float y,float radius){
            var center=new Point(x,y);var result=Penetration.NONE;
            for(var part:parts)if(part.bounds.near(x,y,radius))result=result.deepest(SceneCollision.penetration(part.geometry,center,radius));
            return result;
        }
    }
    public static PawnShape pawnShape(Object shape){return new PawnShape(fromData(shape,0));}
    static PawnShape pawnShape(SceneContent.Spec spec,Object shape){
        if(shape!=null)return pawnShape(shape);
        if(spec.fields().containsKey("radius"))return new PawnShape(List.of(new Circle(new Point(0,0),spec.number("radius",0))));
        return new PawnShape(List.of(rectangle(spec.number("bounds-min-x",-spec.number("width",0)/2),spec.number("bounds-min-y",-spec.number("height",0)/2),
            spec.number("bounds-max-x",spec.number("width",0)/2),spec.number("bounds-max-y",spec.number("height",0)/2))));
    }
    public static Penetration penetration(Object shape,float shapeX,float shapeY,float rotation,float x,float y,float radius) {
        return pawnShape(shape).at(shapeX,shapeY,rotation).penetration(x,y,radius);
    }
    public Penetration penetration(int mask,float x,float y,float radius) {
        if(mask==0||isEmpty())return Penetration.NONE;
        var center=new Point(x,y);var result=Penetration.NONE;
        for(int cy=floor(y-radius);cy<=floor(y+radius);cy++)for(int cx=floor(x-radius);cx<=floor(x+radius);cx++){
            Integer flags=tiles.get(key(cx,cy));
            if(flags!=null&&(flags&mask)!=0)result=result.deepest(penetration(rectangle(cx,cy,cx+1,cy+1),center,radius));
        }
        for(var obstacle:obstacles)if((obstacle.mask&mask)!=0&&obstacle.bounds.near(x,y,radius))result=result.deepest(penetration(obstacle.geometry,center,radius));
        return result;
    }
    private static Penetration penetration(Geom obstacle,Point center,float radius) {
        if(obstacle instanceof Circle circle)return radial(center,circle.center,radius+circle.radius);
        if(obstacle instanceof Capsule capsule)return radial(center,nearest(center,capsule.a,capsule.b),radius+capsule.radius);
        var polygon=(Polygon)obstacle;Point closest=null;double best=Double.POSITIVE_INFINITY;
        float winding=polygon.winding,depth=Float.POSITIVE_INFINITY;int edge=-1;boolean interior=true;
        for(int i=0;i<polygon.points.size();i++){
            var a=polygon.points.get(i);var b=polygon.points.get((i+1)%polygon.points.size());
            var point=nearest(center,a,b);
            double distance=distance2(center,point);if(distance<best){best=distance;closest=point;}
            float nx=a.y-b.y,ny=b.x-a.x,length=polygon.edgeLengths[i];if(length==0)continue;
            // Keep native float plane distances, including their tie order at inside corners.
            float signed=(nx*center.x+ny*center.y-nx*a.x-ny*a.y)*winding;
            interior&=signed>=0;float separation=signed/length;
            if(separation<depth){depth=separation;edge=i;}
        }
        if(!interior||edge<0)return radial(center,closest,radius);
        var a=polygon.points.get(edge);var b=polygon.points.get((edge+1)%polygon.points.size());
        float length=polygon.edgeLengths[edge];if(length==0)return Penetration.NONE;
        float scale=winding*(radius+depth)/length;
        return new Penetration((b.y-a.y)*scale,(a.x-b.x)*scale);
    }
    private static Penetration radial(Point center,Point closest,float radius) {
        float dx=center.x-closest.x,dy=center.y-closest.y,distance=(float)Math.hypot(dx,dy);
        if(distance==0||distance>=radius)return Penetration.NONE;
        float scale=radius/distance-1;return new Penetration(dx*scale,dy*scale);
    }
    private static Point nearest(Point p,Point a,Point b) {
        float dx=b.x-a.x,dy=b.y-a.y,length=dx*dx+dy*dy;
        float t=length==0?0:Math.clamp(((p.x-a.x)*dx+(p.y-a.y)*dy)/length,0,1);
        return new Point(a.x+t*dx,a.y+t*dy);
    }
    /** Native actor shapes at two world poses, with a rounded damage-region expansion. */
    public static boolean overlaps(Object first,float x,float y,float rotation,Object second,float otherX,float otherY,float otherRotation,float expansion) {
        float c=(float)Math.cos(rotation),s=(float)Math.sin(rotation),oc=(float)Math.cos(otherRotation),os=(float)Math.sin(otherRotation);
        var localA=fromData(first,0);
        if(localA.isEmpty())return false;
        var localB=fromData(second,0);
        if(localB.isEmpty())return false;
        // Each compound part has one world pose for this query, independent of its counterpart.
        var posedB=new Geom[localB.size()];
        for(int i=0;i<posedB.length;i++)posedB[i]=transform(localB.get(i),otherX,otherY,oc,os,1);
        for(var a:localA){
            var worldA=transform(a,x,y,c,s,1);
            for(var worldB:posedB){
                if(intersects(worldA,worldB))return true;
                if(expansion<=0)continue;
                if(worldA instanceof Circle circle){if(intersects(new Circle(circle.center,circle.radius+expansion),worldB))return true;}
                else if(worldA instanceof Capsule capsule){if(intersects(new Capsule(capsule.a,capsule.b,capsule.radius+expansion),worldB))return true;}
                else if(worldA instanceof Polygon polygon)for(int i=0;i<polygon.points.size();i++)
                    if(intersects(new Capsule(polygon.points.get(i),polygon.points.get((i+1)%polygon.points.size()),expansion),worldB))return true;
            }
        }
        return false;
    }
    /** True only when this scene pack has no collision metadata (legacy fallback case). */
    public boolean isEmpty() { return !present; }
    /** True when the native scene contains a tile at floor(x), floor(y); absent metadata returns false. */
    public boolean floorSupported(float x,float y){
        if(!present||!Float.isFinite(x)||!Float.isFinite(y))return false;
        try{return floorCells.contains(key(floor(x),floor(y)));}catch(IllegalArgumentException outOfRange){return false;}
    }

    /** Loads an optional prepared collision file and verifies it belongs to this scene layout. */
    public static SceneCollision load(Path folder, String layoutHash) throws IOException {
        Path file = folder.resolve("static-collision.properties");
        if (!Files.exists(file)) return EMPTY;
        long size = Files.size(file);
        if (size <= 0 || size > MAX_BYTES) throw new IOException("Static collision file is too large or empty");
        Properties p = new Properties();
        try (var in = Files.newInputStream(file)) { p.load(in); }
        if (!"1".equals(p.getProperty("format"))) throw new IOException("Unsupported static collision format");
        if (!Objects.equals(layoutHash, p.getProperty("layout-sha256"))) throw new IOException("Static collision layout hash mismatch");
        int tileCount = count(p, "tile-count", MAX_TILES), floorCount=count(p,"floor-count",MAX_FLOORS), shapeCount = count(p, "shape-count", MAX_SHAPES);
        var floorCells=new HashSet<Long>(Math.max(16,floorCount*4/3));
        for(int i=0;i<floorCount;i++){
            String prefix="floor."+i+".";int x=integer(p,prefix+"x"),y=integer(p,prefix+"y");
            if(Math.abs((long)x)>1_000_000||Math.abs((long)y)>1_000_000||!floorCells.add(key(x,y)))throw new IOException("Invalid static collision floor cell");
        }
        var tiles = new HashMap<Long,Integer>(Math.max(16, tileCount * 4 / 3));
        for (int i=0;i<tileCount;i++) {
            String prefix="tile."+i+".";
            int x=integer(p,prefix+"x"), y=integer(p,prefix+"y"), mask=integer(p,prefix+"mask");
            if (mask==0 || Math.abs((long)x)>1_000_000 || Math.abs((long)y)>1_000_000
                    || tiles.put(key(x,y),mask)!=null) throw new IOException("Invalid static collision tile");
        }
        var shapes = new ArrayList<Obstacle>(shapeCount);
        for (int i=0;i<shapeCount;i++) {
            String prefix="shape."+i+".";
            int mask=integer(p,prefix+"mask");
            if(mask==0)throw new IOException("Invalid static collision shape mask");
            shapes.add(new Obstacle(mask,parseGeometry(required(p,prefix+"geometry"))));
        }
        return new SceneCollision(tiles,floorCells,shapes,true);
    }

    /** Returns true when this actor-local shape clears native static geometry at the pose. */
    public boolean clear(int mask,Object localShape,float x,float y,float rotation) {
        if(mask==0 || isEmpty())return true;
        List<Geom> local;
        try { local=fromData(localShape,0); }
        catch(RuntimeException invalid) { return false; }
        return clearAt(mask,local,x,y,rotation);
    }

    /** Samples a translated shape at 0.1-unit intervals to prevent projectile tunnelling. */
    public boolean clearSegment(int mask,Object localShape,float fromX,float fromY,float toX,float toY,float rotation) {
        if(mask==0 || isEmpty())return true;
        double distance=Math.hypot(toX-fromX,toY-fromY);
        if(!Double.isFinite(distance)||distance>10_000)return false;
        List<Geom> local;
        try { local=fromData(localShape,0); }
        catch(RuntimeException invalid) { return false; }
        int steps=Math.max(1,(int)Math.ceil(distance/.1));
        for(int i=0;i<=steps;i++){
            float t=(float)i/steps;
            if(!clearAt(mask,local,fromX+(toX-fromX)*t,fromY+(toY-fromY)*t,rotation))return false;
        }
        return true;
    }

    private boolean clearAt(int mask,List<Geom> local,float x,float y,float rotation){
        if(local.isEmpty()||!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(rotation))return false;
        float c=(float)Math.cos(rotation),s=(float)Math.sin(rotation);
        try{for(Geom geom:local)if(collides(mask,transform(geom,x,y,c,s,1)))return false;}
        catch(RuntimeException invalid){return false;}
        return true;
    }

    private boolean collides(int mask,Geom moving) {
        Bounds b=bounds(moving);
        if(Math.abs((double)b.minX)>1_000_000||Math.abs((double)b.minY)>1_000_000
                ||Math.abs((double)b.maxX)>1_000_000||Math.abs((double)b.maxY)>1_000_000)return true;
        int minX=floor(b.minX),maxX=floor(b.maxX),minY=floor(b.minY),maxY=floor(b.maxY);
        if((long)(maxX-minX+1)*(maxY-minY+1)>1_000_000)return true;
        for(int cy=minY;cy<=maxY;cy++)for(int cx=minX;cx<=maxX;cx++){
            Integer flags=tiles.get(key(cx,cy));
            if(flags!=null&&(flags&mask)!=0&&intersects(moving,rectangle(cx,cy,cx+1,cy+1)))return true;
        }
        for(Obstacle obstacle:obstacles)if((obstacle.mask&mask)!=0&&intersects(moving,obstacle.geometry))return true;
        return false;
    }

    private static List<Geom> fromData(Object value,int depth) {
        if(depth>32||!(value instanceof FireCatalog.DataValue data))throw new IllegalArgumentException("Unknown actor collision shape");
        String type=data.type();
        if(type.endsWith("$TransformedShape")||type.endsWith("$Transformed")){
            Object transform=SceneDamage.field(data,"transform"),translation=SceneDamage.field(transform,"_translation");
            float tx=SceneDamage.number(translation,"x",0),ty=SceneDamage.number(translation,"y",0),r=SceneDamage.number(transform,"_rotation",0),scale=SceneDamage.number(transform,"_scale",1);
            List<Geom> child=fromData(SceneDamage.field(data,"shape"),depth+1);
            float c=(float)Math.cos(r),s=(float)Math.sin(r);return child.stream().map(g->transform(g,tx,ty,c,s,scale)).toList();
        }
        if(type.endsWith("$Compound")){
            Object raw=SceneDamage.field(data,"shapes");if(!(raw instanceof FireCatalog.ListValue list))throw new IllegalArgumentException("Invalid compound shape");
            var out=new ArrayList<Geom>();for(Object child:list.values())out.addAll(fromData(child,depth+1));return out;
        }
        if(type.endsWith("$Circle"))return List.of(new Circle(new Point(0,0),positive(number(data,"radius"))));
        if(type.endsWith("$Point"))return List.of(new Circle(new Point(0,0),0));
        if(type.endsWith("$Rectangle")){
            float w=positive(number(data,"width")),h=positive(number(data,"height"));return List.of(rectangle(-w/2,-h/2,w/2,h/2));
        }
        if(type.endsWith("$Capsule")){
            float r=positive(number(data,"radius")),half=positive(number(data,"length"))/2;return List.of(new Capsule(new Point(-half,0),new Point(half,0),r));
        }
        if(type.endsWith("$Segment")){
            float half=positive(number(data,"length"))/2;return List.of(new Capsule(new Point(-half,0),new Point(half,0),0));
        }
        if(type.endsWith("$Polygon")){
            Object raw=SceneDamage.field(data,"vertices");if(!(raw instanceof FireCatalog.ListValue list)||list.values().size()<3||list.values().size()>4096)throw new IllegalArgumentException("Invalid polygon");
            var points=new ArrayList<Point>();for(Object vertex:list.values())points.add(new Point(number(vertex,"x"),number(vertex,"y")));return List.of(new Polygon(points));
        }
        throw new IllegalArgumentException("Unsupported actor collision shape: "+type);
    }

    private static float number(Object value,String field){Object raw=SceneDamage.field(value,field);if(!(raw instanceof Number n)||!Float.isFinite(n.floatValue()))throw new IllegalArgumentException("Invalid shape number "+field);return n.floatValue();}
    private static float positive(float value){if(value<0)throw new IllegalArgumentException("Negative shape dimension");return value;}

    private static Geom transform(Geom g,float x,float y,float c,float s,float scale){
        if(!Float.isFinite(scale))throw new IllegalArgumentException("Invalid shape scale");
        if(g instanceof Circle circle)return new Circle(point(circle.center,x,y,c,s,scale),circle.radius*Math.abs(scale));
        if(g instanceof Capsule capsule)return new Capsule(point(capsule.a,x,y,c,s,scale),point(capsule.b,x,y,c,s,scale),capsule.radius*Math.abs(scale));
        Polygon polygon=(Polygon)g;return new Polygon(polygon.points.stream().map(p->point(p,x,y,c,s,scale)).toList());
    }
    private static Point point(Point p,float x,float y,float c,float s,float scale){float px=p.x*scale,py=p.y*scale;return new Point(x+px*c-py*s,y+px*s+py*c);}

    private static Geom parseGeometry(String text)throws IOException{
        try{
            String[] f=text.split(";",-1);String kind=f[0];
            if(kind.equals("C")&&f.length==4)return new Circle(new Point(num(f[1]),num(f[2])),positive(num(f[3])));
            if(kind.equals("K")&&f.length==6)return new Capsule(new Point(num(f[1]),num(f[2])),new Point(num(f[3]),num(f[4])),positive(num(f[5])));
            if(kind.equals("P")&&f.length>=7&&f.length%2==1){var points=new ArrayList<Point>();for(int i=1;i<f.length;i+=2)points.add(new Point(num(f[i]),num(f[i+1])));return new Polygon(points);}
            throw new IllegalArgumentException();
        }catch(RuntimeException e){throw new IOException("Invalid static collision geometry",e);}
    }
    private static float num(String s){float f=Float.parseFloat(s);if(!Float.isFinite(f))throw new IllegalArgumentException();return f;}
    private static int count(Properties p,String key,int max)throws IOException{int n=integer(p,key);if(n<0||n>max)throw new IOException("Invalid "+key);return n;}
    private static int integer(Properties p,String key)throws IOException{try{return Integer.parseInt(required(p,key));}catch(NumberFormatException e){throw new IOException("Invalid "+key,e);}}
    private static String required(Properties p,String key)throws IOException{String v=p.getProperty(key);if(v==null)throw new IOException("Missing "+key);return v;}
    private static int floor(float value){if(value<Integer.MIN_VALUE||value>Integer.MAX_VALUE)throw new IllegalArgumentException("Collision coordinates out of range");return (int)Math.floor(value);}
    private static long key(int x,int y){return ((long)x<<32)^(y&0xffffffffL);}

    private static Bounds bounds(Geom g){
        if(g instanceof Circle c)return new Bounds(c.center.x-c.radius,c.center.y-c.radius,c.center.x+c.radius,c.center.y+c.radius);
        if(g instanceof Capsule c)return new Bounds(Math.min(c.a.x,c.b.x)-c.radius,Math.min(c.a.y,c.b.y)-c.radius,Math.max(c.a.x,c.b.x)+c.radius,Math.max(c.a.y,c.b.y)+c.radius);
        Polygon p=(Polygon)g;float minX=Float.POSITIVE_INFINITY,minY=minX,maxX=Float.NEGATIVE_INFINITY,maxY=maxX;
        for(Point v:p.points){minX=Math.min(minX,v.x);minY=Math.min(minY,v.y);maxX=Math.max(maxX,v.x);maxY=Math.max(maxY,v.y);}return new Bounds(minX,minY,maxX,maxY);
    }
    private static Polygon rectangle(float x0,float y0,float x1,float y1){return new Polygon(List.of(new Point(x0,y0),new Point(x1,y0),new Point(x1,y1),new Point(x0,y1)));}
    private static boolean intersects(Geom a,Geom b){
        if(a instanceof Circle x&&b instanceof Circle y)return distance2(x.center,y.center)<=sq(x.radius+y.radius);
        if(a instanceof Circle x&&b instanceof Polygon y)return circlePolygon(x,y);
        if(a instanceof Polygon x&&b instanceof Circle y)return circlePolygon(y,x);
        if(a instanceof Capsule x&&b instanceof Circle y)return pointSegment2(y.center,x.a,x.b)<=sq(x.radius+y.radius);
        if(a instanceof Circle x&&b instanceof Capsule y)return pointSegment2(x.center,y.a,y.b)<=sq(x.radius+y.radius);
        if(a instanceof Capsule x&&b instanceof Capsule y)return segmentDistance2(x.a,x.b,y.a,y.b)<=sq(x.radius+y.radius);
        if(a instanceof Capsule x&&b instanceof Polygon y)return capsulePolygon(x,y);
        if(a instanceof Polygon x&&b instanceof Capsule y)return capsulePolygon(y,x);
        return polygonsIntersect((Polygon)a,(Polygon)b);
    }
    private static boolean circlePolygon(Circle circle,Polygon poly){
        if(inside(circle.center,poly))return true;for(int i=0;i<poly.points.size();i++)if(pointSegment2(circle.center,poly.points.get(i),poly.points.get((i+1)%poly.points.size()))<=sq(circle.radius))return true;return false;
    }
    private static boolean capsulePolygon(Capsule cap,Polygon poly){
        if(inside(cap.a,poly)||inside(cap.b,poly))return true;
        for(int i=0;i<poly.points.size();i++)if(segmentDistance2(cap.a,cap.b,poly.points.get(i),poly.points.get((i+1)%poly.points.size()))<=sq(cap.radius))return true;
        return false;
    }
    private static boolean polygonsIntersect(Polygon a,Polygon b){
        for(int i=0;i<a.points.size();i++)for(int j=0;j<b.points.size();j++)if(segmentsIntersect(a.points.get(i),a.points.get((i+1)%a.points.size()),b.points.get(j),b.points.get((j+1)%b.points.size())))return true;
        return inside(a.points.getFirst(),b)||inside(b.points.getFirst(),a);
    }
    private static boolean inside(Point p,Polygon poly){boolean in=false;for(int i=0,j=poly.points.size()-1;i<poly.points.size();j=i++){Point a=poly.points.get(j),b=poly.points.get(i);if(pointSegment2(p,a,b)<1e-12)return true;if((a.y>p.y)!=(b.y>p.y)&&p.x<(b.x-a.x)*(p.y-a.y)/(b.y-a.y)+a.x)in=!in;}return in;}
    private static boolean segmentsIntersect(Point a,Point b,Point c,Point d){double o1=orient(a,b,c),o2=orient(a,b,d),o3=orient(c,d,a),o4=orient(c,d,b);if((o1==0&&pointSegment2(c,a,b)<1e-12)||(o2==0&&pointSegment2(d,a,b)<1e-12)||(o3==0&&pointSegment2(a,c,d)<1e-12)||(o4==0&&pointSegment2(b,c,d)<1e-12))return true;return (o1>0)!=(o2>0)&&(o3>0)!=(o4>0);}
    private static double orient(Point a,Point b,Point c){return (b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);}
    private static double pointSegment2(Point p,Point a,Point b){double dx=b.x-a.x,dy=b.y-a.y,len=dx*dx+dy*dy,t=len==0?0:Math.max(0,Math.min(1,((p.x-a.x)*dx+(p.y-a.y)*dy)/len));return sq(p.x-a.x-t*dx)+sq(p.y-a.y-t*dy);}
    private static double segmentDistance2(Point a,Point b,Point c,Point d){if(segmentsIntersect(a,b,c,d))return 0;return Math.min(Math.min(pointSegment2(a,c,d),pointSegment2(b,c,d)),Math.min(pointSegment2(c,a,b),pointSegment2(d,a,b)));}
    private static double distance2(Point a,Point b){return sq(a.x-b.x)+sq(a.y-b.y);}
    private static double sq(double x){return x*x;}

    private interface Geom { }
    private record Point(float x,float y) { Point { if(!Float.isFinite(x)||!Float.isFinite(y))throw new IllegalArgumentException("Non-finite point"); } }
    private record Circle(Point center,float radius) implements Geom { Circle { if(!Float.isFinite(radius)||radius<0)throw new IllegalArgumentException("Invalid circle"); } }
    private record Capsule(Point a,Point b,float radius) implements Geom { Capsule { if(!Float.isFinite(radius)||radius<0)throw new IllegalArgumentException("Invalid capsule"); } }
    private static final class Polygon implements Geom {
        final List<Point> points;final float winding;final float[] edgeLengths;
        Polygon(List<Point> points){
            this.points=List.copyOf(points);if(points.size()<3||points.size()>4096)throw new IllegalArgumentException("Invalid polygon");
            edgeLengths=new float[points.size()];double area=0;
            for(int i=0;i<points.size();i++){
                var a=points.get(i);var b=points.get((i+1)%points.size());area+=(double)a.x*b.y-(double)b.x*a.y;
                edgeLengths[i]=(float)Math.hypot(a.y-b.y,b.x-a.x);
            }
            winding=area>=0?1:-1;
        }
    }
    private record Bounds(float minX,float minY,float maxX,float maxY) {
        boolean near(float x,float y,float radius){return x+radius>=minX&&x-radius<=maxX&&y+radius>=minY&&y-radius<=maxY;}
    }
    private record Obstacle(int mask,Geom geometry,Bounds bounds) {
        private Obstacle(int mask,Geom geometry){this(mask,geometry,SceneCollision.bounds(geometry));}
    }
}
