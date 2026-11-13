package org.tortodrone.server.network;

import java.net.*;
import java.util.*;
import java.security.*;
import java.util.concurrent.*;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.support.TestClient;
import org.tortodrone.support.TestWire;
import static org.tortodrone.support.TestWire.check;

/** End-to-end synthetic authentication; never reads game files or saved credentials. */
public final class LocalAuthenticationTest {
    public static void main(String[] args) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair key = generator.generateKeyPair();
        exchange(key, true);
        exchange(key, false);
        rejection(key,"MissingUser","m.no_such_user");
        hardwareExchange(key);
        steamExchange(key);
        steamTicketExchange(key);
        System.out.println("PASS: authentication, snapshots, receivers, batched requests, invocation failure callbacks, logoff, secret-free logs");
    }

    private static void rejection(KeyPair key,String username,String reason)throws Exception {
        try(var workers=Executors.newSingleThreadExecutor();var listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            var server=workers.submit(()->{try(var socket=listener.accept()){SecureHandshake.run(socket,key.getPrivate(),message->{},true);}return null;});
            try(var peer=new Socket(listener.getInetAddress(),listener.getLocalPort())) {
                TestClient.authenticate(peer,key.getPublic(),username,"wrong-password",reason,(a,b,c)->{});
            }
            server.get(5,TimeUnit.SECONDS);
        }
    }

    private static void exchange(KeyPair key, boolean valid) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Future<List<String>> result = executor.submit(() -> {
                List<String> logs = new ArrayList<>();
                try (Socket peer = listener.accept()) {
                    peer.setSoTimeout(3000);
                    SecureHandshake.run(peer, key.getPrivate(), logs::add, true);
                }
                return logs;
            });
            try (Socket peer = new Socket(listener.getInetAddress(), listener.getLocalPort())) {
                // Authentication is the subject here; creation has its own focused program.
                if (valid) TestClient.authenticate(peer, key.getPublic(), "tortodrone", "tortodrone",
                    "success", 30000, (socket, wire, oid) -> TestClient.logoff(socket));
                else TestClient.authenticate(peer, key.getPublic(), false);
            }
            List<String> logs = result.get(40, TimeUnit.SECONDS);
            check(logs.stream().anyMatch(s -> s.contains(valid ? "Client joined;" : "result=rejected")), "completion evidence");
            check(logs.stream().filter(s->s.startsWith("Client joined;")).count()==(valid?1:0),"join metadata is emitted only after accepted authentication and attachment");
            if(valid)check(logs.stream().anyMatch(s->s.startsWith("Client joined;")&&s.contains("language=\"en\"")&&s.contains("timezone=\"UTC\"")&&s.contains("country=unavailable")),"locale and country availability reach the authenticated debug log");
            check(logs.stream().noneMatch(s -> s.contains("password=") || s.contains("private-test-ident")
                || s.contains(TestClient.passwordHash("tortodrone"))), "secret-free logs");
        } finally {
            executor.shutdownNow();
        }
    }

    /** The default synthetic account has id 1, so a Steam client must be aliased as steam:1. */
    private static void steamExchange(KeyPair key)throws Exception {
        var flags=new TestClient.LoginFlags(true,null,false,0,null);
        for(boolean malformed:new boolean[]{false,true})try(var workers=Executors.newSingleThreadExecutor();var listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            var result=workers.submit(()->{
                var logs=new ArrayList<String>();
                try(var socket=listener.accept()){
                    socket.setSoTimeout(5000);
                    try{SecureHandshake.run(socket,key.getPrivate(),logs::add,true);check(!malformed,"malformed steam report accepted");}
                    catch(org.tortodrone.protocol.SessionDecoder.Rejected rejected){check(malformed&&rejected.getMessage().equals("Invalid steam client report"),"malformed steam reports are rejected");}
                }
                return logs;
            });
            try(var peer=new Socket(listener.getInetAddress(),listener.getLocalPort())){
                TestClient.authenticate(peer,key.getPublic(),"tortodrone","tortodrone","success",30000,flags,"steam:1",(socket,wire,oid)->{
                    var writer=new TestWire.Writer(TestWire.NET+"SecureRequest",TestWire.NET+"PublicKeyCredentials","[Ljava.lang.String;",TestWire.NET+"AESAuthRequest");
                    // Unacknowledged stock SteamMarshaller reports: voice preference (5) and auth-ticket release (2).
                    report(writer,socket,50,5,"java.lang.Boolean",(out)->out.writeBoolean(true));
                    report(writer,socket,51,2,"java.lang.Integer",(out)->out.writeInt(7));
                    if(malformed){report(writer,socket,52,5,"java.lang.String",(out)->out.writeUTF("not-a-report"));return;}
                    // Both reports must leave the session alive.
                    writer.send(socket.getOutputStream(),out->writer.message(out,"PingRequest",53));
                    var pong=wire.frame(socket.getInputStream());
                    check(wire.type(pong).equals(TestWire.NET+"PongResponse")&&pong.readShort()==53,"steam reports do not end the session");
                    pong.readLong();pong.readInt();TestWire.end(pong);
                    // Stock friend-invite shape (method 4) carries a confirm listener and must fail natively.
                    writer.send(socket.getOutputStream(),out->{
                        writer.message(out,"ForwardEventRequest",54);writer.type(out,TestWire.DOB+"InvocationRequestEvent");
                        out.writeInt(3);out.writeInt(SessionCodec.STEAM_SERVICE);out.writeByte(4);out.writeBoolean(true);out.writeInt(2);
                        writer.type(out,"java.util.List");out.writeInt(0);
                        writer.type(out,TestWire.INV+"ConfirmMarshaller");out.writeShort(21);
                    });
                    var failure=wire.frame(socket.getInputStream());
                    check(wire.type(failure).equals(TestWire.NET+"EventNotification")&&failure.readShort()==-1,"steam failure callback envelope");
                    check(wire.type(failure).equals(TestWire.DOB+"InvocationResponseEvent"),"steam failure callback type");
                    check(failure.readInt()==oid&&failure.readShort()==21&&failure.readByte()==0,"steam failure callback ownership and correlation");
                    check(failure.readBoolean()&&failure.readInt()==1&&((String)wire.object(failure)).contains("Steam features are not available"),"steam requests fail natively");
                    TestWire.end(failure);
                    writer.send(socket.getOutputStream(),out->writer.message(out,"LogoffRequest",55));
                });
            }
            var logs=result.get(5,TimeUnit.SECONDS);
            check(logs.stream().anyMatch(s->s.startsWith("Client joined;")),"steam alias login joins normally");
            check(logs.stream().anyMatch(s->s.contains("Steam client alias assigned; accountId=1")),"steam alias assignment is logged");
            check(logs.stream().noneMatch(s->s.contains(TestClient.passwordHash("tortodrone"))),"steam alias path retains secret-free logs");
        }
    }

    /** A Steam account/ticket login is refused with a user-visible message, not a protocol error. */
    private static void steamTicketExchange(KeyPair key)throws Exception {
        var flags=new TestClient.LoginFlags(true,null,false,0,null,true);
        try(var workers=Executors.newSingleThreadExecutor();var listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            var result=workers.submit(()->{
                var logs=new ArrayList<String>();
                try(var socket=listener.accept()){
                    socket.setSoTimeout(5000);
                    SecureHandshake.run(socket,key.getPrivate(),logs::add,true);
                }
                return logs;
            });
            try(var peer=new Socket(listener.getInetAddress(),listener.getLocalPort())){
                TestClient.authenticate(peer,key.getPublic(),"steam:76561198000000000","ticket",
                    SecureHandshake.STEAM_LOGIN_MESSAGE,8000,flags,(socket,wire,oid)->{});
            }
            var logs=result.get(5,TimeUnit.SECONDS);
            check(logs.stream().anyMatch(s->s.equals("Steam platform-ticket login rejected; reason=steam_unsupported")),"ticket rejection is logged");
            check(logs.stream().noneMatch(s->s.startsWith("Normal login rejected;")||s.startsWith("Client joined;")),"ticket login never becomes a normal account session");
            check(logs.stream().noneMatch(s->s.contains(TestClient.passwordHash("tortodrone"))),"ticket rejection retains secret-free logs");
        }
    }

    private interface ArgumentWriter { void write(java.io.DataOutputStream out) throws java.io.IOException; }

    private static void report(TestWire.Writer writer,Socket socket,int sequence,int method,String type,ArgumentWriter value)throws Exception {
        writer.send(socket.getOutputStream(),out->{
            writer.message(out,"ForwardEventRequest",sequence);writer.type(out,TestWire.DOB+"InvocationRequestEvent");
            out.writeInt(3);out.writeInt(SessionCodec.STEAM_SERVICE);out.writeByte(method);out.writeBoolean(true);out.writeInt(1);
            writer.type(out,type);value.write(out);
        });
    }

    private static void hardwareExchange(KeyPair key)throws Exception {
        for(boolean foreign:new boolean[]{false,true})try(var workers=Executors.newSingleThreadExecutor();var listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            var result=workers.submit(()->{
                var logs=new ArrayList<String>();
                try(var socket=listener.accept()){
                    socket.setSoTimeout(5000);
                    try{SecureHandshake.run(socket,key.getPrivate(),logs::add,true);check(!foreign,"foreign diagnostic target accepted");}
                    catch(org.tortodrone.protocol.SessionDecoder.Rejected rejected){check(foreign&&rejected.getMessage().equals("Invalid invocation target"),"hardware target ownership remains enforced");}
                }
                return logs;
            });
            try(var peer=new Socket(listener.getInetAddress(),listener.getLocalPort())){
                TestClient.authenticate(peer,key.getPublic(),true,(socket,wire,oid)->{
                    var writer=new TestWire.Writer(TestWire.NET+"SecureRequest",TestWire.NET+"PublicKeyCredentials","[Ljava.lang.String;",TestWire.NET+"AESAuthRequest");
                    for(int copy=0;copy<(foreign?1:2);copy++)writer.send(socket.getOutputStream(),out->{
                        writer.message(out,"ForwardEventRequest",30);writer.type(out,TestWire.DOB+"InvocationRequestEvent");out.writeInt(foreign?oid:3);out.writeInt(1);out.writeByte(10);out.writeBoolean(true);out.writeInt(9);
                        for(String value:List.of("Fixture OS","1.0","Fixture CPU","25","amd64","1920x1080","1280x720")){writer.type(out,"java.lang.String");out.writeUTF(value);}
                        writer.type(out,"java.lang.Boolean");out.writeBoolean(false);writer.type(out,"java.lang.Float");out.writeFloat(1.25f);
                    });
                    if(!foreign)writer.send(socket.getOutputStream(),out->writer.message(out,"LogoffRequest",31));
                });
            }
            var logs=result.get(5,TimeUnit.SECONDS);
            check(logs.stream().filter(s->s.startsWith("Client hardware;")).count()==(foreign?0:1),"native hardware dispatch logs once and refuses foreign targets");
            if(!foreign)check(logs.stream().anyMatch(s->s.startsWith("Client hardware;")&&s.contains("account=\"tortodrone\" (#1)")
                &&s.contains("os=\"Fixture OS\" (build \"1.0\")")&&s.contains("java=\"25\" (\"amd64\")")
                &&s.contains("screen=\"1920x1080\"; window=\"1280x720\"; mode=windowed")&&s.contains("ui-scale=1.25")),"hardware summary stays readable and informative");
            check(logs.stream().noneMatch(s->s.contains("private-test-ident")||s.contains(TestClient.passwordHash("tortodrone"))),"hardware path retains secret-free authentication logs");
        }
    }
}
