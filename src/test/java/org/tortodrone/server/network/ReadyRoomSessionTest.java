package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.storage.Database;
import org.tortodrone.world.*;
import static org.tortodrone.support.TestWire.check;

public final class ReadyRoomSessionTest {
    private static final Map<String,Short> RECEIVERS=Map.of(SceneRegistry.ZONE_RECEIVER,(short)0);
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-ready-room-");
        try(var accounts=new AccountStore(home,false,ItemCatalog.starter())){
            accounts.create("RoomUser","room-password".toCharArray());
            accounts.create("RoomPeer","peer-password".toCharArray());
            var characters=new CharacterStore(new Database(home,false),1,"RoomUser");
            var peerCharacters=new CharacterStore(new Database(home,false),2,"RoomPeer");
            var peerKnight=peerCharacters.create(new Knight(0,"Peer-Knight","Gear/Helm/Spiral Sallet (Blue)",
                "Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            var knight=characters.create(new Knight(0,"Room-Knight","Gear/Helm/Spiral Sallet (Blue)",
                "Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            var registry=SceneRegistry.system();var entry=registry.entry();int initial=entry.simulation().playerCount();
            for(int reconnect=0;reconnect<2;reconnect++){
                var socket=new RecordingSocket();
                try(var output=new SessionOutput(socket);var world=new WorldSession(socket,characters,output,new SessionCodec(),1900,1,m->{},registry)){
                    refuse(()->world.select("Not-Owned",RECEIVERS,true));
                    world.select(knight.name(),RECEIVERS,true);
                    check(world.selected()&&WorldSession.active(1,knight.id()),"selection retains authenticated ownership");
                    check(entry.simulation().playerCount()==initial,"selection creates no world pawn");
                    check(!world.ownsScene(entry.oid())&&!world.ownsScene(0),"Ready Room owns no server place");
                    var competingSocket=new RecordingSocket();
                    try(var competingOutput=new SessionOutput(competingSocket);var competing=new WorldSession(competingSocket,characters,competingOutput,new SessionCodec(),1901,1,m->{},registry)){
                        refuse(()->competing.select(knight.name(),RECEIVERS,true));
                        check(!competing.selected(),"duplicate selection cannot acquire the Ready Room owner");
                    }
                    check(WorldSession.active(1,knight.id()),"closing refused selection preserves owner");
                    var peerSocket=new RecordingSocket();
                    try(var peerOutput=new SessionOutput(peerSocket);var peer=new WorldSession(peerSocket,peerCharacters,peerOutput,new SessionCodec(),1904,1,m->{},registry)){
                        peer.select(peerKnight.name(),RECEIVERS,true);move(peer,ReadyRoom.ZONE,ReadyRoom.SCENE);
                        check(WorldSession.active(1,knight.id())&&WorldSession.active(2,peerKnight.id()),"simultaneous knights own independent Ready Room sessions");
                        check(entry.simulation().playerCount()==initial,"simultaneous Ready Room players create no shared scene pawns");
                    }
                    refuse(()->world.subscribe((short)2));refuse(()->world.subscribeInteriorParty((short)3));
                    move(world,ReadyRoom.ZONE,ReadyRoom.SCENE);
                    move(world,ReadyRoom.ZONE,5);move(world,ReadyRoom.ZONE+1,ReadyRoom.SCENE);
                    move(world,entry.map().zone(),entry.map().id());
                    check(entry.simulation().playerCount()==initial,"invalid routes and unsolicited world entry do not attach");
                    refuse(()->world.invocation(new SessionDecoder.Invocation(3,1,11,List.of(1))));
                    world.invocation(new SessionDecoder.Invocation(3,1,11,List.of()));
                    world.invocation(new SessionDecoder.Invocation(3,1,11,List.of()));
                    move(world,entry.map().zone(),entry.map().id());
                    check(world.ownsScene(entry.oid())&&entry.simulation().playerCount()==initial+1,"return attaches exactly one owned pawn");
                    world.subscribe((short)4);world.entered();
                    move(world,ReadyRoom.ZONE,ReadyRoom.SCENE);
                    check(entry.simulation().playerCount()==initial&&!world.ownsScene(entry.oid()),"Ready Room travel removes the pawn and scene authorization");
                    refuse(()->world.entered());refuse(()->world.subscribe((short)5));
                }
                check(!WorldSession.active(1,knight.id())&&entry.simulation().playerCount()==initial,"disconnect releases owner and scene state");
            }
            var otherTown=registry.catalog().scenes().stream().filter(map->map.town()&&map.id()!=entry.map().id()).findFirst().orElseThrow();
            // A successful enteredPlace, not a client-selected scene number, records the return.
            var travelSocket=new RecordingSocket();
            try(var output=new SessionOutput(travelSocket);var world=new WorldSession(travelSocket,characters,output,new SessionCodec(),1902,1,m->{},registry)){
                world.select(knight.name(),RECEIVERS);
                move(world,otherTown.zone(),otherTown.id());world.subscribe((short)7);world.entered();
                check(characters.lastTownScene(knight.id(),1)==otherTown.id(),"acknowledged town arrival saves destination");
            }
            var reconnectSocket=new RecordingSocket();
            try(var output=new SessionOutput(reconnectSocket);var world=new WorldSession(reconnectSocket,characters,output,new SessionCodec(),1903,1,m->{},registry)){
                world.select(knight.name(),RECEIVERS,true);
                world.invocation(new SessionDecoder.Invocation(3,1,11,List.of()));
                move(world,otherTown.zone(),otherTown.id());
                world.subscribe((short)8);world.entered();
                check(characters.lastTownScene(knight.id(),1)==otherTown.id(),"reconnect returns to the saved non-entry town");
            }
        }finally{try(var files=Files.walk(home)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
        System.out.println("PASS: Ready Room selection, authorization, invalid routes, return, scene cleanup and reconnect");
    }
    private static void move(WorldSession world,int zone,int scene)throws IOException{
        world.invocation(new SessionDecoder.Invocation(3,5,1,List.of(zone,scene,1,
            new SessionDecoder.Listener("com.threerings.whirled.zone.data.ZoneMarshaller$ZoneMoveMarshaller",(short)6))));
    }
    private static void refuse(Operation operation)throws Exception{try{operation.run();throw new AssertionError("Expected refusal");}catch(IOException|CharacterStore.Refused expected){}}
    private interface Operation{void run()throws Exception;}
    private static final class RecordingSocket extends Socket{
        private final OutputStream output=new ByteArrayOutputStream();
        @Override public OutputStream getOutputStream(){return output;}
    }
}
