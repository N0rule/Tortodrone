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
            check(ServerSettings.load(file).maxClients()==16,"existing connection limit remains the default");
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
            check(ServerSettings.read(yaml).newsPort()==8080&&!ServerSettings.read(yaml).webui().enabled(),"shared default port and opt-in administration");
            Files.writeString(yaml,"webui-enabled: true\nwebui-public-url: https://admin.example\nwebui-tls-mode: reverse-proxy\n");
            check(ServerSettings.read(yaml).webui().enabled(),"YAML boolean WebUI setting");
            Files.writeString(yaml,"webui-enabled: true\nwebui-public-url: http://admin.example\n");
            try{ServerSettings.read(yaml);throw new AssertionError("Plaintext administration allowed");}catch(IOException expected){}
            Files.writeString(yaml,"webui-enabled: true\nwebui-public-url: http://192.168.1.50:8080\nwebui-tls-mode: http\n");
            check(ServerSettings.read(yaml).webui().directHttp(),"direct HTTP requires an explicit transport opt-in");
            Files.writeString(file,"webui-enabled=true\nwebui-public-url=http://192.168.1.50:8080\nwebui-tls-mode=http\n");
            check(ServerSettings.read(file).webui().directHttp(),"legacy properties support explicit HTTP mode");
            for(String url:new String[]{"https://192.168.1.50:8080","http://192.168.1.50:8080/admin/","http://user@192.168.1.50:8080","http://192.168.1.50:8080?bad=1","http://192.168.1.50:70000"}){
                Files.writeString(yaml,"webui-enabled: true\nwebui-public-url: "+url+"\nwebui-tls-mode: http\n");
                try{ServerSettings.read(yaml);throw new AssertionError("Invalid direct HTTP origin accepted: "+url);}catch(IOException expected){}
            }
            System.out.println("PASS: bounded client capacity, default and existing configuration compatibility");
        }finally{try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
}
