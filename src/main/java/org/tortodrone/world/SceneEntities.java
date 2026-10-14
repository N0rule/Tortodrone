package org.tortodrone.world;

import java.util.*;
import org.tortodrone.character.StarterCatalog;

/** Instance-owned native actors, pad regeneration, movement and damage targets. */
public final class SceneEntities implements SceneWeapons.Targets {
    public record Pickup(int entityId,String item,boolean temporary,Integer equippedSlot) { }
    private static final Set<String> SUPPORTED=Set.of("Actor","Agent","Stranger","Monster","Generator","Door","Switch","Block","Block$Stateful","Pickup","Trap","DungeonLift");
    private static final class Entity {
        final int id,created;final SceneContent.Spec spec;final SceneContent.Entry binding;
        final String kind;final StarterCatalog.Model config;final Entity pad;
        float x,y,rotation,direction,health,speed;int flags,activity,activityStarted,state,stateEntered,count,hitTime,destroyed=Integer.MAX_VALUE,nextGeneration,claim;
        boolean claimTaken;
        int target,nextAcquire,nextAttack,nextSwitch,nextHeal,carrier,owner,ownerOid,thrownAt,faction,detonateAt,affectedType,affectedTime;float z;int[] excluded;
        SceneLiftState.Point dropPoint;
        SceneContent.Spec vial;String cycle;Set<String> fired=new HashSet<>();
        final SceneStatus status;
        final Object shape;
        final Map<Integer,Integer> hazardHits=new HashMap<>();
        final Set<Integer> refusedPickups=new HashSet<>();
        Entity(int id,int stamp,SceneContent.Entry binding,SceneContent.Spec spec,Entity pad,SceneContent content){
            this.id=id;created=stamp;this.binding=binding;this.spec=spec;this.pad=pad;
            config=spec.config();String type=spec.text("native-type","");kind=type.substring(type.lastIndexOf('.')+1);
            x=binding.x();y=binding.y();rotation=binding.rotation();health=spec.number("health",0);
            activityStarted=stateEntered=stamp;
            status=new SceneStatus(content,true);
            var preparedShape=spec.model("source-shape");shape=preparedShape==null?null:preparedShape.arguments().get("value");
            speed=spec.number("speed",0);
            faction=spec.integer("faction",0);
            if(kind.equals("Switch"))flags|=2; // Native Switch constructor's START flag, including help bubbles.
            if(spec.flag("start-open",false))flags|=2;
            cycle=spec.text("cycle","");int split=cycle.lastIndexOf('|');if(split>=0)cycle=cycle.substring(split+1);
        }
        ActorState snapshot(){return new ActorState(id,0,"",0,created,x,y,rotation,direction,flags,activity,activityStarted,config,false,null,null,
            new SceneEntityState(kind,destroyed,speed,spec.number("radius",0),
                spec.number("health",0)>0?health/spec.number("health",0):-1,state,stateEntered,count,hitTime,binding.key(),
                kind.equals("DungeonLift")?new SceneLiftState(z,faction,excluded,dropPoint):null,status.state(),affectedType,affectedTime));}
    }
    private final SceneContent content;private final CollisionMap terrain;
    private final SceneCollision staticCollision;
    private final Map<Integer,Entity> entities=new LinkedHashMap<>();
    private int nextId=1_000_000_000,lastStamp;
    private record Link(Entity entity,int stamp,boolean ghost) { }
    private final ArrayDeque<Link> links=new ArrayDeque<>();
    public record PlayerHit(int actor,Object damage,int timestamp,int source) { }
    private java.util.function.Consumer<PlayerHit> playerDamage=hit->{};
    public void playerDamage(java.util.function.BiConsumer<Integer,Object> damage){Objects.requireNonNull(damage);playerDamage=hit->damage.accept(hit.actor(),hit.damage());}
    public void playerHits(java.util.function.Consumer<PlayerHit> damage){playerDamage=Objects.requireNonNull(damage);}
    private java.util.function.Consumer<SceneCombatEvent> feedback=event->{};
    public void feedback(java.util.function.Consumer<SceneCombatEvent> value){feedback=Objects.requireNonNull(value);}
    private java.util.function.BiPredicate<Integer,Integer> playerHeal=(actor,amount)->false;
    public void playerHeal(java.util.function.BiPredicate<Integer,Integer> heal){playerHeal=Objects.requireNonNull(heal);}
    private java.util.function.BiConsumer<ActorState,FireCatalog.Action> attack=(actor,action)->{};
    public void attacks(java.util.function.BiConsumer<ActorState,FireCatalog.Action> fire){attack=Objects.requireNonNull(fire);}
    private List<ActorState> players=List.of();
    public SceneEntities(SceneContent content,CollisionMap terrain){
        this(content,terrain,SceneCollision.empty());
    }
    public SceneEntities(SceneContent content,CollisionMap terrain,SceneCollision staticCollision){
        this.content=content;this.terrain=terrain;this.staticCollision=Objects.requireNonNull(staticCollision);int stamp=SceneClock.now();
        for(var binding:content.entries())spawn(binding,binding.spec(),null,stamp);
    }
    private Entity spawn(SceneContent.Entry binding,SceneContent.Spec spec,Entity pad,int stamp){
        String nativeType=spec.text("native-type","");String kind=nativeType.substring(nativeType.lastIndexOf('.')+1);
        if(!SUPPORTED.contains(kind))throw new IllegalArgumentException("Unsupported prepared scene actor: "+kind);
        var entity=new Entity(nextId++,stamp,binding,spec,pad,content);entities.put(entity.id,entity);
        if(kind.equals("Monster")&&spec.integer("activate-time",0)>0)entity.activity=3;
        if(kind.equals("Generator")&&!spec.child().fields().isEmpty()){
            generate(entity,stamp);
        }
        return entity;
    }
    private void generate(Entity pad,int stamp){
        var damage=pad.spec.model("generated-damage");
        boolean blocked=false;
        if(damage!=null&&damage.arguments().get("value") instanceof FireCatalog.DataValue){
            for(var player:players)if(pad.spec.contains(pad.x,pad.y,pad.rotation,player.x(),player.y(),ScenePortals.PAWN_RADIUS)){
                playerDamage.accept(new PlayerHit(player.id(),damage.arguments().get("value"),stamp,0));blocked=true;
            }
        }
        if(blocked){pad.nextGeneration=stamp+pad.spec.integer("delay",0);return;}
        spawn(pad.binding,pad.spec.child(),pad,stamp);pad.count++;pad.nextGeneration=0;pad.flags&=~2;
    }
    public List<ActorState> actors(){return entities.values().stream().map(Entity::snapshot).toList();}
    public ActorState actor(int id){var e=entities.get(id);return e==null||e.destroyed!=Integer.MAX_VALUE?null:e.snapshot();}
    public boolean carry(int id,ActorState player,int stamp){
        var e=entities.get(id);if(e==null||!e.kind.equals("DungeonLift")||e.destroyed!=Integer.MAX_VALUE||(e.flags&6)!=0)return false;
        e.carrier=e.owner=player.id();e.ownerOid=player.clientOid();e.dropPoint=null;e.flags|=4;return true;
    }
    public int vial(ActorState player,SceneContent.Spec item,int stamp){
        var fields=new TreeMap<>(item.section("lift.").fields());fields.put("destroy-on-land","true");
        var binding=new SceneContent.Entry("item:"+nextId,player.x(),player.y(),player.rotation(),new SceneContent.Spec(fields));
        var e=spawn(binding,binding.spec(),null,stamp);e.vial=item;
        if(!carry(e.id,player,stamp)){entities.remove(e.id);return 0;}return e.id;
    }
    public boolean canThrow(int id){var e=entities.get(id);return e!=null&&e.spec.flag("can-throw",false);}
    private Entity snapTarget(Entity lift,ActorState player){
        String tag=lift.spec.text("snap-tag","");if(tag.isBlank())return null;
        float ox=content.stats().getOrDefault("player.interaction-offset-x",.7f),oy=content.stats().getOrDefault("player.interaction-offset-y",0f);
        float c=(float)Math.cos(player.rotation()),s=(float)Math.sin(player.rotation());
        float x=player.x()+ox*c-oy*s,y=player.y()+ox*s+oy*c;
        return entities.values().stream().filter(e->e.destroyed==Integer.MAX_VALUE&&Arrays.asList(e.spec.text("tags","").split(",")).contains(tag)
                &&e.spec.contains(e.x,e.y,e.rotation,x,y,content.stats().getOrDefault("player.interaction-radius",.4f)))
            .min(Comparator.comparingDouble(e->Math.hypot(e.x-x,e.y-y))).orElse(null);
    }
    public boolean prepareDrop(int id,ActorState player){
        var e=entities.get(id);if(e==null||e.carrier!=player.id())return false;
        var target=snapTarget(e,player);e.dropPoint=target==null?null:new SceneLiftState.Point(target.x,target.y);return target!=null;
    }
    public void throwLift(int id,ActorState player,int stamp){
        var e=entities.get(id);if(e==null||e.carrier!=player.id())return;
        e.carrier=0;e.owner=player.id();e.ownerOid=player.clientOid();e.flags=e.flags&~4|2;e.faction=player.id();e.excluded=new int[0];
        e.x=player.x();e.y=player.y();e.rotation=e.direction=player.rotation();e.thrownAt=stamp;
        e.z=content.stats().getOrDefault("player.lift-height",0f);
    }
    public void dropLift(int id,ActorState player,int stamp){
        var e=entities.get(id);if(e==null||e.carrier!=player.id())return;
        e.carrier=0;e.flags&=~6;e.z=0;
        float distance=(ScenePortals.PAWN_RADIUS+e.spec.number("radius",0))*1.01f;
        var target=snapTarget(e,player);
        float x=target==null?player.x()+(float)Math.cos(player.rotation())*distance:target.x;
        float y=target==null?player.y()+(float)Math.sin(player.rotation())*distance:target.y;
        if(terrain.walkable(x,y)&&walkable(x,y,e.id)){e.x=x;e.y=y;}else {e.x=player.x();e.y=player.y();}
        e.dropPoint=new SceneLiftState.Point(e.x,e.y);
        e.rotation=e.direction=player.rotation();e.faction=player.id();e.excluded=new int[0];
        if(e.vial!=null)land(e,stamp);
    }
    public void releaseLifts(int player,int stamp){
        for(var e:List.copyOf(entities.values()))if(e.carrier==player){
            e.carrier=0;e.flags&=~6;if(e.vial!=null)e.destroyed=stamp;
        }
    }
    public List<Pickup> takePickups(int player){
        var result=new ArrayList<Pickup>();
        for(var e:entities.values())if(e.claim==player&&!e.claimTaken){e.claimTaken=true;result.add(new Pickup(e.id,e.spec.text("pickup-item",""),e.spec.flag("pickup-transient",false),e.spec.fields().containsKey("pickup-equip-slot")?e.spec.integer("pickup-equip-slot",0):null));}
        return List.copyOf(result);
    }
    public void completePickup(int player,int actor,boolean success){
        var e=entities.get(actor);if(e==null||e.claim!=player)return;
        e.claim=0;e.claimTaken=false;if(success)e.destroyed=SceneClock.now();else e.refusedPickups.add(player);
    }
    public void cancelClaims(int player){for(var e:entities.values()){if(e.claim==player){e.claim=0;e.claimTaken=false;}e.refusedPickups.remove(player);}}
    public int interactionDuration(int id){var e=entities.get(id);return e==null?670:e.spec.integer("interaction-duration",670);}
    public InteractionState interaction(float x,float y,float rotation){
        Entity nearest=null;float best=Float.POSITIVE_INFINITY;
        for(var e:entities.values())if((content.conversations().containsKey(e.config.name())||e.kind.equals("DungeonLift")&&(e.flags&6)==0)&&e.destroyed==Integer.MAX_VALUE){
            float ox=e.spec.number("interaction-offset-x",.7f),oy=e.spec.number("interaction-offset-y",0);
            float c=(float)Math.cos(rotation),s=(float)Math.sin(rotation);
            float distance=(float)Math.hypot(x+ox*c-oy*s-e.x,y+ox*s+oy*c-e.y);
            if(distance<=e.spec.number("interaction-radius",.4f)+e.spec.number("radius",0)+e.spec.number("interaction-expansion",0)&&distance<best){nearest=e;best=distance;}
        }
        if(nearest==null)return null;
        String description=nearest.spec.text("description","");
        return new InteractionState(nearest.id,description.isEmpty()?null:new StarterCatalog.Model(description,Map.of()),nearest.kind.equals("DungeonLift")?"Lift":nearest.spec.text("interaction-action","Interact"));
    }
    public void advance(int stamp,List<ActorState> players){
        this.players=List.copyOf(players);
        while(!links.isEmpty()&&links.peekFirst().stamp()<=stamp){var link=links.removeFirst();clearLinks(link.entity(),stamp,link.ghost());}
        float elapsed=lastStamp==0?0:Math.min(.25f,(stamp-lastStamp)/1000f);lastStamp=stamp;
        for(var e:List.copyOf(entities.values())){
            if(e.destroyed!=Integer.MAX_VALUE){
                // Monster destroyed time already includes the native death animation.
                // Retain one short publication grace, rather than counting it twice.
                if(stamp-e.destroyed>=100){
                    entities.remove(e.id);
                    if(e.pad!=null)e.pad.nextGeneration=stamp+e.pad.spec.integer("delay",0);
                }
                continue;
            }
            if(e.kind.equals("Generator")&&e.nextGeneration>0){
                e.flags=stamp+e.spec.integer("warning",0)>=e.nextGeneration?2:0;
                if(stamp>=e.nextGeneration){e.flags&=~2;generate(e,e.nextGeneration);}
            }
            if(e.kind.equals("Trap"))cycle(e,stamp);
            if(e.detonateAt>0&&stamp>=e.detonateAt){
                e.destroyed=e.detonateAt;
                String graph=e.spec.text("detonation-action","");if(!graph.isBlank())try{attack.accept(e.snapshot(),FireCatalog.actionJson(graph));}catch(java.io.IOException invalid){throw new IllegalStateException("Invalid block detonation",invalid);}
                continue;
            }
            if(e.kind.equals("Door")&&(e.flags&4)!=0&&stamp-e.stateEntered>e.spec.integer("door-speed",0))e.flags&=~4;
            if(e.kind.equals("Switch"))switches(e,stamp,players);
            if(e.spec.flag("heal-pad",false)&&stamp>=e.nextHeal){
                e.nextHeal=stamp+Math.round(e.spec.number("heal-interval",0)*1000);
                for(var player:players)if(e.spec.contains(e.x,e.y,e.rotation,player.x(),player.y(),ScenePortals.PAWN_RADIUS)
                    &&playerHeal.test(player.id(),e.spec.integer("heal",0))){
                    String graph=e.spec.text("heal-action","");if(!graph.isBlank())try{attack.accept(e.snapshot(),FireCatalog.actionJson(graph));}
                    catch(java.io.IOException invalid){throw new IllegalStateException("Invalid prepared heal action",invalid);}
                }
            }
            if(e.kind.equals("Trap")&&e.state==2&&e.spec.fields().containsKey("sensor")){
                Object sensor=e.spec.model("sensor").arguments().get("value");float c=(float)Math.cos(e.rotation),s=(float)Math.sin(e.rotation);
                for(var player:players){float dx=player.x()-e.x,dy=player.y()-e.y;
                    if(stamp>=e.hazardHits.getOrDefault(player.id(),0)&&SceneShape.intersects(sensor,dx*c+dy*s,-dx*s+dy*c,ScenePortals.PAWN_RADIUS)){
                        try{attack.accept(e.snapshot(),FireCatalog.actionJson(e.spec.text("hazard-action","")));}
                        catch(java.io.IOException invalid){throw new IllegalStateException("Invalid prepared trap action",invalid);}
                        e.hazardHits.put(player.id(),stamp+e.spec.integer("hazard-refractory",0));
                    }
                }
                e.hazardHits.keySet().removeIf(id->players.stream().noneMatch(p->p.id()==id));
            }
            if(e.kind.equals("Pickup")&&e.claim==0)collect(e,elapsed,players);
            if(e.kind.equals("Monster"))move(e,stamp,elapsed,players);
            if(e.kind.equals("Monster"))e.status.advance(stamp,(int)(elapsed*1000),(e.flags&2)!=0,amount->hurt(e,(float)amount,stamp),amount->e.health=Math.min(e.spec.number("health",0),e.health+(float)amount));
            if(e.kind.equals("DungeonLift"))lift(e,stamp,elapsed,players);
            if(e.activity==1&&stamp-e.activityStarted>=e.spec.integer("react-time",0)||e.activity==3&&stamp-e.activityStarted>=e.spec.integer("activate-time",0))e.activity=0;
        }
    }
    private void lift(Entity e,int stamp,float elapsed,List<ActorState> players){
        if(e.carrier!=0){for(var player:players)if(player.id()==e.carrier){e.x=player.x();e.y=player.y();e.rotation=player.rotation();return;}return;}
        if((e.flags&2)==0)return;
        float distance=e.speed*elapsed;boolean collided=false;
        int steps=Math.max(1,(int)Math.ceil(Math.abs(distance)/.1f));
        for(int step=0;step<steps;step++){
            float x=e.x+(float)Math.cos(e.direction)*distance/steps,y=e.y+(float)Math.sin(e.direction)*distance/steps;
            boolean clear=staticCollision.isEmpty()||e.shape==null?terrain.walkableSegment(e.x,e.y,x,y):
                staticCollision.clearSegment(e.spec.integer("thrown-collision-mask",0),e.shape,e.x,e.y,x,y,e.rotation);
            if(!clear||contact(x,y,e.rotation,e.id,e.shape,e.spec.integer("thrown-collision-mask",-1))){collided=true;break;}
            e.x=x;e.y=y;
        }
        if(stamp-e.thrownAt>=e.spec.integer("throw-time",0))e.z=Math.max(0,e.z-e.spec.number("fall-speed",0)*elapsed);
        if(collided||e.z<=0||stamp-e.thrownAt>=e.spec.integer("fuse",0))land(e,stamp);
    }
    private void land(Entity e,int stamp){
        e.flags&=~2;
        String graph=e.vial==null?e.spec.text("land-action",""):e.vial.text("use-action","");
        if(!graph.isBlank())try{attack.accept(e.snapshot(),FireCatalog.actionJson(graph));}catch(java.io.IOException invalid){throw new IllegalStateException("Invalid prepared lift action",invalid);}
        if(e.spec.flag("destroy-on-land",false))e.destroyed=stamp;
    }
    private void collect(Entity e,float elapsed,List<ActorState> players){
        if(e.spec.text("pickup-item","").isEmpty())return;
        for(var player:players){
            float dx=player.x()-e.x,dy=player.y()-e.y,distance=(float)Math.hypot(dx,dy);
            float reach=e.spec.number("radius",0)+ScenePortals.PAWN_RADIUS;
            if(distance>reach+e.spec.number("vacuum-expansion",0)){e.refusedPickups.remove(player.id());continue;}
            if(e.refusedPickups.contains(player.id()))continue;
            if(distance<=reach){e.claim=player.id();return;}
            float step=Math.min(distance,e.spec.number("vacuum-speed",0)*elapsed);
            float x=e.x+dx*step/distance,y=e.y+dy*step/distance;
            if(terrain.walkableSegment(e.x,e.y,x,y)){e.x=x;e.y=y;}
            return;
        }
    }
    private void move(Entity e,int stamp,float elapsed,List<ActorState> players){
        float distance=e.spec.number("acquire-distance",0);if(distance<=0)return;
        if(stamp>=e.nextAcquire){
            float nearest=distance;e.target=0;
            for(var player:players){float d=(float)Math.hypot(player.x()-e.x,player.y()-e.y);if(d<nearest){nearest=d;e.target=player.id();}}
            e.nextAcquire=stamp+e.spec.integer("acquisition-interval",0);
        }
        ActorState target=players.stream().filter(p->p.id()==e.target).findFirst().orElse(null);
        float nearest=target==null?Float.POSITIVE_INFINITY:(float)Math.hypot(target.x()-e.x,target.y()-e.y);
        e.flags=target==null?0:16;
        e.speed=target==null?e.spec.number("speed",0):e.spec.number("targeted-speed",e.spec.number("speed",0));
        int slot=e.spec.integer("attack",-1);String prefix="attack."+slot+".";
        if(e.activity>=4){
            for(String fire:e.spec.text(prefix+"fires","").split(",")){
                if(fire.isBlank()||e.fired.contains(fire))continue;
                String fp=prefix+"fire."+fire+".";
                int due=e.activityStarted+e.spec.integer(prefix+"land",0)+Math.round(e.spec.integer(fp+"delay",0)/e.spec.number(prefix+"fire-speed",1));
                if(stamp>=due){
                    try{attack.accept(e.snapshot(),FireCatalog.actionJson(e.spec.text(fp+"action","")));}
                    catch(java.io.IOException invalid){throw new IllegalStateException("Invalid prepared monster attack",invalid);}
                    e.fired.add(fire);
                }
            }
            if(stamp-e.activityStarted>=e.spec.integer(prefix+"clear",0))e.activity=0;
            return;
        }
        if(target!=null&&e.activity==0&&slot>=0&&stamp>=e.nextAttack&&nearest<=e.spec.number("attack-distance",0)){
            e.rotation=e.direction=(float)Math.atan2(target.y()-e.y,target.x()-e.x);
            e.activity=4+slot;e.activityStarted=stamp;e.nextAttack=stamp+e.spec.integer("attack-interval",0);e.fired.clear();return;
        }
        if(target==null||!e.status.state().modifiers().canMove()||e.activity!=0||nearest<=e.spec.number("radius",0)+ScenePortals.PAWN_RADIUS)return;
        e.rotation=e.direction=(float)Math.atan2(target.y()-e.y,target.x()-e.x);
        float step=Math.min(nearest,e.speed*e.status.state().modifiers().speed()*elapsed);
        float x=e.x+(float)Math.cos(e.direction)*step,y=e.y+(float)Math.sin(e.direction)*step;
        if(terrain.walkableSegment(e.x,e.y,x,y)&&walkable(x,y,e.id)){e.x=x;e.y=y;e.flags|=2;}
    }
    private void cycle(Entity e,int stamp){
        var cycle=content.cycles().getOrDefault(e.cycle,e.spec);
        float down=cycle.number("down",e.spec.number("down-time",0)),warn=cycle.number("warn",e.spec.number("warn-time",0)),up=cycle.number("up",e.spec.number("up-time",0));
        int period=Math.round((down+warn+up)*1000);
        int offset=period<=0?0:Math.floorMod(stamp-Math.round(cycle.number("offset",0)*1000),period);
        int state=e.cycle.equals("UP")?2:e.cycle.equals("DOWN")?0:offset<down*1000?0:offset<(down+warn)*1000?1:2;
        if(e.state!=state){e.state=state;e.stateEntered=stamp;}
        e.flags=state==2?e.flags|2:e.flags&~2;
    }
    private void switches(Entity e,int stamp,List<ActorState> players){
        String mode=e.spec.text("switch-mode","");
        if(mode.equals("pressure")){
            boolean pressed=players.stream().anyMatch(p->e.spec.contains(e.x,e.y,e.rotation,p.x(),p.y(),ScenePortals.PAWN_RADIUS))
                ||entities.values().stream().anyMatch(other->other.kind.equals("DungeonLift")&&Arrays.asList(other.spec.text("tags","").split(",")).contains(e.spec.text("pressure-tag",""))&&other.carrier==0&&other.destroyed==Integer.MAX_VALUE
                    &&e.spec.contains(e.x,e.y,e.rotation,other.x,other.y,other.spec.number("radius",0)));
            int state=pressed?2:0;
            if(state!=e.state)setSwitch(e,state,stamp);
        }else if(mode.equals("button")||mode.equals("damage-timed")){
            if(e.state!=0&&stamp>=e.nextSwitch)setSwitch(e,0,stamp);
            if(mode.equals("button")&&e.state==0&&stamp>=e.nextSwitch&&players.stream().anyMatch(p->e.spec.contains(e.x,e.y,e.rotation,p.x(),p.y(),ScenePortals.PAWN_RADIUS))){
                setSwitch(e,1,stamp);e.nextSwitch=stamp+e.spec.integer("switch-delay",0);
            }
        }
    }
    private void setSwitch(Entity e,int state,int stamp){
        e.state=state;e.stateEntered=stamp;
        signal(e.spec.text("state."+state+".tag",""),e.spec.text("state."+state+".signal",""),stamp);
    }
    private void signal(String tag,String name,int stamp){
        if(tag.isEmpty()||name.isEmpty())return;
        for(var target:entities.values())if(Arrays.asList(target.spec.text("tags","").split(",")).contains(tag)){
            if(target.kind.equals("Door")&&Set.of("open","close","toggle").contains(name)){
                boolean open=name.equals("open")||name.equals("toggle")&&(target.flags&2)==0;target.flags=(open?2:0)|4;target.stateEntered=stamp;
            }else if(target.kind.equals("Trap")){
                if(name.equals("RESET")){target.cycle=target.spec.text("cycle","");int split=target.cycle.lastIndexOf('|');if(split>=0)target.cycle=target.cycle.substring(split+1);}
                else if(name.equals("TOGGLE")){var cycle=content.cycles().get(target.cycle);if(cycle!=null)target.cycle=cycle.text("inverse",target.cycle);}
                else if(content.cycles().containsKey(name))target.cycle=name;
                cycle(target,stamp);
            }
        }
    }
    public boolean walkable(float x,float y,int ignore){
        for(var e:entities.values()){
            if(e.id==ignore||e.destroyed!=Integer.MAX_VALUE)continue;
            if(e.carrier!=0)continue;
            if(e.kind.equals("Door")&&(e.flags&6)==2)continue;
            int mask=Math.round(content.stats().getOrDefault("player.collision-mask",43f));
            if((e.spec.integer("collision-flags",0)&mask)!=0&&e.spec.contains(e.x,e.y,e.rotation,x,y,ScenePortals.PAWN_RADIUS))return false;
        }return true;
    }
    @Override public boolean contact(float x,float y,int owner){
        return contact(x,y,0,owner,null,-1);
    }
    @Override public boolean contact(float x,float y,float rotation,int owner,Object shape,int mask){
        var source=entities.get(owner);
        if(source!=null&&(source.kind.equals("Monster")||source.kind.equals("Trap"))&&(mask&4)!=0&&players.stream().anyMatch(p->shape==null?
            Math.hypot(p.x()-x,p.y()-y)<=ScenePortals.PAWN_RADIUS:SceneShape.intersects(shape,
                (p.x()-x)*(float)Math.cos(rotation)+(p.y()-y)*(float)Math.sin(rotation),
                -(p.x()-x)*(float)Math.sin(rotation)+(p.y()-y)*(float)Math.cos(rotation),ScenePortals.PAWN_RADIUS)))return true;
        for(var e:entities.values())if(e.id!=owner&&e.carrier==0&&e.destroyed==Integer.MAX_VALUE
            &&(e.spec.integer("collision-flags",0)&mask)!=0
            &&!(e.kind.equals("Door")&&(e.flags&2)!=0)
            &&(shape==null||e.shape==null?e.spec.contains(e.x,e.y,e.rotation,x,y,0):SceneCollision.overlaps(shape,x,y,rotation,e.shape,e.x,e.y,e.rotation,0)))return true;
        return false;
    }
    @Override public void damage(StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner){
        damage(region,x,y,rotation,stamp,owner,null);
    }
    @Override public void damage(StarterCatalog.Model region,float x,float y,float rotation,int stamp,int owner,Object sourceShape){
        Object damage=region.arguments().get("damage"),shape=region.arguments().get("region");
        var source=entities.get(owner);float expansion=SceneDamage.number(shape,"expansion",0);
        Object defined=region.arguments().getOrDefault("shape",SceneDamage.field(shape,"shape"));
        if(!(defined instanceof FireCatalog.DataValue)&&shape instanceof FireCatalog.DataValue value&&value.type().endsWith("$Default")){
            var model=source==null?null:source.spec.model("source-shape");
            if(sourceShape!=null)defined=sourceShape;
            else if(model!=null)defined=model.arguments().get("value");
            else expansion+=source==null?ScenePortals.PAWN_RADIUS:source.spec.number("radius",0);
        }
        {
            float c=(float)Math.cos(rotation),s=(float)Math.sin(rotation);
            for(var player:players){float dx=player.x()-x,dy=player.y()-y;
                boolean hit=defined instanceof FireCatalog.DataValue?SceneShape.intersects(defined,dx*c+dy*s,-dx*s+dy*c,ScenePortals.PAWN_RADIUS+expansion):Math.hypot(dx,dy)<=ScenePortals.PAWN_RADIUS+expansion;
                int sourceId=source==null?owner:source.kind.equals("Monster")?owner:source.kind.equals("DungeonLift")?source.owner:0;
                if(hit){
                    // One trap action can cover several occupants. Record each
                    // hit here so the next sensor cannot damage them again on
                    // the same tick or before the authored refractory deadline.
                    if(source!=null&&source.kind.equals("Trap")){
                        if(stamp<source.hazardHits.getOrDefault(player.id(),0))continue;
                        source.hazardHits.put(player.id(),stamp+source.spec.integer("hazard-refractory",0));
                    }
                    playerDamage.accept(new PlayerHit(player.id(),damage,stamp,sourceId));
                }
            }
        }
        for(var e:entities.values()){
            if(e.destroyed!=Integer.MAX_VALUE)continue;
            float dx=e.x-x,dy=e.y-y,c=(float)Math.cos(rotation),s=(float)Math.sin(rotation);
            boolean hit;
            if(defined instanceof FireCatalog.DataValue geometry){
                float tx=dx*c+dy*s,ty=-dx*s+dy*c,r=e.spec.number("radius",0);
                hit=e.shape==null?SceneShape.intersects(geometry,tx,ty,r+Math.max(0,expansion)):
                    SceneCollision.overlaps(geometry,x,y,rotation,e.shape,e.x,e.y,e.rotation,Math.max(0,expansion));
            }else hit=e.spec.contains(e.x,e.y,e.rotation,x,y,Math.max(0,expansion));
            if(!hit)continue;
            if(e.kind.equals("Switch")){
                String mode=e.spec.text("switch-mode","");
                if(mode.startsWith("damage-")&&stamp>=e.nextSwitch&&SceneDamage.amount(damage,content,e.spec)>0){
                    setSwitch(e,e.state==0?1:0,stamp);e.nextSwitch=stamp+e.spec.integer(mode.equals("damage-timed")?"switch-delay":"switch-cooldown",0);
                }
                continue;
            }
            if(e.spec.integer("damage-mask",0)==0)continue;
            Object accepted=SceneDamage.prune(damage,e.spec.integer("damage-mask",0));
            float amount=SceneDamage.amount(accepted,content,e.spec);
            if(e.kind.equals("Monster")){
                e.status.damaged(amount,stamp);e.status.apply(accepted,stamp);if(amount>0){
                    int dealt=(int)Math.ceil(amount);hurt(e,dealt,stamp);
                    int sourceId=source!=null&&source.kind.equals("DungeonLift")?source.owner:owner;
                    float fraction=dealt/e.spec.number("health",1);
                    feedback.accept(new SceneCombatEvent(0,stamp,e.id,dealt,sourceId,fraction>.4f?"NORMAL_LARGE":fraction>.2f?"NORMAL_MEDIUM":"NORMAL_SMALL"));
                }
            }else if(amount>0&&e.kind.startsWith("Block"))blockHit(e,stamp);
        }
    }
    private void blockHit(Entity e,int stamp){
        e.hitTime=stamp;
        switch(e.spec.text("kind","")){
            case "TimedBlock" -> {if(e.state==0){e.state=1;e.stateEntered=stamp;e.detonateAt=stamp+e.spec.integer("block-fuse",0);}}
            case "StarlinkBlock" -> clearLinks(e,stamp,false);
            case "GhostlinkBlock" -> clearLinks(e,stamp,true);
            case "StoneBlock" -> {if(e.state<e.spec.integer("block-hits",1)-1){e.state++;e.stateEntered=stamp;}else e.destroyed=stamp;}
            default -> {if(e.spec.text("damage-action","").equals("destroy"))e.destroyed=stamp;}
        }
    }
    private void clearLinks(Entity source,int stamp,boolean ghost){
        if(source.destroyed!=Integer.MAX_VALUE)return;source.destroyed=stamp;if(ghost)source.flags|=2;
        float c=(float)Math.cos(source.rotation),s=(float)Math.sin(source.rotation);
        for(var target:entities.values())if(target!=source&&target.destroyed==Integer.MAX_VALUE&&target.kind.startsWith("Block")
            &&(ghost||target.spec.text("kind","").equals("StarlinkBlock")&&target.spec.integer("block-color",0)==source.spec.integer("block-color",0))){
            if(target.spec.contains(target.x,target.y,target.rotation,source.x+c,source.y+s,0)
                ||target.spec.contains(target.x,target.y,target.rotation,source.x-c,source.y-s,0)
                ||target.spec.contains(target.x,target.y,target.rotation,source.x-s,source.y+c,0)
                ||target.spec.contains(target.x,target.y,target.rotation,source.x+s,source.y-c,0))links.addLast(new Link(target,stamp+50,ghost));
        }
    }
    private void hurt(Entity e,float amount,int stamp){
        e.health=Math.max(0,e.health-amount);e.hitTime=e.affectedTime=stamp;e.affectedType=0;
        if(e.health==0){e.activity=2;e.activityStarted=stamp;e.destroyed=stamp+e.spec.integer("death-time",100);e.flags=0;}
        else if(amount>=e.spec.number("min-react-percent",0)*e.spec.number("health",0)){e.activity=1;e.activityStarted=stamp;}
    }
}
