package org.tortodrone.benchmark;

import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.Pattern;
import org.tortodrone.account.AccountStore;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.protocol.HandshakeCodec;
import org.tortodrone.storage.Database;
import org.tortodrone.support.*;
import org.tortodrone.world.SceneRegistry;

/** Real TCP/AES/password logins and town movement against a disposable packaged server.
 * Header-only downstream reader: compatibility fixtures validate actor payloads separately. */
public final class LoadBenchmark {
    private static final String PASSWORD="benchmark-password";
    private static final String DELTA="com.threerings.tudey.dobj.SceneDeltaEvent";
    private static final int FIRST_SCENE=10;
    private static final Set<Socket> SOCKETS=ConcurrentHashMap.newKeySet();
    private static volatile Process server;
    private static final class Metrics {
        final Queue<Double> login=new ConcurrentLinkedQueue<>(),ping=new ConcurrentLinkedQueue<>();
        final Queue<String> failures=new ConcurrentLinkedQueue<>();
        final LongAdder deltas=new LongAdder(),bytes=new LongAdder(),inputs=new LongAdder();
        final AtomicInteger ready=new AtomicInteger(),completed=new AtomicInteger();
        volatile long start,end;
    }
    public static void main(String[] args)throws Exception {
        int seconds=20,instances=1;int[] sizes={50,100,200,300};boolean town=true;
        for(int i=0;i<args.length;i++)switch(args[i]) {
            case "--seconds" -> seconds=Integer.parseInt(args[++i]);
            case "--clients" -> sizes=Arrays.stream(args[++i].split(",")).mapToInt(Integer::parseInt).toArray();
            case "--session-only" -> town=false;
            case "--instances" -> instances=Integer.parseInt(args[++i]);
            default -> throw new IllegalArgumentException("Usage: gradlew benchmark --args='[--seconds 20] [--clients 50,100,200,300] [--session-only] [--instances 1]'");
        }
        if(instances<1||instances>16)throw new IllegalArgumentException("instances: 1–16");
        if(seconds<1||seconds>300||sizes.length==0||Arrays.stream(sizes).anyMatch(n->n<1||n>1000))throw new IllegalArgumentException("seconds: 1–300; clients: 1–1000");
        Path home=Files.createTempDirectory("tortodrone-load-");
        System.out.println("Isolated benchmark: "+home+"; workload="+(town?"town movement":"authenticated sessions")+"; seconds="+seconds);
        Thread hook=new Thread(LoadBenchmark::stop,"BenchmarkCleanup");Runtime.getRuntime().addShutdownHook(hook);
        try {
            TestDeployment.copyServer(Path.of("build/tortodrone.jar"),home.resolve("tortodrone.jar"));
            Path manifest=home.resolve("defaults/maps/haven/map.properties");
            var settings=new Properties();try(var in=Files.newInputStream(manifest)){settings.load(in);}
            settings.setProperty("instances",Integer.toString(instances));try(var out=Files.newOutputStream(manifest)){settings.store(out,"isolated benchmark");}
            Path data=home.resolve("data");
            int maximum=Arrays.stream(sizes).max().orElseThrow();
            try(var accounts=new AccountStore(data)) {
                var database=new Database(data,false);
                // Disposable accounts share one synthetic password verifier. Real network
                // logins still run the unchanged 600,000-iteration verification per client.
                var protection=Passwords.protect(PASSWORD.toCharArray());
                for(int i=0;i<maximum;i++) {
                    String user="Bench"+i;database.create(user,protection,false);
                    new CharacterStore(new Database(data,false),i+1,user).create(new Knight(0,name(i),"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
                    if((i+1)%50==0)System.out.println("Prepared "+(i+1)+" disposable accounts.");
                }
            }
            Path report=home.resolve("results.csv");
            Files.writeString(report,"clients,workload,seconds,ready,completed,failed,login_p95_ms,ping_p50_ms,ping_p95_ms,ping_p99_ms,ping_samples,deltas_per_client_s,input_frames,downstream_mib,server_cpu_cores,server_peak_rss_mib,server_peak_threads,instances\n");
            boolean passed=true;for(int count:sizes)passed&=run(home,count,seconds,town,instances,report);
            System.out.println("Results: "+report);
            if(!passed)throw new IOException("Benchmark recorded failures; see reports above");
        }finally {
            stop();Runtime.getRuntime().removeShutdownHook(hook);
            // Keep reports/logs, remove disposable accounts and private keys.
            Path data=home.resolve("data");if(Files.exists(data))try(var paths=Files.walk(data)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        }
    }
    private static boolean run(Path home,int count,int seconds,boolean town,int instances,Path report)throws Exception {
        Files.writeString(home.resolve("data/server.properties"),"server-ip=127.0.0.1\nserver-port=0\nlog-mode=normal\nmax-clients="+count+"\n");
        Path log=home.resolve("server-"+count+".log");
        server=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx512m","-Xss256k","-jar",home.resolve("tortodrone.jar").toString(),"--nogui")
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        int port=0;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline) {
            var match=Pattern.compile("Listening on 127\\.0\\.0\\.1:(\\d+)").matcher(Files.readString(log));
            if(match.find()){port=Integer.parseInt(match.group(1));break;}
            if(!server.isAlive())throw new IOException("Benchmark server exited: "+log);Thread.sleep(50);
        }
        if(port==0)throw new IOException("Benchmark server readiness timeout: "+log);
        String[] key=Files.readString(home.resolve("data/keys/public.txt")).trim().split("#");
        PublicKey publicKey=KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(new BigInteger(key[0],16),new BigInteger(key[1],16)));
        var metrics=new Metrics();var ramp=new Semaphore(8);var settled=new CountDownLatch(count);var begin=new CountDownLatch(1);
        var tasks=new ArrayList<Future<?>>();final int endpoint=port;
        System.out.println("Starting "+count+" clients...");
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            for(int i=0;i<count;i++){final int id=i;tasks.add(workers.submit(()->client(endpoint,publicKey,id,town,instances,ramp,settled,begin,metrics)));}
            if(!settled.await(600,TimeUnit.SECONDS))metrics.failures.add("Ramp timeout");
            long cpu=cpuNanos();metrics.start=System.nanoTime();metrics.end=metrics.start+TimeUnit.SECONDS.toNanos(seconds);begin.countDown();
            long rss=0,threads=0;
            while(System.nanoTime()<metrics.end) {
                long[] sample=sample();rss=Math.max(rss,sample[0]);threads=Math.max(threads,sample[1]);Thread.sleep(200);
            }
            double cores=Math.max(0,cpuNanos()-cpu)/(double)TimeUnit.SECONDS.toNanos(seconds);
            for(var task:tasks)try{task.get(15,TimeUnit.SECONDS);}catch(Exception failure){metrics.failures.add(failure.getClass().getSimpleName());task.cancel(true);}
            String row=String.format(Locale.ROOT,"%d,%s,%d,%d,%d,%d,%.2f,%.2f,%.2f,%.2f,%d,%.2f,%d,%.2f,%.2f,%.2f,%d,%d%n",
                count,town?"town":"session",seconds,metrics.ready.get(),metrics.completed.get(),metrics.failures.size(),percentile(metrics.login,.95),percentile(metrics.ping,.5),percentile(metrics.ping,.95),percentile(metrics.ping,.99),metrics.ping.size(),metrics.deltas.sum()/(double)(count*seconds),metrics.inputs.sum(),metrics.bytes.sum()/1048576d,cores,rss/1024d,threads,instances);
            Files.writeString(report,row,StandardOpenOption.APPEND);System.out.print(row);
            if(!metrics.failures.isEmpty()) {
                Files.write(home.resolve("failures-"+count+".txt"),metrics.failures);
                System.out.println("Failures: "+home.resolve("failures-"+count+".txt"));
            }
            return metrics.failures.isEmpty()&&metrics.completed.get()==count;
        }finally{stop();}
    }
    private static void client(int port,PublicKey key,int id,boolean town,int instances,Semaphore ramp,CountDownLatch settled,CountDownLatch begin,Metrics metrics) {
        boolean[] released={false};
        try {
            ramp.acquire();
            try(var peer=new Socket()) {
                SOCKETS.add(peer);peer.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),port),5000);peer.setTcpNoDelay(true);
                long login=System.nanoTime();
                TestClient.authenticate(peer,key,"Bench"+id,PASSWORD,"success",20000,(socket,wire,oid)->{
                    metrics.login.add((System.nanoTime()-login)/1_000_000d);
                    var writer=new TestWire.Writer(TestWire.NET+"SecureRequest",TestWire.NET+"PublicKeyCredentials","[Ljava.lang.String;",TestWire.NET+"AESAuthRequest");
                    var reader=new Headers(FIRST_SCENE+Math.floorMod(id+1,instances));
                    if(town)enter(socket,writer,reader,oid,id);else {
                        writer.send(socket.getOutputStream(),out->{writer.message(out,"SubscribeRequest",30);out.writeInt(oid);});
                        wire.snapshot(wire.frame(socket.getInputStream()),(short)30,oid);
                    }
                    int ready=metrics.ready.incrementAndGet();if(ready%50==0)System.out.println("Ready: "+ready+" clients.");
                    ramp.release();settled.countDown();released[0]=true;
                    // Read continuously during ramp and measurement so peers never stall output queues.
                    var pending=new ConcurrentHashMap<Short,Long>();var failure=new AtomicReference<Exception>();var receivedPings=new AtomicInteger();
                    socket.setSoTimeout(240000);
                    Thread receiver=Thread.ofVirtual().start(()->{
                        try {
                            while(!socket.isClosed()) {
                                byte[] body=readDownstream(socket.getInputStream());if(body==null){if(metrics.end==0||System.nanoTime()<metrics.end)throw new EOFException("Disconnected during benchmark");break;}
                                var in=new DataInputStream(new ByteArrayInputStream(body));String type=reader.type(in);short sequence=in.readShort();
                                if((TestWire.NET+"PongResponse").equals(type)) {
                                    Long sent=pending.remove(sequence);if(sent!=null&&System.nanoTime()<metrics.end){metrics.ping.add((System.nanoTime()-sent)/1_000_000d);receivedPings.incrementAndGet();}
                                }else if((TestWire.NET+"EventNotification").equals(type)&&DELTA.equals(reader.type(in)))reader.delta(in);
                                if(System.nanoTime()>=metrics.start&&metrics.start!=0&&System.nanoTime()<metrics.end){metrics.bytes.add(body.length+4);if(reader.wasDelta)metrics.deltas.increment();}
                                reader.wasDelta=false;
                            }
                        }catch(Exception e){if(System.nanoTime()<metrics.end||metrics.end==0)failure.set(e);}
                    });
                    boolean success=false;
                    try {
                        // Keep early clients authenticated during a long ramp; only
                        // measured requests enter the latency sample set.
                        while(!begin.await(1,TimeUnit.SECONDS)) {
                            if(failure.get()!=null)throw failure.get();
                            writer.send(socket.getOutputStream(),out->writer.message(out,"PingRequest",50));
                        }
                        socket.setSoTimeout(8000);long nextPing=0,nextInput=0;int sequence=100;
                        while(System.nanoTime()<metrics.end) {
                            if(failure.get()!=null)throw failure.get();long now=System.nanoTime();
                            if(now>=nextPing) {
                                short request=(short)sequence++;pending.put(request,now);
                                writer.send(socket.getOutputStream(),out->writer.message(out,"PingRequest",request));nextPing=now+TimeUnit.SECONDS.toNanos(1);
                            }
                            if(town&&now>=nextInput) {
                                int stamp=reader.timestamp.get();float heading=((now-metrics.start)/TimeUnit.SECONDS.toNanos(2))%2==0?0:(float)Math.PI;
                                writer.send(socket.getOutputStream(),out->{writer.message(out,"ForwardEventRequest",31000);writer.type(out,"com.threerings.tudey.data.InputEnqueueEvent");out.writeInt(reader.scene);out.writeInt(stamp);out.writeInt(stamp);writer.type(out,"[Lcom.threerings.tudey.data.InputFrame;");out.writeInt(1);writer.type(out,"com.threerings.tudey.data.InputFrame");out.writeInt(stamp+50);out.writeFloat(heading);out.writeFloat(heading);out.writeInt(1);});
                                metrics.inputs.increment();nextInput=now+TimeUnit.MILLISECONDS.toNanos(100);
                            }
                            Thread.sleep(10);
                        }
                        if(failure.get()!=null)throw failure.get();
                        if(receivedPings.get()==0)throw new IOException("No ping responses during measurement");
                        if(pending.size()>2)throw new IOException("Unanswered ping requests");
                        writer.send(socket.getOutputStream(),out->writer.message(out,"LogoffRequest",32000));
                        receiver.join(5000);if(receiver.isAlive())throw new IOException("Logoff timeout");
                        success=true;
                    }finally{if(!success)socket.close();receiver.join(2000);}
                });
                metrics.completed.incrementAndGet();
            }
        }catch(Exception|AssertionError failure){metrics.failures.add("client "+id+": "+failure.getClass().getSimpleName()+": "+failure.getMessage());}
        finally{if(!released[0]){ramp.release();settled.countDown();}SOCKETS.removeIf(Socket::isClosed);}
    }
    private static void enter(Socket socket,TestWire.Writer writer,Headers reader,int oid,int id)throws Exception {
        // Register the real zone receiver, select the saved knight, subscribe, then enteredPlace.
        writer.send(socket.getOutputStream(),out->{writer.message(out,"ForwardEventRequest",20);writer.type(out,TestWire.DOB+"EntryAddedEvent");out.writeInt(oid);out.writeBoolean(true);out.writeUTF("receivers");writer.type(out,"com.threerings.presents.client.InvocationReceiver$Registration");out.writeBoolean(true);out.writeUTF(SceneRegistry.ZONE_RECEIVER);out.writeShort(0);});
        writer.send(socket.getOutputStream(),out->{writer.message(out,"ForwardEventRequest",21);writer.type(out,TestWire.DOB+"InvocationRequestEvent");out.writeInt(3);out.writeInt(1);out.writeByte(4);out.writeBoolean(true);out.writeInt(3);writer.type(out,"com.threerings.util.Name");out.writeBoolean(true);out.writeUTF(name(id));out.writeShort(0);writer.type(out,TestWire.INV+"ConfirmMarshaller");out.writeShort(22);});
        // Confirm selection before subscribing, preserving all header mappings seen en route.
        for(int i=0;i<10;i++) {
            var in=frame(socket);String type=reader.type(in);in.readShort();
            if((TestWire.NET+"EventNotification").equals(type)&& (TestWire.DOB+"InvocationResponseEvent").equals(reader.type(in))) {
                in.readInt();short callback=in.readShort();byte method=in.readByte();
                if(callback==22){if(method!=1)throw new IOException("Character selection refused");break;}
            }
            if(i==9)throw new IOException("No selection confirmation");
        }
        writer.send(socket.getOutputStream(),out->{writer.message(out,"SubscribeRequest",23);out.writeInt(reader.scene);});
        var response=frame(socket);if(!(TestWire.NET+"ObjectResponse").equals(reader.type(response))||response.readShort()!=23)throw new IOException("Missing scene subscription");
        writer.send(socket.getOutputStream(),out->{writer.message(out,"ForwardEventRequest",24);writer.type(out,TestWire.DOB+"InvocationRequestEvent");out.writeInt(reader.scene);out.writeInt(10);out.writeByte(1);out.writeBoolean(false);});
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline) {
            var in=frame(socket);if((TestWire.NET+"EventNotification").equals(reader.type(in))){in.readShort();if(DELTA.equals(reader.type(in))){reader.delta(in);reader.wasDelta=false;return;}}
        }
        throw new IOException("No initial scene delta");
    }
    private static DataInputStream frame(Socket socket)throws IOException {byte[] bytes=readDownstream(socket.getInputStream());if(bytes==null)throw new EOFException();return new DataInputStream(new ByteArrayInputStream(bytes));}
    private static byte[] readDownstream(InputStream source)throws IOException {
        var in=new DataInputStream(source);int first=in.read();if(first<0)return null;
        int size=(first<<24)|(in.readUnsignedByte()<<16)|(in.readUnsignedByte()<<8)|in.readUnsignedByte();
        if(size<6||size>org.tortodrone.protocol.SessionCodec.MAX_DOWNSTREAM_FRAME)throw new IOException("Invalid downstream frame length");
        byte[] body=new byte[size-4];in.readFully(body);return body;
    }
    private static final class Headers {
        final int scene;
        Headers(int scene){this.scene=scene;}
        final Map<Integer,String> types=new HashMap<>();final AtomicInteger timestamp=new AtomicInteger();boolean wasDelta;
        String type(DataInputStream in)throws IOException {int code=in.readShort();if(code<0){String name=in.readUTF();types.put(-code,name);return name;}return types.get(code);}
        void delta(DataInputStream in)throws IOException {in.readInt();if(in.readInt()!=scene)throw new IOException("Wrong delta scene");in.readInt();in.readShort();in.readInt();timestamp.set(in.readInt());wasDelta=true;}
    }
    private static String name(int id){return "Load-"+(char)('A'+id/676)+(char)('A'+id/26%26)+(char)('A'+id%26);}
    private static double percentile(Queue<Double> values,double quantile){if(values.isEmpty())return Double.NaN;var sorted=values.stream().mapToDouble(Double::doubleValue).sorted().toArray();return sorted[(int)Math.ceil(sorted.length*quantile)-1];}
    private static long cpuNanos(){return server.info().totalCpuDuration().orElse(Duration.ZERO).toNanos();}
    private static long[] sample(){
        long rss=0,threads=0;
        try{for(String line:Files.readAllLines(Path.of("/proc/"+server.pid()+"/status"))){if(line.startsWith("VmRSS:"))rss=Long.parseLong(line.split("\\s+")[1]);if(line.startsWith("Threads:"))threads=Long.parseLong(line.split("\\s+")[1]);}}catch(IOException ignored){}
        return new long[]{rss,threads};
    }
    private static synchronized void stop(){
        for(var socket:SOCKETS)try{socket.close();}catch(IOException ignored){}SOCKETS.clear();
        if(server!=null&&server.isAlive())try{var input=server.outputWriter();input.write("stop\n");input.flush();if(!server.waitFor(5,TimeUnit.SECONDS)){server.destroy();if(!server.waitFor(5,TimeUnit.SECONDS)){server.destroyForcibly();server.waitFor(5,TimeUnit.SECONDS);}}}
        catch(Exception interrupted){server.destroyForcibly();if(interrupted instanceof InterruptedException)Thread.currentThread().interrupt();}
    }
}
