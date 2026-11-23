package org.tortodrone.server;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Local-console contract for WebUI identities.
 *
 * <p>The server package owns this seam so the optional {@code web} package can depend on it
 * without a reverse import, and the terminal can still list, create, reset and revoke WebUI
 * accounts while the WebUI itself is disabled. A shaded deployment provides the
 * implementation through {@link WebFrontend.Factory#identities(Path)}.
 */
public interface WebIdentities {
    enum Role { VIEWER, MODERATOR, ADMINISTRATOR }
    record Identity(String username,Role role,long revision) { }

    List<Identity> list()throws IOException;
    Identity find(String username)throws IOException;

    /** Privileged local-console replacement; previous sessions are revoked. */
    void put(String username,char[] password,Role role)throws IOException;

    /** Privileged local-console password reset; previous sessions are revoked. */
    void password(String username,char[] password,long revision)throws IOException;

    /** Local access can recover even after intentionally revoking the last administrator. */
    void revoke(String username)throws IOException;
}
