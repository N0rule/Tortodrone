package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.character.Currency;
import org.tortodrone.protocol.*;
import org.tortodrone.storage.*;
import org.tortodrone.town.ExchangeState;
import org.tortodrone.world.TownSquare;
import static org.tortodrone.support.TestWire.check;

/** Two authenticated world sessions exercise the actual native invocation and polling paths. */
public final class EnergyExchangeServiceTest {
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("tortodrone-energy-service-");
        try{
            var db=new Database(root,true);var password=new Passwords.Protection(new byte[16],new byte[32]);db.create("Alice",password,false);db.create("Bob",password,false);
            var alice=new CharacterStore(db,1,"Alice");var bob=new CharacterStore(db,2,"Bob");alice.create(knight("Alice-Knight"));bob.create(knight("Bob-Knight"));db.currency("Alice","Alice-Knight",1,-1,Currency.CROWNS,10000);db.currency("Bob","Bob-Knight",2,-1,Currency.ENERGY,500);
            var socketA=new RecordingSocket();var socketB=new RecordingSocket();
            try(var outputA=new SessionOutput(socketA);var outputB=new SessionOutput(socketB);var a=new WorldSession(socketA,alice,outputA,new SessionCodec(),1002,1,s->{});var b=new WorldSession(socketB,bob,outputB,new SessionCodec(),1004,1,s->{})){
                var receivers=Map.of(TownSquare.ZONE_RECEIVER,(short)0);a.select("Alice-Knight",receivers);a.subscribe((short)1);a.entered();b.select("Bob-Knight",receivers);b.subscribe((short)1);b.entered();
                check(b.exchangeInvocation(request(3,2,5000,false,false)),"native exchange handler consumes an unsubscribed request");check(db.worldProfile(2,1,1).energy()==500,"unsubscribed clients cannot post funded offers");
                a.subscribeExchange((short)2);b.subscribeExchange((short)2);check(a.wantsExchange()&&b.wantsExchange(),"stock order-book subscriptions are registered");
                b.exchangeInvocation(request(3,2,5000,false,false));var ledger=new EnergyExchange(db);var offers=(List<?>)ledger.offers(2,1,1).result();var offer=(ExchangeState.Offer)((List<?>)offers.get(1)).getFirst();
                check(db.worldProfile(2,1,1).energy()==300,"native post-offer path escrows two lots");
                await(()->revision(a)>=ledger.book().revision(),"other subscriber receives the changed market without reopening it");
                a.exchangeInvocation(request(3,1,5000,true,true));check(db.worldProfile(1,1,1).crowns()==5000&&db.worldProfile(1,1,1).energy()==100&&db.worldProfile(2,1,1).crowns()==5000,"native instant trade commits both players' currency delivery");
                var before=db.worldProfile(1,1,1);a.exchangeInvocation(request(1,offer.id()));check(db.worldProfile(1,1,1).equals(before),"foreign cancellation receives a failure without a refund");
                b.unsubscribeExchange();b.exchangeInvocation(request(1,offer.id()));check(db.worldProfile(2,1,1).energy()==300,"unsubscribed cancellation cannot mutate escrow");b.subscribeExchange((short)3);b.exchangeInvocation(request(1,offer.id()));check(db.worldProfile(2,1,1).energy()==400,"native cancel refunds exactly the unfilled lot");
                await(()->revision(a)>=ledger.book().revision(),"cancel updates the other subscriber");
                try{a.exchangeInvocation(new SessionDecoder.Invocation(SessionCodec.EXCHANGE_OID,SessionCodec.EXCHANGE_SERVICE,3,List.of(1,5000,true,false,new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)4))));throw new AssertionError("wrong listener accepted");}catch(SessionDecoder.Rejected expected){}
                check(db.worldProfile(1,1,1).equals(before),"invalid native listener does not spend currency");
            }
            WorldStatePoller.awaitStopped();System.out.println("PASS: Energy Exchange native session routing, subscription authority, cross-session updates, escrow matching, cancellation and malformed listener refusal");
        }finally{WorldStatePoller.awaitStopped();try(var files=Files.walk(root)){for(var path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    private static SessionDecoder.Invocation request(int method,Object... arguments){var values=new ArrayList<Object>(Arrays.asList(arguments));values.add(new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$"+(method==1?"ConfirmMarshaller":"ResultMarshaller"),(short)10));return new SessionDecoder.Invocation(SessionCodec.EXCHANGE_OID,SessionCodec.EXCHANGE_SERVICE,method,values);}
    private static long revision(WorldSession world)throws Exception{synchronized(world){var f=WorldSession.class.getDeclaredField("exchangeRevision");f.setAccessible(true);return f.getLong(world);}}
    private static Knight knight(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0);}
    private static final class RecordingSocket extends Socket {private final OutputStream sink=OutputStream.nullOutputStream();@Override public OutputStream getOutputStream(){return sink;}}
    @FunctionalInterface private interface Condition{boolean value()throws Exception;}
    private static void await(Condition condition,String text)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){if(condition.value())return;Thread.sleep(25);}throw new AssertionError("Timed out: "+text);}
}
