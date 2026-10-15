package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.storage.Database;
import org.tortodrone.world.TownSquare;
import org.tortodrone.support.TestWire;
import static org.tortodrone.support.TestWire.check;

/** Stock item/sprite admin tools, server authorization, live pet equipment and persistence. */
public final class AdminServiceTest {
    public static void main(String[] args) throws Exception {
        TestWire.check(SessionCodec.SERVICES.get(SessionCodec.ADMIN_SERVICE - 1)
            .equals("com.threerings.projectx.admin.data.ProjectXAdminMarshaller"),
            "stock admin marshaller is advertised in the bootstrap service list");
        Path home = Files.createTempDirectory("tortodrone-admin-service-");
        try (var accounts = new AccountStore(home,false,org.tortodrone.character.ItemCatalog.starter())) {
            var database = new Database(home, false);
            accounts.create("Alice", "alice-password".toCharArray());
            accounts.create("Master", "master-password".toCharArray(), true);
            var players = new CharacterStore(database, 1, "Alice");
            var staff = new CharacterStore(database, 2, "Master");
            var player = players.create(new Knight(0, "Player-Knight", "Gear/Helm/Spiral Sallet (Blue)",
                "Gear/Armor/Spiral Culet (Blue)", null, null, "Blue", 1, 2, 0, 0));
            var knight = staff.create(new Knight(0, "Staff-Knight", "Gear/Helm/Spiral Sallet (Blue)",
                "Gear/Armor/Spiral Culet (Blue)", null, null, "Blue", 1, 2, 0, 0));
            var receiver = Map.of(TownSquare.ZONE_RECEIVER, (short) 0);

            // An ordinary player's session cannot reach any admin operation.
            Socket playerSocket = new RecordingSocket();
            var playerFrames = new Frames();
            try (var out = new SessionOutput(playerSocket);
                 var world = new WorldSession(playerSocket, players, out, new SessionCodec(), 1001, 1, message -> {})) {
                world.select(player.name(), receiver);
                world.admin(invoke(SessionCodec.INVOCATION_OID, "Gear/Shield/Proto Shield", 3, false, 0, (short) 5));
                response(playerFrames, playerSocket, (short) 5, (byte) 0, "Game Master permission required");
                check(players.worldProfile(player.name(), 1).items().stream().allMatch(i -> i.starter()),
                    "no admin item reaches an unprivileged account");
                for(int method:new int[]{8,44,45,46}){
                    Object[] values=switch(method){case 8->new Object[]{new SessionDecoder.ConfigReference("Drakon",Map.of())};case 44->new Object[]{99};case 45->new Object[]{0,0,3};default->new Object[]{0,1};};
                    world.admin(spriteInvoke(method,(short)20,values));
                    response(playerFrames,playerSocket,(short)20,(byte)0,"Game Master permission required");
                }
                check(players.worldProfile(player.name(),1).town().sprites().isEmpty(),"ordinary players cannot create or edit sprites with GM calls");
            }

            // The authorized administrator creates a real, persistent item with the stock tool.
            Socket staffSocket = new RecordingSocket();
            var staffFrames = new Frames();
            var scenes=new org.tortodrone.world.SceneRegistry(org.tortodrone.world.SceneRegistry.system().catalog());
            scenes.town(org.tortodrone.town.TownCatalog.load(Path.of("content/packs/vanilla/town/catalog.json")));
            try (var out = new SessionOutput(staffSocket);
                 var world = new WorldSession(staffSocket, staff, out, new SessionCodec(), 1002, 1, message -> {},
                     scenes, () -> true)) {
                world.select(knight.name(), receiver);
                world.subscribe((short) 1);
                world.entered();
                world.admin(invoke(SessionCodec.INVOCATION_OID, "Gear/Shield/Proto Shield", 3, false, 0, (short) 6));
                response(staffFrames, staffSocket, (short) 6, (byte) 1, null);
                var granted = staff.worldProfile(knight.name(), 1).items().stream()
                    .filter(i -> !i.starter() && i.config().equals("Gear/Shield/Proto Shield")).toList();
                check(granted.size() == 3 && granted.stream().allMatch(i -> i.count() == 1 && i.equippedSlot() == null),
                    "ItemCreator grants the requested quantity unequipped into the caller's bag");

                // Invalid, oversized and unsupported modifier requests are refused, never faked.
                world.admin(invoke(SessionCodec.INVOCATION_OID, "Gear/Shield/Nothing Like This", 1, false, 0, (short) 7));
                response(staffFrames, staffSocket, (short) 7, (byte) 0, "Unknown item; use !items or items to find a catalogue ID or exact config reference");
                world.admin(invoke(SessionCodec.INVOCATION_OID, "Gear/Shield/Proto Shield", 1000, false, 0, (short) 8));
                response(staffFrames, staffSocket, (short) 8, (byte) 0, "Quantity must be between 1 and 20");
                world.admin(invoke(SessionCodec.INVOCATION_OID, "Gear/Shield/Proto Shield", 1, false, 30, (short) 9));
                response(staffFrames, staffSocket, (short) 9, (byte) 0, "Item expiry is not supported yet");
                world.admin(invoke(SessionCodec.INVOCATION_OID, "Gear/Shield/Proto Shield", 1, true, 0, (short) 10));
                response(staffFrames, staffSocket, (short) 10, (byte) 0, "Locked items are not supported for this catalogue entry");
                world.admin(new SessionDecoder.Invocation(SessionCodec.INVOCATION_OID, SessionCodec.ADMIN_SERVICE, 47,
                    List.of("Gear/Shield/Proto Shield", new SessionDecoder.Listener(
                        "com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller", (short) 11))));
                response(staffFrames, staffSocket, (short) 11, (byte) 0, "This admin tool is not available yet");
                check(staff.worldProfile(knight.name(), 1).items().stream().filter(i -> !i.starter()).count() == 3,
                    "refused admin operations grant nothing");
                spriteCommands(world,staffFrames,staffSocket,staff,knight,accounts,database,scenes);
            }
            // A second stock session after a reconnect still owns the granted item.
            Socket reconnect = new RecordingSocket();
            try (var out = new SessionOutput(reconnect);
                 var world = new WorldSession(reconnect, staff, out, new SessionCodec(), 1003, 1, message -> {})) {
                world.select(knight.name(), receiver);
                check(world.selected(), "administrator reconnects to the saved knight");
            }
            var reopened = new Database(home, false);
            var reloaded = new CharacterStore(reopened, 2, "Master").worldProfile(knight.name(), 1);
            check(reloaded.items().stream().filter(i -> i.config().equals("Gear/Shield/Proto Shield") && !i.starter())
                .count() == 3, "created item persists in the database across sessions");
            var savedSprite=reloaded.town().sprites().getFirst();
            check(savedSprite.level()==99&&savedSprite.powers().get(8)==3&&savedSprite.ultimates().get(2)==2,
                "GM sprite level, power and ultimate persist across sessions");
            System.out.println("PASS: stock admin service, item/sprite grants, sprite equip/level/power/ultimate, authorization, rollback, live equipment and persistence");
        } finally {
            try (var paths = Files.walk(home)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void spriteCommands(WorldSession world,Frames frames,Socket socket,CharacterStore staff,Knight knight,
            AccountStore accounts,Database database,org.tortodrone.world.SceneRegistry scenes)throws Exception{
        world.admin(spriteInvoke(44,(short)30,99));
        response(frames,socket,(short)30,(byte)0,"Equip a battle sprite first; /sprite_list shows owned sprite IDs");
        world.admin(spriteInvoke(8,(short)31,new SessionDecoder.ConfigReference("Drakon",Map.of())));
        response(frames,socket,(short)31,(byte)1,null);
        var initial=staff.worldProfile(knight.name(),1);int first=initial.town().equippedSprite();
        check(initial.town().sprites().size()==1&&first==initial.town().sprites().getFirst().id(),"Sprite Creator grants and equips the first pet");
        world.admin(spriteInvoke(45,(short)32,2,2,3));response(frames,socket,(short)32,(byte)1,null);
        world.admin(spriteInvoke(46,(short)33,2,2));response(frames,socket,(short)33,(byte)1,null);
        var edited=staff.worldProfile(knight.name(),1).town().sprites().getFirst();
        check(edited.level()==0&&edited.powers().get(8)==3&&edited.ultimates().get(2)==2,"GM setters bypass point and skill unlock requirements at level 1");
        world.admin(spriteInvoke(44,(short)34,99));response(frames,socket,(short)34,(byte)1,null);
        var baseline=staff.worldProfile(knight.name(),1);
        for(Object[] invalid:new Object[][]{{44,-1},{44,100},{45,3,0,1},{45,0,3,1},{45,0,0,4},{46,0,3},{8,new SessionDecoder.ConfigReference("Not a sprite",Map.of())}}){
            world.admin(spriteInvoke((Integer)invalid[0],(short)35,Arrays.copyOfRange(invalid,1,invalid.length)));
            String reason=switch((Integer)invalid[0]){case 44->"Sprite level must be between 1 and 100";case 45->"Sprite skill and power must be 1-3; value must be 0-3";case 46->"Sprite skill must be 1-3; ultimate must be 0 (none), 1 (A) or 2 (B)";default->"Unknown sprite configuration";};
            response(frames,socket,(short)35,(byte)0,reason);
            check(staff.worldProfile(knight.name(),1).equals(baseline),"invalid sprite admin write rolls back without a revision change");
        }
        world.admin(spriteInvoke(8,(short)36,new SessionDecoder.ConfigReference("Maskeraith",Map.of())));
        response(frames,socket,(short)36,(byte)1,null);
        var two=staff.worldProfile(knight.name(),1);int second=two.town().sprites().get(1).id();
        check(two.town().equippedSprite()==first,"creating another sprite retains the equipped one");
        var confirm=new SessionDecoder.Listener(TestWire.INV+"ConfirmMarshaller",(short)37);
        check(world.townInvocation(new SessionDecoder.Invocation(SessionCodec.INVOCATION_OID,14,4,List.of(second,confirm))),"native /sprite_equip reaches the pet service");
        response(frames,socket,(short)37,(byte)1,null);
        var actor=scenes.entry().simulation().snapshot().players().stream().filter(a->a.id()==two.worldId()).findFirst().orElseThrow();
        check(actor.sceneKnight().sprite().id()==second,"sprite equip immediately updates the live knight");
        world.admin(spriteInvoke(44,(short)38,49));response(frames,socket,(short)38,(byte)1,null);
        check(staff.worldProfile(knight.name(),1).town().sprites().get(1).level()==49,"GM setters address the newly equipped sprite");
        check(world.townInvocation(new SessionDecoder.Invocation(SessionCodec.INVOCATION_OID,14,4,List.of(first,confirm))),"owned sprite can be re-equipped");
        response(frames,socket,(short)37,(byte)1,null);
        baseline=staff.worldProfile(knight.name(),1);
        accounts.setGameMaster("Master",false);
        // The role source deliberately remains true: the database must independently reject revocation.
        world.admin(spriteInvoke(44,(short)39,0));response(frames,socket,(short)39,(byte)0,"Game Master permission required");
        check(staff.worldProfile(knight.name(),1).equals(baseline),"revoked GM cannot edit a sprite using stale session privileges");
        accounts.setGameMaster("Master",true);
        try{staff.town(scenes.town()).adminSprite(2,knight.id(),999,44,List.of(0));throw new AssertionError("Stale authentication accepted");}catch(IOException expected){}
        check(database.worldProfile(2,knight.id(),1).equals(baseline),"stale authentication leaves all sprite state unchanged");
    }

    private static SessionDecoder.Invocation spriteInvoke(int method,short id,Object... values){
        var args=new ArrayList<>(Arrays.asList(values));args.add(new SessionDecoder.Listener(TestWire.INV+"ConfirmMarshaller",id));
        return new SessionDecoder.Invocation(SessionCodec.INVOCATION_OID,SessionCodec.ADMIN_SERVICE,method,args);
    }

    /** Encodes a client-side createItem invocation and decodes it through the real session decoder. */
    private static SessionDecoder.Invocation invoke(int oid, String config, int quantity, boolean locked,
            int expireDays, short requestId) throws IOException {
        var writer = new TestWire.Writer();
        byte[] body = writer.body(out -> {
            writer.message(out, "ForwardEventRequest", 1);
            writer.type(out, TestWire.DOB + "InvocationRequestEvent");
            out.writeInt(oid);
            out.writeInt(SessionCodec.ADMIN_SERVICE);
            out.writeByte(7); // stock createItem
            out.writeBoolean(true); out.writeInt(5);
            writer.type(out, "com.threerings.config.ConfigReference");
            out.writeShort(-1); out.writeUTF(config); // first interned config name
            writer.type(out, "com.threerings.config.ArgumentMap"); out.writeInt(0);
            writer.type(out, "java.lang.Integer"); out.writeInt(quantity);
            writer.type(out, "java.lang.Boolean"); out.writeBoolean(locked);
            writer.type(out, "java.lang.Integer"); out.writeInt(expireDays);
            writer.type(out, TestWire.INV + "ConfirmMarshaller"); out.writeShort(requestId);
        });
        return (SessionDecoder.Invocation) new HandshakeCodec().sessionRequests(body).getFirst().event();
    }

    /** Reads one InvocationResponseEvent and verifies method, callback and failure text. */
    private static void response(Frames reader, Socket socket, short requestId, byte method, String reason) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            byte[] frame = ((RecordingSocket) socket).frames.poll(100, TimeUnit.MILLISECONDS);
            if (frame == null) continue;
            var in = new DataInputStream(new ByteArrayInputStream(frame));
            check(in.readInt() == frame.length, "complete downstream frame");
            if (!"com.threerings.presents.net.EventNotification".equals(reader.type(in))) continue;
            in.readShort(); // unsolicited notification id
            if (!"com.threerings.presents.dobj.InvocationResponseEvent".equals(reader.type(in))) continue;
            in.readInt(); // recipient oid
            check(in.readShort() == requestId, "admin callback correlation");
            check(in.readByte() == method, "admin response method");
            if (in.readBoolean()) {
                in.readInt(); reader.type(in);
                String text = in.readUTF();
                check(reason != null && text.equals("~" + reason), "admin failure reason");
            } else {
                check(reason == null, "successful admin operation reports no reason");
            }
            return;
        }
        throw new AssertionError("Admin response timed out");
    }
    /** Narya class codes: a negative code introduces a name, a positive code repeats it. */
    private static final class Frames {
        private final Map<Integer,String> names = new HashMap<>();
        String type(DataInputStream in) throws IOException {
            int code = in.readShort();
            // This callback-only reader skips unrelated payloads. Their nested
            // class introductions can later reappear as top-level item events;
            // an unknown code is therefore an unrelated event, not corruption.
            if (code >= 0) return names.get(code);
            String name = in.readUTF(); names.put(-code, name); return name;
        }
    }

    private static final class RecordingSocket extends Socket {
        final BlockingQueue<byte[]> frames = new LinkedBlockingQueue<>();
        private final OutputStream sink = new OutputStream() {
            @Override public void write(int value) { throw new AssertionError("Expected complete frames"); }
            @Override public void write(byte[] bytes) { frames.add(bytes.clone()); }
        };
        @Override public OutputStream getOutputStream() { return sink; }
    }
}
