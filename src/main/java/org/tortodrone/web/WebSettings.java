package org.tortodrone.web;

import java.io.IOException;
import java.net.URI;

/** Explicit transport selection; HTTPS through a trusted proxy remains the default. */
public record WebSettings(boolean enabled, String publicUrl, String tlsMode) {
    public static final WebSettings DISABLED=new WebSettings(false,"","reverse-proxy");
    public WebSettings {
        if(!java.util.Set.of("reverse-proxy","http").contains(tlsMode))throw new IllegalArgumentException("webui-tls-mode must be reverse-proxy or http");
        if(enabled) {
            URI uri;
            try{uri=URI.create(publicUrl);}catch(Exception invalid){throw new IllegalArgumentException("Invalid webui-public-url");}
            String scheme=tlsMode.equals("http")?"http":"https";
            if(!scheme.equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!uri.getPath().isEmpty()||uri.getPort()>65535||uri.getPort()==0)
                throw new IllegalArgumentException("webui-public-url must be an "+scheme.toUpperCase(java.util.Locale.ROOT)+" origin without a trailing slash");
        }
    }
    public boolean directHttp(){return tlsMode.equals("http");}
    public static WebSettings read(java.util.Properties p)throws IOException {
        String enabled=p.getProperty("webui-enabled","false");
        if(!enabled.equals("true")&&!enabled.equals("false"))throw new IOException("webui-enabled must be true or false");
        try{return new WebSettings(Boolean.parseBoolean(enabled),p.getProperty("webui-public-url",""),p.getProperty("webui-tls-mode","reverse-proxy"));}
        catch(IllegalArgumentException e){throw new IOException(e.getMessage());}
    }
}
