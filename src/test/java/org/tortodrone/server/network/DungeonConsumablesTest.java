package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.resources.Json;
import org.tortodrone.storage.Database;
import org.tortodrone.world.*;
import static org.tortodrone.support.TestWire.check;

/** Capacity refusal keeps a shared pickup claimable, without allocating an extra hidden stack. */
public final class DungeonConsumablesTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-consumables-");
        try {
            var db=new Database(home,true);int account=db.create("Collector",new Passwords.Protection(new byte[16],new byte[32]),false);
            var characters=new CharacterStore(db,account,"Collector");var knight=characters.create(new Knight(0,"Collector-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0));
            var registry=new SceneRegistry(SceneRegistry.system().catalog());
            var socket=new Socket(){public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}};
            try(var output=new SessionOutput(socket);var session=new WorldSession(socket,characters,output,new SessionCodec(),1201,1,m->{},registry)){
                synchronized(session){
                    session.select(knight.name(),Map.of());var member=(SceneSimulation.Membership)field(session,"membership");var simulation=registry.entry().simulation();
                    synchronized(simulation){
                        var entered=SceneSimulation.Membership.class.getDeclaredField("entered");entered.setAccessible(true);entered.setBoolean(member,true);
                        var player=simulation.snapshot().players().getFirst();var entities=(SceneEntities)field(simulation,"entities");
                        @SuppressWarnings("unchecked") var items=(Map<Long,WorldProfile.Item>)field(session,"temporaryItems");
                        long[] slots=(long[])field(session,"quickbar");
                        for(int i=0;i<3;i++)pickup(session,entities,player,"Pickup/Vial/Fire Vial");
                        check(items.size()==1&&items.values().iterator().next().count()==3,"three uses in one carried stack");
                        int refused=pickup(session,entities,player,"Pickup/Vial/Fire Vial");
                        check(items.size()==1&&items.values().iterator().next().count()==3&&entities.actor(refused)!=null,"fourth use remains in the scene");
                        for(String name:List.of("Freeze","Shock","Poison"))pickup(session,entities,player,"Pickup/Vial/"+name+" Vial");
                        check(items.size()==4&&Arrays.stream(slots).allMatch(id->id<0)&&Arrays.stream(slots).distinct().count()==4,"four carried types fill four distinct quickbar slots");
                        int fifth=pickup(session,entities,player,"Pickup/Vial/Sleep Vial");
                        check(items.size()==4&&entities.actor(fifth)!=null,"fifth type remains available to another knight");
                        var peer=new ActorState(333,1333,"Peer",0,player.created(),player.x(),player.y(),0,0,0,player.config());
                        entities.playerActive(id->true);entities.advance(SceneClock.now(),List.of(peer));var claims=entities.takePickups(peer.id());
                        check(claims.stream().anyMatch(p->p.entityId()==fifth),"another knight can claim the refused pickup");
                        for(var claim:claims)entities.completePickup(peer.id(),claim.entityId(),true);
                        check(entities.actor(fifth)==null,"successful peer claim removes it for both observers");
                        long id=slots[0];items.remove(id);var publish=WorldSession.class.getDeclaredMethod("publishQuickbar");publish.setAccessible(true);publish.invoke(session);
                        check(slots[0]==0,"consumed or discarded stack frees its quickbar slot");
                        pickup(session,entities,player,"Pickup/Vial/Sleep Vial");check(items.size()==4&&slots[0]!=0,"new type reuses the freed slot");
                        check(characters.worldProfile(knight.name(),1).items().stream().noneMatch(item->item.id()<0),"temporary consumables never enter SQLite");
                    }
                }
            }
            System.out.println("PASS: three-use stacks, four carried types/slots, capacity refusal, shared peer claim, slot reuse and temporary ownership");
        }finally{org.tortodrone.support.ContentFixture.delete(home);}
    }
    private static Object field(Object value,String name)throws Exception{var f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    private static int pickup(WorldSession session,SceneEntities entities,ActorState player,String item)throws Exception{
        var fields=Map.of("native-type","com.threerings.projectx.dungeon.data.actor.Pickup","config","Item/Pickups/Vial/Fire Vial","radius",".2","pickup-item",item,"pickup-transient","true");
        entities.spawnPickup(new StarterCatalog.Model("Pickup",Map.of("fields",Json.stringify(fields))),player.x(),player.y(),0,SceneClock.now());
        int id=entities.actors().getLast().id();entities.advance(SceneClock.now(),List.of(player));
        var collect=WorldSession.class.getDeclaredMethod("collectPickups");collect.setAccessible(true);collect.invoke(session);return id;
    }
}
