package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.storage.*;
import org.tortodrone.world.*;
import static org.tortodrone.support.TestWire.check;
import org.tortodrone.core.WireTypes;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.WorldProfile;

/** Stock arsenal services (forge, equip, loadouts) stay reachable from the client-local Ready Room. */
public final class ReadyRoomArsenalSessionTest {
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("tortodrone-ready-room-arsenal-");
        try{
            var content=ContentManager.load(Path.of("."));var scenes=org.tortodrone.world.SceneRegistry.create(content);var db=new Database(root,false);
            try(var alice=ready(db,content,scenes,"Alice")){
                var profile=alice.chars.worldProfile("Alice",1);
                var sword=profile.items().stream().filter(i->i.config().equals("Weapon/Sword/Proto Sword")).findFirst().orElseThrow();
                var shield=profile.items().stream().filter(i->i.config().equals("Gear/Shield/Proto Shield")).findFirst().orElseThrow();
                check(sword.equippedSlot()!=null&&shield.equippedSlot()!=null,"the staged knight carries the stock proto gear");
                // Heat the sword to full and add crystals exactly like the native forge window expects.
                db.itemModifiers("Alice","Alice",profile.accountId(),profile.worldId(),profile.revision(),sword.id(),new ItemModifiers(0,1f,List.of()),alice.chars.catalog(),content.town());
                db.grantItems("Alice","Alice",profile.accountId(),-1,alice.chars.catalog().require("Rarity/Fire Crystal/Cracked"),5,-1,-1);
                // The weapon forge from the Ready Room: native result, level up, crystals consumed.
                forge(alice,sword.id(),3,0,(short)21);
                response(alice,(short)21,(byte)1,"m.forge_new_level|%item:m.proto_sword|~2");
                var forged=alice.chars.worldProfile("Alice",1);
                check(forged.items().stream().anyMatch(i->i.id()==sword.id()&&i.level()==1&&i.heatProgress()==0),"Ready Room forge commits its level before publishing the callback");
                check(forged.items().stream().filter(i->i.config().equals("Rarity/Fire Crystal/Cracked")).mapToInt(WorldProfile.Item::count).sum()==2,"Ready Room forge consumes its native fire crystals");
                // A stale repeat keeps the native cost-update failure without changing state.
                forge(alice,sword.id(),3,0,(short)22);
                response(alice,(short)22,(byte)0,"m.costupdate");
                // Insufficient crystals at a full heat bar: native key, no level change.
                var reheated=alice.chars.worldProfile("Alice",1);
                db.itemModifiers("Alice","Alice",reheated.accountId(),reheated.worldId(),reheated.revision(),sword.id(),new ItemModifiers(1,1f,List.of()),alice.chars.catalog(),content.town());
                forge(alice,sword.id(),3,1,(short)23);
                response(alice,(short)23,(byte)0,"m.insufficient_crystals");
                var spared=alice.chars.worldProfile("Alice",1);
                check(spared.items().stream().anyMatch(i->i.id()==sword.id()&&i.level()==1),"the refused forge leaves the level untouched");
                check(spared.items().stream().filter(i->i.config().equals("Rarity/Fire Crystal/Cracked")).mapToInt(WorldProfile.Item::count).sum()==2,"the refused forge keeps the fire crystals");
                // Unheated gear keeps the native forge lock.
                var settled=alice.chars.worldProfile("Alice",1);
                db.itemModifiers("Alice","Alice",settled.accountId(),settled.worldId(),settled.revision(),sword.id(),new ItemModifiers(1,0.5f,List.of()),alice.chars.catalog(),content.town());
                forge(alice,sword.id(),2,1,(short)24);
                response(alice,(short)24,(byte)0,"m.forge_locked");
                // Another knight's item id never forges.
                forge(alice,shield.id()*1000,3,2,(short)25);
                response(alice,(short)25,(byte)0,"m.internal_error");
                check(!alice.socket.written.stream().anyMatch(f->f.contains("Enter a scene first")),"arsenal operations from the Ready Room never hit the scene gate");
                // Equipment changes: the stock unequip (moveItem to inventory) and arsenal equip.
                alice.world.invocation(new SessionDecoder.Invocation(SessionCodec.INVOCATION_OID,SessionCodec.ITEM_SERVICE,9,List.of(shield.id(),new org.tortodrone.core.wire.EnumValue(WireTypes.ITEM_LOCATION,0),-1,confirm((short)31))));
                response(alice,(short)31,(byte)1,null);
                check(alice.chars.worldProfile("Alice",1).equipment()[2]==0,"Ready Room unequip clears the equipped shield");
                alice.world.invocation(new SessionDecoder.Invocation(SessionCodec.INVOCATION_OID,SessionCodec.ITEM_SERVICE,3,List.of(shield.id(),2,confirm((short)32))));
                response(alice,(short)32,(byte)1,null);
                check(alice.chars.worldProfile("Alice",1).equipment()[2]==shield.id(),"Ready Room arsenal equip restores the shield");
                // Named loadouts: save, apply and remove the staging kit.
                alice.world.invocation(new SessionDecoder.Invocation(SessionCodec.INVOCATION_OID,SessionCodec.ITEM_SERVICE,10,List.of("Room set",confirm((short)33))));
                response(alice,(short)33,(byte)1,null);
                check(alice.chars.worldProfile("Alice",1).town().loadouts().stream().anyMatch(l->l.name().equals("Room set")),"Ready Room can save a named loadout");
                alice.world.invocation(new SessionDecoder.Invocation(SessionCodec.INVOCATION_OID,SessionCodec.ITEM_SERVICE,9,List.of(shield.id(),new org.tortodrone.core.wire.EnumValue(WireTypes.ITEM_LOCATION,0),-1,confirm((short)34))));
                response(alice,(short)34,(byte)1,null);
                alice.world.invocation(new SessionDecoder.Invocation(SessionCodec.INVOCATION_OID,SessionCodec.ITEM_SERVICE,4,List.of("Room set",confirm((short)35))));
                response(alice,(short)35,(byte)1,null);
                check(alice.chars.worldProfile("Alice",1).equipment()[2]==shield.id(),"Ready Room can apply a saved loadout");
                alice.world.invocation(new SessionDecoder.Invocation(SessionCodec.INVOCATION_OID,SessionCodec.ITEM_SERVICE,4,List.of("No such set",confirm((short)36))));
                response(alice,(short)36,(byte)0,"No saved loadout with that name");
                alice.world.invocation(new SessionDecoder.Invocation(SessionCodec.INVOCATION_OID,SessionCodec.ITEM_SERVICE,2,List.of("Room set",confirm((short)37))));
                response(alice,(short)37,(byte)1,null);
                check(alice.chars.worldProfile("Alice",1).town().loadouts().isEmpty(),"Ready Room can remove a saved loadout");
                // Station-bound inventory operations stay scene-gated in the Ready Room.
                alice.world.townInvocation(new SessionDecoder.Invocation(SessionCodec.INVOCATION_OID,SessionCodec.ITEM_SERVICE,1,List.of(shield.id(),confirm((short)38))));
                response(alice,(short)38,(byte)0,"Select a knight first");
            }
            // Stock parity for a forge call with no selected knight: the native internal error key.
            db.create("Bob",new Passwords.Protection(new byte[16],new byte[32]),false);
            var strangerSocket=new RecordingSocket();
            try(var output=new SessionOutput(strangerSocket)){
                var characters=new CharacterStore(db,2,"Bob",content.items());
                try(var stranger=new WorldSession(strangerSocket,characters,output,new SessionCodec(content.items()),1399,1,m->{},scenes)){
                    var strangerSession=new Person(stranger,strangerSocket,output,characters);
                    forge(strangerSession,1,3,0,(short)41);
                    response(strangerSession,(short)41,(byte)0,"m.internal_error");
                }
            }
            System.out.println("PASS: Ready Room forge success and native failures, equip/unequip, loadouts, trash gate and unselected guard");
        }finally{org.tortodrone.support.ContentFixture.delete(root);}
    }
    private static void forge(Person person,long item,int crystals,int level,short request)throws IOException{
        check(person.world.townInvocation(new SessionDecoder.Invocation(SessionCodec.INVOCATION_OID,SessionCodec.ITEM_SERVICE,8,List.of(item,crystals,level,new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ResultMarshaller",request)))),"stock forge request reaches the Town item handler");
    }
    private static SessionDecoder.Listener confirm(short request){return new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",request);}
    private static void response(Person person,short requestId,byte method,String reason)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end){
            byte[] frame=person.socket.frames.poll(100,TimeUnit.MILLISECONDS);
            if(frame==null)continue;
            var in=new DataInputStream(new ByteArrayInputStream(frame));
            check(in.readInt()==frame.length,"complete downstream frame");
            if(!"com.threerings.presents.net.EventNotification".equals(person.names.type(in)))continue;
            in.readShort();
            if(!"com.threerings.presents.dobj.InvocationResponseEvent".equals(person.names.type(in)))continue;
            in.readInt();
            check(in.readShort()==requestId,"arsenal callback correlation");
            check(in.readByte()==method,"arsenal response method");
            if(in.readBoolean()){
                in.readInt();person.names.type(in);
                String text=in.readUTF();
                check(reason!=null&&text.equals(method==0&&!reason.startsWith("m.")?"~"+reason:reason),"arsenal response reason: "+text+" expected "+reason);
            }else check(reason==null,"successful arsenal operation reports no reason");
            return;
        }
        throw new AssertionError("Arsenal response timed out");
    }
    private static final class Person implements AutoCloseable{
        final WorldSession world;final RecordingSocket socket;final SessionOutput output;final CharacterStore chars;
        final Frames names=new Frames();
        Person(WorldSession world,RecordingSocket socket,SessionOutput output,CharacterStore chars){this.world=world;this.socket=socket;this.output=output;this.chars=chars;}
        public void close(){world.close();output.close();}
    }
    private static Person ready(Database db,org.tortodrone.resources.ContentRegistry content,SceneRegistry scenes,String name)throws Exception {
        int account=db.create(name,new Passwords.Protection(new byte[16],new byte[32]),false);
        var chars=new CharacterStore(db,account,name,content.items());
        chars.create(new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
        var socket=new RecordingSocket();var output=new SessionOutput(socket);
        var world=new WorldSession(socket,chars,output,new SessionCodec(content.items()),1300+account,1,m->{},scenes);
        world.select(name,Map.of(SceneRegistry.ZONE_RECEIVER,(short)0),true);
        return new Person(world,socket,output,chars);
    }
    /** Narya class codes: a negative code introduces a name, a positive code repeats it. */
    private static final class Frames {
        private final Map<Integer,String> names=new HashMap<>();
        String type(DataInputStream in)throws IOException{
            int code=in.readShort();
            if(code>=0)return names.get(code);
            String name=in.readUTF();names.put(-code,name);return name;
        }
    }
    private static final class RecordingSocket extends Socket {
        final BlockingQueue<byte[]> frames=new LinkedBlockingQueue<>();
        final List<String> written=new CopyOnWriteArrayList<>();
        public OutputStream getOutputStream(){return new OutputStream(){public void write(int value){throw new AssertionError();}public void write(byte[] bytes){frames.add(bytes.clone());written.add(new String(bytes,java.nio.charset.StandardCharsets.ISO_8859_1));}};}
    }
}
