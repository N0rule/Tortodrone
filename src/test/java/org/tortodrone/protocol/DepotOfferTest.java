package org.tortodrone.protocol;

import java.util.*;
import org.tortodrone.support.TestWire;
import org.tortodrone.town.TownCatalog;
import static org.tortodrone.support.TestWire.check;

/** Featured offers must survive serialization into the native Specials filter. */
public final class DepotOfferTest {
    public static void main(String[] args)throws Exception {
        var good=new TownCatalog.Good("Ticket/Keys/Silver Key",1,0,750,0,"",0);
        var offers=new TownCatalog.DepotOffers(Map.of(1,TownCatalog.DepotGood.timed(good,false,1000,true,3_600_001,1),2,new TownCatalog.DepotGood(good,false)));
        var wire=new TestWire();var in=wire.frame(new SessionCodec().townResult(1001,(short)7,offers,null));
        check(wire.type(in).equals(TestWire.NET+"EventNotification")&&in.readShort()==-1,"notification envelope");
        check(wire.type(in).equals(TestWire.DOB+"InvocationResponseEvent")&&in.readInt()==1001&&in.readShort()==7&&in.readByte()==1,"result callback");
        check(in.readBoolean()&&in.readInt()==1&&wire.type(in).equals("java.util.HashMap")&&in.readInt()==2,"offer map");
        for(int id=1;id<=2;id++){
            check(wire.object(in).equals(id)&&wire.type(in).equals("com.threerings.projectx.mission.data.RankGood"),"offer identity");
            check(wire.type(in).equals("com.threerings.projectx.shop.config.GoodConfig$Item"),"item good");
            check(in.readInt()==0&&in.readInt()==750&&in.readInt()==(id==1?1000:0)&&in.readBoolean()==(id==1)&&in.readInt()==1&&in.readInt()==0,"price/count/limit");
            wire.object(in);
            check(in.readBoolean()==(id==1),"timed good is native RankGood.special");
            check(Objects.equals(TestWire.string(in),id==1?"~":null)&&Objects.equals(TestWire.string(in),id==1?"m.promo_hours_left|~1":null),"native promotion strings");
        }
        TestWire.end(in);
        check(TownCatalog.DepotGood.timed(good,false,0,false,36*3_600_000L,0).promoDetail().equals("m.promo_days_left|~2"),"36-hour boundary");
        check(TownCatalog.DepotGood.timed(good,false,0,false,1,0).promoDetail().equals("m.promo_hours_left|~0"),"final partial hour");
        check(TownCatalog.DepotGood.timed(good,false,0,false,1,1).promoDetail()==null,"expired window has no countdown");System.out.println("PASS: featured and ordinary Depot offer flags, callback and native item layout");
    }
}
