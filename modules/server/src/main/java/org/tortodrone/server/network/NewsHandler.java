package org.tortodrone.server.network;

import java.io.IOException;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.protocol.SessionDecoder;

/** Read-only Uplink news and social-feed requests for one connection. */
final class NewsHandler {
    private final WorldSession session;
    private final SessionResponder responder;

    NewsHandler(WorldSession session) {
        this.session = session;
        this.responder = session.responder();
    }

    boolean invocation(SessionDecoder.Invocation request) throws IOException {
        synchronized (session) {
            int service = request.service(), method = request.method();
            var args = request.arguments();
            if (service == SessionCodec.UPLINK_SERVICE && method == 7) {
                if (args.size() != 2
                        || !(args.get(0) instanceof String)
                        || !(args.get(1) instanceof SessionDecoder.Listener listener)
                        || !listener.type().endsWith("$ResultMarshaller"))
                    throw new SessionDecoder.Rejected("Invalid Uplink News request");
                if (session.selected()) {
                    var news = session.sceneRegistry().news();
                    responder.send(() ->
                            responder.codec().listResult(responder.oid(), listener.requestId(), news.propertyList()));
                } else responder.failure(listener.requestId(), "Select a knight first");
                return true;
            }
            if (service == SessionCodec.FEED_SERVICE && method == 1) {
                if (args.size() != 2
                        || !(args.get(0) instanceof Long)
                        || !(args.get(1) instanceof SessionDecoder.Listener listener)
                        || !listener.type().endsWith("$ResultMarshaller"))
                    throw new SessionDecoder.Rejected("Invalid social feed request");
                if (session.selected())
                    responder.send(() -> responder.codec().emptyListResult(responder.oid(), listener.requestId()));
                else responder.failure(listener.requestId(), "Select a knight first");
                return true;
            }
            return false;
        }
    }
}
