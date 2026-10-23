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

/** A lost downstream connection after commit cannot leave an awarded actor claimable. */
public final class PickupNotificationTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-pickup-notification-");
        try{
            var db=new Database(home,true);int account=db.create("Collector",new Passwords.Protection(new byte[16],new byte[32]),false);
            var characters=new CharacterStore(db,account,"Collector");
            var knight=characters.create(new Knight(0,"Collector-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0));
            var registry=new SceneRegistry(SceneRegistry.system().catalog());
            var socket=new Socket(){public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}};
            try(var output=new SessionOutput(socket);var session=new WorldSession(socket,characters,output,new SessionCodec(),1201,1,m->{},registry)){
                synchronized(session){
                    session.select(knight.name(),Map.of());
                    var membershipField=WorldSession.class.getDeclaredField("membership");membershipField.setAccessible(true);
                    var member=(SceneSimulation.Membership)membershipField.get(session);var simulation=registry.entry().simulation();
                    var entitiesField=SceneSimulation.class.getDeclaredField("entities");entitiesField.setAccessible(true);var entities=(SceneEntities)entitiesField.get(simulation);
                    int before=db.worldProfile(account,knight.id(),1).crowns();
                    synchronized(simulation){
                        // Keep the WorldSession unsubscribed: only this explicit collection calls the reward path.
                        var entered=SceneSimulation.Membership.class.getDeclaredField("entered");entered.setAccessible(true);entered.setBoolean(member,true);
                        var player=simulation.snapshot().players().getFirst();
                        var fields=Map.of("native-type","com.threerings.projectx.dungeon.data.actor.Pickup","config","Item/Consumable/Crowns/Copper Crown","radius",".2","pickup-item","Instant/Crown/Copper Crown");
                        check(entities.spawnPickup(new StarterCatalog.Model("Pickup",Map.of("fields",Json.stringify(fields))),player.x(),player.y(),0,SceneClock.now()-1000),"native shared coin spawned past its activation delay");
                        entities.advance(SceneClock.now(),List.of(player));
                        int coin=entities.actors().stream().filter(a->a.entity().kind().equals("Pickup")&&a.config().name().equals("Item/Consumable/Crowns/Copper Crown")).findFirst().orElseThrow().id();
                        output.close(); // Inventory publication will throw, after the SQLite reward has committed.
                        var collect=WorldSession.class.getDeclaredMethod("collectPickups");collect.setAccessible(true);collect.invoke(session);
                        check(db.worldProfile(account,knight.id(),1).crowns()==before+1,"reward commits despite notification failure");
                        check(entities.actor(coin)==null,"committed coin disappears for every observer even when inventory delivery fails");
                        collect.invoke(session);
                        check(db.worldProfile(account,knight.id(),1).crowns()==before+1,"failed delivery cannot replay the reward");
                    }
                }
            }
            System.out.println("PASS: persistent pickup commit, failed downstream notification, shared removal and duplicate safety");
        }finally{try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
}
