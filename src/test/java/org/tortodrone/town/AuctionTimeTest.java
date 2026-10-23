package org.tortodrone.town;

import org.tortodrone.protocol.*;
import org.tortodrone.support.TestWire;
import static org.tortodrone.support.TestWire.check;

/** Wiki spans plus the archive's captured TWO_DAYS/VERY_LONG observation. */
public final class AuctionTimeTest {
    public static void main(String[] args)throws Exception {
        long now=1_000_000L;
        long[] remaining={-1,0,1,299_999,300_000,3_599_999,3_600_000,14_399_999,14_400_000,86_399_999,86_400_000,172_800_000};
        int[] expected={5,5,0,0,1,1,2,2,3,3,4,4};
        for(int i=0;i<remaining.length;i++)check(listing(now+remaining[i],false,false).timeLeft(now)==expected[i],"native threshold at "+remaining[i]);
        check(listing(now+100000,false,true).timeLeft(now)==6,"cancelled precedes remaining duration");
        check(listing(Long.MAX_VALUE,true,false).timeLeft(now)==5,"ended precedes future expiry");
        check(listing(Long.MAX_VALUE,false,false).timeLeft(now)==4,"infinite staff listing");
        // Exercise the real serializer through the native TimeLeft value (item may be null here).
        var wire=new TestWire();var in=wire.frame(new SessionCodec().townResult(1001,(short)1,listing(System.currentTimeMillis()+172_800_000,false,false),null));
        check(wire.type(in).equals(TestWire.NET+"EventNotification")&&in.readShort()==-1,"notification envelope");
        check(wire.type(in).equals(TestWire.DOB+"InvocationResponseEvent")&&in.readInt()==1001&&in.readShort()==1&&in.readByte()==1,"result envelope");
        check(in.readBoolean()&&in.readInt()==1&&wire.type(in).equals("com.threerings.projectx.auction.data.ListingInfo"),"native listing");
        check(in.readLong()==1,"listing ID");
        check(wire.type(in).equals("com.threerings.projectx.auction.data.BidStatus")&&in.readByte()==0,"no bid");
        check(wire.type(in).equals("com.threerings.projectx.auction.data.TimeLeft")&&in.readByte()==4,"two-day listing is VERY_LONG on wire");
        System.out.println("PASS: auction time-left thresholds, terminal precedence and native VERY_LONG serialization");
    }
    private static AuctionState.Listing listing(long expires,boolean ended,boolean cancelled) {
        var item=new org.tortodrone.character.WorldProfile.Item(1,org.tortodrone.character.WorldProfile.Slot.OTHER,"Material/Gel Drop",null,0,null,false,false,false,1,false,0,0,java.util.List.of());
        return new AuctionState.Listing(1,1,item,10,0,0,expires,ended,cancelled,false,false,0);
    }
}
