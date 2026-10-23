package org.tortodrone.integration;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import org.tortodrone.resources.Json;
import org.tortodrone.support.TestDeployment;
import static org.tortodrone.support.TestWire.check;

/** One packaged executable: first-run page, local recovery while stopped, and persistent setup closure. */
public final class WebUiSetupIntegrationTest {
    private static final String FIRST="first-admin", PASSWORD="disposable-first-password", RECOVERED="disposable-recovered-password";
    public static void main(String[] args)throws Exception {
        Path source=Path.of(args[0]).toAbsolutePath(),root=Files.createTempDirectory("tortodrone-webui-setup-integration-");
        try {
            try(var jar=new JarFile(source.toFile())){check(jar.getEntry("org/tortodrone/admin/AdminApplication.class")==null,"obsolete admin application is absent from server JAR");}
            TestDeployment.copyServer(source,root.resolve("tortodrone.jar"));
            Files.writeString(root.resolve("config/server.yml"),"server-port: 0\nwebui/content_url-port: 0\n");
            try(var server=new Running(root)) {
                server.ready();server.await("WebUI first-run setup code: ");String code=server.setupCode();check(code!=null,"first-run code appears only in local console output");
                String output=server.output();
                check(output.indexOf("Done! Listening on")<output.indexOf("WebUI Ready ")&&output.indexOf("WebUI Ready ")<output.indexOf("Server ready (")&&output.indexOf("Type help for server commands.")<output.indexOf("WebUI first-run setup code:"),"usable WebUI URL and private setup code follow startup messages in order");
                var rootPage=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.port()+"/")).GET().build(),HttpResponse.BodyHandlers.ofString());
                check(rootPage.statusCode()==302&&rootPage.headers().firstValue("Location").orElse("").equals("/admin/"),"bare IP redirects to WebUI on the same listener");
                var admin=new Client(server.port());
                check(Boolean.TRUE.equals(admin.get("setup").get("required")),"default configuration serves first-run WebUI without explicit enable, URL or transport settings");
                var html=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(admin.origin+"/admin/")).GET().build(),HttpResponse.BodyHandlers.ofString());
                check(html.statusCode()==200&&html.body().contains("setup-fields"),"frontend assets shipped beside the single JAR");
                var setup=new HashMap<String,Object>(Map.of("username",FIRST,"password",PASSWORD,"passwordConfirmation",PASSWORD,"setupCode","incorrect"));
                admin.post("setup",setup,403);check(Boolean.TRUE.equals(admin.get("setup").get("required")),"wrong code never claims setup");
                setup.put("setupCode",code);var created=admin.post("setup",setup,200);admin.csrf=(String)created.get("csrf");
                check(admin.get("session").get("role").equals("ADMINISTRATOR"),"first browser account has administrator role");
                admin.post("setup",setup,403);check(Boolean.FALSE.equals(admin.get("setup").get("required")),"setup closes after commit");
                var first=admin.user(FIRST);
                admin.post("webui-user-delete",Map.of("username",FIRST,"revision",first.get("revision"),"nameConfirmation",FIRST),409);
                admin.post("webui-user-role",Map.of("username",FIRST,"revision",first.get("revision"),"role","VIEWER"),409);
                admin.post("webui-user-create",Map.of("username","staff-viewer","password",PASSWORD,"passwordConfirmation",PASSWORD,"role","VIEWER"),200);
                var viewer=new Client(server.port());viewer.login("staff-viewer",PASSWORD);
                viewer.request("webui-users",null,403);viewer.post("webui-user-create",Map.of("username","forbidden","password",PASSWORD,"passwordConfirmation",PASSWORD,"role","ADMINISTRATOR"),403);
                check(!Files.readString(root.resolve("data/logs/latest.log")).contains(code),"setup code is never written to operational logs");
                check(!Json.stringify(admin.request("logs",null,200)).contains(code),"setup code is not exposed through WebUI logs");
                admin.post("stop",Map.of(),200);await(()->{try{return admin.get("status").get("state").equals("STOPPED");}catch(Exception e){return false;}},"game listener stop");
                server.send("webui password "+FIRST+"\n"+RECOVERED+"\n"+RECOVERED+"\n");server.await("WebUI password changed: "+FIRST);
                admin.request("session",null,401);check(viewer.get("status").get("state").equals("STOPPED"),"console recovery works while HTTP stays up and game listener is stopped");
                server.send("webui user rescue ADMINISTRATOR\n"+PASSWORD+"\n"+PASSWORD+"\n");server.await("WebUI account saved: rescue");
                server.send("webui list\n");server.await("rescue | ADMINISTRATOR");
                var rescue=new Client(server.port());rescue.login("rescue",PASSWORD);
                var row=rescue.user("staff-viewer");
                rescue.post("webui-user-password",Map.of("username","staff-viewer","revision",row.get("revision"),"password",RECOVERED,"passwordConfirmation",RECOVERED),200);
                viewer.request("session",null,401);
                viewer.login("staff-viewer",RECOVERED);row=rescue.user("staff-viewer");
                rescue.post("webui-user-role",Map.of("username","staff-viewer","revision",row.get("revision"),"role","MODERATOR"),200);viewer.request("session",null,401);
                row=rescue.user("staff-viewer");rescue.post("webui-user-delete",Map.of("username","staff-viewer","revision",row.get("revision"),"nameConfirmation","staff-viewer"),200);
                check(!Files.readString(root.resolve("data/logs/latest.log")).contains(PASSWORD)&&!Files.readString(root.resolve("data/logs/latest.log")).contains(RECOVERED),"recovery passwords never reach logs");
                server.send("start\n");await(()->{try{return rescue.get("status").get("state").equals("RUNNING");}catch(Exception e){return false;}},"console starts listener again");
                rescue.post("shutdown",Map.of("shutdown","SHUT DOWN PROCESS"),200);check(server.process.waitFor(15,TimeUnit.SECONDS)&&server.process.exitValue()==0,"confirmed full shutdown exits cleanly");
            }
            try(var server=new Running(root)) {
                server.ready();check(server.setupCode()==null,"setup never reopens on restart");
                var client=new Client(server.port());check(Boolean.FALSE.equals(client.get("setup").get("required")),"existing accounts show sign-in instead");
                client.login("rescue",PASSWORD);server.send("shutdown\nSHUT DOWN PROCESS\n");
                check(server.process.waitFor(15,TimeUnit.SECONDS)&&server.process.exitValue()==0,"existing WebUI credentials survive restart");
            }
            System.out.println("PASS: single packaged JAR, first-run setup, private one-time console code, WebUI account CRUD, console recovery while stopped and persistent setup closure");
        }finally{try(var paths=Files.walk(root)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    private static void await(BooleanSupplier test,String name)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);while(System.nanoTime()<end){if(test.getAsBoolean())return;Thread.sleep(25);}throw new AssertionError("Timed out: "+name);}
    private static final class Running implements AutoCloseable {
        final Process process;final Path output;
        Running(Path root)throws IOException {
            output=root.resolve("console-output.txt");
            process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"--enable-native-access=ALL-UNNAMED","-jar",root.resolve("tortodrone.jar").toString(),"--nogui").directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        }
        String output(){try{return Files.readString(output);}catch(IOException e){return "";}}
        void ready()throws Exception{WebUiSetupIntegrationTest.await(()->output().contains("Type help for server commands.")&&output().contains("WebUI Ready "),"packaged server ready");check(process.isAlive(),"server remains running");}
        int port(){var m=Pattern.compile("WebUI Ready http://127\\.0\\.0\\.1:([0-9]+)").matcher(output());check(m.find(),"shared HTTP port printed");return Integer.parseInt(m.group(1));}
        String setupCode(){var m=Pattern.compile("WebUI first-run setup code: ([0-9]{8})").matcher(output());return m.find()?m.group(1):null;}
        void send(String text)throws IOException{process.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));process.getOutputStream().flush();}
        void await(String message)throws Exception{WebUiSetupIntegrationTest.await(()->output().contains(message),message);}
        public void close()throws Exception{if(process.isAlive()){process.destroy();if(!process.waitFor(15,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS);}}}
    }
    private static final class Client {
        final String origin;final HttpClient client;String csrf="";
        Client(int port){origin="http://127.0.0.1:"+port;client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER)).connectTimeout(Duration.ofSeconds(5)).build();}
        @SuppressWarnings("unchecked") Map<String,Object> get(String route)throws Exception{return (Map<String,Object>)request(route,null,200);}
        @SuppressWarnings("unchecked") Map<String,Object> post(String route,Map<String,?> fields,int expected)throws Exception{return (Map<String,Object>)request(route,fields,expected);}
        void login(String username,String password)throws Exception{csrf=(String)post("login",Map.of("username",username,"password",password),200).get("csrf");}
        @SuppressWarnings("unchecked") Map<String,Object> user(String username)throws Exception{return ((List<Map<String,Object>>)request("webui-users",null,200)).stream().filter(u->u.get("username").equals(username)).findFirst().orElseThrow();}
        Object request(String route,Map<String,?> fields,int expected)throws Exception {
            var builder=HttpRequest.newBuilder(URI.create(origin+"/admin/api/"+route)).timeout(Duration.ofSeconds(15));
            if(fields==null)builder.GET();else {
                var values=new HashMap<String,Object>(fields);values.put("confirmation",route);
                String body=values.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue().toString(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
                builder.header("Origin",origin).header("Content-Type","application/x-www-form-urlencoded;charset=UTF-8").header("X-CSRF-Token",csrf).POST(HttpRequest.BodyPublishers.ofString(body));
            }
            var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
            check(response.statusCode()==expected,route+" status "+response.statusCode()+" expected "+expected+": "+response.body());return Json.parse(response.body());
        }
    }
}
