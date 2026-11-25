package org.tortodrone.server.network;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.core.character.SlotUpgrades;
import org.tortodrone.core.town.TownCatalog;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.storage.TownStore;

/** Native town services for one connection: the modal dialog, station operations, crafting,
 *  wrapping and the battle-sprite editor. The dialog stamp and service id belong to this
 *  connection; every mutation commits through the store before any callback publishes. */
final class TownHandler {
    private final WorldSession session;
    private final SessionResponder responder;
    private final CharacterStore store;
    private TownCatalog.Station dialog;
    private ActorState source;
    private long created;
    private int service = 100;
    private long shopCycle, tradeGeneration;
    private float x, y;

    TownHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
        this.store = session.charactersStore();
    }

    boolean dialogOpen() {
        return dialog != null;
    }

    long created() {
        return created;
    }

    void close() throws IOException {
        if (dialog == null) return;
        dialog = null;
        source = null;
        service++;
        // Read the scene under the session monitor before the output queue encodes the frame:
        // an encoder that acquires this monitor inverts the session/output lock order.
        int place = session.currentScene().oid();
        responder.send(() -> responder.codec().townDialog(responder.oid(), place, null, null, created, service));
    }

    /** Drop dialog state without a callback, for a scene replacement. */
    void reset() {
        dialog = null;
        source = null;
        service++;
    }

    void open(TownCatalog.Station station, ActorState source) throws IOException {
        if (session.tradeHandler().active()) throw new IOException("Finish the trade before using a town station");
        // Arsenal and auction stations are client-side windows, not modal dialogs; the
        // stock controller opens them from a native command instead of TownDialogInfo.
        if (station.kind().equals("OpenArsenal") || station.kind().equals("OpenAuction")) {
            if (station.kind().equals("OpenArsenal")) {
                // The window reads equipPolicy while opening; publish the policy on this
                // same ordered output stream even if the scene publication was coalesced.
                session.publishArsenalPolicy();
            }
            String command = station.kind().equals("OpenAuction") ? "auction" : "show_arsenal_station";
            session.conversations().close();
            close();
            responder.send(() -> responder.codec().controllerAction(responder.oid(), command, source));
            return;
        }
        tradeGeneration = session.tradeHandler().dialogGeneration();
        session.conversations().close();
        close();
        var own = session.selfActor();
        if (own == null) throw new IOException("Knight left scene");
        x = own.x();
        y = own.y();
        dialog = station;
        this.source = source;
        created = Math.max(System.currentTimeMillis(), created + 1);
        service++;
        if (station.kind().equals("OpenShop") || station.kind().equals("OpenHarnessInstaller")) {
            shopCycle = session.sceneRegistry().town().shopCycle(station.shop());
            publishShop();
        }
        int place = session.currentScene().oid();
        responder.send(() -> responder.codec().townDialog(responder.oid(), place, station, source, created, service));
    }

    private void publishShop() throws IOException {
        var knight = session.selectedKnight();
        var goods = session
                .sceneRegistry()
                .town()
                .goods(dialog, shopCycle, session.currentProfile().worldId())
                .stream()
                .map(g -> {
                    var def = store.catalog().find(g.item());
                    int price = g.price() >= 0
                            ? g.price()
                            : def == null ? 0 : session.sceneRegistry().town().price(g.item(), def.rarity(), true);
                    return new TownCatalog.Good(
                            g.item(), g.count(), price, g.energy(), g.limit(), g.token(), g.tokenCount());
                })
                .toList();
        var available = store.services()
                .town(session.sceneRegistry().town())
                .availability(store.accountId(), knight.id(), session.authRevision(), dialog, shopCycle);
        responder.send(() -> responder.codec().townShop(responder.oid(), goods, available));
    }

    /** Close on a trade-generation change or when the knight walks away from the station. */
    void expire(ActorState own) throws IOException {
        if (dialog == null) return;
        if (session.tradeHandler().dialogChanged(tradeGeneration)) {
            close();
            return;
        }
        if (own == null || Math.hypot(own.x() - x, own.y() - y) > 1) close();
    }

    boolean invocation(SessionDecoder.Invocation request) throws IOException {
        if (dialog != null && session.tradeHandler().dialogChanged(tradeGeneration)) close();
        var a = request.arguments();
        if (dialog != null && session.ownsScene(request.oid()) && request.service() == 6 && request.method() == 1) {
            if (a.size() != 1 || !(a.getFirst() instanceof Long stamp))
                throw new SessionDecoder.Rejected("Invalid Town dialog close");
            if (stamp != created) return true;
            close();
            return true;
        }
        boolean global = request.oid() == SessionCodec.INVOCATION_OID;
        var knight = session.selectedKnight();
        // KnightMarshaller 2 is canRename (30-day window check); 8 is renameKnight [Name, Confirm].
        if (global
                && request.service() == SessionCodec.KNIGHT_SERVICE
                && (request.method() == 2 || request.method() == 8)) {
            boolean rename = request.method() == 8;
            if (a.size() != (rename ? 2 : 1)
                    || rename && !(a.getFirst() instanceof String)
                    || !(a.getLast() instanceof SessionDecoder.Listener listener)
                    || !listener.type().endsWith("$ConfirmMarshaller"))
                throw new SessionDecoder.Rejected("Invalid knight rename request");
            try {
                var map = session.currentScene().map();
                if (session.isClosed()
                        || !session.selected()
                        || !session.sceneEntered()
                        || !session.sceneSubscribed()
                        || !map.town()) throw new IOException("Enter a town first");
                if (session.tradeHandler().active()) throw new IOException("Finish the trade first");
                if (rename) {
                    var result = store.services()
                            .town(session.sceneRegistry().town())
                            .rename(store.accountId(), knight.id(), session.authRevision(), (String) a.getFirst());
                    store.refresh();
                    session.refreshKnight();
                    session.conversations().close();
                    close();
                    session.leaveChatForTown();
                    session.townChanged(result);
                    session.publishPartyState();
                    String name = session.selectedKnight().name();
                    responder.send(() -> responder.codec().knightName(responder.oid(), name));
                    responder.confirmed(listener.requestId());
                    // Official dV companion dY ends the session after a rename and returns to the
                    // login window carrying the new name, so the server closes it here.
                    session.requestRenameDisconnect();
                } else {
                    store.services()
                            .town(session.sceneRegistry().town())
                            .canRename(store.accountId(), knight.id(), session.authRevision());
                    responder.confirmed(listener.requestId());
                }
            } catch (IOException failure) {
                responder.failure(listener.requestId(), failure.getMessage());
            }
            return true;
        }
        // ItemMarshaller 1 is the stock Trash action [itemId, Confirm]; every persistent owned
        // non-permanent item is deletable, whole. Scene-item Use (method 14) stays separate.
        if (global && request.service() == SessionCodec.ITEM_SERVICE && request.method() == 1) {
            if (a.size() != 2
                    || !(a.getFirst() instanceof Long id)
                    || !(a.getLast() instanceof SessionDecoder.Listener listener)
                    || !listener.type().endsWith("$ConfirmMarshaller"))
                throw new SessionDecoder.Rejected("Invalid item deletion request");
            try {
                if (session.isClosed() || !session.selected() || !session.sceneEntered() || !session.sceneSubscribed())
                    throw new IOException("Select a knight first");
                session.loadOwnProfile();
                if (session.tradeHandler().active()) throw new IOException("Finish the trade first");
                if (!session.trashTemporary(id))
                    session.townChanged(store.services()
                            .town(session.sceneRegistry().town())
                            .trash(store.accountId(), knight.id(), session.authRevision(), id));
                responder.confirmed(listener.requestId());
            } catch (CharacterStore.Refused | IOException refused) {
                responder.failure(listener.requestId(), refused.getMessage());
            }
            return true;
        }
        boolean open = session.ownsScene(request.oid()) && request.service() == 11;
        boolean operation = session.ownsScene(request.oid()) && request.service() >= 100;
        boolean sprite = global && request.service() == SessionCodec.BATTLE_SPRITE_SERVICE;
        boolean hatch = global && request.service() == SessionCodec.ITEM_SERVICE && request.method() == 7;
        boolean wrap = global && request.service() == SessionCodec.ITEM_SERVICE && request.method() == 15;
        boolean unwrap = global && request.service() == SessionCodec.ITEM_SERVICE && request.method() == 13;
        boolean forge = global && request.service() == SessionCodec.ITEM_SERVICE && request.method() == 8;
        // The stock inventory's Use action sends method 14 [item, data, confirm].
        // Method 1 is deleteItem, and must never learn a recipe as a side effect.
        var profile = session.currentProfile();
        var rules = session.sceneRegistry().town();
        boolean use = global
                && request.service() == SessionCodec.ITEM_SERVICE
                && request.method() == 14
                && a.size() == 3
                && a.get(1) == null;
        boolean learn = use
                && a.getFirst() instanceof Long id
                && session.selected()
                && profile.items().stream()
                        .anyMatch(i -> i.id() == id && rules.item(i.config()).containsKey("recipe"));
        boolean harness = use
                && a.getFirst() instanceof Long id
                && session.selected()
                && profile.items().stream()
                        .anyMatch(i -> i.id() == id && rules.item(i.config()).containsKey("harness"));
        boolean extend = global && request.service() == SessionCodec.ITEM_SERVICE && request.method() == 5;
        boolean slotUpgrade = use
                && a.getFirst() instanceof Long id
                && session.selected()
                && profile.items().stream().anyMatch(i -> i.id() == id && SlotUpgrades.find(i.config()) != null);
        if (!open
                && !operation
                && !sprite
                && !hatch
                && !forge
                && !learn
                && !harness
                && !wrap
                && !unwrap
                && !slotUpgrade
                && !extend) return false;
        if (a.isEmpty() || !(a.getLast() instanceof SessionDecoder.Listener listener))
            throw new SessionDecoder.Rejected("Missing Town listener");
        boolean resultListener = hatch
                || forge
                || unwrap
                || operation
                        && (dialog == null
                                || !Set.of("OpenShop", "OpenHarnessInstaller").contains(dialog.kind()))
                || sprite && request.method() == 7;
        if (!listener.type()
                .equals(org.tortodrone.core.WireTypes.LISTENER_PREFIX
                        + (resultListener ? "ResultMarshaller" : "ConfirmMarshaller")))
            throw new SessionDecoder.Rejected("Invalid Town listener");
        try {
            if (forge) {
                // The stock Forge window sends this from any attached HUD with no scene
                // precondition (its button is disabled only during PvP), and its unselected
                // failure is the native internal error like every stock item reply.
                if (session.isClosed() || !session.selected()) throw new IOException("m.internal_error");
                if (session.inReadyRoom()) {
                    // The client-local Ready Room stages the knight with no party, so only the
                    // selected knight and the native forge rules are required; scenes keep
                    // their station gates below.
                    store.worldProfile(knight.name(), session.authRevision(), profile);
                    if (a.size() != 4
                            || !(a.get(0) instanceof Long item)
                            || !(a.get(1) instanceof Integer crystals)
                            || !(a.get(2) instanceof Integer level))
                        throw new SessionDecoder.Rejected("Invalid forge request");
                    if (session.tradeHandler().active()) throw new IOException("Finish the trade first");
                    var forged = store.services()
                            .town(rules)
                            .forge(store.accountId(), knight.id(), session.authRevision(), item, crystals, level);
                    session.townChanged(forged);
                    responder.result(listener.requestId(), forged.result(), forged.profile());
                    return true;
                }
            }
            if (session.isClosed() || !session.selected() || !session.sceneEntered() || !session.sceneSubscribed())
                throw new IOException("Enter a scene first");
            store.worldProfile(knight.name(), session.authRevision(), profile);
            boolean staff = session.gameMasterRole();
            var map = session.currentScene().map();
            if (!map.town() && !map.kind().equals("training") && !staff)
                throw new IOException("This operation requires a town");
            var town = store.services().town(rules);
            TownStore.Result result;
            var priorRecipes = profile.town().recipes();
            if (extend) {
                if (a.size() != 3 || !(a.get(0) instanceof Long active) || !(a.get(1) instanceof Long unused))
                    throw new SessionDecoder.Rejected("Invalid upgrade extension request");
                if (session.tradeHandler().active()) throw new IOException("Finish the trade first");
                result = town.extendUpgrade(store.accountId(), knight.id(), session.authRevision(), active, unused);
                session.townChanged(result);
                responder.confirmed(listener.requestId());
                return true;
            } else if (slotUpgrade) {
                if (session.tradeHandler().active()) throw new IOException("Finish the trade first");
                result = town.activateUpgrade(
                        store.accountId(), knight.id(), session.authRevision(), (Long) a.getFirst());
                session.townChanged(result);
                responder.confirmed(listener.requestId());
                return true;
            } else if (open) {
                if (!map.town() || a.size() != 1 || request.method() < 1 || request.method() > 4)
                    throw new IOException("Invalid town service");
                String kind = switch (request.method()) {
                    case 1 -> "OpenAccessorizer";
                    case 2 -> "OpenCrafter";
                    case 3 -> "OpenUnbinder";
                    default -> "OpenVariantMaker";
                };
                open(new TownCatalog.Station("", kind, "", "NORMAL", "~", "~", "", "", List.of()), null);
                responder.confirmed(listener.requestId());
                return true;
            } else if (operation) {
                if (dialog == null
                        || request.service() != service
                        || session.tradeHandler().active()) throw new IOException("Town dialog expired");
                if (dialog.kind().equals("OpenShop")) {
                    if (request.method() != 1
                            || a.size() != 3
                            || !(a.get(0) instanceof org.tortodrone.core.wire.Multiset buys)
                            || !(a.get(1) instanceof org.tortodrone.core.wire.Multiset sells)
                            || !buys.counts().keySet().stream().allMatch(k -> k instanceof Integer)
                            || !sells.counts().keySet().stream().allMatch(k -> k instanceof Long))
                        throw new SessionDecoder.Rejected("Invalid shop transaction");
                    var purchases = new HashMap<Integer, Integer>();
                    buys.counts().forEach((key, value) -> purchases.put((Integer) key, value));
                    var sales = new HashMap<Long, Integer>();
                    sells.counts().forEach((key, value) -> sales.put((Long) key, value));
                    result = town.shop(
                            store.accountId(),
                            knight.id(),
                            session.authRevision(),
                            dialog,
                            shopCycle,
                            purchases,
                            sales);
                } else if (dialog.kind().equals("OpenHarnessInstaller")) {
                    if (request.method() != 1
                            || a.size() != 2
                            || !(a.getFirst() instanceof List<?> indices)
                            || indices.isEmpty()
                            || indices.size() > 100
                            || !indices.stream().allMatch(v -> v instanceof Integer))
                        throw new SessionDecoder.Rejected("Invalid harness installation");
                    var selected = new HashSet<Integer>();
                    for (var index : indices)
                        if (!selected.add((Integer) index)) throw new IOException("Duplicate harness offer");
                    result = town.installHarnesses(
                            store.accountId(), knight.id(), session.authRevision(), dialog, shopCycle, selected);
                } else if (dialog.kind().equals("OpenUnbinder")) {
                    if (request.method() != 1
                            || a.size() != 3
                            || !(a.get(0) instanceof Long)
                            || !(a.get(1) instanceof Long)) throw new SessionDecoder.Rejected("Invalid unbind request");
                    result = town.unbind(
                            store.accountId(), knight.id(), session.authRevision(), (Long) a.get(0), (Long) a.get(1));
                } else if (dialog.kind().equals("OpenAccessorizer")) {
                    if (a.size() != 3
                            || !(a.get(0) instanceof Long gear)
                            || request.method() < 1
                            || request.method() > 3
                            || !(request.method() == 2
                                    ? a.get(1) instanceof org.tortodrone.core.wire.EnumValue e
                                            && e.type()
                                                    .equals(
                                                            "com.threerings.projectx.item.data.ItemCodes$AccessoryLocation")
                                    : a.get(1) instanceof Long))
                        throw new SessionDecoder.Rejected("Invalid accessory request");
                    result = town.accessorize(
                            store.accountId(),
                            knight.id(),
                            session.authRevision(),
                            request.method(),
                            gear,
                            a.get(1),
                            knight.costume());
                } else if (dialog.kind().equals("OpenVariantMaker")) {
                    boolean custom = request.method() == 1;
                    int offset = custom ? 0 : 1;
                    if (request.method() < 1
                            || request.method() > 2
                            || a.size() != (custom ? 4 : 5)
                            || !custom && !(a.get(0) instanceof Integer)
                            || !(a.get(offset) instanceof Long)
                            || !(a.get(offset + 1) instanceof Long)
                            || !(a.get(offset + 2) instanceof Integer))
                        throw new SessionDecoder.Rejected("Invalid variant request");
                    result = town.variant(
                            store.accountId(),
                            knight.id(),
                            session.authRevision(),
                            custom ? 1 : (Integer) a.get(0),
                            (Long) a.get(offset),
                            (Long) a.get(offset + 1),
                            (Integer) a.get(offset + 2),
                            custom);
                } else if (dialog.kind().equals("OpenCrafter")
                        || dialog.kind().equals("OpenCatalogCrafter")
                        || dialog.kind().equals("OpenGuildCatalogCrafter")) {
                    if (a.size() != 4 || !(a.get(1) instanceof long[] ids) || !(a.get(2) instanceof Boolean upgrade))
                        throw new SessionDecoder.Rejected("Invalid craft request");
                    String recipe;
                    boolean catalog = request.method() == 1;
                    if (catalog) {
                        if (!(a.get(0) instanceof Integer index)
                                || index < 0
                                || index >= dialog.recipes().size()) throw new IOException("Invalid recipe index");
                        recipe = dialog.recipes().get(index);
                    } else {
                        if (request.method() != 2 || !(a.get(0) instanceof Long id))
                            throw new SessionDecoder.Rejected("Invalid recipe request");
                        recipe = profile.town().recipes().stream()
                                .filter(r -> r.id() == id)
                                .map(org.tortodrone.core.town.TownState.LearnedRecipe::config)
                                .findFirst()
                                .orElseThrow(() -> new IOException("Recipe not learned"));
                        if (!dialog.recipes().isEmpty() && !dialog.recipes().contains(recipe))
                            throw new IOException("Recipe not served here");
                    }
                    result = town.craft(
                            store.accountId(), knight.id(), session.authRevision(), recipe, ids, upgrade, catalog);
                } else throw new IOException("This station's operation is unavailable");
            } else if (wrap || unwrap) {
                if (session.tradeHandler().active()) throw new IOException("Finish the trade first");
                if (unwrap) {
                    if (a.size() != 2 || !(a.getFirst() instanceof Long id))
                        throw new SessionDecoder.Rejected("Invalid gift unwrap request");
                    result = town.unwrap(store.accountId(), knight.id(), session.authRevision(), id);
                } else {
                    if (a.size() != 4
                            || !(a.get(0) instanceof Long paper)
                            || !(a.get(1) instanceof org.tortodrone.core.wire.Multiset contents)
                            || !(a.get(2) instanceof String message)
                            || !contents.counts().keySet().stream().allMatch(k -> k instanceof Long))
                        throw new SessionDecoder.Rejected("Invalid gift wrap request");
                    var selected = new LinkedHashMap<Long, Integer>();
                    contents.counts().forEach((key, count) -> selected.put((Long) key, count));
                    result = town.wrap(
                            store.accountId(),
                            knight.id(),
                            session.authRevision(),
                            paper,
                            selected,
                            message,
                            knight.costume());
                }
            } else if (forge) {
                if (a.size() != 4
                        || !(a.get(0) instanceof Long item)
                        || !(a.get(1) instanceof Integer crystals)
                        || !(a.get(2) instanceof Integer level))
                    throw new SessionDecoder.Rejected("Invalid forge request");
                if (session.tradeHandler().active()) throw new IOException("Finish the trade first");
                result = town.forge(store.accountId(), knight.id(), session.authRevision(), item, crystals, level);
            } else if (hatch || learn || harness) {
                if (a.size() != (hatch ? 2 : 3) || !(a.get(0) instanceof Long id))
                    throw new SessionDecoder.Rejected("Invalid Town item request");
                if (session.tradeHandler().active()) throw new IOException("Finish the trade first");
                result = hatch
                        ? town.hatch(store.accountId(), knight.id(), session.authRevision(), id)
                        : harness
                                ? town.installHarness(store.accountId(), knight.id(), session.authRevision(), id)
                                : town.learn(store.accountId(), knight.id(), session.authRevision(), id);
            } else {
                int method = request.method();
                int[] counts = {0, 4, 6, 3, 2, 3, 3, 4, 3, 4};
                if (method < 1 || method > 9 || a.size() != counts[method])
                    throw new SessionDecoder.Rejected("Invalid sprite argument count");
                boolean valid = switch (method) {
                    case 1 -> a.get(0) instanceof Integer && a.get(1) instanceof Integer && a.get(2) instanceof Integer;
                    case 2 -> a.subList(0, 5).stream().allMatch(v -> v instanceof Integer);
                    case 3 ->
                        a.get(0) instanceof Integer
                                && (a.get(1) == null
                                        || a.get(1) instanceof org.tortodrone.core.wire.EnumValue e
                                                && e.type().equals(org.tortodrone.core.WireTypes.SPRITE_PERK));
                    case 4 -> a.get(0) instanceof Integer;
                    case 5 -> a.get(0) instanceof Integer && a.get(1) instanceof Long;
                    case 6 -> a.get(0) instanceof Long && a.get(1) instanceof Integer;
                    case 7 -> a.get(0) instanceof Integer && a.get(1) instanceof Long && a.get(2) instanceof Boolean;
                    case 8 -> a.get(0) instanceof Integer && a.get(1) instanceof String;
                    case 9 ->
                        a.get(0) instanceof Integer
                                && a.get(1) instanceof Integer
                                && a.get(2) instanceof org.tortodrone.core.wire.EnumValue e
                                && e.type().equals(org.tortodrone.core.WireTypes.SPRITE_ULTIMATE);
                    default -> false;
                };
                if (!valid) throw new SessionDecoder.Rejected("Invalid sprite arguments");
                result = town.sprite(store.accountId(), knight.id(), session.authRevision(), method, a, staff);
            }
            // Learning publishes its committed recipe through EntryAddedEvent below. Replacing
            // recipes first makes the stock DSet reject that addition and suppress its observer.
            if (learn) session.recipeLearnedEffect();
            session.townChanged(result, !learn);
            if (operation
                    && dialog != null
                    && Set.of("OpenShop", "OpenHarnessInstaller").contains(dialog.kind())) publishShop();
            Object payload = result.result();
            if (learn) {
                var fresh = result.profile().town().recipes().stream()
                        .filter(r -> priorRecipes.stream().noneMatch(p -> p.id() == r.id()))
                        .toList();
                for (var recipe : fresh) {
                    var entry = recipe;
                    responder.send(() -> responder.codec().recipeAdded(responder.oid(), entry));
                }
            }
            if (hatch && payload instanceof Integer id)
                payload = session.currentProfile().town().sprites().stream()
                        .filter(s -> s.id() == id)
                        .findFirst()
                        .orElseThrow();
            final Object response = payload;
            if (resultListener) responder.result(listener.requestId(), response, session.currentProfile());
            else responder.confirmed(listener.requestId());
        } catch (CharacterStore.Refused | IOException refused) {
            if (refused instanceof SessionDecoder.Rejected rejected) throw rejected;
            if (forge)
                responder.send(() ->
                        responder.codec().forgeFailure(responder.oid(), listener.requestId(), refused.getMessage()));
            else responder.failure(listener.requestId(), refused.getMessage());
        }
        return true;
    }
}
