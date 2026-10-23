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
            WebIdentityStore.createProxyKey(data.resolve("webui-proxy.key"));secret=Files.readString(data.resolve("webui-proxy.key")).strip();
            if(Files.getFileStore(data).supportsFileAttributeView("posix"))check(Files.getPosixFilePermissions(data.resolve("webui-proxy.key")).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")),"proxy secret private from creation");
            try{WebIdentityStore.createProxyKey(data.resolve("webui-proxy.key"));throw new AssertionError("Existing proxy key overwritten");}catch(FileAlreadyExistsException expected){}
            check(secret.equals(Files.readString(data.resolve("webui-proxy.key")).strip()),"existing secret preserved");
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
                    var bad=headers();bad.put("X-Tortodrone-Proxy","wrong");check(request("GET","/admin/",null,bad).status==403,"forged proxy rejected");
                    bad=headers();bad.put("X-Forwarded-For","127.0.0.1");check(request("GET","/admin/",null,bad).status==403,"untrusted forwarded chain rejected");
                    bad=headers();bad.put("Host","evil.example");check(request("GET","/admin/",null,bad).status==403,"host rejected");
                    check(get("status").status==401,"API requires authentication");
                    bad=headers();bad.put("Origin","https://evil.example");check(request("POST","/admin/api/login",form(Map.of("username","administrator","password",PASSWORD)),bad).status==403,"cross-origin login rejected");
                    login("administrator");
                    check(get("session").status==200,"server-side session");check(get("status").status==200,"dashboard DTO serialization");
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
                    check(post("account-create",Map.of("padding","x".repeat(66000))).status==400,"request size bounded");
                    check(post("logout",Map.of()).status==200&&get("status").status==401,"logout revokes session");
                    login("viewer");check(get("status").status==200&&get("accounts").status==403,"viewer monitoring only");check(post("stop",Map.of("confirmation","stop")).status==403,"viewer cannot stop listener");
                    login("moderator");check(get("accounts").status==200&&get("depot").status==403,"moderator scope");check(post("account-create",create).status==403,"moderator cannot create account");
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
            check(request("GET","/news/public.png",null,Map.of()).status==200,"automatic WebUI and News share one port");
            var bad=new HashMap<>(headers);bad.put("Host","attacker.example:"+port);bad.put("Origin","http://attacker.example:"+port);
            check(request("GET","/admin/",null,bad).status==403,"automatic mode rejects arbitrary matching Host/Origin pairs");
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
