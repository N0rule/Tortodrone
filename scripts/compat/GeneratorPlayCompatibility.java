package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.storage.Database;
import org.tortodrone.world.*;
import static org.tortodrone.server.network.OpeningGateCompatibility.*;

/** Headless input driver against original 337 and production gameplay services.
 * Starts at an explicit post-Camp fixture; cannot establish the fresh-knight route. */
public final class GeneratorPlayCompatibility {
    public static void main(String[] args)throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("GAME BASE RAW337 RAW218");
        var content=ContentManager.load(Path.of(args[1]));var base=content.createScenes();var maps=new TreeMap<Integer,SceneCatalog.MapScene>();
        for(var map:base.catalog().scenes())maps.put(map.id(),map);maps.put(337,SceneCatalog.loadTemplate(337,Path.of(args[2])));maps.put(218,SceneCatalog.loadTemplate(218,Path.of(args[3])));
        var registry=new SceneRegistry(SceneCatalog.of(maps,base.catalog().entry().id()));registry.town(content.town());registry.weapons(content.weapons());registry.equipment(content.equipment());registry.fires(content.fires());registry.petFires(content.petFires());SceneRegistry.install(registry);
        check(registry.openingGate(OpeningMission.ANCIENT_GENERATOR)!=null,"current pinned mission preparation required");
        var folder=Files.createTempDirectory("tortodrone-generator-play-");
        try{
            var db=new Database(folder,true);int account=db.create("GeneratorDriver",new Passwords.Protection(new byte[16],new byte[32]),false);
            var characters=new CharacterStore(db,account,"GeneratorDriver",content.items());var knight=characters.createForOpening(knight("Generator-Driver"),1);
            var initial=checkpoint(characters,knight,registry); // Explicit setup, never a gameplay-completion substitute.
            try(var socket=new SinkSocket();var output=new SessionOutput(socket);var world=new WorldSession(socket,characters,output,new SessionCodec(content.items()),1901,1,System.out::println,registry)){
                world.select(knight.name(),Map.of(SceneRegistry.ZONE_RECEIVER,(short)0),true);
                world.townInvocation(request(2,"ancient_generator",0,true,false,false,101));enter(world,registry,initial.worldId());
                var driver=new Driver(world,initial.worldId(),characters);driver.play();
                System.out.println("INPUT MODE: "+(driver.steered?"interactive original-area waypoints; not an unattended acceptance":"unattended original-area waypoints"));
                var after=characters.worldProfile(knight.name(),1);
                check(driver.scene().map().id()==218&&after.progress().openingStage()==2&&after.progress().rankPair().equals("1-3"),"actual mission play must reach original powered Camp with its saved rank");
                check(characters.lastTownScene(knight.id(),1)==218&&characters.openingMissions(registry.town()).load(account,knight.id(),1).containsKey(OpeningMission.ANCIENT_GENERATOR),"durable original mission receipt and destination");
                world.townInvocation(request(2,"ancient_generator",0,true,false,false,102));check(characters.worldProfile(knight.name(),1).equals(after),"duplicate mission start grants no additional reward");
            }
            check(registry.statuses().stream().noneMatch(s->s.templateId()==337),"finished floor releases all actors and subscriptions");
            try(var socket=new SinkSocket();var output=new SessionOutput(socket);var reconnect=new WorldSession(socket,characters,output,new SessionCodec(content.items()),1902,1,System.out::println,registry)){
                reconnect.select(knight.name(),Map.of(SceneRegistry.ZONE_RECEIVER,(short)0),true);
                check((boolean)field(reconnect,"readyRoom")&&((SceneRegistry.Instance)field(reconnect,"scene")).map().id()==218,"reconnect stages the saved powered Camp through Ready Room");
            }
            System.out.println("PASS: original Generator gameplay through production movement/combat/interactions/elevator/report/travel, durable receipt and reconnect. POST-CAMP FIXTURE; no fresh-knight or graphical acceptance claimed.");
        }finally{try(var paths=Files.walk(folder)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    private static final class Driver {
        private record Target(int id,float x,float y,float rotation,String kind,SceneContent.Spec spec,ActorState actor) { }
        final WorldSession world;final int id;final CharacterStore characters;int request=1000,ticks,cursor,fightId,revives;long logged,planned,encounterSearch;
        String routeKey="",progressKey="";List<ScenePathing.Point> route=List.of();
        float progressX,progressY;double healthTotal;long progressAt;int entityCount,stateTotal;
        final StringBuilder command=new StringBuilder();String via;boolean steered,discardCommand;
        SceneSimulation.Snapshot combatSnapshot;
        Driver(WorldSession world,int id,CharacterStore characters){this.world=world;this.id=id;this.characters=characters;}
        SceneRegistry.Instance scene()throws Exception{return (SceneRegistry.Instance)field(world,"scene");}
        ActorState pawn(SceneSimulation.Snapshot snapshot){return snapshot.players().stream().filter(a->a.id()==id).findFirst().orElseThrow();}
        void play()throws Exception {
            // Authored entry IDs, resolved from the verified original content at runtime.
            var goals=List.of("160","2388","3263","3317","3427","604","2493","2995","3283","3183","2345","3532","2930");int goal=0;long until=System.nanoTime()+750_000_000_000L;
            while(System.nanoTime()<until){
                if(travel())continue;var place=scene();if(place.map().id()==218)return;
                commands(place);
                if(place.simulation().failure()!=null)throw new AssertionError("Original scene simulation failed",place.simulation().failure());
                var snapshot=place.simulation().snapshot();var own=pawn(snapshot);
                if(snapshot.health(id).current()==0){
                    if((snapshot.health(id).reviveFlags()&8)!=0){Thread.sleep(50);continue;}
                    revive(place,own);continue;
                }
                for(var hit:snapshot.combatEventsSince(combatSnapshot))if(hit.actor()==id&&hit.source()!=id&&snapshot.actors().stream().anyMatch(a->a.id()==hit.source()&&hostile(place,a)))fightId=hit.source();
                combatSnapshot=snapshot;
                if(dialogue(place))continue;
                if(field(world,"floorReport")!=null){
                    long dialog=(long)field(world,"missionDialogCreated");if(dialog!=0)world.townInvocation(new SessionDecoder.Invocation(place.oid(),6,1,List.of(dialog)));
                    world.interiorPartyInvocation(new SessionDecoder.Invocation(SceneRegistry.system().parties().view((PartyRegistry.Member)field(world,"partyMember")).oid(),SessionCodec.LEVEL_PARTY_SERVICE,3,List.of(false)));Thread.sleep(50);continue;
                }
                String key=via==null?goals.get(Math.min(goal,goals.size()-1)):via;var target=target(place,snapshot,key);
                progress(snapshot,own,key);
                if(done(own,target)){
                    if(via!=null){System.out.println("VIA "+via+" physically reached at "+own.x()+","+own.y());via=null;continue;}
                    if(goal<goals.size()-1){System.out.println("GOAL "+key+" completed at "+own.x()+","+own.y());goal++;continue;}
                }
                var carried=own.sceneKnight().lift()==0?null:place.simulation().interactionSource(own.sceneKnight().lift()).specification();
                boolean carryingKey=carried!=null&&carried.text("actionable-signal","").equals("gold_unlock");
                ActorState enemy=carryingKey?null:enemy(place,snapshot,own);
                var fight=carryingKey?null:snapshot.actors().stream().filter(a->a.id()==fightId&&hostile(place,a)).findFirst().orElse(null);
                if(fight==null)fightId=0;
                ActorState covered=null;
                if(!carryingKey&&fight!=null&&enemy!=null&&enemy.entity().kind().startsWith("Block")){
                    covered=enemy;enemy=Math.hypot(fight.x()-own.x(),fight.y()-own.y())<6.5&&visible(place,snapshot,own.x(),own.y(),fight)?fight:null;
                }
                boolean damageSwitch=target.spec().text("switch-mode","").startsWith("damage-");
                if(enemy!=null&&enemy.entity().kind().startsWith("Block")&&damageSwitch&&Math.hypot(target.x()-own.x(),target.y()-own.y())<6&&visible(place,snapshot,own.x(),own.y(),target.actor()))enemy=null;
                float face=(float)Math.atan2(target.y()-own.y(),target.x()-own.x());int flags=8;
                // Proto bullets travel 7.5 units after their native .85-unit
                // muzzle offset. Aim from safely inside that range, with sight
                // of the lever; proximity alone is not a firing position.
                // Leave room for an aligned half-unit planning cell outside a
                // resting lift's own collider. The server still decides whether
                // the forward native pickup prompt is actually in reach.
                float margin=target.kind().equals("Door")?1f:damageSwitch?6f:target.kind().equals("DungeonLift")?.8f:.2f;
                var opponent=enemy!=null&&enemy.entity().kind().equals("Monster")?enemy:fight;
                var destination=opponent==null?target:target(place,opponent);
                String navigation=opponent==null?key:"fight:"+opponent.id();
                if(opponent!=null)margin=4;
                final float reach=margin;
                if(enemy!=null){face=(float)Math.atan2(enemy.y()-own.y(),enemy.x()-own.x());if(ticks%4==0)flags|=4;
                    if(enemy.entity().kind().equals("Monster")&&Math.hypot(enemy.x()-own.x(),enemy.y()-own.y())<3){float away=(float)Math.atan2(own.y()-enemy.y(),own.x()-enemy.x());input(place,face,away,flags|1);Thread.sleep(50);ticks++;continue;}
                }else if(own.interaction()!=null&&(own.interaction().actorId()==target.id()||own.interaction().action().equals("UnlockGold"))){if(ticks%4==0)flags|=4;}
                else if(damageSwitch&&Math.hypot(target.x()-own.x(),target.y()-own.y())<6.5&&visible(place,snapshot,own.x(),own.y(),target.actor())){if(ticks%4==0)flags|=4;}
                float heading=face;
                // Approach a future gated room along a partial live path. This
                // reaches its authored entry triggers without ignoring props,
                // doors or enemies that make the final destination unreachable.
                if(!routeKey.equals(navigation)||System.nanoTime()-planned>2_000_000_000L){
                    routeKey=navigation;cursor=0;
                    route=ScenePathing.region(own.x(),own.y(),(x,y)->{
                        float remaining=(float)Math.hypot(destination.x()-x,destination.y()-y)-reach;
                        return remaining>0?remaining:(damageSwitch||fightId!=0)&&!visible(place,snapshot,x,y,destination.actor())?.01f:0;
                    },(x,y,tx,ty)->clear(place.simulation(),x,y,tx,ty));
                    if(route.isEmpty()&&fight==null)route=ScenePathing.partialRegion(own.x(),own.y(),(x,y)->Math.max(0,(float)Math.hypot(destination.x()-x,destination.y()-y)-reach),(x,y,tx,ty)->clear(place.simulation(),x,y,tx,ty));
                    planned=System.nanoTime();
                }
                while(cursor<route.size()&&Math.hypot(route.get(cursor).x()-own.x(),route.get(cursor).y()-own.y())<.18){
                    // A nearby seed can still be a required corner. Skipping it
                    // would cut a blocked diagonal from the actual pawn pose.
                    if(cursor+1<route.size()&&!clear(place.simulation(),own.x(),own.y(),route.get(cursor+1).x(),route.get(cursor+1).y()))break;
                    cursor++;
                }
                if(enemy==null&&covered!=null&&cursor>=route.size()){enemy=covered;face=(float)Math.atan2(enemy.y()-own.y(),enemy.x()-own.x());if(ticks%4==0)flags|=4;}
                // Finish a visible fight before leaving its room. Running past
                // every shooter strands the driver at an authentic wave gate.
                if((enemy==null||enemy.entity().kind().startsWith("Block")||opponent!=null&&Math.hypot(opponent.x()-own.x(),opponent.y()-own.y())>4.5)&&cursor<route.size()){var point=route.get(cursor);if(clear(place.simulation(),own.x(),own.y(),point.x(),point.y())){heading=(float)Math.atan2(point.y()-own.y(),point.x()-own.x());flags|=1;}}
                if(enemy==null&&(flags&1)==0&&fightId==0&&System.nanoTime()-encounterSearch>5_000_000_000L&&(route.isEmpty()||cursor<route.size()&&!clear(place.simulation(),own.x(),own.y(),route.get(cursor).x(),route.get(cursor).y()))){
                    // A native wave door can require a side-room encounter. Find
                    // a reachable firing position for a living enemy instead of
                    // waiting against the door or fabricating its completion.
                    encounterSearch=System.nanoTime();
                    for(var candidate:snapshot.actors().stream().filter(a->hostile(place,a)&&Math.hypot(a.x()-own.x(),a.y()-own.y())<30).sorted(Comparator.comparingDouble(a->Math.hypot(a.x()-own.x(),a.y()-own.y()))).limit(8).toList()){
                        var approach=ScenePathing.region(own.x(),own.y(),(x,y)->{float d=(float)Math.hypot(candidate.x()-x,candidate.y()-y)-5;return d>0?d:visible(place,snapshot,x,y,candidate)?0:.01f;},(x,y,tx,ty)->clear(place.simulation(),x,y,tx,ty));
                        if(!approach.isEmpty()){fightId=candidate.id();routeKey="";System.out.println("SIDE ENCOUNTER "+candidate.config().name()+" at="+candidate.x()+","+candidate.y());break;}
                    }
                }
                if(System.nanoTime()-logged>5_000_000_000L){logged=System.nanoTime();System.out.println("PLAY goal="+key+" pawn="+own.x()+","+own.y()+" hp="+snapshot.health(id).current()+" activity="+own.activity()+" ticks="+place.simulation().tickCount()+" flags="+own.flags()+" can-move="+own.sceneKnight().status().modifiers().canMove()+" weapon="+(own.combat()==null||own.combat().weapon()==null?"none":own.combat().weapon().config())+" path="+(route.size()-cursor)+" next="+(cursor<route.size()?route.get(cursor):"none")+" lift="+own.sceneKnight().lift()+" enemy="+(enemy==null?"none":enemy.config().name()+" at="+enemy.x()+","+enemy.y()+" health="+enemy.entity().health()));
                    if(cursor<route.size()&&!clear(place.simulation(),own.x(),own.y(),route.get(cursor).x(),route.get(cursor).y()))for(var obstacle:snapshot.actors())if(obstacle.entity()!=null&&Math.hypot(obstacle.x()-own.x(),obstacle.y()-own.y())<3)System.out.println("NEAR "+obstacle.entity().key()+" "+obstacle.config().name()+" at="+obstacle.x()+","+obstacle.y()+" flags="+obstacle.flags());
                }
                // A gun activity can lock facing until rearm. Release attack
                // while turning to a new target rather than chaining forever
                // along the previous target's heading.
                if(Math.abs(Math.atan2(Math.sin(face-own.rotation()),Math.cos(face-own.rotation())))>.1)flags&=~4;
                input(place,face,heading,flags);Thread.sleep(50);ticks++;
            }
            throw new AssertionError("Generator input driver timed out; full gameplay not established");
        }
        void revive(SceneRegistry.Instance place,ActorState own)throws Exception {
            check(revives<2,"driver exhausted its bounded native revive attempts at "+own.x()+","+own.y());
            int before=sparks(characters.worldProfile(own.name(),1));check(before>0,"driver died without an owned Spark; gameplay not completed");
            int party=SceneRegistry.system().parties().view((PartyRegistry.Member)field(world,"partyMember")).oid();
            world.interiorPartyInvocation(new SessionDecoder.Invocation(party,20,4,List.of(own.name(),-1,new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)++request))));
            check(sparks(characters.worldProfile(own.name(),1))==before-1&&place.simulation().snapshot().health(id).current()>0,"native self-revive must commit exactly one owned Spark and restore health through the production service");
            revives++;fightId=0;routeKey="";System.out.println("NATIVE REVIVE "+revives+": one persisted Spark consumed");Thread.sleep(50);
        }
        int sparks(WorldProfile profile){return profile.items().stream().filter(i->i.config().equals("Rarity/Spark of Life")).mapToInt(WorldProfile.Item::count).sum();}
        void commands(SceneRegistry.Instance place)throws IOException {
            for(int budget=0;budget<128&&System.in.available()>0;budget++){
                int next=System.in.read();if(next<0)return;if(next=='\r')continue;
                if(next!='\n'){if(command.length()<64&&!discardCommand)command.append((char)next);else discardCommand=true;continue;}
                String line=command.toString().trim();command.setLength(0);
                if(discardCommand){discardCommand=false;System.out.println("COMMAND REFUSED: length");continue;}
                if(!line.matches("via [0-9]{1,9}")){System.out.println("COMMAND REFUSED: only 'via <original area entry id>' is supported");continue;}
                String key=line.substring(4);
                if(place.map().content().entries().stream().noneMatch(e->e.key().equals(key)&&e.spec().flag("scene-sensor",false))){System.out.println("COMMAND REFUSED: missing original area");continue;}
                via=key;steered=true;routeKey="";fightId=0;
                System.out.println("STEERING: walk through original area "+via+" using production input; no actor/progression mutation");
            }
        }
        boolean done(ActorState own,Target target){
            if(target.actor()==null)return Math.hypot(target.x()-own.x(),target.y()-own.y())<.25;
            var kind=target.kind();if(kind.equals("Door"))return (target.actor().flags()&2)!=0;
            if(kind.equals("DungeonLift"))return own.sceneKnight().lift()==target.id();
            if(kind.equals("Switch"))return target.actor().entity().state()!=0&&(!target.spec().text("switch-mode","").equals("party")||Math.hypot(target.x()-own.x(),target.y()-own.y())<.5);return false;
        }
        Target target(SceneRegistry.Instance place,ActorState actor){return new Target(actor.id(),actor.x(),actor.y(),actor.rotation(),actor.entity().kind(),place.simulation().interactionSource(actor.id()).specification(),actor);}
        Target target(SceneRegistry.Instance place,SceneSimulation.Snapshot snapshot,String key){
            var actor=entry(snapshot,key);if(actor!=null)return target(place,actor);
            var sensor=place.map().content().entries().stream().filter(e->e.key().equals(key)&&e.spec().flag("scene-sensor",false)).findFirst().orElseThrow(()->new AssertionError("Required original entry is missing: "+key));
            return new Target(0,sensor.x(),sensor.y(),sensor.rotation(),"Area",sensor.spec(),null);
        }
        void progress(SceneSimulation.Snapshot snapshot,ActorState own,String key){
            var actors=snapshot.actors().stream().filter(a->a.entity()!=null&&a.entity().destroyed()==Integer.MAX_VALUE).toList();
            double health=actors.stream().filter(a->a.entity().kind().equals("Monster")).mapToDouble(a->a.entity().health()).sum();
            int states=actors.stream().mapToInt(a->a.entity().state()).sum();long now=System.nanoTime();
            if(!progressKey.equals(key)||Math.hypot(own.x()-progressX,own.y()-progressY)>.25||health!=healthTotal||actors.size()!=entityCount||states!=stateTotal){
                progressKey=key;progressX=own.x();progressY=own.y();healthTotal=health;entityCount=actors.size();stateTotal=states;progressAt=now;
            }
            check(now-progressAt<90_000_000_000L,"No movement, combat or scene-control progress for 90 seconds: goal="+key+", position="+own.x()+","+own.y()+", facing="+own.rotation()+"; full gameplay not established");
        }
        ActorState enemy(SceneRegistry.Instance place,SceneSimulation.Snapshot snapshot,ActorState own){
            return snapshot.actors().stream().filter(a->a.entity()!=null&&(a.entity().kind().equals("Monster")&&a.entity().health()>0||a.entity().kind().startsWith("Block"))&&a.entity().destroyed()==Integer.MAX_VALUE&&Math.hypot(a.x()-own.x(),a.y()-own.y())<6.5&&visible(place,snapshot,own.x(),own.y(),a))
                .filter(a->{var spec=place.simulation().interactionSource(a.id()).specification();return spec!=null&&(a.entity().kind().startsWith("Block")?breakable(spec):hostile(place,a));})
                .min(Comparator.comparingDouble(a->Math.hypot(a.x()-own.x(),a.y()-own.y())+(a.entity().kind().startsWith("Block")?10:0))).orElse(null);
        }
        boolean breakable(SceneContent.Spec spec){return spec.text("damage-action","").equals("destroy")||(spec.integer("damage-mask",0)&2)!=0&&Set.of("TimedBlock","StarlinkBlock","GhostlinkBlock","StoneBlock").contains(spec.text("kind",""));}
        boolean hostile(SceneRegistry.Instance place,ActorState actor){
            if(actor.entity()==null||!actor.entity().kind().equals("Monster")||actor.entity().health()<=0||actor.entity().destroyed()!=Integer.MAX_VALUE)return false;
            var spec=place.simulation().interactionSource(actor.id()).specification();if(spec==null||(spec.integer("damage-mask",0)&2)==0)return false;
            var ai=SceneTargeting.read(spec);return ai==null||ai.monsterMask()==0||ai.includePlayers();
        }
        boolean visible(SceneRegistry.Instance place,SceneSimulation.Snapshot snapshot,float x,float y,ActorState target){
            var point=new FireCatalog.DataValue("com.threerings.tudey.shape.config.ShapeConfig$Point",Map.of());
            if(!place.map().staticCollision().clearSegment(137,point,x,y,target.x(),target.y(),0))return false;
            int steps=Math.max(1,(int)Math.ceil(Math.hypot(target.x()-x,target.y()-y)/.1));
            for(var obstacle:snapshot.actors())if(obstacle.id()!=target.id()&&obstacle.entity()!=null&&obstacle.entity().destroyed()==Integer.MAX_VALUE&&!(obstacle.entity().kind().equals("Door")&&(obstacle.flags()&6)==2)){
                var spec=place.simulation().interactionSource(obstacle.id()).specification();if(spec==null||(spec.integer("collision-flags",0)&137)==0)continue;
                for(int i=0;i<steps;i++)if(spec.contains(obstacle.x(),obstacle.y(),obstacle.rotation(),x+(target.x()-x)*i/steps,y+(target.y()-y)*i/steps,0))return false;
            }
            return true;
        }
        boolean dialogue(SceneRegistry.Instance place)throws Exception {
            var dialog=(SceneConversation)field(world,"conversation");if(dialog==null)return false;String node=(String)field(world,"conversationNode");
            if(dialog.nodes().get(node).responses().isEmpty())return false;
            System.out.println("DIALOGUE "+dialog.actor()+" / "+node+" / 0");
            world.conversationInvocation(new SessionDecoder.Invocation(place.oid(),SceneConversation.SERVICE,1,List.of(0,new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)++request))));Thread.sleep(50);return true;
        }
        boolean travel()throws Exception {
            var move=field(world,"portalMove");if(move==null)return false;
            var method=move.getClass().getDeclaredMethod("destination");method.setAccessible(true);var target=(SceneRegistry.Instance)method.invoke(move);
            System.out.println("NATIVE TRAVEL -> "+target.map().id());
            world.invocation(new SessionDecoder.Invocation(3,5,1,List.of(target.map().zone(),target.map().id(),1,new SessionDecoder.Listener("com.threerings.whirled.zone.data.ZoneMarshaller$ZoneMoveMarshaller",(short)++request))));
            if(!target.map().town())world.subscribeInteriorParty((short)++request);world.subscribe((short)++request);world.entered();return true;
        }
        void input(SceneRegistry.Instance place,float facing,float heading,int flags)throws IOException {int t=SceneClock.now();world.input(new SessionDecoder.Input(place.oid(),0,t,List.of(new SessionDecoder.InputFrame(t,facing,heading,flags,t))));}
        ActorState entry(SceneSimulation.Snapshot snapshot,String key){return snapshot.actors().stream().filter(a->a.entity()!=null&&a.entity().key().equals(key)).findFirst().orElse(null);}
        boolean clear(SceneSimulation simulation,float x,float y,float tx,float ty){int n=Math.max(1,(int)Math.ceil(Math.hypot(tx-x,ty-y)/.1));for(int i=1;i<=n;i++)if(!simulation.placement(x+(tx-x)*i/n,y+(ty-y)*i/n))return false;return true;}
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static final class SinkSocket extends Socket {@Override public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}}
}
