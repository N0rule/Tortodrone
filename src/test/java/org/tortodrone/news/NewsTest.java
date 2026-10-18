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

/** Uplink News YAML validation, native result serialization and HTTP image serving. */
public final class NewsTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-news-");
        try {
            Path news=home.resolve("content/shared/news");Files.createDirectories(news);
            byte[] featured="FEATURED-IMAGE".getBytes(StandardCharsets.US_ASCII);
            Files.write(news.resolve("featured.png"),featured);Files.write(news.resolve("left.png"),new byte[]{5});Files.write(news.resolve("right.jpg"),new byte[]{6});
            Files.createDirectories(home.resolve("content/shared"));Files.write(home.resolve("content/shared/secret.png"),"TOP-SECRET".getBytes(StandardCharsets.US_ASCII));

            write(news,"""
                enabled: true
                featured:
                  image: featured.png
                  tooltip: Featured
                  url: https://example.invalid/featured
                spotlights:
                  left:
                    image: left.png
                  right:
                    image: right.jpg
                """);
            var catalog=NewsYaml.load(news);
            check(catalog.enabled(),"news enabled");
            check(catalog.propertyList().equals(List.of(
                "top.image","news/featured.png","top.tip","Featured","top.url","https://example.invalid/featured",
                "left.image","news/left.png","right.image","news/right.jpg")),"native property pairs use the news/ image prefix");

            Files.delete(news.resolve("news.yml"));
            check(!NewsYaml.load(news).enabled()&&NewsYaml.load(news).propertyList().isEmpty(),"an absent news.yml keeps Uplink News disabled");

            write(news,"enabled: false\nfeatured:\n  image: featured.png\n");
            check(NewsYaml.load(news).propertyList().isEmpty(),"disabled news answers with no properties");
            write(news,"enabled: true\nfeatured:\n  image: featured.png\n");
            check(NewsYaml.load(news).propertyList().equals(List.of("top.image","news/featured.png")),"tooltip and url stay optional");

            // Strict startup validation; each malformed file must be refused, not silently ignored.
            String[][] invalid={
                {"enabled: true\nunknown: 1\n","unknown field"},
                {"enabled: 1\n","expected true or false"},
                {"enabled: true\nfeatured: nope\n","expected a mapping"},
                {"enabled: true\nfeatured:\n  tooltip: x\n","requires an image"},
                {"enabled: true\nfeatured:\n  image: ../secret.png\n","escapes"},
                {"enabled: true\nfeatured:\n  image: featured.txt\n","PNG or JPEG"},
                {"enabled: true\nfeatured:\n  image: absent.png\n","image is missing"},
                {"enabled: true\nfeatured:\n  image: featured.png\n  action: x\n","unknown field"},
                {"enabled: true\nspotlights:\n  middle:\n    image: left.png\n","unknown field"},
            };
            for(String[] bad:invalid) {
                write(news,bad[0]);
                failure(()->NewsYaml.load(news),bad[1]);
            }
            write(news,"enabled: true\nfeatured:\n  image: featured.png\n");

            // The shipped default must enable all three banners for the unmodified client.
            var shipped=NewsYaml.load(Path.of("content/shared/news"));
            check(shipped.enabled()&&shipped.propertyList().equals(List.of(
                "top.image","news/featured.png","left.image","news/left.png","right.image","news/right.png")),"shipped default enables all three banners");

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
            System.out.println("PASS: Uplink News YAML, native property list, result serialization and HTTP image serving");
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
