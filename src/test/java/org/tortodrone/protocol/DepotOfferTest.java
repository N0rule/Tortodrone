package org.tortodrone.protocol;

import java.util.*;
import org.tortodrone.support.TestWire;
import org.tortodrone.town.TownCatalog;
import static org.tortodrone.support.TestWire.check;

/** Featured offers must survive serialization into the native Specials filter. */
public final class DepotOfferTest {
    public static void main(String[] args)throws Exception {
        var good=new TownCatalog.Good("Ticket/Keys/Silver Key",1,0,750,0,"",0);
        var offers=new TownCatalog.DepotOffers(Map.of(1,new TownCatalog.DepotGood(good,true,1000,true),2,new TownCatalog.DepotGood(good,false)));
        var wire=new TestWire();var in=wire.frame(new SessionCodec().townResult(1001,(short)7,offers,null));
        check(wire.type(in).equals(TestWire.NET+"EventNotification")&&in.readShort()==-1,"notification envelope");
        check(wire.type(in).equals(TestWire.DOB+"InvocationResponseEvent")&&in.readInt()==1001&&in.readShort()==7&&in.readByte()==1,"result callback");
        check(in.readBoolean()&&in.readInt()==1&&wire.type(in).equals("java.util.HashMap")&&in.readInt()==2,"offer map");
        for(int id=1;id<=2;id++){
            check(wire.object(in).equals(id)&&wire.type(in).equals("com.threerings.projectx.mission.data.RankGood"),"offer identity");
            check(wire.type(in).equals("com.threerings.projectx.shop.config.GoodConfig$Item"),"item good");
            check(in.readInt()==0&&in.readInt()==750&&in.readInt()==(id==1?1000:0)&&in.readBoolean()==(id==1)&&in.readInt()==1&&in.readInt()==0,"price/count/limit");
            wire.object(in);
            check(in.readBoolean()==(id==1)&&TestWire.string(in)==null&&TestWire.string(in)==null,"featured flag is native RankGood.special");
        }
        TestWire.end(in);System.out.println("PASS: featured and ordinary Depot offer flags, callback and native item layout");
    }
}
