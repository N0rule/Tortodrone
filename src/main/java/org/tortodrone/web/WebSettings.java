package org.tortodrone.web;

import java.io.IOException;
import java.net.URI;

/** Administration is reachable only through an authenticated, same-host TLS reverse proxy. */
public record WebSettings(boolean enabled, String publicUrl, String tlsMode) {
    public static final WebSettings DISABLED=new WebSettings(false,"","reverse-proxy");
    public WebSettings {
        if(!tlsMode.equals("reverse-proxy"))throw new IllegalArgumentException("webui-tls-mode must be reverse-proxy");
        if(enabled) {
            URI uri;
            try{uri=URI.create(publicUrl);}catch(Exception invalid){throw new IllegalArgumentException("Invalid webui-public-url");}
            if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!uri.getPath().isEmpty())
                throw new IllegalArgumentException("webui-public-url must be an HTTPS origin without a trailing slash");
        }
    }
    public static WebSettings read(java.util.Properties p)throws IOException {
        String enabled=p.getProperty("webui-enabled","false");
        if(!enabled.equals("true")&&!enabled.equals("false"))throw new IOException("webui-enabled must be true or false");
        try{return new WebSettings(Boolean.parseBoolean(enabled),p.getProperty("webui-public-url",""),p.getProperty("webui-tls-mode","reverse-proxy"));}
        catch(IllegalArgumentException e){throw new IOException(e.getMessage());}
    }
}
