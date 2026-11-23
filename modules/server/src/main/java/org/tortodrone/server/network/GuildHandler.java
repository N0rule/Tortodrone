package org.tortodrone.server.network;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.protocol.SessionDecoder;

/** Guild service ownership, subscription, mutation fan-out and chat for one connection.
 *  Every handler method runs under the session monitor and writes only through the responder. */
final class GuildHandler {
    private final WorldSession session;
    private final SessionResponder responder;
    private final CharacterStore store;
    private org.tortodrone.core.guild.View view;
    private boolean subscribed;

    GuildHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
        this.store = session.charactersStore();
    }

    org.tortodrone.core.guild.View view() {
        return view;
    }

    org.tortodrone.core.guild.Guild guildInfo() {
        return view == null ? null : view.guild();
    }

    void reload() throws IOException {
        synchronized (session) {
            if (store.hasWorldStorage()) view = load();
        }
    }

    private org.tortodrone.core.guild.View load() throws IOException {
        var profile = session.currentProfile();
        if (!store.hasWorldStorage() || profile == null) return null;
        var guild = store.services().guilds().guildOf(profile.worldId());
        return guild == null ? null : store.services().guilds().view(guild.id());
    }

    boolean ownsGuild(int object) throws IOException {
        synchronized (session) {
            return view != null
                    && object
                            == org.tortodrone.protocol.SessionCodec.guildOid(
                                    view.guild().id());
        }
    }

    void subscribeGuild(short message) throws IOException {
        synchronized (session) {
            if (session.isClosed() || !session.selected()) throw new IOException("Select a knight first");
            if (view == null) throw new IOException("You are not in a guild");
            subscribed = true;
            responder.send(() -> responder.codec().guild(message, view));
        }
    }

    void unsubscribeGuild(int object) {
        synchronized (session) {
            if (view != null
                    && object
                            == org.tortodrone.protocol.SessionCodec.guildOid(
                                    view.guild().id())) subscribed = false;
        }
    }
    /** Publish a guild mutation to this session and to every online member of the same guild. */
    private void guildChanged(org.tortodrone.core.guild.View before) throws IOException {
        var after = load();
        view = after;
        if (after == null) {
            responder.send(() -> responder.codec().guildCleared(responder.oid()));
            return;
        }
        responder.send(() -> responder.codec().guildChanges(before, after));
        var remaining = new java.util.HashSet<Integer>();
        for (var member : after.members()) remaining.add(member.worldId());
        var peers = new ArrayList<WorldSession>();
        for (var peer : SessionDirectory.activeSessions())
            if (peer != session
                    && !peer.isClosed()
                    && peer.guilds.view != null
                    && peer.guilds.view.guild().id() == after.guild().id()) peers.add(peer);
        // A member who is no longer in the roster learns it from their own session, not from a
        // delta addressed to the guild they just left.
        for (var peer : peers) {
            if (peer.currentProfile() != null
                    && remaining.contains(peer.currentProfile().worldId()))
                peer.guilds.receiveGuildChange(before, after);
            else peer.guilds.receiveGuildEnded();
        }
    }

    void receiveGuildChange(org.tortodrone.core.guild.View before, org.tortodrone.core.guild.View after)
            throws IOException {
        view = after;
        if (subscribed && !session.isClosed())
            responder.send(() -> responder.codec().guildChanges(before, after));
    }

    void receiveGuildEnded() throws IOException {
        view = null;
        subscribed = false;
        if (!session.isClosed()) responder.send(() -> responder.codec().guildCleared(responder.oid()));
    }
    /** Guild service: twenty-two stock calls, all addressed to the bootstrap handle. */
    boolean guildInvocation(SessionDecoder.Invocation request) throws IOException {
        synchronized (session) {
            var a = request.arguments();
            if (request.service() == org.tortodrone.protocol.SessionCodec.GUILD_CHAT_SERVICE) {
                if (view == null
                        || request.oid()
                                != org.tortodrone.protocol.SessionCodec.guildOid(
                                        view.guild().id())) return false;
                if (!org.tortodrone.server.ServerPolicy.chatEnabled())
                    throw new IOException("Chat is disabled on this server.");
                if (request.method() != 1
                        || a.size() != 2
                        || !(a.get(0) instanceof String text)
                        || !(a.get(1) instanceof Byte mode)) throw new SessionDecoder.Rejected("Invalid guild chat");
                if (!session.chatRate().allow(System.nanoTime())) {
                    session.unavailable("Please slow down before sending another message.");
                    return true;
                }
                var message = new org.tortodrone.core.world.SceneChat.Message(
                        session.selectedKnight().name(), text, mode);
                var peers = new ArrayList<WorldSession>();
                for (var peer : SessionDirectory.activeSessions())
                    if (!peer.isClosed()
                            && peer.guilds.view != null
                            && peer.guilds.view.guild().id() == view.guild().id()) peers.add(peer);
                for (var peer : peers)
                    peer.responder.send(() -> responder
                            .codec()
                            .chat(
                                    org.tortodrone.protocol.SessionCodec.guildOid(
                                            view.guild().id()),
                                    message));
                return true;
            }
            if (request.oid() != org.tortodrone.protocol.SessionCodec.INVOCATION_OID
                    || request.service() != org.tortodrone.protocol.SessionCodec.GUILD_SERVICE) return false;
            if (a.isEmpty() || !(a.getLast() instanceof SessionDecoder.Listener listener))
                throw new SessionDecoder.Rejected("Invalid guild listener");
            int method = request.method();
            boolean result = method == 9 || method == 12 || method == 14 || method == 20;
            if (!listener.type()
                    .equals(org.tortodrone.core.WireTypes.LISTENER_PREFIX
                            + (result ? "ResultMarshaller" : "ConfirmMarshaller")))
                throw new SessionDecoder.Rejected("Invalid guild listener");
            try {
                if (session.isClosed() || !session.selected()) throw new IOException("Select a knight first");
                var guilds = store.services().guilds();
                boolean changed = false;
                Object response = null;
                switch (method) {
                    case 1 -> {
                        requireArgs(a, 3, Integer.class, org.tortodrone.core.wire.EnumValue.class, Integer.class);
                        changed = true;
                        guilds.buyExpansion(
                                store.accountId(),
                                session.selectedKnight().id(),
                                session.authRevision(),
                                (Integer) a.get(0),
                                ((org.tortodrone.core.wire.EnumValue) a.get(1)).ordinal());
                    }
                    case 2 -> {
                        requireArgs(a, 0);
                        guilds.canRename(
                                store.accountId(), session.selectedKnight().id(), session.authRevision());
                    }
                    case 3 -> {
                        requireArgs(a, 2, String.class, Byte.class);
                        changed = true;
                        guilds.changeRank(
                                store.accountId(),
                                session.selectedKnight().id(),
                                session.authRevision(),
                                (String) a.get(0),
                                (Byte) a.get(1));
                    }
                    case 4 -> {
                        requireArgs(a, 1, String.class);
                        guilds.checkName((String) a.get(0));
                    }
                    case 5 -> {
                        requireArgs(a, 2, Integer.class, Integer.class);
                        changed = true;
                        guilds.depositEnergy(
                                store.accountId(),
                                session.selectedKnight().id(),
                                session.authRevision(),
                                (Integer) a.get(0),
                                (Integer) a.get(1));
                    }
                    case 6 -> throw new IOException("The hall editor is not available on this server.");
                    case 7 -> {
                        requireArgs(a, 1, String.class);
                        changed = true;
                        guilds.expel(store.accountId(), session.selectedKnight().id(), session.authRevision(), (String)
                                a.get(0));
                    }
                    case 8 -> {
                        requireArgs(a, 1, String.class);
                        changed = true;
                        var formed = guilds.form(
                                store.accountId(), session.selectedKnight().id(), session.authRevision(), (String)
                                        a.get(0));
                        session.applyProfile(formed.profile());
                    }
                    case 9 -> throw new IOException("Guild storage is not available on this server.");
                    case 10 -> {
                        requireArgs(a, 3, String.class, String.class, String.class);
                        changed = true;
                        guilds.invite(
                                store.accountId(),
                                session.selectedKnight().id(),
                                session.authRevision(),
                                (String) a.get(0),
                                (String) a.get(1),
                                (String) a.get(2));
                    }
                    case 11 -> {
                        requireArgs(a, 0);
                        changed = true;
                        guilds.leave(store.accountId(), session.selectedKnight().id(), session.authRevision());
                    }
                    case 12 -> {
                        requireArgs(a, 6, Integer.class, Long.class, Integer.class, Integer.class, null, null);
                        validateLogArgs(a);
                        int category =
                                a.get(4) == null ? -1 : ((org.tortodrone.core.wire.EnumValue) a.get(4)).ordinal();
                        response = guilds.log(
                                store.accountId(),
                                session.selectedKnight().id(),
                                session.authRevision(),
                                (Integer) a.get(0),
                                (Long) a.get(1),
                                (Integer) a.get(2),
                                (Integer) a.get(3),
                                category < 0 ? null : category,
                                (String) a.get(5));
                    }
                    case 13 -> throw new IOException("The guild shop is not available on this server.");
                    case 14 -> {
                        requireArgs(a, 3, Integer.class, org.tortodrone.core.wire.EnumValue.class, null);
                        changed = true;
                        guilds.removeExpansion(
                                store.accountId(),
                                session.selectedKnight().id(),
                                session.authRevision(),
                                (Integer) a.get(0),
                                ((org.tortodrone.core.wire.EnumValue) a.get(1)).ordinal());
                    }
                    case 15 -> {
                        requireArgs(a, 1, String.class);
                        changed = true;
                        guilds.rename(
                                store.accountId(), session.selectedKnight().id(), session.authRevision(), (String)
                                        a.get(0));
                    }
                    case 16 -> {
                        requireArgs(a, 3, Integer.class, org.tortodrone.core.wire.EnumValue.class, String.class);
                        changed = true;
                        guilds.setHallGlobal(
                                store.accountId(),
                                session.selectedKnight().id(),
                                session.authRevision(),
                                (Integer) a.get(0),
                                ((org.tortodrone.core.wire.EnumValue) a.get(1)).ordinal(),
                                (String) a.get(2));
                    }
                    case 17 -> {
                        requireArgs(a, 2, Integer.class, Boolean.class);
                        changed = true;
                        guilds.setHallOpen(
                                store.accountId(),
                                session.selectedKnight().id(),
                                session.authRevision(),
                                (Integer) a.get(0),
                                (Boolean) a.get(1));
                    }
                    case 18 -> {
                        requireArgs(a, 2, Integer.class, String.class);
                        changed = true;
                        guilds.setMotd(
                                store.accountId(),
                                session.selectedKnight().id(),
                                session.authRevision(),
                                (Integer) a.get(0),
                                (String) a.get(1));
                    }
                    case 19 -> {
                        requireArgs(a, 1, Integer.class);
                        // Reload instead of trusting this session's cached view: a peer may have
                        // bought or removed an expansion since the last guild event.
                        var current = load();
                        if (current == null || current.guild().id() != (Integer) a.get(0))
                            throw new IOException("m.access_denied");
                        long now = System.currentTimeMillis();
                        float prorate = current.guild().upkeepTime() <= now
                                ? 0f
                                : Math.min(1f, (current.guild().upkeepTime() - now) / 604_800_000f);
                        responder.send(() -> responder
                                .codec()
                                .expansionDialog(
                                        responder.oid(),
                                        current.guild().id(),
                                        current.expansions(),
                                        current.guild().inDefault(),
                                        prorate,
                                        now));
                    }
                    case 20 -> {
                        requireArgs(a, 2, Integer.class, org.tortodrone.core.wire.EnumValue.class);
                        var current = load();
                        if (current == null || current.guild().id() != (Integer) a.get(0))
                            throw new IOException("m.access_denied");
                        int type = ((org.tortodrone.core.wire.EnumValue) a.get(1)).ordinal();
                        String setting = type == 0
                                ? current.guild().theme()
                                : current.guild().environment();
                        responder.send(() -> responder
                                .codec()
                                .hallGlobalDialog(
                                        responder.oid(),
                                        current.guild().id(),
                                        type,
                                        current.expansions(),
                                        setting,
                                        System.currentTimeMillis()));
                    }
                    case 21 -> throw new IOException("Guild halls are not available on this server yet.");
                    case 22 -> {
                        requireArgs(a, 1, Integer.class);
                        changed = true;
                        var withdrawn = guilds.withdrawEnergy(
                                store.accountId(), session.selectedKnight().id(), session.authRevision(), (Integer)
                                        a.get(0));
                        session.applyProfile(withdrawn.profile());
                    }
                    default -> throw new IOException("m.internal_error");
                }
                if (changed) {
                    var before = view;
                    view = load();
                    if (before == null) {
                        if (view != null)
                            responder.send(() -> responder.codec().guildChanges(emptyGuildView(view), view));
                    } else guildChanged(before);
                }
                Object reply = response;
                // The record page travels as an InvocationResponseEvent whose argument is a
                // GuildLogPage. The town result writer has no branch for that type, and an
                // unencodable frame fails the downstream stream and closes the connection, so the
                // page is written by the guild codec that was built for it.
                if (method == 12) {
                    if (!(reply instanceof org.tortodrone.core.guild.Page page))
                        throw new IOException("m.internal_error");
                    responder.send(() -> responder.codec().guildLog(listener.requestId(), responder.oid(), page));
                } else if (result)
                    responder.send(() -> responder
                            .codec()
                            .townResult(responder.oid(), listener.requestId(), reply, session.currentProfile()));
                else responder.send(() -> responder.codec().invocationConfirmed(responder.oid(), listener.requestId()));
            } catch (IOException | RuntimeException refused) {
                String reason = refused.getMessage() == null ? "m.internal_error" : refused.getMessage();
                responder.send(
                        () -> responder.codec().invocationFailure(responder.oid(), listener.requestId(), reason));
            }
            return true;
        }
    }

    private static org.tortodrone.core.guild.View emptyGuildView(org.tortodrone.core.guild.View view) {
        return new org.tortodrone.core.guild.View(view.guild(), List.of(), view.expansions());
    }

    private static void requireArgs(List<Object> args, int count, Class<?>... types) throws IOException {
        if (args.size() != count + 1) throw new IOException("m.internal_error");
        for (int i = 0; i < types.length; i++)
            if (types[i] != null && !(types[i].isInstance(args.get(i)))) throw new IOException("m.internal_error");
    }

    private static void validateLogArgs(List<Object> args) throws IOException {
        if (args.get(4) != null
                && (!(args.get(4) instanceof org.tortodrone.core.wire.EnumValue e)
                        || !e.type().equals("com.threerings.projectx.guild.data.GuildLogEntry$Category")))
            throw new IOException("m.internal_error");
        if (args.get(5) != null && !(args.get(5) instanceof String)) throw new IOException("m.internal_error");
    }
}
