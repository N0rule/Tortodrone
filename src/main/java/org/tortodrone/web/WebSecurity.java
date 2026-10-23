package org.tortodrone.web;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URI;
import java.net.InetAddress;
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
    private String setupCode;
    private long loginWindow;
    private int loginAttempts;
    public WebSecurity(WebSettings settings,WebIdentityStore identities,Path data)throws IOException {
        this(settings,identities,data,System::currentTimeMillis);
    }
    WebSecurity(WebSettings settings,WebIdentityStore identities,Path data,java.util.function.LongSupplier clock)throws IOException {
        this.settings=settings;this.identities=identities;this.clock=clock;
        setupCode=identities.bootstrapAvailable()?WebIdentityStore.token():null;
        if(settings.directHttp()){proxySecret=null;return;}
        Path secret=data.resolve("webui-proxy.key");
        if(Files.notExists(secret,LinkOption.NOFOLLOW_LINKS))try{WebIdentityStore.createProxyKey(secret);}catch(FileAlreadyExistsException raced){/* Validate the winner below. */}
        ContentFiles.regular(secret);
        if(Files.size(secret)>128)throw new IOException("Invalid proxy secret file");
        proxySecret=Files.readString(secret).strip();
        if(!proxySecret.matches("[A-Za-z0-9_-]{43}"))throw new IOException("Invalid WebUI proxy secret file");
    }
    public boolean trusted(HttpExchange e){
        var h=e.getRequestHeaders();
        if(h.containsKey("Forwarded")||h.containsKey("X-Forwarded-Host")||h.containsKey("X-Forwarded-For"))return false;
        String host=single(e,"Host");
        String expectedOrigin=settings.publicUrl();
        if(settings.automaticHost()) {
            if(!localAuthority(e,host))return false;
            expectedOrigin="http://"+host;
        } else if(!URI.create(settings.publicUrl()).getRawAuthority().equals(host))return false;
        if(settings.directHttp()) {
            if(h.containsKey("X-Forwarded-Proto")||h.containsKey("X-Tortodrone-Proxy"))return false;
        } else if(!e.getRemoteAddress().getAddress().isLoopbackAddress()||!"https".equals(single(e,"X-Forwarded-Proto"))||!equal(proxySecret,single(e,"X-Tortodrone-Proxy")))return false;
        String origin=single(e,"Origin");
        return (!h.containsKey("Origin")||expectedOrigin.equals(origin))&&(!e.getRequestMethod().equals("POST")||expectedOrigin.equals(origin));
    }
    /** Automatic HTTP access accepts this connection's concrete local IP, never an arbitrary DNS Host. */
    private static boolean localAuthority(HttpExchange e,String host){
        if(host==null)return false;
        try {
            URI uri=URI.create("http://"+host);
            if(!host.equals(uri.getRawAuthority())||uri.getHost()==null||uri.getUserInfo()!=null||!uri.getPath().isEmpty()||uri.getQuery()!=null||uri.getFragment()!=null)return false;
            if((uri.getPort()==-1?80:uri.getPort())!=e.getLocalAddress().getPort())return false;
            String name=uri.getHost();
            if(name.equals("localhost"))return e.getLocalAddress().getAddress().isLoopbackAddress()&&e.getRemoteAddress().getAddress().isLoopbackAddress();
            if(name.startsWith("[")&&name.endsWith("]"))name=name.substring(1,name.length()-1);
            return InetAddress.ofLiteral(name).equals(e.getLocalAddress().getAddress());
        }catch(IllegalArgumentException invalid){return false;}
    }
    private static String single(HttpExchange e,String name){var values=e.getRequestHeaders().get(name);return values==null||values.size()!=1?null:values.getFirst();}
    public static boolean equal(String a,String b){return a!=null&&b!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    public synchronized boolean allowLogin(){
        long now=clock.getAsLong();if(now-loginWindow>60_000){loginWindow=now;loginAttempts=0;}
        return ++loginAttempts<=10;
    }
    public synchronized Session login(String username,char[] password)throws IOException {
        var id=identities.authenticate(username,password);if(id==null)return null;
        return newSession(id);
    }
    public synchronized boolean setupRequired()throws IOException {return setupCode!=null&&identities.bootstrapAvailable();}
    /** Local console only. Never serialize this capability or write it to operational logs. */
    public synchronized String setupCode()throws IOException {return setupRequired()?setupCode:null;}
    public synchronized Session setup(String code,String username,char[] password)throws IOException {
        if(!setupRequired()||!equal(setupCode,code))return null;
        var identity=identities.bootstrap(username,password);
        setupCode=null;
        return newSession(identity);
    }
    private Session newSession(WebIdentityStore.Identity id)throws IOException {
        prune();if(sessions.size()>=256)throw new IOException("Session limit reached");
        long now=clock.getAsLong();var s=new Session(WebIdentityStore.token(),WebIdentityStore.token(),id,now+8*60*60_000,now);sessions.put(s.token(),s);return s;
    }
    public synchronized Session session(HttpExchange e)throws IOException {
        prune();var cookies=e.getRequestHeaders().get("Cookie");if(cookies==null)return null;
        String prefix=cookieName()+"=";
        String token=null;
        for(String cookie:cookies)for(String part:cookie.split(";")){
            String p=part.strip();if(!p.startsWith(prefix))continue;
            if(token!=null)return null;
            token=p.substring(prefix.length());
            if(token.length()>=2&&token.startsWith("\"")&&token.endsWith("\""))token=token.substring(1,token.length()-1);
        }
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
