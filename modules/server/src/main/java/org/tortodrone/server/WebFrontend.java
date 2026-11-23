package org.tortodrone.server;

import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ServiceLoader;
import org.tortodrone.account.AccountStore;
import org.tortodrone.core.config.ServerSettings;
import org.tortodrone.resources.ContentRegistry;

/** Composition seam for the optional WebUI.
 *
 * <p>The server package owns this contract, so the {@code web} package depends on it without
 * a reverse import from the server and the package cycle is gone. A shaded deployment
 * registers exactly one {@link Factory} through {@code META-INF/services}.
 */
public interface WebFrontend extends AutoCloseable {
    /** Handler mounted on the shared News listener. */
    HttpHandler handler();

    /** One-time first-run code, or null when an administrator already exists. */
    String setupCode();

    @Override void close();

    interface Factory {
        WebFrontend create(AccountStore accounts,ServerControl control,ServerLog log,Path deployment,
                Path config,ContentRegistry content,ServerSettings settings)throws IOException;

        /** Local-console identity access, available even when the WebUI listener is disabled. */
        WebIdentities identities(Path data)throws IOException;
    }

    /** Load the single registered frontend; a missing provider is a broken build, not a
     *  silently disabled WebUI. */
    static WebFrontend create(AccountStore accounts,ServerControl control,ServerLog log,Path deployment,
            Path config,ContentRegistry content,ServerSettings settings)throws IOException {
        return factory().create(accounts,control,log,deployment,config,content,settings);
    }

    /** Local-console identity access through the same single provider. */
    static WebIdentities identities(Path data)throws IOException {
        return factory().identities(data);
    }

    private static Factory factory()throws IOException {
        var factories=ServiceLoader.load(Factory.class,WebFrontend.class.getClassLoader()).stream().toList();
        if(factories.size()!=1)throw new IOException(factories.isEmpty()
            ? "WebUI implementation is missing from this build"
            : "Multiple WebUI implementations are registered");
        return factories.getFirst().get();
    }
}
