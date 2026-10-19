package org.tortodrone.town;

import java.util.List;
import org.tortodrone.character.WorldProfile;

public final class AuctionState {
    private AuctionState() { }
    public record Listing(long id,int seller,WorldProfile.Item item,int bid,int buy,int bidder,long expires,boolean ended,boolean cancelled,boolean mine,boolean participated,int fee,boolean watching,int featuredTotal,int featuredRemaining,long created,long availableAt) {
        public Listing(long id,int seller,WorldProfile.Item item,int bid,int buy,int bidder,long expires,boolean ended,boolean cancelled,boolean mine,boolean participated,int fee,boolean watching,int featuredTotal,int featuredRemaining,long created){this(id,seller,item,bid,buy,bidder,expires,ended,cancelled,mine,participated,fee,watching,featuredTotal,featuredRemaining,created,0);}
        public boolean featured(){return featuredTotal>0;}
        public boolean stock(){return featuredTotal>1;}
        public Listing(long id,int seller,WorldProfile.Item item,int bid,int buy,int bidder,long expires,boolean ended,boolean cancelled,boolean mine,boolean participated,int fee,boolean watching){this(id,seller,item,bid,buy,bidder,expires,ended,cancelled,mine,participated,fee,watching,0,0,0);}
        public Listing(long id,int seller,WorldProfile.Item item,int bid,int buy,int bidder,long expires,boolean ended,boolean cancelled,boolean mine,boolean participated,int fee){this(id,seller,item,bid,buy,bidder,expires,ended,cancelled,mine,participated,fee,false);}
    }
    public record Page(int pages,List<Listing> listings){public Page{listings=List.copyOf(listings);}}
    public record Update(Listing listing,String message) { }
    public record Mail(long id,int world,long sent,String subject,String body,int crowns,WorldProfile.Item item,boolean claimed,boolean read,int friendWorld,String friendName) {
        public Mail(long id,int world,long sent,String subject,String body,int crowns,WorldProfile.Item item,boolean claimed,boolean read){this(id,world,sent,subject,body,crowns,item,claimed,read,0,null);}
        public boolean friendRequest(){return friendWorld>0;}
        public String sender(){return friendRequest()?friendName:" 7";}
        public int nativeType(){return friendRequest()?1:3;}
    }
}
