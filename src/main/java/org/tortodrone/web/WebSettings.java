package org.tortodrone.web;

import java.io.IOException;
import java.net.URI;

/** Transport and browser-origin settings for shared HTTP administration. */
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
            if(!tlsMode.equals("http")&&uri.getHost().equals("0.0.0.0"))throw new IllegalArgumentException("Automatic WebUI addresses require http mode");
        }
    }
    public boolean directHttp(){return tlsMode.equals("http");}
    public boolean automaticHost(){return directHttp()&&"0.0.0.0".equals(URI.create(publicUrl).getHost());}
    public static WebSettings read(java.util.Properties p)throws IOException {
        return read(p,org.tortodrone.config.ServerSettings.DEFAULT_HTTP_PORT);
    }
    public static WebSettings read(java.util.Properties p,int httpPort)throws IOException {
        String enabled=p.getProperty("webui-enabled","false");
        if(!enabled.equals("true")&&!enabled.equals("false"))throw new IOException("webui-enabled must be true or false");
        try{
            String url=p.getProperty("webui-public-url",""),mode=p.getProperty("webui-tls-mode","reverse-proxy");
            if(Boolean.parseBoolean(enabled)&&!url.contains("://"))
                url=(mode.equals("http")?"http://":"https://")+url;
            if(Boolean.parseBoolean(enabled)&&mode.equals("http")) {
                URI uri=URI.create(url);
                if(uri.getHost()!=null&&uri.getPort()==-1&&httpPort!=80&&httpPort!=0)
                    url=new URI(uri.getScheme(),uri.getUserInfo(),uri.getHost(),httpPort,uri.getPath(),uri.getQuery(),uri.getFragment()).toString();
            }
            return new WebSettings(Boolean.parseBoolean(enabled),url,mode);
        }
        catch(java.net.URISyntaxException e){throw new IOException("Invalid webui-public-url",e);}
        catch(IllegalArgumentException e){throw new IOException(e.getMessage());}
    }
}
