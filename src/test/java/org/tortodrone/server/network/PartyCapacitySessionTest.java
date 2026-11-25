package org.tortodrone.server.network;

import static org.tortodrone.support.TestWire.check;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.config.ServerSettings;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.world.WeaponCatalog;
import org.tortodrone.protocol.*;
import org.tortodrone.resources.SceneCatalog;
import org.tortodrone.storage.Database;
import org.tortodrone.world.*;

/**
 * Adds knights to one party one at a time and records where admission stops, so the configured cap
 * is the only bound. The wire still advertises {@code PartyView.CAPACITY}; this covers the server.
 */
public final class PartyCapacitySessionTest {
    static final int CAP = 9;
    static final int KNIGHTS = 10;
    static final int TRAINING = 294;
    static final String[] NAMES = {
        "Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel", "India", "Juliet"
    };

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("tortodrone-party-capacity-");
        var previous = SceneRegistry.system();
        try {
            Path yaml = home.resolve("party.yml");
            Files.writeString(yaml, "party-max-players: " + CAP + "\n");
            var settings = ServerSettings.read(yaml);
            check(settings.partyMaxPlayers() == CAP, "a party cap of nine is configurable");

            var db = new Database(home.resolve("data"), true);
            var items = ItemCatalog.load(org.tortodrone.support.ContentFixture.path("items/catalog.json"));
            var scenes = new SceneRegistry(SceneCatalog.load(org.tortodrone.support.ContentFixture.path("maps")));
            scenes.town(org.tortodrone.core.town.TownCatalog.load(
                    org.tortodrone.support.ContentFixture.path("town/catalog.json")));
            scenes.weapons(WeaponCatalog.load(org.tortodrone.support.ContentFixture.path("combat/weapons.tsv")));
            scenes.fires(FireCatalog.load(org.tortodrone.support.ContentFixture.path("combat/fires.json")));
            scenes.parties().capacity(settings.partyMaxPlayers());
            SceneRegistry.install(scenes);

            var players = new ArrayList<Player>();
            for (int i = 0; i < KNIGHTS; i++) players.add(new Player(db, items, scenes, NAMES[i], 1500 + i));
            try {
                var leader = players.getFirst();
                int party = leader.party().oid();
                String refusal = null;
                for (int i = 1; i < players.size(); i++) {
                    var joiner = players.get(i);
                    String invite = leader.invite(joiner);
                    if (invite != null) {
                        refusal = invite;
                        check(
                                leader.party().members().size() == CAP,
                                "admission stops at the configured cap, not at four or eight");
                        continue;
                    }
                    joiner.accept(party);
                    check(
                            leader.party().oid() == joiner.party().oid()
                                    && leader.party().members().size() == i + 1,
                            "knight " + (i + 1) + " joins the one party");
                }
                check(
                        refusal != null && refusal.contains("full"),
                        "the knight beyond the cap is told the party is full");
                check(leader.party().members().size() == CAP, "nine knights share one party of nine");

                for (var player : players) player.travel(TRAINING);
                var place = leader.place();
                long same = players.stream().filter(p -> p.place() == place).count();
                check(
                        same == CAP && place.simulation().snapshot().players().size() == CAP,
                        "all nine arrive in one Training simulation");
                check(leader.publishedMembers() == CAP, "the party object publishes nine members");
                System.out.println("PASS: one party holds the configured nine knights in one Training simulation"
                        + " and the next invitation is refused as full");
            } finally {
                for (var player : players) player.close();
            }
        } finally {
            SceneRegistry.install(previous);
            try (var paths = Files.walk(home)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                    try {
                        Files.delete(path);
                    } catch (IOException ignored) {
                    }
            }
        }
    }

    private static SessionDecoder.Listener listener() {
        return new SessionDecoder.Listener(
                "com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller", (short) 20);
    }

    static final class Player implements AutoCloseable {
        final WorldSession world;
        final SessionOutput output;
        final RecordingSocket socket = new RecordingSocket();
        final SceneRegistry scenes;
        final String name;

        Player(Database db, ItemCatalog items, SceneRegistry scenes, String user, int oid) throws Exception {
            this.scenes = scenes;
            int account = db.create(user, new Passwords.Protection(new byte[16], new byte[32]), false);
            var characters = new CharacterStore(db, account, user, items);
            name = "Party-" + user;
            characters.create(new Knight(
                    0,
                    name,
                    "Gear/Helm/Spiral Sallet (Blue)",
                    "Gear/Armor/Spiral Culet (Blue)",
                    null,
                    null,
                    "Blue",
                    1,
                    2,
                    0,
                    0));
            output = new SessionOutput(socket);
            world = new WorldSession(socket, characters, output, new SessionCodec(items), oid, 1, m -> {}, scenes);
            world.select(name, Map.of(SceneRegistry.ZONE_RECEIVER, (short) 0));
            world.subscribeInteriorParty((short) 1);
            world.subscribe((short) 2);
            world.entered();
        }

        PartyRegistry.Member member() {
            return (PartyRegistry.Member) field(world, "partyMember");
        }

        org.tortodrone.core.party.PartyView.View party() {
            try {
                return scenes.parties().view(member());
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        }

        SceneRegistry.Instance place() {
            return world.currentScene();
        }

        int publishedMembers() {
            var view = world.publishedParty();
            return view == null ? 0 : view.members().size();
        }

        /** Returns null when the invitation reached the target, or the refusal text. */
        String invite(Player target) throws Exception {
            for (int attempt = 0; attempt < 6; attempt++) {
                socket.frames.clear();
                target.socket.frames.clear();
                world.partyInvocation(
                        new SessionDecoder.Invocation(party().oid(), 20, 2, List.of(target.name, listener())));
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1200);
                while (System.nanoTime() < deadline
                        && !target.socket.contains("Notification$PartyInvite")
                        && !refused()) Thread.sleep(20);
                if (target.socket.contains("Notification$PartyInvite")) return null;
                if (socket.text().contains("slow down")) {
                    Thread.sleep(1100);
                    continue;
                }
                return refused() ? "The party is full" : socket.text();
            }
            throw new AssertionError("invitation of " + target.name + " stayed rate limited");
        }

        private boolean refused() {
            return socket.text().contains("full");
        }

        void accept(int party) throws Exception {
            world.partyInvocation(
                    new SessionDecoder.Invocation(3, 12, 7, List.of("PARTY_INV:" + party, 1, listener())));
        }

        void travel(int template) throws Exception {
            synchronized (world) {
                if (!world.sceneTravelPending()) {
                    var trigger = place().map().portals().triggers().stream()
                            .filter(p -> p.destination() == template)
                            .findFirst()
                            .orElse(null);
                    if (trigger != null) world.forcePortal(trigger);
                }
                world.invocation(new SessionDecoder.Invocation(
                        3,
                        5,
                        1,
                        List.of(
                                scenes.catalog().scene(template).zone(),
                                template,
                                0,
                                new SessionDecoder.Listener(
                                        "com.threerings.whirled.zone.data.ZoneMarshaller$ZoneMoveMarshaller",
                                        (short) 3))));
                world.subscribeInteriorParty((short) 11);
                world.subscribe((short) 4);
                world.entered();
            }
        }

        public void close() {
            world.close();
            output.close();
        }

        private static Object field(Object object, String name) {
            try {
                var f = object.getClass().getDeclaredField(name);
                f.setAccessible(true);
                return f.get(object);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }
    }

    static final class RecordingSocket extends Socket {
        final BlockingQueue<byte[]> frames = new LinkedBlockingQueue<>();

        public OutputStream getOutputStream() {
            return new OutputStream() {
                public void write(int b) {
                    throw new AssertionError("Expected complete frame");
                }

                public void write(byte[] bytes) {
                    frames.add(bytes.clone());
                }
            };
        }

        boolean contains(String text) {
            return frames.stream().anyMatch(f -> new String(f, StandardCharsets.ISO_8859_1).contains(text));
        }

        /** Printable text of every frame received since the last clear. */
        String text() {
            var out = new StringBuilder();
            for (var frame : frames) {
                for (char c : new String(frame, StandardCharsets.ISO_8859_1).toCharArray())
                    out.append(c >= 32 && c < 127 ? c : ' ');
            }
            return out.toString().replaceAll("\\s+", " ").strip();
        }
    }
}
