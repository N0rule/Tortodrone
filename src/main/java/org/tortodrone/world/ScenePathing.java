package org.tortodrone.world;

import java.util.*;

/** Bounded scene-local A*, using Clyde Pathfinder's unit-cell search and live collision queries. */
public final class ScenePathing {
    private ScenePathing() { }
    public record Point(float x,float y) { }
    @FunctionalInterface public interface Traversal { boolean clear(float x,float y,float tx,float ty); }
    private record Cell(int x,int y) { }
    private record Node(Cell cell,float cost,float estimate) { }
    @FunctionalInterface public interface Distance {float remaining(float x,float y);}
    /** Reach an attack region around an occupied body, without entering that body. */
    public static List<Point> region(float x,float y,Distance distance,Traversal traversal){
        var queue=new PriorityQueue<Node>(Comparator.comparingDouble(Node::estimate).thenComparingDouble(Node::cost));
        var costs=new HashMap<Cell,Float>();var parents=new HashMap<Cell,Cell>();
        var start=new Cell(0,0);costs.put(start,0f);queue.add(new Node(start,0,Math.max(0,distance.remaining(x,y))));
        Cell goal=null;int budget=4096;
        while(!queue.isEmpty()&&budget-->0){
            var node=queue.remove();var cell=node.cell();if(node.cost()>costs.getOrDefault(cell,Float.POSITIVE_INFINITY))continue;
            float px=x+cell.x(),py=y+cell.y();if(distance.remaining(px,py)<=0){goal=cell;break;}
            for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++){
                if(dx==0&&dy==0)continue;var next=new Cell(cell.x()+dx,cell.y()+dy);float cost=node.cost()+(float)Math.hypot(dx,dy);
                float nx=x+next.x(),ny=y+next.y();if(cost>=costs.getOrDefault(next,Float.POSITIVE_INFINITY)||!traversal.clear(px,py,nx,ny))continue;
                costs.put(next,cost);parents.put(next,cell);queue.add(new Node(next,cost,cost+Math.max(0,distance.remaining(nx,ny))));
            }
        }
        if(goal==null)return aligned(x,y,distance,traversal,null);var path=new ArrayList<Point>();
        for(var cell=goal;!cell.equals(start);cell=parents.get(cell))path.add(new Point(x+cell.x(),y+cell.y()));
        Collections.reverse(path);return List.copyOf(path);
    }
    /** Clyde Pathfinder's optional partial path: approach an unreachable region
     * without ever admitting a blocked edge. Existing complete-path users keep
     * their failure semantics. No path means the search found no closer pose. */
    public static List<Point> partialRegion(float x,float y,Distance distance,Traversal traversal){
        if(distance.remaining(x,y)<=0)return List.of();
        return aligned(x,y,distance,traversal,null,true);
    }
    public static List<Point> route(float x,float y,float tx,float ty,Traversal traversal) {
        if(traversal.clear(x,y,tx,ty))return List.of(new Point(tx,ty));
        var queue=new PriorityQueue<Node>(Comparator.comparingDouble(Node::estimate).thenComparingDouble(Node::cost));
        var costs=new HashMap<Cell,Float>();var parents=new HashMap<Cell,Cell>();
        var start=new Cell(0,0);costs.put(start,0f);queue.add(new Node(start,0,0));
        Cell goal=null;int budget=4096;
        while(!queue.isEmpty()&&budget-->0){
            var node=queue.remove();var cell=node.cell();if(node.cost()>costs.getOrDefault(cell,Float.POSITIVE_INFINITY))continue;
            float px=x+cell.x(),py=y+cell.y();
            if(Math.hypot(tx-px,ty-py)<=1.5&&traversal.clear(px,py,tx,ty)){goal=cell;break;}
            for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++){
                if(dx==0&&dy==0)continue;
                var next=new Cell(cell.x()+dx,cell.y()+dy);float cost=node.cost()+(float)Math.hypot(dx,dy);
                if(cost>=costs.getOrDefault(next,Float.POSITIVE_INFINITY)||!traversal.clear(px,py,x+next.x(),y+next.y()))continue;
                costs.put(next,cost);parents.put(next,cell);
                queue.add(new Node(next,cost,cost+(float)Math.hypot(tx-x-next.x(),ty-y-next.y())));
            }
        }
        if(goal==null)return aligned(x,y,(px,py)->(float)Math.hypot(tx-px,ty-py),traversal,new Point(tx,ty));
        var path=new ArrayList<Point>();path.add(new Point(tx,ty));
        for(var cell=goal;!cell.equals(start);cell=parents.get(cell))path.add(new Point(x+cell.x(),y+cell.y()));
        Collections.reverse(path);return List.copyOf(path);
    }
    /** Clyde uses a world-aligned half-unit lattice. A start-relative unit grid can
     * miss every point in an authored narrow passage after fractional movement. */
    private static List<Point> aligned(float x,float y,Distance distance,Traversal traversal,Point destination){
        return aligned(x,y,distance,traversal,destination,false);
    }
    private static List<Point> aligned(float x,float y,Distance distance,Traversal traversal,Point destination,boolean partial){
        if(!Float.isFinite(x)||!Float.isFinite(y)||Math.abs(x)>1_000_000||Math.abs(y)>1_000_000)return List.of();
        var queue=new PriorityQueue<Node>(Comparator.comparingDouble(Node::estimate).thenComparingDouble(Node::cost));
        var costs=new HashMap<Cell,Float>();var parents=new HashMap<Cell,Cell>();
        int cx=Math.round(x*2),cy=Math.round(y*2);
        for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++){
            var cell=new Cell(cx+dx,cy+dy);float px=cell.x()*.5f,py=cell.y()*.5f;
            if(!traversal.clear(x,y,px,py))continue;
            float cost=(float)Math.hypot(px-x,py-y);costs.put(cell,cost);queue.add(new Node(cell,cost,cost+Math.max(0,distance.remaining(px,py))));
        }
        Cell goal=null,closest=null;float remaining=partial?Math.max(0,distance.remaining(x,y)):0;int budget=4096;
        while(!queue.isEmpty()&&budget-->0){
            var node=queue.remove();var cell=node.cell();if(node.cost()>costs.getOrDefault(cell,Float.POSITIVE_INFINITY))continue;
            float px=cell.x()*.5f,py=cell.y()*.5f;
            if(partial){float candidate=Math.max(0,distance.remaining(px,py));if(candidate<remaining-.0001f){remaining=candidate;closest=cell;}}
            if(destination==null?distance.remaining(px,py)<=0:Math.hypot(destination.x()-px,destination.y()-py)<=1.5&&traversal.clear(px,py,destination.x(),destination.y())){goal=cell;break;}
            for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++){
                if(dx==0&&dy==0)continue;var next=new Cell(cell.x()+dx,cell.y()+dy);
                float cost=node.cost()+(float)Math.hypot(dx,dy)*.5f,nx=next.x()*.5f,ny=next.y()*.5f;
                if(cost>=costs.getOrDefault(next,Float.POSITIVE_INFINITY)||!traversal.clear(px,py,nx,ny))continue;
                costs.put(next,cost);parents.put(next,cell);queue.add(new Node(next,cost,cost+Math.max(0,distance.remaining(nx,ny))));
            }
        }
        if(goal==null){if(!partial||closest==null)return List.of();goal=closest;}
        var path=new ArrayList<Point>();if(destination!=null)path.add(destination);
        for(var cell=goal;cell!=null;cell=parents.get(cell))path.add(new Point(cell.x()*.5f,cell.y()*.5f));
        Collections.reverse(path);return List.copyOf(path);
    }
}
