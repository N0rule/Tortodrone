package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.character.Currency;
import org.tortodrone.protocol.*;
import org.tortodrone.storage.Database;
import org.tortodrone.world.SceneRegistry;
import static org.tortodrone.support.TestWire.check;

public final class PlayerTradeSessionTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-trade-session-");
        try {
            var db=new Database(home,true);var protection=new Passwords.Protection(new byte[16],new byte[32]);
            int alice=db.create("Alice",protection,false),bob=db.create("Bob",protection,false);
            var as=new CharacterStore(db,alice,"Alice");var bs=new CharacterStore(db,bob,"Bob");
            as.create(choices("Alice-Knight"));bs.create(choices("Bob-Knight"));
            db.grantItems("Alice","Alice-Knight",alice,-1,ItemCatalog.starter().require("Weapon/Sword/Proto Sword"),1,-1,-1);
            db.currency("Bob","Bob-Knight",bob,-1,Currency.CROWNS,20);
            long item=db.worldProfile(alice,1,1).items().stream().filter(i->!i.starter()).findFirst().orElseThrow().id();
            var sa=new RecordingSocket();var sb=new RecordingSocket();
            try(var oa=new SessionOutput(sa);var ob=new SessionOutput(sb);
                    var a=new WorldSession(sa,as,oa,new SessionCodec(),1102,1,m->{});
                    var b=new WorldSession(sb,bs,ob,new SessionCodec(),1104,1,m->{})) {
                ready(a,"Alice-Knight");ready(b,"Bob-Knight");
                a.tradeInvocation(rpc(10,6,4,"Bob-Knight",confirm()));sb.await("Notification$TradeRequest");
                a.tradeInvocation(rpc(10,6,4,"Bob-Knight",confirm()));sa.await("already open");
                b.tradeInvocation(rpc(3,12,5,"TRADE_REQ:Alice-Knight",1,confirm()));
                check(a.ownsTrade(1001)&&b.ownsTrade(1001),"only the two participants own the opened trade");
                a.subscribeTrade(1001,(short)8);b.subscribeTrade(1001,(short)9);
                a.tradeInvocation(rpc(1001,1,2,item,1,confirm()));
                a.tradeInvocation(rpc(1001,1,1,2,confirm()));
                b.tradeInvocation(rpc(1001,1,4,10,confirm())); // Changes revision and invalidates first acceptance.
                a.tradeInvocation(rpc(1001,1,1,2,confirm()));sa.await("offer changed");
                check(db.worldProfile(alice,1,1).items().stream().anyMatch(i->i.id()==item),"stale acceptance does not transfer ownership");
                a.tradeInvocation(rpc(1001,1,1,3,confirm()));b.tradeInvocation(rpc(1001,1,1,3,confirm()));
                var committed=db.worldProfile(bob,1,1);
                check(committed.items().stream().anyMatch(i->i.id()==item)&&committed.crowns()==10&&db.worldProfile(alice,1,1).crowns()==10,"final acceptance transfers atomically");
                b.tradeInvocation(rpc(1001,1,1,3,confirm()));sb.await("already completed");
                check(db.worldProfile(bob,1,1).equals(committed),"completed acceptance cannot replay");
                a.tradeInvocation(rpc(10,6,1,0L));check(a.ownsTrade(1001),"stale dialog close preserves newer dialog");
                a.unsubscribeTrade(1001);check(!a.ownsTrade(1001)&&!b.ownsTrade(1001),"closing one view releases both participants");
                a.tradeInvocation(rpc(10,6,4,"Bob-Knight",confirm()));b.tradeInvocation(rpc(3,12,5,"TRADE_REQ:Alice-Knight",1,confirm()));
                check(a.ownsTrade(1003),"new trade gets a fresh object identity");
                a.unsubscribe();check(!a.ownsTrade(1003)&&!b.ownsTrade(1003),"scene departure cancels both sides");
                a.tradeInvocation(rpc(1003,1,1,1,confirm()));sa.await("Enter a scene");
                a.subscribe((short)10);a.entered();
                a.tradeInvocation(rpc(10,6,4,"Bob-Knight",confirm()));b.tradeInvocation(rpc(3,12,5,"TRADE_REQ:Alice-Knight",1,confirm()));
                db.currency("Bob","Bob-Knight",bob,-1,Currency.ENERGY,1);
                a.tradeInvocation(rpc(1005,1,1,1,confirm()));sa.await("Inventory changed");
                check(!a.ownsTrade(1005)&&!b.ownsTrade(1005),"external state change cancels pending offers before commit");
                db.password("Bob",protection,bob);
                a.tradeInvocation(rpc(10,6,4,"Bob-Knight",confirm()));sa.await("account changed");
            }
            System.out.println("PASS: stock trade routing, invitation/readiness, offer revision invalidation, exactly-once commit, stale close, travel and immediate storage/authentication cancellation");
        }finally{try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    private static void ready(WorldSession world,String name)throws Exception {world.select(name,Map.of(SceneRegistry.ZONE_RECEIVER,(short)0));world.subscribe((short)1);world.entered();}
    private static Knight choices(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0);}
    private static SessionDecoder.Listener confirm(){return new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)7);}
    private static SessionDecoder.Invocation rpc(int oid,int service,int method,Object... args){return new SessionDecoder.Invocation(oid,service,method,Arrays.asList(args));}
    private static final class RecordingSocket extends Socket {
        private final BlockingQueue<String> frames=new LinkedBlockingQueue<>();
        public OutputStream getOutputStream(){return new OutputStream(){public void write(int value){throw new AssertionError("whole frames");}public void write(byte[] bytes){frames.add(new String(bytes,StandardCharsets.ISO_8859_1));}};}
        void await(String text)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){var frame=frames.poll(100,TimeUnit.MILLISECONDS);if(frame!=null&&frame.contains(text))return;}throw new AssertionError("Missing response: "+text);}
    }
}
