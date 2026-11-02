import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import com.threerings.config.ConfigManager;
import com.threerings.math.Vector2f;
import com.threerings.tudey.data.TudeySceneModel;
import com.threerings.tudey.shape.Shape;
import com.threerings.tudey.util.Coord;
import com.threerings.tudey.util.CoordIntMap;

/** Exports numeric native collision metadata beside a prepared scene layout. */
public final class SceneCollisionExport {
    private static final int MAX_BYTES=4*1024*1024,MAX_CELLS=1_000_000,MAX_SHAPES=100_000,MAX_VERTICES=4096;
    private SceneCollisionExport() { }

    public static void export(ConfigManager cm,TudeySceneModel scene,Path output)throws Exception {
        Objects.requireNonNull(cm);Objects.requireNonNull(scene);Path folder=output.toAbsolutePath().normalize();
        if(Files.exists(folder.resolve("static-collision.properties")))throw new IOException("Refusing to replace static collision metadata");
        Path layout=folder.resolve("scene.dat");if(!Files.isRegularFile(layout))throw new IOException("Missing scene.dat for collision metadata");
        var collisionField=TudeySceneModel.class.getDeclaredField("bSE");collisionField.setAccessible(true);
        var nativeCells=(CoordIntMap)collisionField.get(scene);if(nativeCells==null)throw new IOException("Native collision map is unavailable");
        var cells=new TreeMap<Long,Cell>();var floors=new TreeMap<Long,Cell>();
        for(Coord coord:scene.adE()){
            if(scene.aQ(coord.x,coord.y)!=null)floors.put(key(coord.x,coord.y),new Cell(coord.x,coord.y,0));
            int flags=nativeCells.aY(coord.x,coord.y);if(flags!=0)cells.put(key(coord.x,coord.y),new Cell(coord.x,coord.y,flags));
        }
        if(cells.size()>MAX_CELLS||floors.size()>MAX_CELLS)throw new IOException("Too many native collision cells");
        var shapes=new LinkedHashSet<String>();var surfaces=new LinkedHashSet<String>();
        for(var entry:scene.ci(TudeySceneModel.PlaceableEntry.class)){
            if(entry.aB(cm).floorTile){
                var shape=entry.av(cm);if(shape==null)throw new IOException("Native floor placeable has no shape");
                var parts=new ArrayList<String>();geometry(shape,parts,0,shape.aeu());surfaces.addAll(parts);
                if(surfaces.size()>MAX_SHAPES)throw new IOException("Too many native floor shapes");
            }
            int mask=entry.ax(cm);if(mask==0)continue;
            Shape shape=entry.av(cm);if(shape==null)throw new IOException("Colliding placeable has no native shape");
            var geometries=new ArrayList<String>();
            try{geometry(shape,geometries,0,shape.aeu());}catch(IOException invalid){throw new IOException("Collision at entry "+entry.getKey()+" ("+entry.placeable.getName()+"): "+invalid.getMessage(),invalid);}
            for(String geometry:geometries)shapes.add(mask+";"+geometry);
            if(shapes.size()>MAX_SHAPES)throw new IOException("Too many native placeable collision shapes");
        }
        String digest=sha256(layout);var lines=new ArrayList<String>();
        lines.add("format=1");lines.add("layout-sha256="+digest);lines.add("tile-count="+cells.size());lines.add("floor-count="+floors.size());lines.add("shape-count="+shapes.size());
        lines.add("floor-shape-count="+surfaces.size());int surface=0;for(String geometry:surfaces)lines.add("floor-shape."+(surface++)+".geometry="+geometry);
        int i=0;for(Cell cell:floors.values()){
            String prefix="floor."+(i++)+".";lines.add(prefix+"x="+cell.x);lines.add(prefix+"y="+cell.y);
        }
        i=0;for(Cell cell:cells.values()){
            String prefix="tile."+(i++)+".";lines.add(prefix+"x="+cell.x);lines.add(prefix+"y="+cell.y);lines.add(prefix+"mask="+cell.mask);
        }
        i=0;for(String value:shapes){int split=value.indexOf(';');String prefix="shape."+(i++)+".";
            lines.add(prefix+"mask="+value.substring(0,split));lines.add(prefix+"geometry="+value.substring(split+1));
        }
        Collections.sort(lines);String text=String.join("\n",lines)+"\n";
        if(text.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IOException("Static collision metadata exceeds 4 MiB");
        Files.writeString(folder.resolve("static-collision.properties"),text,StandardCharsets.UTF_8);
        System.out.println("PASS: exported native static collision cells="+cells.size()+" floors="+floors.size()+" shapes="+shapes.size()+" layout="+digest);
    }

    static void geometry(Shape shape,List<String> out,int depth,com.threerings.math.Rect gate)throws IOException {
        if(depth>32)throw new IOException("Native compound collision shape is too deep");
        String type=shape.getClass().getName();
        if(type.endsWith(".b")){
            Vector2f center=shape.tv();float radius=number(field(shape,"radius"));
            out.add("C;"+f(center.x)+";"+f(center.y)+";"+f(radius));return;
        }
        if(type.endsWith(".g")){
            Vector2f center=(Vector2f)invoke(shape,"aet");out.add("C;"+f(center.x)+";"+f(center.y)+";0.0");return;
        }
        if(type.endsWith(".h")){
            int n=((Number)invoke(shape,"getVertexCount")).intValue();if(n<2||n>MAX_VERTICES)throw new IOException("Invalid native polygon size: "+n);
            var values=new StringBuilder(n==2?"D":"P");for(int j=0;j<n;j++){Vector2f v=(Vector2f)invoke(shape,"gL",int.class,j);values.append(';').append(f(v.x)).append(';').append(f(v.y));}if(n==2)values.append(';').append(f(gate.tt().x)).append(';').append(f(gate.tt().y)).append(';').append(f(gate.tu().x)).append(';').append(f(gate.tu().y));out.add(values.toString());return;
        }
        if(type.endsWith(".i")){
            Vector2f a=(Vector2f)invoke(shape,"aeo"),b=(Vector2f)invoke(shape,"aep");
            out.add("K;"+f(a.x)+";"+f(a.y)+";"+f(b.x)+";"+f(b.y)+";0.0");return;
        }
        if(type.endsWith(".a")){
            Vector2f a=(Vector2f)invoke(shape,"aeo"),b=(Vector2f)invoke(shape,"aep");float radius=number(field(shape,"radius"));
            out.add("K;"+f(a.x)+";"+f(a.y)+";"+f(b.x)+";"+f(b.y)+";"+f(radius));return;
        }
        if(type.endsWith(".c")){
            Shape[] children=(Shape[])invoke(shape,"aer");if(children.length>MAX_VERTICES)throw new IOException("Invalid native compound shape size");
            for(Shape child:children)geometry(child,out,depth+1,shape.aeu());return;
        }
        throw new IOException("Unsupported native collision shape class: "+type);
    }
    private static Object invoke(Object target,String name,Object... args)throws IOException{
        try{Class<?>[] types=new Class<?>[args.length/2];Object[] actual=new Object[args.length/2];
            for(int i=0,j=0;i<args.length;i+=2,j++){types[j]=(Class<?>)args[i];actual[j]=args[i+1];}
            Method method=target.getClass().getMethod(name,types);return method.invoke(target,actual);
        }catch(ReflectiveOperationException e){throw new IOException("Cannot read native collision shape",e);}
    }
    private static Object field(Object target,String name)throws IOException{
        try{Field field=target.getClass().getField(name);return field.get(target);}catch(ReflectiveOperationException e){throw new IOException("Cannot read native collision shape field "+name,e);}
    }
    private static float number(Object value)throws IOException{if(!(value instanceof Number n)||!Float.isFinite(n.floatValue())||n.floatValue()<0)throw new IOException("Invalid native collision dimension");return n.floatValue();}
    private static String f(float value)throws IOException{if(!Float.isFinite(value))throw new IOException("Non-finite native collision coordinate");return Float.toString(value);}
    private static long key(int x,int y){return ((long)x<<32)^(y&0xffffffffL);}
    private static String sha256(Path file)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
    private record Cell(int x,int y,int mask) { }
}
