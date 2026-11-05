package org.tortodrone.server.network;

import java.net.*;
import java.util.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
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
            check(logs.stream().filter(s->s.startsWith("Client hardware (client-reported);")).count()==(foreign?0:1),"native hardware dispatch logs once and refuses foreign targets");
            check(logs.stream().noneMatch(s->s.contains("private-test-ident")||s.contains(TestClient.passwordHash("tortodrone"))),"hardware path retains secret-free authentication logs");
        }
    }
}
