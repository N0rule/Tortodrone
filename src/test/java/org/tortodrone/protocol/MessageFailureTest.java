package org.tortodrone.protocol;

import org.tortodrone.support.TestWire;
import static org.tortodrone.support.TestWire.check;

/** Stock m.* failure keys must reach the client for localization instead of literal display. */
public final class MessageFailureTest {
    public static void main(String[] args)throws Exception{
        check(failure("m.nsf_crowns").equals("m.nsf_crowns"),"crown shortage keeps its stock shop/global localization key");
        check(failure("m.nsf_tokens").equals("m.nsf_tokens"),"token shortage keeps its stock shop localization key");
        check(failure("m.nsf_energy").equals("m.nsf_energy"),"energy shortage keeps its stock global localization key");
        check(failure("m.inventory_full").equals("m.inventory_full"),"inventory shortage keeps its stock global localization key");
        check(failure("Enter a scene first").equals("~Enter a scene first"),"literal server text is still protected from translation lookup");
        check(failure("m.unsupported key").equals("~m.unsupported key"),"non-key text with spaces stays literal");
        System.out.println("PASS: stock m.* failure keys stay localizable and literal text remains escaped");
    }
    private static String failure(String reason)throws Exception{
        var wire=new TestWire();var in=wire.frame(new SessionCodec().invocationFailure(1001,(short)9,reason));
        check(wire.type(in).equals(TestWire.NET+"EventNotification")&&in.readShort()==-1,"notification envelope");
        check(wire.type(in).equals(TestWire.DOB+"InvocationResponseEvent")&&in.readInt()==1001&&in.readShort()==9&&in.readByte()==0,"failure callback");
        check(in.readBoolean()&&in.readInt()==1,"single failure argument");
        Object message=wire.object(in);
        TestWire.end(in);
        return (String)message;
    }
}
