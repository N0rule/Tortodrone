package org.tortodrone.support;

import java.io.*;
import java.net.Socket;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.*;
import static org.tortodrone.support.TestWire.check;

/** Real authenticated account replacement, independent accounts and abrupt reconnect. */
public final class MultiSessionScenario {
    public static void run(int port, PublicKey key) throws Exception {
        ExecutorService workers = Executors.newCachedThreadPool();
        try (Connection first = new Connection(workers, port, key, "Alice", "alice-password", 2)) {
            Channel a = first.channel();
            a.save(null, "First-Knight", (short) 50);
            a.changed(List.of("First-Knight")); a.confirmed((short) 50, "Knight saved.");
            first.expectDisconnect=true;
            try (Connection second=new Connection(workers,port,key,"Alice","alice-password",2);
                 Connection other=new Connection(workers,port,key,"Bob","bob-password",3)) {
            Channel b=second.channel(),bob=other.channel();
            check(a.socket.getInputStream().read()==-1,"account replacement closes original stream");
            b.subscribe(List.of("First-Knight"));
            b.save("First-Knight", "Renamed-Knight", (short) 51);
            b.changed(List.of("Renamed-Knight")); b.confirmed((short) 51, "Knight updated.");bob.ping();
            bob.select("Renamed-Knight",(short)60); bob.confirmed((short)60,"No such knight");
            bob.delete("Renamed-Knight", (short) 52); bob.confirmed((short) 52, "No such knight");
            b.ping();
            b.unsubscribe();
            b.save(null, "Second-Knight", (short) 53);
            b.changed(List.of("Renamed-Knight", "Second-Knight"));
            b.confirmed((short) 53, "Knight saved.");
            b.ping(); // The writer receives one direct save update, with no duplicate subscription event.
            b.subscribe(List.of("Renamed-Knight", "Second-Knight"));
            b.delete("Renamed-Knight", (short) 54);
            b.changed(List.of("Second-Knight")); b.confirmed((short) 54, null);
            second.expectDisconnect=true;b.socket.close();second.close();
            try(Connection reconnect=new Connection(workers,port,key,"Alice","alice-password",2)) {
            b=reconnect.channel();b.subscribe(List.of("Second-Knight"));
            b.delete("Second-Knight", (short) 55);b.changed(List.of()); b.confirmed((short)55,null);
            b.save(null,"Parallel-Two",(short)57);b.changed(List.of("Parallel-Two"));b.confirmed((short)57,"Knight saved.");bob.ping();
            b.subscribe(List.of("Parallel-Two"));
            }
            }
        } finally { workers.shutdownNow(); }
    }

    private static final class Connection implements AutoCloseable {
        final CompletableFuture<Channel> ready = new CompletableFuture<>();
        final CountDownLatch finish = new CountDownLatch(1);
        final Future<?> completed;
        private boolean closed;
        volatile boolean expectDisconnect;
        Connection(ExecutorService workers, int port, PublicKey key, String username, String password, int accountId) {
            completed = workers.submit(() -> {
                try (Socket socket = new Socket("127.0.0.1", port)) {
                    TestClient.authenticate(socket, key, username, password, true, (peer, wire, oid) -> {
                        Channel channel = new Channel(peer, wire, oid);
                        var player = channel.snapshot();
                        check(player.get("username").equals(username) && player.get("playerId").equals(accountId), "authenticated player identity");
                        ready.complete(channel);
                        if (!finish.await(30, TimeUnit.SECONDS)) throw new AssertionError("session scenario timeout");
                        channel.writer.send(peer.getOutputStream(), out -> channel.writer.message(out, "LogoffRequest", 99));
                    });
                } catch (Exception | AssertionError failure) {
                    ready.completeExceptionally(failure);
                    if(!expectDisconnect)throw new RuntimeException(failure);
                }
            });
        }
        Channel channel() throws Exception { return ready.get(15, TimeUnit.SECONDS); }
        @Override public void close() throws Exception {
            if (closed) return;
            closed = true; finish.countDown(); completed.get(10, TimeUnit.SECONDS);
        }
    }

