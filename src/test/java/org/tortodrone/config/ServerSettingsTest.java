package org.tortodrone.config;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import static org.tortodrone.support.TestWire.check;

public final class ServerSettingsTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-settings-");Path file=home.resolve("server.properties");
        try {
            check(ServerSettings.defaultPath(home).equals(home.resolve("config/server.yml")),"settings default to config/server.yml");
            check(ServerSettings.read(home.resolve("data/server.properties")).contentPacks().equals("vanilla")&&!Files.exists(home.resolve("data")),"reading content defaults creates no data or config directory");
            check(ServerSettings.load(file).maxClients()==16&&ServerSettings.load(file).webui().enabled()&&ServerSettings.load(file).webui().automaticHost(),"bundled properties enable HTTP administration with the existing client limit");
            check(Files.readString(file).contains("content-packs=vanilla")&&Files.readString(file).contains("legacy-content=auto"),"new settings include content selection defaults");
            Files.writeString(file,"content-packs=vanilla,addon\nlegacy-content=disabled\n");
            check(ServerSettings.load(file).contentPacks().equals("vanilla,addon")&&ServerSettings.load(file).legacyContent().equals("disabled"),"content selection uses the same settings file");
            Files.writeString(file,"legacy-content=invalid\n");
            try{ServerSettings.load(file);throw new AssertionError("Invalid legacy policy accepted");}catch(IOException expected){}
            Files.writeString(file,"server-port=0\n");
            check(ServerSettings.load(file).maxClients()==16,"existing configurations need no edits");
            for(int count:new int[]{50,100,200,300,1024}) {
                Files.writeString(file,"max-clients="+count+"\n");
                check(ServerSettings.load(file).maxClients()==count,"explicit benchmark capacity");
            }
            for(String value:new String[]{"0","-1","1025","invalid"}) {
                Files.writeString(file,"max-clients="+value+"\n");
                try{ServerSettings.load(file);throw new AssertionError("Unbounded connection limit accepted");}
                catch(IOException expected){check(expected.getMessage().startsWith("max-clients"),"clear limit error");}
            }
            Path yaml=home.resolve("server.yml");
            Files.writeString(yaml,"server-port: 0\n");
            var defaults=ServerSettings.read(yaml);
            check(defaults.bind().equals("0.0.0.0")&&defaults.httpPort()==8080&&defaults.webui().enabled()&&defaults.webui().directHttp()&&defaults.webui().automaticHost(),"defaults start HTTP WebUI on all interfaces at shared port 8080");
            check(defaults.webui().publicUrl().equals("http://0.0.0.0:8080"),"default public URL resolves the shared port");
            var loopback=java.net.InetAddress.ofLiteral("127.0.0.1");
            check(defaults.webui().browserAddresses(loopback,8080).equals(java.util.List.of("http://127.0.0.1:8080")),"browser URL uses concrete bind address and nonstandard port");
            check(defaults.webui().browserAddresses(loopback,80).equals(java.util.List.of("http://127.0.0.1")),"browser URL omits standard HTTP port");
            check(defaults.webui().browserAddresses(java.net.InetAddress.ofLiteral("0.0.0.0"),8080).contains("http://127.0.0.1:8080"),"wildcard binding advertises a usable loopback URL");
            check(new org.tortodrone.web.WebSettings(true,"https://admin.example:443","reverse-proxy").publicUrl().equals("https://admin.example"),"standard HTTPS port uses browser origin normalization");
            check(new org.tortodrone.web.WebSettings(true,"http://127.0.0.1:80","http").publicUrl().equals("http://127.0.0.1"),"standard HTTP port uses browser origin normalization");
            Path generated=home.resolve("generated/server.yml");var bundled=ServerSettings.load(generated);
            check(bundled.webui().enabled()&&bundled.webui().automaticHost()&&bundled.httpPort()==8080,"fresh bundled YAML enables HTTP administration");
            Files.writeString(yaml,"webui-public-url: 192.168.1.50\nwebui/content_url-port: 8091\n");
            check(ServerSettings.read(yaml).webui().publicUrl().equals("http://192.168.1.50:8091"),"bare HTTP host inherits scheme and shared port");
            Files.writeString(yaml,"webui-public-url: admin.example:8443\nwebui-tls-mode: reverse-proxy\n");
            check(ServerSettings.read(yaml).webui().publicUrl().equals("https://admin.example:8443"),"bare proxy host inherits HTTPS and retains external port");
            for(String host:new String[]{"admin.example/admin", "user@admin.example", "admin.example?bad=1", "admin.example#bad", ""}) {
                Files.writeString(yaml,"webui-public-url: \""+host+"\"\n");
                try{ServerSettings.read(yaml);throw new AssertionError("Invalid bare origin accepted: "+host);}catch(IOException expected){}
            }
            Files.writeString(yaml,"webui-enabled: false\n");check(!ServerSettings.read(yaml).webui().enabled(),"explicit disable remains supported");
            Files.writeString(yaml,"webui-enabled: true\nwebui-public-url: https://admin.example\nwebui-tls-mode: reverse-proxy\n");
            check(ServerSettings.read(yaml).webui().enabled(),"YAML boolean WebUI setting");
            Files.writeString(yaml,"webui-enabled: true\nwebui-public-url: http://admin.example\nwebui-tls-mode: reverse-proxy\n");
            try{ServerSettings.read(yaml);throw new AssertionError("Plaintext administration allowed");}catch(IOException expected){}
            Files.writeString(yaml,"webui-enabled: true\nwebui-public-url: http://192.168.1.50:8080\nwebui-tls-mode: http\n");
            check(ServerSettings.read(yaml).webui().directHttp(),"explicit HTTP settings remain compatible");
            Files.writeString(file,"webui-enabled=true\nwebui-public-url=http://192.168.1.50:8080\nwebui-tls-mode=http\n");
            check(ServerSettings.read(file).webui().directHttp(),"legacy properties support explicit HTTP mode");
            for(String url:new String[]{"https://192.168.1.50:8080","http://192.168.1.50:8080/admin/","http://user@192.168.1.50:8080","http://192.168.1.50:8080?bad=1","http://192.168.1.50:70000"}){
                Files.writeString(yaml,"webui-enabled: true\nwebui-public-url: "+url+"\nwebui-tls-mode: http\n");
                try{ServerSettings.read(yaml);throw new AssertionError("Invalid direct HTTP origin accepted: "+url);}catch(IOException expected){}
            }
            Files.writeString(yaml,"server-ip: 0.0.0.0\nwebui-enabled: true\nwebui-public-url: http://0.0.0.0\nwebui/content_url-port: 8091\nwebui-tls-mode: http\n");
            var shared=ServerSettings.read(yaml);
            check(shared.bind().equals("0.0.0.0")&&shared.httpPort()==8091&&shared.newsPort()==8091,"one canonical port shared with legacy Java accessor");
            check(shared.webui().automaticHost()&&shared.webui().publicUrl().equals("http://0.0.0.0:8091"),"wildcard URL inherits the shared port");
            Files.writeString(yaml,Files.readString(yaml).replace("http://0.0.0.0","http://192.168.1.50"));
            check(ServerSettings.read(yaml).webui().publicUrl().equals("http://192.168.1.50:8091"),"concrete host URL inherits shared port too");
            Files.writeString(file,"news-port=8091\nwebui-enabled=true\nwebui-public-url=http://192.168.1.50\nwebui-tls-mode=http\n");
            check(ServerSettings.read(file).httpPort()==8091&&ServerSettings.read(file).webui().publicUrl().equals("http://192.168.1.50:8091"),"legacy port remains compatible and fills the origin port");
            Files.writeString(file,Files.readString(file)+"webui/content_url-port=8091\n");
            check(ServerSettings.read(file).httpPort()==8091,"matching old/new port aliases are compatible");
            Files.writeString(file,Files.readString(file).replace("news-port=8091","news-port=8080"));
            try{ServerSettings.read(file);throw new AssertionError("Conflicting shared HTTP ports accepted");}catch(IOException expected){check(expected.getMessage().contains("same listener"),"clear conflicting port error");}
            for(String portValue:new String[]{"-1","65536","invalid"}) {
                Files.writeString(yaml,"webui/content_url-port: "+portValue+"\n");
                try{ServerSettings.read(yaml);throw new AssertionError("Invalid shared HTTP port accepted");}catch(IOException expected){}
            }
            Files.writeString(yaml,"webui-enabled: true\nwebui-public-url: http://0.0.0.0\nwebui/content_url-port: 0\nwebui-tls-mode: http\n");
            check(ServerSettings.read(yaml).httpPort()==0&&ServerSettings.read(yaml).webui().automaticHost(),"ephemeral automatic HTTP fixtures supported");
            Files.writeString(yaml,"webui-enabled: true\nwebui-public-url: https://admin.example\nwebui/content_url-port: 8091\nwebui-tls-mode: reverse-proxy\n");
            check(ServerSettings.read(yaml).webui().publicUrl().equals("https://admin.example"),"HTTPS proxy public port is independent of backend listener");
            Files.writeString(yaml,Files.readString(yaml).replace("https://admin.example","https://0.0.0.0"));
            try{ServerSettings.read(yaml);throw new AssertionError("Wildcard proxy hostname accepted");}catch(IOException expected){}
            System.out.println("PASS: bounded settings, one shared HTTP port, legacy compatibility, automatic host origins and proxy defaults");
        }finally{try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
}
