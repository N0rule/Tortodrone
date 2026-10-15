package org.tortodrone.world;

/** Native prepared shape geometry, evaluated in its source actor's local coordinates. */
public final class SceneShape {
    private SceneShape() { }
    public static boolean intersects(Object shape,float x,float y,float radius) {
        if(!(shape instanceof FireCatalog.DataValue geometry))return false;
        String type=geometry.type();
        if(type.endsWith("$TransformedShape")){
            Object transform=SceneDamage.field(shape,"transform");
            Object translation=SceneDamage.field(transform,"_translation");
            float dx=x-SceneDamage.number(translation,"x",0),dy=y-SceneDamage.number(translation,"y",0);
            float rotation=SceneDamage.number(transform,"_rotation",0),scale=SceneDamage.number(transform,"_scale",1);
            if(scale==0)return false;
            float c=(float)Math.cos(rotation),s=(float)Math.sin(rotation);
            return intersects(SceneDamage.field(shape,"shape"),(dx*c+dy*s)/scale,(-dx*s+dy*c)/scale,radius/Math.abs(scale));
        }
        if(type.endsWith("$Compound")){
            Object shapes=SceneDamage.field(shape,"shapes");
            if(shapes instanceof FireCatalog.ListValue list)for(Object child:list.values())if(intersects(child,x,y,radius))return true;
            return false;
        }
        if(type.endsWith("$Segment")){float dx=x-Math.clamp(x,0,SceneDamage.number(shape,"length",0));return dx*dx+y*y<=radius*radius;}
        if(type.endsWith("$Circle"))return Math.hypot(x,y)<=SceneDamage.number(shape,"radius",0)+radius;
        if(type.endsWith("$Rectangle")){
            float dx=Math.max(0,Math.abs(x)-SceneDamage.number(shape,"width",0)/2);
            float dy=Math.max(0,Math.abs(y)-SceneDamage.number(shape,"height",0)/2);
            return dx*dx+dy*dy<=radius*radius;
        }
        if(type.endsWith("$Polygon")){
            Object vertices=SceneDamage.field(shape,"vertices");
            if(!(vertices instanceof FireCatalog.ListValue list)||list.values().size()<3)return false;
            boolean inside=false;int count=list.values().size();
            for(int i=0,j=count-1;i<count;j=i++){
                Object a=list.values().get(j),b=list.values().get(i);
                float ax=SceneDamage.number(a,"x",0),ay=SceneDamage.number(a,"y",0),bx=SceneDamage.number(b,"x",0),by=SceneDamage.number(b,"y",0);
                if((ay>y)!=(by>y)&&x<(bx-ax)*(y-ay)/(by-ay)+ax)inside=!inside;
                float dx=bx-ax,dy=by-ay,length=dx*dx+dy*dy;
                float t=length==0?0:Math.clamp(((x-ax)*dx+(y-ay)*dy)/length,0,1);
                float px=x-ax-t*dx,py=y-ay-t*dy;
                if(px*px+py*py<=radius*radius)return true;
            }
            return inside;
        }
        if(type.endsWith("$Point"))return Math.hypot(x,y)<=radius;
        return false;
    }
}