    private static final class Channel {
        final Socket socket;
        final TestWire wire;
        final int oid;
        final TestWire.Writer writer = new TestWire.Writer(TestWire.NET + "SecureRequest", TestWire.NET + "PublicKeyCredentials",
            "[Ljava.lang.String;", TestWire.NET + "AESAuthRequest");
        Channel(Socket socket, TestWire wire, int oid) { this.socket = socket; this.wire = wire; this.oid = oid; }
        Map<String, Object> snapshot() throws Exception {
            writer.send(socket.getOutputStream(), out -> { writer.message(out, "SubscribeRequest", 40); out.writeInt(oid); });
            return wire.snapshot(wire.frame(socket.getInputStream()), (short) 40, oid);
        }
        void select(String name,short callback) throws IOException {
            writer.send(socket.getOutputStream(),out -> {
                writer.message(out,"ForwardEventRequest",40); writer.type(out,TestWire.DOB+"InvocationRequestEvent");
                out.writeInt(3); out.writeInt(1); out.writeByte(4); out.writeBoolean(true); out.writeInt(3);
                writer.type(out,"com.threerings.util.Name"); out.writeBoolean(true); out.writeUTF(name);
                out.writeShort(0); writer.type(out,TestWire.INV+"ConfirmMarshaller"); out.writeShort(callback);
            });
        }
        void subscribe(List<String> expected) throws Exception { names(snapshot().get("availableKnights"), expected); }
        void unsubscribe() throws Exception {
            writer.send(socket.getOutputStream(), out -> { writer.message(out, "UnsubscribeRequest", 41); out.writeInt(oid); });
            var response = wire.frame(socket.getInputStream());
            check(wire.type(response).equals(TestWire.NET + "UnsubscribeResponse") && response.readShort() == 41 && response.readInt() == oid, "unsubscribe response");
            TestWire.end(response);
        }
        void ping() throws Exception {
            writer.send(socket.getOutputStream(), out -> writer.message(out, "PingRequest", 42));
            var response = wire.frame(socket.getInputStream());
            check(wire.type(response).equals(TestWire.NET + "PongResponse") && response.readShort() == 42, "no unexpected, duplicate, unsubscribed or foreign-account event before pong");
            response.readLong(); response.readInt(); TestWire.end(response);
        }
        void save(String oldName, String name, short callback) throws Exception {
            writer.send(socket.getOutputStream(), out -> {
                invocation(out, 5, 14); name(out, oldName); name(out, name);
                for (String asset : List.of("Gear/Helm/Spiral Sallet (Blue)", "Gear/Armor/Spiral Culet (Blue)")) { writer.type(out, "java.lang.String"); out.writeUTF(asset); }
                out.writeShort(0); out.writeShort(0); writer.type(out, "java.lang.String"); out.writeUTF("Blue");
                writer.type(out, "java.lang.Integer"); out.writeInt(1);
                writer.type(out, "com.threerings.projectx.data.ProjectXCodes$KnightHeight"); out.writeByte(2);
                writer.type(out, "com.threerings.projectx.data.ProjectXCodes$EyeType"); out.writeByte(0);
                out.writeShort(0); writer.type(out, "java.util.List"); out.writeInt(0); out.writeShort(0);
                writer.type(out, TestWire.INV + "ConfirmMarshaller"); out.writeShort(callback);
            });
        }
        void delete(String name, short callback) throws Exception {
            writer.send(socket.getOutputStream(), out -> {
                invocation(out, 6, 2); name(out, name);
                writer.type(out, TestWire.INV + "ConfirmMarshaller"); out.writeShort(callback);
            });
        }
        void invocation(DataOutputStream out, int method, int count) throws IOException {
            writer.message(out, "ForwardEventRequest", 43); writer.type(out, TestWire.DOB + "InvocationRequestEvent");
            out.writeInt(3); out.writeInt(1); out.writeByte(method); out.writeBoolean(true); out.writeInt(count);
        }
        void name(DataOutputStream out, String value) throws IOException {
            if (value == null) out.writeShort(0);
            else { writer.type(out, "com.threerings.util.Name"); out.writeBoolean(true); out.writeUTF(value); }
        }
        DataInputStream event() throws Exception {
            var event = wire.frame(socket.getInputStream());
            check(wire.type(event).equals(TestWire.NET + "EventNotification") && event.readShort() == -1, "notification envelope");
            return event;
        }
        void changed(List<String> expected) throws Exception {
            var event = event(); check(wire.type(event).equals(TestWire.DOB + "AttributeChangedEvent"), "roster update type");
            changedBody(event, expected);
        }
        void changedBody(DataInputStream event, List<String> expected) throws Exception {
            check(event.readInt() == oid && TestWire.string(event).equals("availableKnights"), "roster targets recipient's own player object");
            names(wire.object(event), expected); TestWire.end(event);
        }
        void confirmed(short callback, String reason) throws Exception {
            var event = event(); check(wire.type(event).equals(TestWire.DOB + "InvocationResponseEvent"), "callback type");
            callbackBody(event, callback, reason);
        }
        void callbackBody(DataInputStream event, short callback, String reason) throws Exception {
            check(event.readInt() == oid && event.readShort() == callback && event.readByte() == (reason == null ? 1 : 0), "callback ownership and correlation");
            if (reason == null) check(!event.readBoolean(), "confirmation arguments");
            else check(event.readBoolean() && event.readInt() == 1 && ((String) wire.object(event)).contains(reason), "failure callback reason");
            TestWire.end(event);
        }
        void twoChanges(short callback) throws Exception {
            int updates = 0; boolean confirmed = false;
            while (updates < 2 || !confirmed) {
                var event = event(); String type = wire.type(event);
                if (type.equals(TestWire.DOB + "AttributeChangedEvent")) {
                    check(event.readInt() == oid && TestWire.string(event).equals("availableKnights"), "concurrent update target");
                    var roster = (List<?>) wire.object(event); TestWire.end(event);
                    check(roster.size() == ++updates, "concurrent updates ordered without stale snapshots");
                    if (updates == 2) check(new HashSet<>(roster.stream().map(value -> ((Map<?, ?>) value).get("name")).toList()).equals(Set.of("Parallel-One", "Parallel-Two")), "both concurrent saves present");
                } else {
                    check(type.equals(TestWire.DOB + "InvocationResponseEvent") && !confirmed, "one correlated confirmation");
                    callbackBody(event, callback, "Knight saved."); confirmed = true;
                }
            }
        }
        void names(Object value, List<String> expected) {
            check(((List<?>) value).stream().map(entry -> ((Map<?, ?>) entry).get("name")).toList().equals(expected), "current roster names: " + value);
        }
    }
}
