package org.tortodrone.server.network;

import java.io.IOException;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;

/** KnightMarshaller commands that belong to the connection rather than a scene service:
 *  the Ready Room return request and the inspect-privacy toggle. Validation stays here; the
 *  session still owns the ready-room flag, the scene and the player-flags publication. */
final class KnightCommandHandler {
    private final WorldSession session;
    private final SessionResponder responder;

    KnightCommandHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
    }

    /** KnightMarshaller 11 is the stock return-to-last request (used by Ready Room return);
     *  method 13 is the inspect-privacy toggle. */
    boolean invocation(SessionDecoder.Invocation request) throws IOException {
        synchronized (session) {
            int service = request.service(), method = request.method();
            if (service != SessionCodec.KNIGHT_SERVICE) return false;
            var args = request.arguments();
            if (method == 11) {
                if (!args.isEmpty()) throw new SessionDecoder.Rejected("Invalid return-to-last request");
                if (!session.selected() || session.forcedReceiverValue() == null)
                    throw new SessionDecoder.Rejected("Select a knight first");
                try {
                    session.charactersStore()
                            .worldProfile(
                                    session.selectedKnight().name(), session.authRevision(), session.currentProfile());
                } catch (CharacterStore.Refused refused) {
                    throw new IOException("Knight changed; reconnect", refused);
                }
                if (session.inReadyRoom()) session.requestReturnToLast();
                return true;
            }
            if (method == 13) {
                if (args.size() != 1 || !(args.getFirst() instanceof Boolean hidden))
                    throw new SessionDecoder.Rejected("Invalid inspect privacy request");
                session.setInspectionHidden(hidden);
                return true;
            }
            return false;
        }
    }
}
