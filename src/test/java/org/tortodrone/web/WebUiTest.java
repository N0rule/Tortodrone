package org.tortodrone.web;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.tortodrone.account.AccountStore;
import org.tortodrone.character.ItemCatalog;
import org.tortodrone.resources.Json;
import org.tortodrone.server.*;
import static org.tortodrone.support.TestWire.check;

/** Real single-listener HTTP requests, disposable identities and game data only. */
public final class WebUiTest {
    private static final String PASSWORD="disposable-web-password";
    private static String secret,cookie,csrf;
    private static int port;
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("tortodrone-webui-");
        try {
            Path data=root.resolve("data"),news=root.resolve("content/shared/news"),frontend=root.resolve("webui");
            Files.createDirectories(data);Files.createDirectories(news);Files.createDirectories(frontend.resolve("assets"));
            Files.write(news.resolve("public.png"),new byte[]{1,2,3});Files.writeString(frontend.resolve("index.html"),"<html>Administration</html>");
            Files.createDirectories(frontend.resolve("assets/js/pages"));
            Files.writeString(frontend.resolve("assets/js/pages/fixture.js"),"export const fixture = true;");
            Files.writeString(frontend.resolve("assets/fixture.css"),"body { color: blue; }");
            Files.writeString(frontend.resolve("assets/manifest.json"),"{}");
            Files.write(frontend.resolve("assets/fixture.webp"),new byte[]{1,2,3});
            WebIdentityStore.createProxyKey(data.resolve("webui-proxy.key"));secret=Files.readString(data.resolve("webui-proxy.key")).strip();
            if(Files.getFileStore(data).supportsFileAttributeView("posix"))check(Files.getPosixFilePermissions(data.resolve("webui-proxy.key")).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")),"proxy secret private from creation");
            try{WebIdentityStore.createProxyKey(data.resolve("webui-proxy.key"));throw new AssertionError("Existing proxy key overwritten");}catch(FileAlreadyExistsException expected){}
            check(secret.equals(Files.readString(data.resolve("webui-proxy.key")).strip()),"existing secret preserved");
            firstRunSetup(root,news,frontend);
            var identities=new WebIdentityStore(data);
            identities.put("administrator",PASSWORD.toCharArray(),WebIdentityStore.Role.ADMINISTRATOR);
            identities.put("viewer",PASSWORD.toCharArray(),WebIdentityStore.Role.VIEWER);
            identities.put("moderator",PASSWORD.toCharArray(),WebIdentityStore.Role.MODERATOR);
            check(identities.authenticate("administrator","incorrect-password".toCharArray())==null,"wrong password rejected");
            var settings=new WebSettings(true,"https://admin.example","reverse-proxy");
            var clock=new AtomicLong(System.currentTimeMillis());
            var security=new WebSecurity(settings,identities,data,clock::get);
            var control=new ServerControl("test",data);
            try(var accounts=new AccountStore(data,false,ItemCatalog.starter());var log=new ServerLog(data.resolve("logs"));var service=new AdminService(accounts,control,log,root)){
                var handler=new AdminHandler(security,identities,service,frontend);
                try(var http=NewsServer.start(InetAddress.getLoopbackAddress(),0,news,x->{},handler)){
                    port=http.port();
                    check(request("GET","/news/public.png",null,Map.of()).status==200,"News public on shared listener");
                    check(request("GET","/admin/",null,Map.of()).status==403,"direct HTTP refused");
                    check(request("GET","/admin/",null,headers()).status==200,"trusted frontend");
                    var module=request("GET","/admin/assets/js/pages/fixture.js",null,headers());
                    check(module.status==200&&module.headers.get("content-type").startsWith("text/javascript"),"nested modules have JavaScript MIME");
                    var conditional=headers();conditional.put("If-None-Match",module.headers.get("etag"));
                    check(request("GET","/admin/assets/js/pages/fixture.js",null,conditional).status==304,"static assets revalidate by content hash");
                    String replacement="export const fixture = false;";
                    Path moduleFile=frontend.resolve("assets/js/pages/fixture.js");
                    var modified=Files.getLastModifiedTime(moduleFile);
                    Files.writeString(moduleFile,replacement);Files.setLastModifiedTime(moduleFile,modified);
                    var changed=request("GET","/admin/assets/js/pages/fixture.js",null,conditional);
                    String expectedTag="\""+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(replacement.getBytes(StandardCharsets.UTF_8)))+"\"";
                    check(changed.status==200&&changed.body.equals(replacement)&&changed.headers.get("etag").equals(expectedTag),"response hash follows exact served bytes even with unchanged modification time");
                    check(request("GET","/admin/assets/missing.js",null,headers()).status==404,"missing static asset returns not found");
                    Files.write(frontend.resolve("assets/empty.css"),new byte[0]);
                    var empty=request("GET","/admin/assets/empty.css",null,headers());
                    check(empty.status==200&&empty.body.isEmpty(),"empty assets finish without a chunked response body");
                    Files.write(frontend.resolve("assets/large.json"),new byte[2*1024*1024+1]);
                    check(request("GET","/admin/assets/large.json",null,headers()).status==409,"oversized assets are refused");
                    check(request("GET","/admin/assets/fixture.webp",null,headers()).headers.get("content-type").equals("image/webp"),"optimized image MIME");
                    check(request("GET","/admin/assets/manifest.json",null,headers()).status==200,"icon manifest served");
                    if(Files.getFileStore(frontend).supportsFileAttributeView("posix")) {
                        Files.writeString(root.resolve("private.json"),"private fixture");Files.createSymbolicLink(frontend.resolve("assets/escape.json"),root.resolve("private.json"));
                        check(request("GET","/admin/assets/escape.json",null,headers()).status!=200,"static assets cannot escape by symlink");
                    }
                    var bad=headers();bad.put("X-Tortodrone-Proxy","wrong");check(request("GET","/admin/",null,bad).status==403,"forged proxy rejected");
                    bad=headers();bad.put("X-Forwarded-For","127.0.0.1");check(request("GET","/admin/",null,bad).status==403,"untrusted forwarded chain rejected");
                    bad=headers();bad.put("Host","evil.example");check(request("GET","/admin/",null,bad).status==403,"host rejected");
                    check(get("status").status==401,"API requires authentication");
                    bad=headers();bad.put("Origin","https://evil.example");check(request("POST","/admin/api/login",form(Map.of("username","administrator","password",PASSWORD)),bad).status==403,"cross-origin login rejected");
                    login("administrator");
                    check(get("session").status==200,"server-side session");check(get("status").status==200,"dashboard DTO serialization");
                    check(get("console").status==200&&post("console",Map.of("command","help","confirmation","console")).body.contains("kick <session-id>"),"administrator console help uses the allowed vocabulary");
                    check(post("console",Map.of("command","status","confirmation","console")).status==200,"console reads typed server status");
                    check(post("console",Map.of("command","stop","confirmation","console")).status==400,"console actions require explicit command confirmation");
                    check(post("console",Map.of("command","shutdown","commandConfirmation","shutdown","confirmation","console")).status==400,"console full shutdown requires its own phrase");
                    for(String command:List.of("help; stop","status\nstop","account password operator secret","../secret","kick -1"))
                        check(post("console",Map.of("command",command,"commandConfirmation",command,"confirmation","console")).status==400,"console refuses unsupported syntax and unsafe arguments");
                    var create=Map.of("username","Alice","password","game-password","gm","true","confirmation","account-create");
                    bad=headers();bad.put("Cookie",cookie);check(request("POST","/admin/api/account-create",form(create),bad).status==403,"CSRF required");
                    check(post("account-create",create).status==200,"authorized account creation");
                    int account=accounts.list().getFirst().id();
                    check(post("account-gm",Map.of("username","Alice","accountId",account+1,"gm",false,"confirmation","account-gm")).status==409,"stale account identity rejected");
                    check(accounts.list().getFirst().gameMaster(),"failed modification rolled back");
                    check(post("account-delete",Map.of("username","Alice","accountId",account,"confirmation","account-delete","nameConfirmation","wrong")).status==400,"destructive name confirmation required");
                    check(get("../assets/app.js").status==404,"traversal rejected");
                    check(request("GET","/admin/%2e%2e/data/webui.sqlite",null,authenticated()).status==404,"encoded traversal rejected");
                    check(request("GET","/admin/assets/secret.key",null,authenticated()).status==404,"static allowlist");
                    var welcome=(Map<?,?>)Json.parse(get("welcome").body);
                    check(welcome.get("message").equals(org.tortodrone.config.ServerSettings.DEFAULT_WELCOME_MESSAGE),"welcome setting is readable");
                    check(post("welcome-save",Map.of("message","Welcome, knight!","revision",welcome.get("revision"),"confirmation","welcome-save")).status==200&&Files.readString(root.resolve("config/server.yml")).contains("welcome-message: 'Welcome, knight!'"),"welcome setting writes server.yml");
                    var latestWelcome=(Map<?,?>)Json.parse(get("welcome").body);
                    var invalidWelcome=post("welcome-save",Map.of("message","x".repeat(257),"revision",latestWelcome.get("revision"),"confirmation","welcome-save"));
                    check(invalidWelcome.status==422&&invalidWelcome.body.contains("at most 256 characters"),"welcome bounds reach the browser without filesystem detail");
                    check(post("account-create",Map.of("padding","x".repeat(66000))).status==400,"request size bounded");
                    check(post("logout",Map.of()).status==200&&get("status").status==401,"logout revokes session");
                    login("viewer");check(get("console").status==403&&post("console",Map.of("command","status","confirmation","console")).status==403,"viewer cannot access console");check(get("status").status==200&&get("accounts").status==403,"viewer monitoring only");check(post("stop",Map.of("confirmation","stop")).status==403,"viewer cannot stop listener");
                    check(get("metrics").status==200&&get("catalogue").status==403&&get("knights-all").status==403,"new reads preserve role boundaries");
                    check(post("news-upload",Map.of("name","fixture.png","data","AA==","confirmation","news-upload")).status==403,"image uploads require administrator permission");
                    login("moderator");check(get("console").status==403&&post("console",Map.of("command","help","confirmation","console")).status==403,"moderator cannot access console");check(get("accounts").status==200&&get("depot").status==403,"moderator scope");check(post("account-create",create).status==403,"moderator cannot create account");
                    check(get("catalogue").status==200&&get("knights-all").status==200,"moderator read-only inspection");
                    clock.addAndGet(31*60_000);check(get("status").status==401,"idle session expires");
                    login("administrator");identities.put("administrator",PASSWORD.toCharArray(),WebIdentityStore.Role.ADMINISTRATOR);check(get("status").status==401,"credential reset revokes sessions");
                    login("administrator");identities.revoke("administrator");identities.put("administrator",PASSWORD.toCharArray(),WebIdentityStore.Role.ADMINISTRATOR);check(get("status").status==401,"delete and recreate cannot resurrect old sessions");
                    login("administrator");
                    for(int i=0;i<16;i++){clock.addAndGet(29*60_000);check(get("status").status==200,"active session before absolute expiry");}
                    clock.addAndGet(17*60_000);check(get("status").status==401,"absolute eight-hour expiry despite activity");
                    check(!identities.audit().isEmpty(),"persistent action audit");
                    for(int i=0;i<12;i++)security.allowLogin();check(!security.allowLogin(),"bounded global login rate");
                }
                httpMode(root,news,frontend,identities,service);
                automaticHttpMode(root,news,frontend,identities,service);
                cookie=null;csrf=null;
                try(var http=NewsServer.start(InetAddress.getLoopbackAddress(),org.tortodrone.config.ServerSettings.DEFAULT_NEWS_PORT,news,x->{})){
                    port=http.port();check(port==8080&&request("GET","/news/public.png",null,Map.of()).status==200,"public News on default port 8080");
                }catch(java.net.BindException occupied){System.out.println("SKIP: port 8080 already occupied; default configuration and ephemeral listener verified");}
                try(var http=NewsServer.start(InetAddress.getLoopbackAddress(),0,news,x->{})){
                    port=http.port();check(request("GET","/news/public.png",null,Map.of()).status==200,"News with WebUI disabled");check(request("GET","/admin/",null,headers()).status==404,"disabled admin routes absent");
                }
            }
            try{new WebSettings(true,"http://remote.example","reverse-proxy");throw new AssertionError("plaintext origin accepted");}catch(IllegalArgumentException expected){}
            try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+data.resolve("webui.sqlite"));var s=c.createStatement()){s.execute("PRAGMA user_version=999");}
            try{new WebIdentityStore(data);throw new AssertionError("unknown schema accepted");}catch(IOException expected){}
            System.out.println("PASS: shared News, opt-in direct HTTP and default HTTPS proxy, Host/Origin/CSRF, roles, cookies, revocation, expiration, audit and rollback");
        }finally{try(var walk=Files.walk(root)){for(var p:walk.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    private static void firstRunSetup(Path root,Path news,Path frontend)throws Exception {
        Path data=root.resolve("setup-data");var identities=new WebIdentityStore(data);
        var settings=new WebSettings(true,"http://0.0.0.0","http");
        var old=new WebSecurity(settings,identities,data);String expiredCode=old.setupCode();
        var security=new WebSecurity(settings,identities,data);String code=security.setupCode();
        check(code!=null&&code.matches("[0-9]{8}"),"short cryptographically random numeric setup code");
        var locked=new WebSecurity(settings,identities,data);
        for(int attempt=0;attempt<10;attempt++)check(locked.setup("invalid","LockedAdmin",PASSWORD.toCharArray())==null,"wrong setup attempt rejected");
        check(locked.setup(locked.setupCode(),"LockedAdmin",PASSWORD.toCharArray())==null&&identities.list().isEmpty(),"ten incorrect guesses lock setup until process restart without creating an account");
        check(code!=null&&!code.equals(expiredCode),"setup capability changes on process restart");
        try(var accounts=new AccountStore(data,false,ItemCatalog.starter());var log=new ServerLog(data.resolve("logs"));var service=new AdminService(accounts,new ServerControl("setup",data),log,root);
            var http=NewsServer.start(InetAddress.getLoopbackAddress(),0,news,x->{},new AdminHandler(security,identities,service,frontend))){
            port=http.port();var h=new HashMap<String,String>();h.put("Host","127.0.0.1:"+port);h.put("Origin","http://127.0.0.1:"+port);
            var state=request("GET","/admin/api/setup",null,h);
            check(state.status==200&&state.body.contains("true")&&!state.body.contains(code),"first-run state is public but never reveals the code");
            var fields=new HashMap<String,Object>(Map.of("username","FirstAdmin","password",PASSWORD,"passwordConfirmation",PASSWORD,"setupCode",expiredCode));
            check(request("POST","/admin/api/setup",form(fields),h).status==403,"old setup capability rejected");
            fields.put("setupCode",code);var cross=new HashMap<>(h);cross.put("Origin","http://evil.example");
            check(request("POST","/admin/api/setup",form(fields),cross).status==403,"setup requires same Origin");
            fields.put("passwordConfirmation","mismatch");check(request("POST","/admin/api/setup",form(fields),h).status==400,"setup password confirmation");fields.put("passwordConfirmation",PASSWORD);
            var created=request("POST","/admin/api/setup",form(fields),h);check(created.status==200,"browser creates first administrator");
            check(identities.list().size()==1&&identities.list().getFirst().role()==WebIdentityStore.Role.ADMINISTRATOR,"first-run role fixed on backend");
            h.put("Cookie",created.headers.get("set-cookie").split(";")[0]);h.put("X-CSRF-Token",(String)((Map<?,?>)Json.parse(created.body)).get("csrf"));
            check(request("GET","/admin/api/session",null,h).status==200,"first-run account signs in after commit");
            check(request("POST","/admin/api/setup",form(fields),h).status==403&&!security.setupRequired(),"setup is one-time");
            var users=request("GET","/admin/api/webui-users",null,h);var row=(Map<?,?>)((List<?>)Json.parse(users.body)).getFirst();
            check(row.get("revision") instanceof String&&!users.body.contains("verifier")&&!users.body.contains("salt"),"exact revisions without credential disclosure");
            var create=Map.of("username","WebViewer","password",PASSWORD,"passwordConfirmation",PASSWORD,"role","VIEWER","confirmation","webui-user-create");
            check(request("POST","/admin/api/webui-user-create",form(create),h).status==200,"administrator creates more WebUI accounts");
            check(request("POST","/admin/api/webui-user-delete",form(Map.of("username","FirstAdmin","revision",row.get("revision"),"nameConfirmation","FirstAdmin","confirmation","webui-user-delete")),h).status==409,"cannot revoke last administrator remotely");
            var viewer=request("POST","/admin/api/login",form(Map.of("username","WebViewer","password",PASSWORD)),Map.of("Host","127.0.0.1:"+port,"Origin","http://127.0.0.1:"+port));
            var vh=new HashMap<>(h);vh.put("Cookie",viewer.headers.get("set-cookie").split(";")[0]);vh.put("X-CSRF-Token",(String)((Map<?,?>)Json.parse(viewer.body)).get("csrf"));
            check(request("GET","/admin/api/webui-users",null,vh).status==403&&request("POST","/admin/api/webui-user-create",form(create),vh).status==403,"viewer cannot inspect or manage WebUI identities");
            identities.revoke("FirstAdmin");identities.revoke("WebViewer");
            check(!security.setupRequired()&&!new WebSecurity(settings,identities,data).setupRequired(),"empty accounts after revocation never reopen first-run setup");
        }
        cookie=null;csrf=null;
    }
    private static void httpMode(Path root,Path news,Path frontend,WebIdentityStore identities,AdminService service)throws Exception {
        String origin="http://192.168.1.50:8080";
        Path noProxyKey=root.resolve("http-without-proxy-key");
        var clock=new AtomicLong(System.currentTimeMillis());
        var security=new WebSecurity(new WebSettings(true,origin,"http"),identities,noProxyKey,clock::get);
        check(!Files.exists(noProxyKey),"direct HTTP needs no proxy key or proxy configuration");
        var headers=new HashMap<String,String>();headers.put("Host","192.168.1.50:8080");headers.put("Origin",origin);
        try(var http=NewsServer.start(InetAddress.getLoopbackAddress(),0,news,x->{},new AdminHandler(security,identities,service,frontend))){
            port=http.port();
            var front=request("GET","/admin/",null,headers);
            check(front.status==200&&!front.headers.containsKey("strict-transport-security")&&front.headers.containsKey("content-security-policy"),"HTTP frontend keeps CSP without HSTS");
            check(request("GET","/news/public.png",null,Map.of()).status==200,"HTTP mode shares the public News listener");
            check(request("GET","/admin/api/status",null,headers).status==401,"HTTP API still requires authentication");
            var bad=new HashMap<>(headers);bad.put("Host","attacker.example");check(request("GET","/admin/",null,bad).status==403,"HTTP Host allowlist");
            bad=new HashMap<>(headers);bad.put("Origin","http://attacker.example");check(request("POST","/admin/api/login",form(Map.of("username","administrator","password",PASSWORD)),bad).status==403,"HTTP cross-origin login rejected");
            bad=new HashMap<>(headers);bad.remove("Origin");check(request("POST","/admin/api/login",form(Map.of("username","administrator","password",PASSWORD)),bad).status==403,"HTTP login requires Origin");
            for(String header:List.of("Forwarded","X-Forwarded-For","X-Forwarded-Host","X-Forwarded-Proto","X-Tortodrone-Proxy")){
                bad=new HashMap<>(headers);bad.put(header,"spoofed");check(request("GET","/admin/",null,bad).status==403,"HTTP rejects proxy assertions: "+header);
            }
            var login=request("POST","/admin/api/login",form(Map.of("username","administrator","password",PASSWORD)),headers);
            check(login.status==200,"direct HTTP administrator login");
            String set=login.headers.get("set-cookie");
            check(set.startsWith("tortodrone-http=")&&set.contains("HttpOnly")&&set.contains("SameSite=Strict")&&!set.contains("Secure")&&!set.contains("Domain="),"HTTP-compatible host-only session cookie");
            var authenticated=new HashMap<>(headers);authenticated.put("Cookie",set.split(";")[0]);
            String csrf=(String)((Map<?,?>)Json.parse(login.body)).get("csrf");
            check(request("GET","/admin/api/status",null,authenticated).status==200,"HTTP session round trip");
            var quoted=new HashMap<>(authenticated);String pair=authenticated.get("Cookie");int equals=pair.indexOf('=');
            quoted.put("Cookie",pair.substring(0,equals+1)+"\""+pair.substring(equals+1)+"\"");
            check(request("GET","/admin/api/status",null,quoted).status==200,"quoted session cookie accepted");
            quoted.put("Cookie","$Version=\"1\"\r\nCookie: "+quoted.get("Cookie"));
            check(request("GET","/admin/api/status",null,quoted).status==200,"split Cookie headers preserve the unique session");
            var duplicate=new HashMap<>(authenticated);duplicate.put("Cookie",pair+"\r\nCookie: "+pair);
            check(request("GET","/admin/api/status",null,duplicate).status==401,"duplicate session cookies remain rejected");
            check(request("POST","/admin/api/logout","",authenticated).status==403,"HTTP session still requires CSRF");
            var wrongName=new HashMap<>(authenticated);wrongName.put("Cookie",authenticated.get("Cookie").replace("tortodrone-http=","__Secure-tortodrone="));
            check(request("GET","/admin/api/status",null,wrongName).status==401,"HTTP and HTTPS cookie names remain separate");
            authenticated.put("X-CSRF-Token",csrf);
            var logout=request("POST","/admin/api/logout","",authenticated);
            check(logout.status==200&&logout.headers.get("set-cookie").contains("Max-Age=0"),"HTTP logout clears cookie");
            check(request("GET","/admin/api/status",null,authenticated).status==401,"HTTP logout revokes server session");
            login=request("POST","/admin/api/login",form(Map.of("username","viewer","password",PASSWORD)),headers);
            check(login.status==200,"HTTP viewer login");
            authenticated.put("Cookie",login.headers.get("set-cookie").split(";")[0]);authenticated.put("X-CSRF-Token",(String)((Map<?,?>)Json.parse(login.body)).get("csrf"));
            check(request("GET","/admin/api/status",null,authenticated).status==200,"HTTP viewer monitoring");
            check(request("POST","/admin/api/stop",form(Map.of("confirmation","stop")),authenticated).status==403,"HTTP role authorization remains enforced");
            clock.addAndGet(31*60_000);check(request("GET","/admin/api/status",null,authenticated).status==401,"HTTP idle expiration");
        }
    }
    private static void automaticHttpMode(Path root,Path news,Path frontend,WebIdentityStore identities,AdminService service)throws Exception {
        Path config=root.resolve("automatic-http.yml");
        Files.writeString(config,"server-ip: 0.0.0.0\nwebui-enabled: true\nwebui-public-url: http://0.0.0.0\nwebui/content_url-port: 0\nwebui-tls-mode: http\n");
        var settings=org.tortodrone.config.ServerSettings.read(config);
        var security=new WebSecurity(settings.webui(),identities,root.resolve("automatic-without-proxy-key"));
        try(var http=NewsServer.start(InetAddress.getByName(settings.bind()),settings.httpPort(),news,x->{},new AdminHandler(security,identities,service,frontend))){
            port=http.port();String authority="127.0.0.1:"+port;
            var headers=new HashMap<String,String>();headers.put("Host",authority);headers.put("Origin","http://"+authority);
            check(request("GET","/admin/",null,headers).status==200,"automatic mode accepts concrete connection address");
            var redirect=request("GET","/",null,headers);
            check(redirect.status==302&&"/admin/".equals(redirect.headers.get("location")),"root redirects with a relative location on the same listener");
            check(request("GET","/admin",null,headers).status==302,"admin without trailing slash redirects");
            check(request("POST","/",form(Map.of()),headers).status==405,"root accepts navigation only");
            check(request("GET","/missing",null,headers).status==404,"root context does not expose other files");
            check(request("GET","/news/public.png",null,Map.of()).status==200,"automatic WebUI and News share one port");
            var bad=new HashMap<>(headers);bad.put("Host","attacker.example:"+port);bad.put("Origin","http://attacker.example:"+port);
            check(request("GET","/admin/",null,bad).status==403,"automatic mode rejects arbitrary matching Host/Origin pairs");
            check(request("GET","/",null,bad).status==403,"root redirect preserves Host validation");
            bad=new HashMap<>(headers);bad.put("Host","0.0.0.0:"+port);bad.put("Origin","http://0.0.0.0:"+port);
            check(request("GET","/admin/",null,bad).status==403,"wildcard is configuration, never a browser Host");
            bad=new HashMap<>(headers);bad.put("Host","127.0.0.1:"+(port==65535?65534:port+1));
            check(request("GET","/admin/",null,bad).status==403,"automatic mode validates actual listener port");
            bad=new HashMap<>(headers);bad.put("Origin","http://attacker.example:"+port);
            check(request("POST","/admin/api/login",form(Map.of("username","administrator","password",PASSWORD)),bad).status==403,"automatic mode keeps Origin protection");
            bad=new HashMap<>(headers);bad.put("X-Forwarded-Host",authority);
            check(request("GET","/admin/",null,bad).status==403,"automatic mode rejects forwarded authority");
            var login=request("POST","/admin/api/login",form(Map.of("username","administrator","password",PASSWORD)),headers);
            check(login.status==200,"automatic-address login");
            headers.put("Cookie",login.headers.get("set-cookie").split(";")[0]);headers.put("X-CSRF-Token",(String)((Map<?,?>)Json.parse(login.body)).get("csrf"));
            check(request("GET","/admin/api/status",null,headers).status==200,"automatic-address authenticated session");
            check(request("POST","/admin/api/logout","",headers).status==200,"automatic-address logout");
            check(request("GET","/admin/api/status",null,headers).status==401,"automatic-address logout revokes session");
            var localhost=Map.of("Host","localhost:"+port,"Origin","http://localhost:"+port);
            check(request("GET","/admin/",null,localhost).status==200,"loopback localhost alias accepted");
            var otherAddress=Map.of("Host","127.0.0.2:"+port,"Origin","http://127.0.0.2:"+port);
            check(request("GET","/admin/",null,otherAddress).status==403,"Host must match the destination interface");
            check(requestTo(InetAddress.ofLiteral("127.0.0.2"),"GET","/admin/",null,otherAddress).status==200,"one wildcard listener accepts another concrete local address");
        }
    }
    private static HashMap<String,String> headers(){var h=new HashMap<String,String>();h.put("Host","admin.example");h.put("X-Tortodrone-Proxy",secret);h.put("X-Forwarded-Proto","https");h.put("Origin","https://admin.example");return h;}
    private static HashMap<String,String> authenticated(){var h=headers();if(cookie!=null)h.put("Cookie",cookie);if(csrf!=null)h.put("X-CSRF-Token",csrf);return h;}
    private static Response get(String route)throws Exception{return request("GET","/admin/api/"+route,null,authenticated());}
    private static Response post(String route,Map<String,?> p)throws Exception{return request("POST","/admin/api/"+route,form(p),authenticated());}
    private static String form(Map<String,?> p){return p.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue().toString(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));}
    private static void login(String user)throws Exception{var r=post("login",Map.of("username",user,"password",PASSWORD));check(r.status==200,"login "+user+": "+r.body);String set=r.headers.get("set-cookie");check(set.contains("HttpOnly")&&set.contains("Secure")&&set.contains("SameSite=Strict"),"cookie protections");cookie=set.split(";")[0];csrf=(String)((Map<?,?>)Json.parse(r.body)).get("csrf");}
    private record Response(int status,Map<String,String> headers,String body){}
    private static Response request(String method,String path,String body,Map<String,String> headers)throws Exception{
        return requestTo(InetAddress.getLoopbackAddress(),method,path,body,headers);
    }
    private static Response requestTo(InetAddress destination,String method,String path,String body,Map<String,String> headers)throws Exception{
        try(var socket=new Socket(destination,port)){
            socket.setSoTimeout(10000);var out=socket.getOutputStream();byte[] bytes=body==null?new byte[0]:body.getBytes(StandardCharsets.UTF_8);
            StringBuilder request=new StringBuilder(method+" "+path+" HTTP/1.1\r\nConnection: close\r\n");
            if(!headers.containsKey("Host"))request.append("Host: localhost\r\n");headers.forEach((k,v)->request.append(k).append(": ").append(v).append("\r\n"));
            if(body!=null)request.append("Content-Type: application/x-www-form-urlencoded;charset=UTF-8\r\nContent-Length: ").append(bytes.length).append("\r\n");
            out.write(request.append("\r\n").toString().getBytes(StandardCharsets.UTF_8));out.write(bytes);out.flush();
            String raw=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);int split=raw.indexOf("\r\n\r\n");var lines=raw.substring(0,split).split("\r\n");var responseHeaders=new HashMap<String,String>();for(int i=1;i<lines.length;i++){int colon=lines[i].indexOf(':');responseHeaders.put(lines[i].substring(0,colon).toLowerCase(Locale.ROOT),lines[i].substring(colon+1).strip());}return new Response(Integer.parseInt(lines[0].split(" ")[1]),responseHeaders,raw.substring(split+4));
        }
    }
}
