package org.tortodrone.world;

import java.util.*;

/** Bounded scene-local A*, using Clyde Pathfinder's unit-cell search and live collision queries. */
public final class ScenePathing {
    private ScenePathing() { }
    public record Point(float x,float y) { }
    @FunctionalInterface public interface Traversal { boolean clear(float x,float y,float tx,float ty); }
    private record Cell(int x,int y) { }
    private record Node(Cell cell,float cost,float estimate) { }
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
        if(goal==null)return List.of();
        var path=new ArrayList<Point>();path.add(new Point(tx,ty));
        for(var cell=goal;!cell.equals(start);cell=parents.get(cell))path.add(new Point(x+cell.x(),y+cell.y()));
        Collections.reverse(path);return List.copyOf(path);
    }
}
