package org.tortodrone.web;

import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.file.Path;
import org.tortodrone.account.AccountStore;
import org.tortodrone.core.config.ServerSettings;
import org.tortodrone.resources.ContentRegistry;
import org.tortodrone.server.ServerControl;
import org.tortodrone.server.ServerLog;
import org.tortodrone.server.WebFrontend;
import org.tortodrone.server.WebIdentities;

/** ServiceLoader entry for the optional WebUI; owns the admin service for the process. */
public final class AdminFrontend implements WebFrontend,WebFrontend.Factory {
    private final AdminService service;
    private final AdminHandler handler;
    private final String setupCode;

    /** ServiceLoader instantiation only. */
    public AdminFrontend() { this(null,null,null); }

    private AdminFrontend(AdminService service,AdminHandler handler,String setupCode) {
        this.service=service;this.handler=handler;this.setupCode=setupCode;
    }

    @Override public WebFrontend create(AccountStore accounts,ServerControl control,ServerLog log,Path deployment,
            Path data,Path config,ContentRegistry content,ServerSettings settings)throws IOException {
        var service=new AdminService(accounts,control,log,deployment,content,config);
        var identities=new WebIdentityStore(data);
        var security=new WebSecurity(settings.webui(),identities,data);
        var handler=new AdminHandler(security,identities,service,deployment.resolve("webui"));
        return new AdminFrontend(service,handler,security.setupCode());
    }

    @Override public WebIdentities identities(Path data)throws IOException {
        return new WebIdentityStore(data);
    }

    @Override public HttpHandler handler() { return handler; }

    @Override public String setupCode() { return setupCode; }

    @Override public void close() {
        if(service==null)return;
        try { service.close(); }
        catch(Exception failure) { throw new IllegalStateException("WebUI shutdown failed",failure); }
    }
}
