import com.samskivert.util.at;
import com.threerings.presents.client.*;
import com.threerings.presents.dobj.ObjectAccessException;
import com.threerings.projectx.client.aU;
import com.threerings.projectx.data.PlayerEquipment;
import com.threerings.projectx.data.PlayerObject;
import com.threerings.projectx.data.ProjectXCredentials;
import com.threerings.projectx.town.data.TownSceneObject;
import com.threerings.util.Name;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Two stock clients exercise scene method-2 inspection and knight method-13 privacy. */
public final class InspectConnectionCheck {
    private InspectConnectionCheck() {}
    private static final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    private static final Thread dispatch = Thread.currentThread();
    private static final at queue = new at() {
        public void postRunnable(Runnable task) { tasks.add(task); }
        public boolean isDispatchThread() { return Thread.currentThread() == dispatch; }
        public boolean isRunning() { return true; }
    };

    private static final class Reply implements A.c {
        boolean done;
        Object value;
        String reason;
        public void br(Object result) { value = result; done = true; }
        public void aT(String error) { reason = error; done = true; }
    }

    private static final class Confirm implements A.a {
        boolean done;
        String reason;
        public void xx() { done = true; }
        public void aT(String error) { reason = error; done = true; }
    }

    private static final class Endpoint implements AutoCloseable {
        final Client client;
        final PlayerObject player;
        TownSceneObject scene;
        Endpoint(int port, Path key, String username, String password, String knight) throws Exception {
            client = new Client(new ProjectXCredentials(new Name(username), password), queue);
            client.b("127.0.0.1", new int[]{port}, new int[0]);
            client.dr(Files.readString(key).trim()); client.ds("inspect-check"); client.dt("projectx"); client.xg();
            waitFor(client::isLoggedOn); player = (PlayerObject) client.xc();
            registerSceneReceiver(client);
            var selected = new Confirm();
            ((aU) client.bU(aU.class)).b(new Name(knight), null, selected); waitFor(() -> selected.done);
            if (selected.reason != null) throw new AssertionError("Select " + knight + ": " + selected.reason);
            enterTown();
        }
        void enterTown() throws Exception {
            Class<?> listenerType = Class.forName("com.threerings.whirled.zone.a.g$a");
            Class<?> serviceType = Class.forName("com.threerings.whirled.zone.a.g");
            int[] oid = {0}; boolean[] arrived = {false}; String[] failure = {null};
            Object listener = java.lang.reflect.Proxy.newProxyInstance(listenerType.getClassLoader(),
                new Class[]{listenerType}, (proxy, method, values) -> {
                    if (method.getName().equals("aT")) { failure[0] = String.valueOf(values[0]); arrived[0] = true; }
                    if (method.getName().equals("a") && values.length == 4 && values[3] instanceof com.threerings.whirled.data.SceneModel) {
                        oid[0] = (Integer) values[0]; arrived[0] = true;
                    }
                    return null;
                });
            serviceType.getMethod("a", int.class, int.class, int.class, listenerType)
                .invoke(client.bU(serviceType), 1, 1, 0, listener);
            waitFor(() -> arrived[0]); if (failure[0] != null) throw new AssertionError(failure[0]);
            var subscription = new com.threerings.presents.dobj.t<TownSceneObject>() {
                public void a(TownSceneObject value) { scene = value; }
                public void a(int id, ObjectAccessException error) { throw new AssertionError(error); }
            };
            client.xb().a(oid[0], subscription); waitFor(() -> scene != null); scene.tudeySceneService.adB();
        }
        public void close() { if (client.isLoggedOn()) client.Q(false); }
    }

    private static void registerSceneReceiver(Client client) throws Exception {
        Class<?> receiver = Class.forName("com.threerings.whirled.zone.a.f");
        Object instance = java.lang.reflect.Proxy.newProxyInstance(receiver.getClassLoader(),
            new Class[]{receiver}, (proxy, method, values) -> null);
        Object decoder = Class.forName("com.threerings.whirled.zone.a.a").getConstructor(receiver).newInstance(instance);
        client.xd().a((com.threerings.presents.client.w) decoder);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 8) throw new IllegalArgumentException(
            "Usage: InspectConnectionCheck <port> <public-key> <user1> <password1> <knight1> <user2> <password2> <knight2>");
        System.setProperty("com.threerings.io.enumPolicy", "ORDINAL");
        int port = Integer.parseInt(args[0]); Path key = Path.of(args[1]);
        try (var first = new Endpoint(port, key, args[2], args[3], args[4]);
             var second = new Endpoint(port, key, args[5], args[6], args[7])) {
            if (first.scene.xL() != second.scene.xL()) throw new AssertionError("Players are not in one scene instance");
            inspect(first.scene, args[7], false);
            var privacy = (aU) second.client.bU(aU.class);
            privacy.aD(true); // OptionsDialog sends the inverse checkbox value: true hides full inspection.
            waitFor(() -> (second.player.flags & 1024) != 0);
            inspect(first.scene, args[7], true);
            privacy.aD(false);
            waitFor(() -> (second.player.flags & 1024) == 0);
            inspect(first.scene, args[7], false);
        }
        System.out.println("PASS: two stock clients inspect a same-scene peer, hide/unhide full equipment through the native privacy RPC");
    }

    private static void inspect(TownSceneObject scene, String target, boolean hidden) throws Exception {
        var reply = new Reply();
        scene.projectXSceneService.a(new Name(target), reply);
        waitFor(() -> reply.done);
        if (reply.reason != null || !(reply.value instanceof PlayerEquipment equipment))
            throw new AssertionError("Inspect failed: " + reply.reason + " result=" + reply.value);
        if (hidden) {
            if (equipment.items != null || equipment.visibleEquipment == null || equipment.accessories == null)
                throw new AssertionError("Private inspection leaked full equipment");
        } else if (equipment.items == null || equipment.visibleEquipment != null || equipment.accessories != null) {
            throw new AssertionError("Public inspection did not return the equipped item profile");
        }
    }

    private static void waitFor(BooleanSupplier ready) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (!ready.getAsBoolean() && System.nanoTime() < end) {
            Runnable task = tasks.poll(100, TimeUnit.MILLISECONDS); if (task != null) task.run();
        }
        if (!ready.getAsBoolean()) throw new AssertionError("Inspect fixture timeout");
    }
}
