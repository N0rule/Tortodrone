import java.io.*;
import java.util.*;
import com.threerings.presents.client.A;
import com.threerings.presents.dobj.*;
import com.threerings.presents.net.*;
import org.tortodrone.protocol.*;
import org.tortodrone.town.ExchangeState;
import org.tortodrone.character.WorldProfile;

/** Native Energy Exchange object, RPC and response oracle; no client modifications or graphics. */
public final class ExchangeCompatibility {
    private static InvocationRequestEvent captured;
    private static final class Dispatch extends com.threerings.presents.client.x {
        @Override public void a(int oid,int service,int method,Object[] args,com.threerings.presents.net.Transport transport){captured=new InvocationRequestEvent(oid,service,method,args);}
    }
    public static void main(String[] args)throws Exception{
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        var marshaller=new com.threerings.projectx.exchange.data.ExchangeMarshaller();marshaller.a(SessionCodec.EXCHANGE_OID,SessionCodec.EXCHANGE_SERVICE,new Dispatch());
        marshaller.i(17,(A.a)null);check(1,List.of(17),"ConfirmMarshaller");marshaller.k((A.c)null);check(2,List.of(),"ResultMarshaller");marshaller.a(2,6000,true,false,(A.c)null);check(3,List.of(2,6000,true,false),"ResultMarshaller");
        var codec=new SessionCodec();var bytes=new ByteArrayOutputStream();
        append(bytes,HandshakeCodec.response((short)1,"0123456789abcdef0123456789abcdef"));append(bytes,HandshakeCodec.authenticationResponse((short)2,true,"synthetic","Alice"));
        append(bytes,codec.bootstrap(1,1002,"127.0.0.1",47624));
        var book=new ExchangeState.Book(1,5600,List.of(new ExchangeState.Level(7,5500)),List.of(new ExchangeState.Level(3,5700),new ExchangeState.Level(4,5800)));
        append(bytes,codec.exchange((short)3,book));var update=new ExchangeState.Book(2,5700,book.buys(),List.of(new ExchangeState.Level(2,5700)));append(bytes,codec.exchangeChanged(update));
        var profile=new WorldProfile(10,1,1,0,0,1,List.of());var offer=new ExchangeState.Offer(17,true,5500,7);
        append(bytes,codec.townResult(1002,(short)4,List.of(List.of(offer),List.of()),profile));append(bytes,codec.townResult(1002,(short)5,offer,profile));append(bytes,codec.townResult(1002,(short)6,null,profile));
        var in=new com.threerings.io.v(new ByteArrayInputStream(bytes.toByteArray()));in.qe();in.qe();var bootstrap=(com.threerings.projectx.data.ProjectXBootstrapData)((BootstrapNotification)in.qe()).yh();if(bootstrap.exchangeOid!=SessionCodec.EXCHANGE_OID)throw new AssertionError("Energy Exchange bootstrap is missing");
        var exchange=(com.threerings.projectx.exchange.data.ExchangeObject)((ObjectResponse<?>)in.qe()).yk();if(exchange.exchangeService.xA()!=SessionCodec.EXCHANGE_SERVICE||exchange.lastPrice!=5600||exchange.buyOffers.length!=1||exchange.buyOffers[0].price!=5500||exchange.buyOffers[0].volume!=7||exchange.sellOffers[1].price!=5800)throw new AssertionError("Native order book differs");
        var changes=(CompoundEvent)((EventNotification)in.qe()).yj();for(var e:changes.xE())e.b(exchange);if(exchange.lastPrice!=5700||exchange.sellOffers.length!=1||exchange.sellOffers[0].volume!=2)throw new AssertionError("Native order book update differs");
        var own=(List<?>)response(in);var decoded=(com.threerings.coin.data.CoinExOfferInfo)((List<?>)own.get(0)).getFirst();verify(decoded);verify((com.threerings.coin.data.CoinExOfferInfo)response(in));if(response(in)!=null||in.available()!=0)throw new AssertionError("Trailing native callback data");
        System.out.println("PASS: installed Energy Exchange bootstrap, subscriptions, order book updates, own offers, result payloads and all RPC layouts");
    }
    private static Object response(com.threerings.io.v in)throws Exception{return ((InvocationResponseEvent)((EventNotification)in.qe()).yj()).yd()[0];}
    private static void verify(com.threerings.coin.data.CoinExOfferInfo offer){if(offer.offerId!=17||!offer.buy||offer.price!=5500||offer.volume!=7)throw new AssertionError("Native offer fields differ: "+offer);}
    private static void append(ByteArrayOutputStream out,byte[] frame){out.write(frame,4,frame.length-4);}
    private static void check(int method,List<?> args,String listener)throws Exception{
        var bytes=new ByteArrayOutputStream();var out=new com.threerings.io.w(bytes);out.bf(new ForwardEventRequest(captured));out.flush();var parsed=(SessionDecoder.Invocation)new HandshakeCodec().sessionRequest(bytes.toByteArray()).event();
        if(parsed.oid()!=SessionCodec.EXCHANGE_OID||parsed.service()!=SessionCodec.EXCHANGE_SERVICE||parsed.method()!=method||!parsed.arguments().subList(0,parsed.arguments().size()-1).equals(args)||!(parsed.arguments().getLast() instanceof SessionDecoder.Listener l)||!l.type().endsWith("$"+listener))throw new AssertionError("Native exchange invocation differs: "+parsed);
    }
}
