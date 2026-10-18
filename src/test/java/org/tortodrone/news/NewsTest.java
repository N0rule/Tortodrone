package org.tortodrone.news;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.resources.NewsYaml;
import org.tortodrone.server.NewsServer;
import org.tortodrone.support.TestWire;
import static org.tortodrone.support.TestWire.check;

/** Uplink News YAML validation, random pack selection, native result serialization and HTTP image serving. */
public final class NewsTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-news-");
        try {
            Path news=home.resolve("content/shared/news");Files.createDirectories(news);
            byte[] featured="FEATURED-IMAGE".getBytes(StandardCharsets.US_ASCII);
            Files.write(news.resolve("featured.png"),featured);Files.write(news.resolve("left.png"),new byte[]{5});
            Files.write(news.resolve("right.jpg"),new byte[]{6});Files.write(news.resolve("second.png"),new byte[]{7});
            Files.createDirectories(home.resolve("content/shared"));Files.write(home.resolve("content/shared/secret.png"),"TOP-SECRET".getBytes(StandardCharsets.US_ASCII));

            write(news,"""
                enabled: true
                packs:
                  - featured:
                      image: featured.png
                      tooltip: Featured
                      url: https://example.invalid/featured
                    spotlights:
                      left:
                        image: left.png
                      right:
                        image: right.jpg
                  - featured:
                      image: second.png
                """);
            var catalog=NewsYaml.load(news);
            check(catalog.enabled()&&catalog.size()==2,"two news packs enabled");
            check(catalog.propertyList(0).equals(List.of(
                "top.image","news/featured.png","top.tip","Featured","top.url","https://example.invalid/featured",
                "left.image","news/left.png","right.image","news/right.jpg")),"first pack property pairs use the news/ image prefix");
            check(catalog.propertyList(1).equals(List.of("top.image","news/second.png")),"spotlights stay optional");

            // Every response is one randomly chosen pack; with two packs both must appear.
            var seen=new HashSet<List<String>>();
            for(int i=0;i<200&&seen.size()<2;i++)seen.add(catalog.propertyList());
            check(seen.size()==2&&seen.contains(catalog.propertyList(0))&&seen.contains(catalog.propertyList(1)),"each request shows one random pack");

            Files.delete(news.resolve("news.yml"));
            check(!NewsYaml.load(news).enabled()&&NewsYaml.load(news).size()==0&&NewsYaml.load(news).propertyList().isEmpty(),"an absent news.yml keeps Uplink News disabled");

            write(news,"enabled: false\npacks:\n  - featured:\n      image: featured.png\n");
            var off=NewsYaml.load(news);
            check(off.size()==1&&off.propertyList().isEmpty()&&off.propertyList(0).isEmpty(),"disabled news answers with no properties");
            write(news,"enabled: false\n");
            check(NewsYaml.load(news).propertyList().isEmpty(),"disabled news needs no packs");

            // Strict startup validation; each malformed file must be refused, not silently ignored.
            String[][] invalid={
                {"enabled: true\nunknown: 1\n","unknown field"},
                {"enabled: 1\n","expected true or false"},
                {"enabled: true\npacks: []\n","at least one pack"},
                {"enabled: true\npacks: nope\n","expected a list"},
                {"enabled: true\npacks:\n  - nope\n","expected a mapping"},
                {"enabled: true\npacks:\n  - featured:\n      image: featured.png\n    extra: 1\n","unknown field"},
                {"enabled: true\npacks:\n  - spotlights:\n      left:\n        image: left.png\n","requires a featured banner"},
                {"enabled: true\npacks:\n  - featured:\n      image: featured.png\n    spotlights:\n      middle:\n        image: left.png\n","unknown field"},
                {"enabled: true\npacks:\n  - featured:\n      image: featured.png\n      action: x\n","unknown field"},
                {"enabled: true\npacks:\n  - featured:\n      tooltip: x\n","requires an image"},
                {"enabled: true\npacks:\n  - featured:\n      image: ../secret.png\n","escapes"},
                {"enabled: true\npacks:\n  - featured:\n      image: featured.txt\n","PNG or JPEG"},
                {"enabled: true\npacks:\n  - featured:\n      image: absent.png\n","image is missing"},
            };
            for(String[] bad:invalid) {
                write(news,bad[0]);
                failure(()->NewsYaml.load(news),bad[1]);
            }

            // The shipped default is one pack with the featured tooltip and link.
            var shipped=NewsYaml.load(Path.of("content/shared/news"));
            check(shipped.enabled()&&shipped.size()==1,"shipped default enables one news pack");
            check(shipped.propertyList(0).equals(List.of(
                "top.image","news/featured.png","top.tip","Tortodrone Server","top.url","https://github.com/N0rule/Tortodrone",
                "left.image","news/left.png","right.image","news/right.png")),"shipped pack carries the featured tooltip and link");

            // Native result: one List<String> of alternating names and values on the result listener.
            byte[] frame=new SessionCodec().listResult(42,(short)7,List.of("top.image","news/featured.png"));
            var in=new TestWire().frame(frame);var types=new HashMap<Integer,String>();
            check(type(in,types).equals("com.threerings.presents.net.EventNotification")&&in.readShort()==-1,"news result event notification");
            check(type(in,types).equals("com.threerings.presents.dobj.InvocationResponseEvent"),"news result invocation response");
            check(in.readInt()==42&&in.readShort()==7,"news result identity and correlation");
            check(in.readByte()==1&&in.readBoolean()&&in.readInt()==1,"news result success with one argument");
            check(type(in,types).equals("com.threerings.util.StreamableArrayList")&&in.readInt()==2,"news result is a two-entry list");
            check(type(in,types).equals("java.lang.String")&&in.readUTF().equals("top.image"),"news result property name");
            check(type(in,types).equals("java.lang.String")&&in.readUTF().equals("news/featured.png"),"news result property value");
            TestWire.end(in);

            // HTTP: the client fetches content_url + value, so only stored images are served.
            try(var server=NewsServer.start(InetAddress.getLoopbackAddress(),0,news,message->{ })) {
                var got=request(server.port(),"GET","/news/featured.png");
                check(got.status()==200&&Arrays.equals(got.body(),featured),"news image is served");
                check(got.head().toLowerCase(Locale.ROOT).contains("content-type: image/png"),"news image content type");
                check(request(server.port(),"GET","/news/right.jpg").head().contains("image/jpeg"),"jpeg content type");
                check(request(server.port(),"GET","/news/unknown.png").status()==404,"missing image is not served");
                check(request(server.port(),"GET","/news/news.yml").status()==404,"non-image files are not served");
                check(request(server.port(),"GET","/news/").status()==404,"the news root lists nothing");
                var escaped=request(server.port(),"GET","/news/../secret.png");
                check(escaped.status()!=200&&!new String(escaped.body(),StandardCharsets.ISO_8859_1).contains("TOP-SECRET"),"path traversal is refused");
                check(request(server.port(),"PUT","/news/featured.png").status()==405,"only GET and HEAD are allowed");
                var head=request(server.port(),"HEAD","/news/featured.png");
                check(head.status()==200&&head.body().length==0,"HEAD reports the image without a body");
            }
            System.out.println("PASS: Uplink News packs, random selection, native property list, result serialization and HTTP image serving");
        }finally{delete(home);}
    }
    private static void write(Path news,String yaml)throws IOException {Files.writeString(news.resolve("news.yml"),yaml);}
    private static String type(DataInputStream in,Map<Integer,String> types)throws IOException {
        int code=in.readShort();
        if(code<0){String name=in.readUTF();types.put(-code,name);return name;}
        check(types.containsKey(code),"known downstream class mapping");return types.get(code);
    }
    private record Response(int status,String head,byte[] body) { }
    /** Raw request writer, so traversal targets are not normalized by an HTTP client. */
    private static Response request(int port,String method,String target)throws IOException {
        try(var socket=new Socket(InetAddress.getLoopbackAddress(),port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write((method+" "+target+" HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            byte[] all=socket.getInputStream().readAllBytes();
            int split=indexOf(all,"\r\n\r\n");
            String head=new String(all,0,split,StandardCharsets.ISO_8859_1);
            byte[] body=Arrays.copyOfRange(all,split+4,all.length);
            return new Response(Integer.parseInt(head.substring(9,12)),head,body);
        }
    }
    private static int indexOf(byte[] data,String needle) {
        byte[] pattern=needle.getBytes(StandardCharsets.ISO_8859_1);
        for(int i=0;i<=data.length-pattern.length;i++) {
            boolean match=true;for(int j=0;j<pattern.length;j++)if(data[i+j]!=pattern[j]){match=false;break;}
            if(match)return i;
        }
        throw new AssertionError("Missing HTTP header terminator");
    }
    private interface Action{void run()throws Exception;}
    private static void failure(Action action,String message)throws Exception {
        try{action.run();throw new AssertionError("Invalid news configuration accepted: "+message);}
        catch(IOException expected){check(expected.toString().toLowerCase(Locale.ROOT).contains(message.toLowerCase(Locale.ROOT)),"actionable news error: "+expected);}
    }
    private static void delete(Path directory)throws IOException {
        try(var paths=Files.walk(directory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
    }
}
