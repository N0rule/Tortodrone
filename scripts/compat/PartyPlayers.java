package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.core.world.SceneClock;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.server.Server;
import org.tortodrone.world.PartyRegistry;
import org.tortodrone.world.SceneRegistry;

/**
 * Headless stock-protocol players for graphical acceptance.
 *
 * <p>These are the same in-process {@link WorldSession}s the socket tests build: no window, no
 * stock client and no rendering. They authenticate against seeded accounts, idle in Haven, accept
 * the real client's party invitations, follow it into the Advanced Training Hall and send ordinary
 * movement input. The stock client only sees their scene occupants and party members, exactly as
 * if five other players had connected.
 *
 * <p>This runs inside the disposable server process because {@code WorldSession} is package-local.
 * The first argument is the player count, then the capture directory for readiness markers and the
 * disposable data directory.
 */
public final class PartyPlayers {
    private static final String PASSWORD = "lan-test-password";
    private static final int TRAINING = 294;
    private static final String[] NAMES = {
        "Hud-Knight-One",
        "Hud-Knight-Two",
        "Hud-Knight-Three",
        "Hud-Knight-Four",
        "Hud-Knight-Five",
        "Hud-Knight-Six",
        "Hud-Knight-Seven",
        "Hud-Knight-Eight"
    };
    private static final SessionDecoder.Listener CONFIRM = new SessionDecoder.Listener(
            "com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller", (short) 20);

    public static void main(String[] args) throws Exception {
        int count = Integer.parseInt(args[0]);
        Path captures = Path.of(args[1]).toAbsolutePath().normalize();
        Path data = Path.of(args[2]).toAbsolutePath().normalize();
        if (count < 1 || count > NAMES.length) throw new IllegalArgumentException("Unsupported player count " + count);
        Files.createDirectories(captures);
        System.out.println("HEADLESS: " + count + " in-process players will follow the real client");
        Thread server =
                new Thread(() -> Server.main(new String[] {"--nogui", "--skip-client-assets"}), "fixture-server");
        server.start();
        // The server installs its registry before it writes the generated public key, so the key
        // file is also the signal that SceneRegistry.system() is the live world and not a lazy copy.
        Path publicKey = data.resolve("keys/public.txt");
        long deadline = System.nanoTime() + 120_000_000_000L;
        while (!Files.isRegularFile(publicKey) && System.nanoTime() < deadline) Thread.sleep(50);
        if (!Files.isRegularFile(publicKey))
            throw new IllegalStateException("Disposable server never generated its key");
        var scenes = SceneRegistry.system();
        try (var accounts = new AccountStore(data)) {
            var players = new ArrayList<Player>();
            for (int i = 1; i <= count; i++) players.add(new Player(accounts, scenes, captures, i, count));
            for (var player : players) player.start();
            System.out.println("HEADLESS: " + players.size() + " players are idling in Haven");
            server.join();
            for (var player : players) player.close();
        }
    }

    /** One authenticated stock-protocol session with no display attached. */
    private static final class Player implements AutoCloseable {
        private final WorldSession world;
        private final SessionOutput output;
        private final Sink socket = new Sink();
        private final PartyRegistry.Member member;
        private final Path captures;
        private final int index;
        private final int expectedParty;
        private final Thread thread;
        private volatile boolean ready;
        private volatile boolean joined;
        private volatile boolean travelled;
        private boolean partyFull;
        private long travelAt;
        private long nextMove;

