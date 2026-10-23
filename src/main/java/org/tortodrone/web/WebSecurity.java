package org.tortodrone.web;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.tortodrone.resources.ContentFiles;

/** Transport-specific ingress validation, origin checks and bounded in-memory sessions. */
public final class WebSecurity {
    public record Session(String token,String csrf,WebIdentityStore.Identity identity,long expires,long lastUsed){}
    private final WebSettings settings;
    private final WebIdentityStore identities;
    private final String proxySecret;
    private final Map<String,Session> sessions=new HashMap<>();
    private final java.util.function.LongSupplier clock;
    private long loginWindow;
    private int loginAttempts;
    public WebSecurity(WebSettings settings,WebIdentityStore identities,Path data)throws IOException {
        this(settings,identities,data,System::currentTimeMillis);
    }
    WebSecurity(WebSettings settings,WebIdentityStore identities,Path data,java.util.function.LongSupplier clock)throws IOException {
        this.settings=settings;this.identities=identities;this.clock=clock;
        if(settings.directHttp()){proxySecret=null;return;}
        Path secret=data.resolve("webui-proxy.key");ContentFiles.regular(secret);
        if(Files.size(secret)>128)throw new IOException("Invalid proxy secret file");
        proxySecret=Files.readString(secret).strip();
        if(!proxySecret.matches("[A-Za-z0-9_-]{43}"))throw new IOException("Invalid WebUI proxy secret; use local --webui-proxy-key");
    }
    public boolean trusted(HttpExchange e){
        var h=e.getRequestHeaders();
        if(h.containsKey("Forwarded")||h.containsKey("X-Forwarded-Host")||h.containsKey("X-Forwarded-For"))return false;
        String host=URI.create(settings.publicUrl()).getRawAuthority();
        if(!host.equals(single(e,"Host")))return false;
        if(settings.directHttp()) {
            if(h.containsKey("X-Forwarded-Proto")||h.containsKey("X-Tortodrone-Proxy"))return false;
        } else if(!e.getRemoteAddress().getAddress().isLoopbackAddress()||!"https".equals(single(e,"X-Forwarded-Proto"))||!equal(proxySecret,single(e,"X-Tortodrone-Proxy")))return false;
        String origin=single(e,"Origin");
        return (!h.containsKey("Origin")||settings.publicUrl().equals(origin))&&(!e.getRequestMethod().equals("POST")||settings.publicUrl().equals(origin));
    }
    private static String single(HttpExchange e,String name){var values=e.getRequestHeaders().get(name);return values==null||values.size()!=1?null:values.getFirst();}
    public static boolean equal(String a,String b){return a!=null&&b!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    public synchronized boolean allowLogin(){
        long now=clock.getAsLong();if(now-loginWindow>60_000){loginWindow=now;loginAttempts=0;}
        return ++loginAttempts<=10;
    }
    public synchronized Session login(String username,char[] password)throws IOException {
        var id=identities.authenticate(username,password);if(id==null)return null;
        prune();if(sessions.size()>=256)throw new IOException("Session limit reached");
        long now=clock.getAsLong();var s=new Session(WebIdentityStore.token(),WebIdentityStore.token(),id,now+8*60*60_000,now);sessions.put(s.token(),s);return s;
    }
    public synchronized Session session(HttpExchange e)throws IOException {
        prune();String cookie=single(e,"Cookie");if(cookie==null)return null;
        String prefix=cookieName()+"=";
        String token=null;for(String part:cookie.split(";")){String p=part.strip();if(p.startsWith(prefix)){if(token!=null)return null;token=p.substring(prefix.length());}}
        var s=sessions.get(token);if(s==null)return null;
        if(!identities.current(s.identity())){sessions.remove(token);return null;}
        var updated=new Session(s.token(),s.csrf(),s.identity(),s.expires(),clock.getAsLong());sessions.put(token,updated);return updated;
    }
    public synchronized void logout(Session s){sessions.remove(s.token());}
    private void prune(){long now=clock.getAsLong();sessions.values().removeIf(s->s.expires()<=now||now-s.lastUsed()>30*60_000);}
    private String cookieName(){return settings.directHttp()?"tortodrone-http":"__Secure-tortodrone";}
    public void cookie(HttpExchange e,Session s){e.getResponseHeaders().set("Set-Cookie",cookieName()+"="+(s==null?"":s.token())+"; Path=/admin/; HttpOnly; "+(settings.directHttp()?"":"Secure; ")+"SameSite=Strict; Max-Age="+(s==null?0:28800));}
    public void headers(HttpExchange e){var h=e.getResponseHeaders();h.set("Cache-Control","no-store");h.set("X-Content-Type-Options","nosniff");h.set("X-Frame-Options","DENY");h.set("Referrer-Policy","no-referrer");h.set("Permissions-Policy","camera=(), microphone=(), geolocation=()");if(!settings.directHttp())h.set("Strict-Transport-Security","max-age=31536000");h.set("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; font-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");}
}
