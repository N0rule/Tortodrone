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
            security.headers(e);
            if(!security.trusted(e)){send(e,403,Map.of("error","Request does not match the configured WebUI transport and origin"));return;}
            if(!requests.tryAcquire()){send(e,503,Map.of("error","Busy; retry shortly"));return;}
            try{handleTrusted(e);}catch(IllegalArgumentException invalid){send(e,400,Map.of("error","Invalid request fields"));}
            catch(IOException invalid){
                String message=safeValidation(invalid.getMessage());
                send(e,message==null?409:422,Map.of("error",message==null?"Operation refused; refresh and verify the selection and values":message));
            }
            catch(Exception failed){send(e,500,Map.of("error","Administration operation unavailable"));}
            finally{requests.release();}
        }
    }
    /** Only fixed, reviewed validation messages cross the HTTP boundary; no filesystem/SQL details. */
    private static String safeValidation(String message) {
        if(message==null)return null;
        return switch(message) {
            case "Sign out of the game before changing equipment","Item cannot be equipped in that slot","Activate a slot upgrade first","No such owned item","Invalid item level or heat","Level is outside this item's progression table",
                "Maximum-level items cannot store heat","Choose up to three distinct UVs valid for this item",
                "Choose only one UV from each variant family","Use a PNG or JPEG filename","Invalid image encoding",
                "Uploaded images must be at most 1 MiB","Image exceeds the 570 by 300 featured slot","News image library is full",
                "welcome-message must be at most 256 characters","welcome-message must not contain control characters","welcome-message must not contain invalid text"->message;
            case "e.existing_accessory"->"An accessory already occupies that location. Remove attached accessories before adding a replacement.";
            case "e.cant_use_accessory"->"This accessory is not compatible with the selected equipment.";
            default->null;
        };
    }
    private void handleTrusted(HttpExchange e)throws Exception {
        String path=e.getRequestURI().getRawPath();
        if(path.contains("%")||path.contains("..")||path.contains("\\")||path.contains("//")){send(e,404,Map.of("error","Not found"));return;}
        boolean post=e.getRequestMethod().equals("POST");
        if(!post&&!e.getRequestMethod().equals("GET")){send(e,405,Map.of("error","Method not allowed"));return;}
        if(path.equals("/")||path.equals("/admin")){
            if(post){send(e,405,Map.of("error","Method not allowed"));return;}
            e.getResponseHeaders().set("Location","/admin/");e.sendResponseHeaders(302,-1);return;
        }
        if(!path.startsWith("/admin/")){send(e,404,Map.of("error","Not found"));return;}
        if(!path.startsWith("/admin/api/")) {if(post){send(e,405,Map.of("error","Method not allowed"));return;}staticFile(e,path);return;}
        String route=path.substring("/admin/api/".length());
        int limit=route.equals("news-upload")?2*1024*1024:65536;
        var p=parameters(post?body(e,limit):Objects.requireNonNullElse(e.getRequestURI().getRawQuery(),""),limit);
        if(route.equals("setup")&&!post){send(e,200,Map.of("required",security.setupRequired()));return;}
        if(route.equals("setup")&&post){
            if(!security.allowLogin()||!logins.tryAcquire()){send(e,429,Map.of("error","Setup rate limit; wait one minute"));return;}
            try {
                if(!p.text("password").equals(p.text("passwordConfirmation")))throw new IllegalArgumentException("Passwords do not match");
                char[] password=p.text("password").toCharArray();
                WebSecurity.Session created;
                try{created=security.setup(p.text("setupCode"),p.text("username"),password);}finally{Arrays.fill(password,'\0');}
                if(created==null){identities.audit("anonymous","setup","","denied");send(e,403,Map.of("error","Setup code is incorrect or first-run setup has already completed"));return;}
                security.cookie(e,created);send(e,200,session(created));return;
            }finally{logins.release();}
        }
        if(route.equals("login")&&post){
            if(!security.allowLogin()||!logins.tryAcquire()){send(e,429,Map.of("error","Login rate limit; wait one minute"));return;}
            try{
                char[] password=p.text("password").toCharArray();if(password.length>128)throw new IllegalArgumentException();
                WebSecurity.Session s;try{s=security.login(p.text("username"),password);}finally{Arrays.fill(password,'\0');}
                identities.audit(s==null?"anonymous":s.identity().username(),"login","",s==null?"denied":"authenticated");
                if(s==null){send(e,401,Map.of("error","Invalid credentials"));return;}
                var old=security.session(e);if(old!=null)security.logout(old);
                security.cookie(e,s);send(e,200,session(s));return;
            }finally{logins.release();}
        }
        var s=security.session(e);if(s==null){send(e,401,Map.of("error","Sign in required"));return;}
        if(post&&!WebSecurity.equal(s.csrf(),e.getRequestHeaders().getFirst("X-CSRF-Token"))){send(e,403,Map.of("error","CSRF validation failed"));return;}
        if(route.equals("session")&&!post){send(e,200,session(s));return;}
        if(route.equals("logout")&&post){security.logout(s);security.cookie(e,null);send(e,200,Map.of("ok",true));return;}
        var permission=AdminService.permission(route,post);
        if(s.identity().role().ordinal()<permission.ordinal()){identities.audit(s.identity().username(),route,"","denied");send(e,403,Map.of("error","Role does not permit this operation"));return;}
        if(!post){
            Object result=switch(route){
                case "audit"->identities.audit();
                // Preserve all 64 revision bits across the JavaScript JSON boundary.
                case "webui-users"->identities.list().stream().map(id->Map.of("username",id.username(),"role",id.role(),"revision",Long.toString(id.revision()))).toList();
                default->service.read(route,p);
            };
            send(e,200,result);return;
        }
        String target=route.equals("console")?AdminConsole.auditTarget(p.optional("command")):p.optional("username")+":"+p.optional("knight")+":"+p.optional("id");
        identities.audit(s.identity().username(),route,target,"requested");
        Object result;
        try{result=route.startsWith("webui-user-")?identityChange(route,p,s.identity()):service.write(route,p);}
        catch(Exception failure){identities.audit(s.identity().username(),route,target,"refused");throw failure;}
        try{identities.audit(s.identity().username(),route,target,"committed");}
        catch(IOException auditFailure){send(e,503,Map.of("error","Action completed but final audit recording failed. Refresh before retrying."));return;}
        send(e,200,result);
    }
    private Object identityChange(String route,AdminService.Input p,WebIdentityStore.Identity actor)throws IOException {
        if(!p.text("confirmation").equals(route))throw new IllegalArgumentException("Confirmation did not match");
        switch(route){
            case "webui-user-create","webui-user-password"->{
                if(!p.text("password").equals(p.text("passwordConfirmation")))throw new IllegalArgumentException("Passwords do not match");
                char[] password=p.text("password").toCharArray();
                try {
                    if(route.equals("webui-user-create"))identities.create(actor,p.text("username"),password,WebIdentityStore.Role.valueOf(p.text("role")));
                    else identities.password(actor,p.text("username"),password,positiveRevision(p));
                }finally{Arrays.fill(password,'\0');}
            }
            case "webui-user-role"->identities.role(actor,p.text("username"),WebIdentityStore.Role.valueOf(p.text("role")),positiveRevision(p));
            case "webui-user-delete"->{
                if(!p.text("username").equals(p.text("nameConfirmation")))throw new IllegalArgumentException("Username confirmation required");
                identities.remove(actor,p.text("username"),positiveRevision(p));
            }
            default->throw new IllegalArgumentException("Unknown WebUI account action");
        }
        return Map.of("ok",true);
    }
    private static long positiveRevision(AdminService.Input p){long revision=p.number("revision");if(revision<1)throw new IllegalArgumentException("Invalid revision");return revision;}
    private static Object session(WebSecurity.Session s){return Map.of("username",s.identity().username(),"role",s.identity().role(),"csrf",s.csrf(),"expires",s.expires());}
    private static String body(HttpExchange e,int limit)throws IOException {
        if(!"application/x-www-form-urlencoded;charset=UTF-8".equalsIgnoreCase(e.getRequestHeaders().getFirst("Content-Type")))throw new IllegalArgumentException();
        byte[] b=e.getRequestBody().readNBytes(limit+1);if(b.length>limit)throw new IllegalArgumentException("Request too large");return new String(b,StandardCharsets.UTF_8);
    }
    private static AdminService.Input parameters(String text,int limit){
        if(text.length()>limit)throw new IllegalArgumentException();var result=new HashMap<String,String>();
        if(!text.isEmpty())for(String pair:text.split("&")){int pos=pair.indexOf('=');if(pos<1)throw new IllegalArgumentException();String key=URLDecoder.decode(pair.substring(0,pos),StandardCharsets.UTF_8),value=URLDecoder.decode(pair.substring(pos+1),StandardCharsets.UTF_8);if(result.putIfAbsent(key,value)!=null||result.size()>40)throw new IllegalArgumentException();}
        return new AdminService.Input(Map.copyOf(result));
    }
    private void staticFile(HttpExchange e,String path)throws IOException {
        String name=path.equals("/admin/")?"index.html":path.substring("/admin/".length());
        String type=null;
        if(name.equals("index.html"))type="text/html; charset=utf-8";
        else if(name.matches("assets/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_.-]+")) {
            String extension=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
            type=switch(extension){case "js"->"text/javascript; charset=utf-8";case "css"->"text/css; charset=utf-8";
                case "json"->"application/json; charset=utf-8";case "png"->"image/png";case "jpg","jpeg"->"image/jpeg";
                case "webp"->"image/webp";case "svg"->"image/svg+xml";case "woff2"->"font/woff2";default->null;};
        }
        if(type==null){send(e,404,Map.of("error","Not found"));return;}
        Path file=ContentFiles.relative(frontend,name);
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)){send(e,404,Map.of("error","Not found"));return;}
        int limit=2*1024*1024;
        if(Files.size(file)>limit)throw new IOException("Asset too large");
        byte[] b;
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){b=input.readNBytes(limit+1);}
        if(b.length>limit)throw new IOException("Asset too large");
        e.getResponseHeaders().set("Content-Type",type);
        if(!name.equals("index.html"))e.getResponseHeaders().set("Cache-Control",name.matches("assets/game/icons/[a-f0-9]{64}\\.(?:png|webp)")?"private, max-age=31536000, immutable":"private, max-age=0, must-revalidate");
        // Hash the response snapshot: a second file read can race an atomic asset replacement.
        try{e.getResponseHeaders().set("ETag","\""+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b))+"\"");}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        if(e.getResponseHeaders().getFirst("ETag").equals(e.getRequestHeaders().getFirst("If-None-Match"))){e.sendResponseHeaders(304,-1);return;}
        e.sendResponseHeaders(200,b.length==0?-1:b.length);if(b.length>0)e.getResponseBody().write(b);
    }
    private static void send(HttpExchange e,int status,Object result)throws IOException {byte[] b=WebJson.encode(result).getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");e.sendResponseHeaders(status,b.length);e.getResponseBody().write(b);}
}
