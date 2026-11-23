package org.tortodrone.server.network;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.wire.SteamFriend;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;
import org.tortodrone.world.SceneRegistry;

/** Character roster service for one connection: name validation, creation, selection, editing
 *  and deletion. These requests arrive before world entry and are the router's fallback; they
 *  validate arguments and answer through the native confirmation/failure listener. */
final class CharacterHandler {
    static final String SAVED_WITHOUT_WORLD = "Knight saved. World entry requires a scene receiver.";
    static final String UPDATED_WITHOUT_WORLD = "Knight updated. World entry requires a scene receiver.";

    private CharacterHandler() {}

    static void handle(
            SessionOutput output,
            SessionCodec outgoing,
            SessionDecoder.Invocation invocation,
            int clientOid,
            SessionLog log,
            CharacterStore characters,
            boolean subscribed,
            long authRevision,
            WorldSession world,
            Map<String, Short> receivers)
            throws IOException {
        var args = invocation.arguments();
        if (args.isEmpty()
                || !(args.get(args.size() - 1) instanceof SessionDecoder.Listener listener)
                || !listener.type().endsWith("$ConfirmMarshaller")) {
            throw new SessionDecoder.Rejected("Missing character confirmation listener");
        }
        String failure = null;
        if (invocation.method() == 3) {
            try {
                failure = args.size() == 2 && args.get(0) instanceof String name
                        ? characters.nameFailure(name)
                        : "Invalid name validation request";
            } catch (IOException unavailable) {
                failure = "Knight name could not be checked; try again";
            }
        } else if (invocation.method() == 4) {
            if (args.size() != 3 || !(args.get(0) instanceof String name) || !optionalString(args.get(1))) {
                failure = "Invalid character selection request";
            } else if (characters.roster().stream()
                    .noneMatch(knight -> knight.name().equalsIgnoreCase(name))) {
                failure = "No such knight";
            } else {
                if (!receivers.containsKey(SceneRegistry.ZONE_RECEIVER))
                    failure = "World entry requires a scene receiver";
                else
                    try {
                        world.select(name, receivers, true);
                    } catch (CharacterStore.Refused | IOException refused) {
                        failure = refused.getMessage();
                    }
            }
        } else if (invocation.method() == 6) {
            if (args.size() != 2 || !(args.get(0) instanceof String name)) {
                failure = "Invalid character deletion request";
            } else {
                try {
                    synchronized (characters) {
                        if (characters.roster().stream()
                                .anyMatch(k -> k.name().equalsIgnoreCase(name)
                                        && SessionDirectory.active(characters.accountId(), k.id())))
                            throw new IOException("Knight is online; disconnect before deleting it");
                        characters.delete(name, authRevision);
                    }
                } catch (CharacterStore.Refused e) {
                    failure = e.getMessage();
                } catch (IOException e) {
                    log.warn("Character deletion failed; " + e.getClass().getSimpleName());
                    failure = "Character could not be deleted; try again";
                }
                if (failure == null) {
                    if (!subscribed) {
                        var roster = characters.available();
                        output.send(() -> outgoing.rosterChanged(clientOid, roster));
                    }
                    log.info("Character deleted; accountId=" + characters.accountId() + "; knight=" + args.get(0)
                            + "; available knights=" + characters.roster().size());
                }
            }
        } else {
            if (args.size() != 14
                    || !optionalString(args.get(0))
                    || !(args.get(1) instanceof String)
                    || !(args.get(2) instanceof String)
                    || !(args.get(3) instanceof String)
                    || !optionalString(args.get(4))
                    || !optionalString(args.get(5))
                    || !(args.get(6) instanceof String)
                    || !(args.get(7) instanceof Integer)
                    || !enumeration(args.get(8), "KnightHeight")
                    || !enumeration(args.get(9), "EyeType")
                    || !optionalString(args.get(10))
                    || !(args.get(11) instanceof List<?> friends)
                    || !friends.stream().allMatch(SteamFriend.class::isInstance)
                    || !optionalString(args.get(12))) {
                failure = "Invalid character save request";
            } else {
                // Steam persona, friend hints and SteamID are advisory client reports. Accounts
                // stay username/password and creation must not depend on the player's Steam list.
                try {
                    Knight choices = new Knight(
                            0,
                            (String) args.get(1),
                            (String) args.get(2),
                            (String) args.get(3),
                            (String) args.get(4),
                            (String) args.get(5),
                            (String) args.get(6),
                            (Integer) args.get(7),
                            ((org.tortodrone.core.wire.EnumValue) args.get(8)).ordinal(),
                            ((org.tortodrone.core.wire.EnumValue) args.get(9)).ordinal(),
                            0);
                    if (args.get(0) == null) {
                        if (!world.openingAvailable())
                            throw new IOException("The opening adventure is unavailable; please try again later");
                        characters.createForOpening(choices, authRevision);
                    } else {
                        String old = (String) args.get(0);
                        synchronized (characters) {
                            if (characters.roster().stream()
                                    .anyMatch(k -> k.name().equalsIgnoreCase(old)
                                            && SessionDirectory.active(characters.accountId(), k.id())))
                                throw new IOException("Knight is online; disconnect before editing it");
                            characters.update(old, choices, authRevision);
                        }
                    }
                } catch (CharacterStore.Refused e) {
                    failure = e.getMessage();
                } catch (IOException e) {
                    log.warn("Character save failed; " + e.getClass().getSimpleName());
                    failure = "Character could not be saved; try again";
                }
                if (failure == null) {
                    if (!subscribed) {
                        var roster = characters.available();
                        output.send(() -> outgoing.rosterChanged(clientOid, roster));
                    }
                    var savedName = (String) args.get(1);
                    var saved = characters.roster().stream()
                            .filter(k -> k.name().equalsIgnoreCase(savedName))
                            .findFirst();
                    log.info("Character saved; accountId=" + characters.accountId() + "; knight=" + savedName
                            + "; rosterId="
                            + (saved.isEmpty() ? "unavailable" : saved.get().id()) + "; available knights="
                            + characters.roster().size());
                    try {
                        if (characters.hasWorldStorage())
                            log.info("Knight stored; knightId="
                                    + characters
                                            .worldProfile(savedName, authRevision)
                                            .worldId());
                    } catch (IOException | CharacterStore.Refused unavailable) {
                        log.debug("Stored knight identity unavailable after save; "
                                + unavailable.getClass().getSimpleName());
                    }
                    // The stock creator waits for selection and scene events after success.
                    if (characters.hasWorldStorage() && receivers.containsKey(SceneRegistry.ZONE_RECEIVER)) {
                        try {
                            world.select((String) args.get(1), receivers);
                        } catch (CharacterStore.Refused | IOException refused) {
                            failure = refused.getMessage();
                        }
                    } else failure = args.get(0) == null ? SAVED_WITHOUT_WORLD : UPDATED_WITHOUT_WORLD;
                }
            }
        }
        String result = failure;
        output.send(() -> result == null
                ? outgoing.invocationConfirmed(clientOid, listener.requestId())
                : outgoing.invocationFailure(clientOid, listener.requestId(), result));
    }

    private static boolean optionalString(Object value) {
        return value == null || value instanceof String;
    }

    private static boolean enumeration(Object value, String name) {
        return value instanceof org.tortodrone.core.wire.EnumValue enumeration
                && enumeration.type().equals("com.threerings.projectx.data.ProjectXCodes$" + name);
    }
}
