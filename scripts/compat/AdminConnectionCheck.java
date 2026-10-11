import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import com.samskivert.util.at;
import com.threerings.config.ConfigReference;

/** Live original F3 dashboard: the stock admin service and its ItemCreator, authorized on the server. */
public final class AdminConnectionCheck {
    /** Confirms the stock confirm listener; success records no reason. */
    static final class Confirm {
        boolean done;
        String reason;
        void processed() { done = true; }
        void failed(String why) { reason = why; done = true; }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("com.threerings.io.enumPolicy", "ORDINAL");
        Thread thread = Thread.currentThread();
        at queue = new at() {
            public void postRunnable(Runnable task) { WorldConnectionCheck.tasks.add(task); }
            public boolean isDispatchThread() { return Thread.currentThread() == thread; }
            public boolean isRunning() { return true; }
        };
        Path data = Path.of(args[1]).getParent().getParent();
        Class<?> marshaller = Class.forName("com.threerings.projectx.admin.data.ProjectXAdminMarshaller");
        Class<?> service = marshaller.getInterfaces()[0];
        Class<?> listener = null;
        Method createItem = null;
        for (Method method : marshaller.getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 5 && types[0] == ConfigReference.class && types[1] == int.class
                    && types[2] == boolean.class && types[3] == int.class) {
                createItem = method; listener = types[4];
            }
        }
        if (createItem == null) throw new AssertionError("Stock admin marshaller has no createItem");
        try (var tools = new org.tortodrone.account.AccountStore(data)) {
            tools.create("AdminStaff", "lan-test-password".toCharArray(), true);
            seed(tools, "AdminStaff", "Admin-Knight");
            tools.create("AdminPlayer", "lan-test-password".toCharArray());
            seed(tools, "AdminPlayer", "Watch-Knight");
            String item = "Gear/Shield/Proto Shield";
            tools.catalog().require(item);
            try (var staff = new GameMasterConnectionCheck.Endpoint(args, queue, "AdminStaff", "Admin-Knight");
                 var watch = new GameMasterConnectionCheck.Endpoint(args, queue, "AdminPlayer", "Watch-Knight")) {
                // The original dashboard opens for a support-plus account and never for an admin token.
                if (!staff.player.tokens.Nl() || staff.player.tokens.isAdmin()
                        || watch.player.tokens.Nl()) throw new AssertionError("GM support token mismatch");
                Object admin = staff.client.bU(service);
                if (admin == null) throw new AssertionError("Stock admin service is unavailable from the bootstrap");
                var admitted = new Confirm();
                int before = ids(staff.player, item).size();
                create(staff, admin, createItem, listener, item, 1, admitted);
                if (admitted.reason != null) throw new AssertionError("createItem refused: " + admitted.reason);
                // The knight's own starter kit also holds a Proto Shield; a grant adds a new entry.
                WorldConnectionCheck.waitFor(() -> ids(staff.player, item).size() > before);
                int granted = ids(staff.player, item).size();
                if (granted != before + 1) throw new AssertionError("Created item count mismatch: " + granted);

                // A revoked account is refused by every call, not by the client's UI permission check.
                tools.setGameMaster("AdminStaff", false);
                WorldConnectionCheck.waitFor(() -> !staff.player.tokens.Nl());
                var revoked = new Confirm();
                create(staff, admin, createItem, listener, item, 1, revoked);
                if (revoked.reason == null || !revoked.reason.contains("Game Master permission required"))
                    throw new AssertionError("Revoked account was not refused: " + revoked.reason);
                if (ids(staff.player, item).size() != granted) throw new AssertionError("Refused createItem changed the inventory");

                // An unknown item never reaches the catalogue, and a plain player cannot call it at all.
                tools.setGameMaster("AdminStaff", true);
                WorldConnectionCheck.waitFor(() -> staff.player.tokens.Nl());
                var unknown = new Confirm();
                create(staff, admin, createItem, listener, "Gear/Shield/Not A Real Item", 1, unknown);
                if (unknown.reason == null || !unknown.reason.contains("Unknown item"))
                    throw new AssertionError("Unknown item was not refused: " + unknown.reason);
                Object playerAdmin = watch.client.bU(service);
                var denial = new Confirm();
                create(watch, playerAdmin, createItem, listener, item, 1, denial);
                if (denial.reason == null || !denial.reason.contains("Game Master permission required"))
                    throw new AssertionError("Unprivileged account was not refused: " + denial.reason);
                if (ids(watch.player, item).size() != 1) throw new AssertionError("Unprivileged createItem granted an item");

                // The same knight still owns the item after a full reconnect, straight from the database.
                var reconnect = new GameMasterConnectionCheck.Endpoint(args, queue, "AdminStaff", "Admin-Knight");
                try {
                    if (reconnect.player.tokens.Nl() && ids(reconnect.player, item).size() != granted)
                        throw new AssertionError("Reconnect lost the created item");
                } finally { reconnect.close(); }
                System.out.println("PASS: stock F3 admin service advertisement, ItemCreator grant, per-call authorization, refusal reasons and reconnect persistence");
            }
        } finally {
            try (var tools = new org.tortodrone.account.AccountStore(data)) { tools.setGameMaster("AdminStaff", false); }
        }
    }

    private static void create(GameMasterConnectionCheck.Endpoint endpoint, Object admin, Method method,
            Class<?> listener, String config, int quantity, Confirm callback) throws Exception {
        method.invoke(admin, new ConfigReference(config), quantity, false, 0, java.lang.reflect.Proxy.newProxyInstance(
            listener.getClassLoader(), new Class[]{listener}, (proxy, invocation, values) -> {
                String name = invocation.getName();
                if ("xq".equals(name) || "xx".equals(name)) callback.processed();
                else if (values != null && values.length > 0 && values[0] instanceof String text) callback.failed(text);
                return null;
            }));
        WorldConnectionCheck.waitFor(() -> callback.done);
    }
    private static Set<Long> ids(com.threerings.projectx.data.PlayerObject player, String config) {
        var result = new HashSet<Long>();
        for (var item : player.items) if (item.Vx().getName().equals(config)) result.add(item.VA());
        return result;
    }
    private static void seed(org.tortodrone.account.AccountStore tools, String user, String name) throws Exception {
        char[] hash = org.tortodrone.account.AccountStore.transform("lan-test-password".toCharArray());
        try { tools.authenticate(user, hash).characters().create(new org.tortodrone.character.Knight(0, name,
            "Gear/Helm/Spiral Sallet (Blue)", "Gear/Armor/Spiral Culet (Blue)", null, null, "Blue", 1, 2, 0, 0)); }
        finally { Arrays.fill(hash, '\0'); }
    }
}