        Player(AccountStore accounts, SceneRegistry scenes, Path captures, int index, int players) throws Exception {
            this.captures = captures;
            this.index = index;
            this.expectedParty = players + 1;
            var password = AccountStore.transform(PASSWORD.toCharArray());
            AccountStore.Account account;
            try {
                account = accounts.authenticate("HudPlayer" + index, password);
            } finally {
                Arrays.fill(password, '\0');
            }
            if (account == null) throw new IllegalStateException("Missing seeded account HudPlayer" + index);
            output = new SessionOutput(socket);
            world = new WorldSession(
                    socket,
                    account.characters(),
                    output,
                    new SessionCodec(accounts.catalog()),
                    20000 + index,
                    account.authRevision(),
                    message -> {},
                    scenes);
            world.select(NAMES[index - 1], Map.of(SceneRegistry.ZONE_RECEIVER, (short) 0));
            world.subscribeInteriorParty((short) 1);
            world.subscribe((short) 2);
            world.entered();
            member = world.partyMemberSnapshot();
            thread = new Thread(this::loop, "headless-player-" + index);
            thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        private void loop() {
            try {
                while (!world.isClosed()) {
                    tick();
                    Thread.sleep(100);
                }
            } catch (Throwable failure) {
                System.out.println("HEADLESS: " + NAMES[index - 1] + " stopped: " + failure);
                failure.printStackTrace(System.out);
            }
        }

        private void tick() throws Exception {
            if (!ready) {
                if (world.currentScene() == null) return;
                ready = true;
                Path marker = captures.resolve("guest-" + index).resolve("guest-ready");
                Files.createDirectories(marker.getParent());
                Files.writeString(marker, "");
                System.out.println("HEADLESS: " + NAMES[index - 1] + " entered Haven");
                return;
            }
            if (!joined) {
                int party = pendingInvite();
                if (party == 0) return;
                world.partyInvocation(
                        new SessionDecoder.Invocation(3, 12, 5, List.of("PARTY_INV:" + party, 1, CONFIRM)));
                joined = true;
                System.out.println("HEADLESS: " + NAMES[index - 1] + " accepted the party invitation");
                return;
            }
            if (!travelled) {
                // Every player still in the social party when the last invitation lands, so a
                // latched "full" is seen by all of them. Wait a moment for the leader's own
                // Training command, then follow; if the leader is slower, one of these arrivals
                // creates the shared training group and the rest join it.
                if (!partyFull) {
                    var view = world.sceneRegistry().parties().view(member);
                    if (view.members().size() < expectedParty) return;
                    partyFull = true;
                    travelAt = System.nanoTime() + 2_500_000_000L;
                    System.out.println("HEADLESS: " + NAMES[index - 1] + " sees the full party");
                    return;
                }
                if (System.nanoTime() < travelAt) return;
                travel();
                travelled = true;
                System.out.println("HEADLESS: " + NAMES[index - 1] + " followed the party into Training");
                return;
            }
            move();
        }

        /** The last pending invitation's party oid, or zero. The registry keeps it private. */
        private int pendingInvite() throws ReflectiveOperationException {
            var field = PartyRegistry.class.getDeclaredField("invitations");
            field.setAccessible(true);
            var all = (Map<?, ?>) field.get(world.sceneRegistry().parties());
            var pending = (Map<?, ?>) all.get(member);
            if (pending == null || pending.isEmpty()) return 0;
            var first = pending.keySet().iterator().next();
            return first instanceof Integer oid ? oid : 0;
        }

        /** The same zone move the stock client sends for /training. */
        private void travel() throws Exception {
            synchronized (world) {
                var scene = world.sceneRegistry().catalog().scene(TRAINING);
                world.invocation(new SessionDecoder.Invocation(
                        3,
                        5,
                        1,
                        List.of(
                                scene.zone(),
                                TRAINING,
                                0,
                                new SessionDecoder.Listener(
                                        "com.threerings.whirled.zone.data.ZoneMarshaller$ZoneMoveMarshaller",
                                        (short) 3))));
                world.subscribeInteriorParty((short) 11);
                world.subscribe((short) 4);
                world.entered();
            }
            if (world.membership() != null) {
                var place = world.currentScene();
                world.membership()
                        .teleport(
                                place.map().spawnX() + (index - 1) * 0.8f,
                                place.map().spawnY() + ((index & 1) == 0 ? 0.6f : -0.6f),
                                (float) (index * Math.PI / 3));
            }
        }

        /** Ordinary input so the real client sees remote knights moving, not mannequins. */
        private void move() throws Exception {
            var place = world.currentScene();
            if (place == null || place.map().id() != TRAINING) return;
            long now = System.nanoTime();
            if (now < nextMove) return;
            nextMove = now + 250_000_000L;
            int stamp = SceneClock.now() + 1;
            float facing = (float) ((index * Math.PI / 3) + (now / 2_000_000_000.0) % (Math.PI * 2));
            world.input(new SessionDecoder.Input(
                    place.oid(), 0, stamp, List.of(new SessionDecoder.InputFrame(stamp, facing, facing, 1, stamp))));
        }

        @Override
        public void close() {
            world.close();
            output.close();
        }
    }

    /** A socket that discards the server's downstream stream; these players never read it. */
    private static final class Sink extends Socket {
        private static final OutputStream DISCARD = new OutputStream() {
            @Override
            public void write(int value) {}

            @Override
            public void write(byte[] bytes, int offset, int length) {}
        };

        @Override
        public OutputStream getOutputStream() {
            return DISCARD;
        }
    }
}
