package org.tortodrone.web;

import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.tortodrone.resources.ContentFiles;

/** Administration policy is scoped to /admin; News never passes through this handler. */
public final class AdminHandler implements HttpHandler {
    private final WebSecurity security;
    private final WebIdentityStore identities;
    private final AdminService service;
    private final Path frontend;
    private final Semaphore requests=new Semaphore(8),logins=new Semaphore(2);
    public AdminHandler(WebSecurity security,WebIdentityStore identities,AdminService service,Path frontend){this.security=security;this.identities=identities;this.service=service;this.frontend=frontend;}
    @Override public void handle(HttpExchange e)throws IOException {
        try(e){
            WebSecurity.headers(e);
            if(!security.trusted(e)){send(e,403,Map.of("error","Trusted HTTPS proxy required"));return;}
            if(!requests.tryAcquire()){send(e,503,Map.of("error","Busy; retry shortly"));return;}
            try{handleTrusted(e);}catch(IllegalArgumentException invalid){send(e,400,Map.of("error","Invalid request fields"));}
            catch(IOException invalid){send(e,409,Map.of("error","Operation refused; refresh and verify the selection and values"));}
            catch(Exception failed){send(e,500,Map.of("error","Administration operation unavailable"));}
            finally{requests.release();}
        }
    }
    private void handleTrusted(HttpExchange e)throws Exception {
        String path=e.getRequestURI().getRawPath();
        if(path.contains("%")||path.contains("..")||path.contains("\\")||path.contains("//")){send(e,404,Map.of("error","Not found"));return;}
        boolean post=e.getRequestMethod().equals("POST");
        if(!post&&!e.getRequestMethod().equals("GET")){send(e,405,Map.of("error","Method not allowed"));return;}
        if(!path.startsWith("/admin/api/")) {if(post){send(e,405,Map.of("error","Method not allowed"));return;}staticFile(e,path);return;}
        String route=path.substring("/admin/api/".length());
        var p=parameters(post?body(e):Objects.requireNonNullElse(e.getRequestURI().getRawQuery(),""));
        if(route.equals("login")&&post){
            if(!security.allowLogin()||!logins.tryAcquire()){send(e,429,Map.of("error","Login rate limit; wait one minute"));return;}
            try{
                char[] password=p.text("password").toCharArray();if(password.length>128)throw new IllegalArgumentException();
                WebSecurity.Session s;try{s=security.login(p.text("username"),password);}finally{Arrays.fill(password,'\0');}
                identities.audit(s==null?"anonymous":s.identity().username(),"login","",s==null?"denied":"authenticated");
                if(s==null){send(e,401,Map.of("error","Invalid credentials"));return;}
                var old=security.session(e);if(old!=null)security.logout(old);
                WebSecurity.cookie(e,s);send(e,200,session(s));return;
            }finally{logins.release();}
        }
        var s=security.session(e);if(s==null){send(e,401,Map.of("error","Sign in required"));return;}
        if(post&&!WebSecurity.equal(s.csrf(),e.getRequestHeaders().getFirst("X-CSRF-Token"))){send(e,403,Map.of("error","CSRF validation failed"));return;}
        if(route.equals("session")&&!post){send(e,200,session(s));return;}
        if(route.equals("logout")&&post){security.logout(s);WebSecurity.cookie(e,null);send(e,200,Map.of("ok",true));return;}
        var permission=AdminService.permission(route,post);
        if(s.identity().role().ordinal()<permission.ordinal()){identities.audit(s.identity().username(),route,"","denied");send(e,403,Map.of("error","Role does not permit this operation"));return;}
        if(!post){send(e,200,route.equals("audit")?identities.audit():service.read(route,p));return;}
        String target=p.optional("username")+":"+p.optional("knight")+":"+p.optional("id");
        identities.audit(s.identity().username(),route,target,"requested");
        Object result;
        try{result=service.write(route,p);}catch(Exception failure){identities.audit(s.identity().username(),route,target,"refused");throw failure;}
        try{identities.audit(s.identity().username(),route,target,"committed");}
        catch(IOException auditFailure){send(e,503,Map.of("error","Action completed but final audit recording failed. Refresh before retrying."));return;}
        send(e,200,result);
    }
    private static Object session(WebSecurity.Session s){return Map.of("username",s.identity().username(),"role",s.identity().role(),"csrf",s.csrf(),"expires",s.expires());}
    private static String body(HttpExchange e)throws IOException {
        if(!"application/x-www-form-urlencoded;charset=UTF-8".equalsIgnoreCase(e.getRequestHeaders().getFirst("Content-Type")))throw new IllegalArgumentException();
        byte[] b=e.getRequestBody().readNBytes(65537);if(b.length>65536)throw new IllegalArgumentException("Request too large");return new String(b,StandardCharsets.UTF_8);
    }
    private static AdminService.Input parameters(String text){
        if(text.length()>65536)throw new IllegalArgumentException();var result=new HashMap<String,String>();
        if(!text.isEmpty())for(String pair:text.split("&")){int pos=pair.indexOf('=');if(pos<1)throw new IllegalArgumentException();String key=URLDecoder.decode(pair.substring(0,pos),StandardCharsets.UTF_8),value=URLDecoder.decode(pair.substring(pos+1),StandardCharsets.UTF_8);if(result.putIfAbsent(key,value)!=null||result.size()>40)throw new IllegalArgumentException();}
        return new AdminService.Input(Map.copyOf(result));
    }
    private void staticFile(HttpExchange e,String path)throws IOException {
        String name=path.equals("/admin/")?"index.html":path.substring("/admin/".length());
        String type=switch(name){case "index.html"->"text/html; charset=utf-8";case "assets/app.js","assets/api.js"->"text/javascript; charset=utf-8";case "assets/app.css"->"text/css; charset=utf-8";default->null;};
        if(type==null){send(e,404,Map.of("error","Not found"));return;}
        Path file=ContentFiles.relative(frontend,name);ContentFiles.regular(file);if(Files.size(file)>2*1024*1024)throw new IOException("Asset too large");
        byte[] b=Files.readAllBytes(file);e.getResponseHeaders().set("Content-Type",type);e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);
    }
    private static void send(HttpExchange e,int status,Object result)throws IOException {byte[] b=WebJson.encode(result).getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");e.sendResponseHeaders(status,b.length);e.getResponseBody().write(b);}
}
