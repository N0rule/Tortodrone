package org.tortodrone.town;

import java.util.List;
import org.tortodrone.character.WorldProfile;

public final class AuctionState {
    private AuctionState() { }
    public record Listing(long id,int seller,WorldProfile.Item item,int bid,int buy,int bidder,long expires,boolean ended,boolean cancelled,boolean mine,boolean participated,int fee,boolean watching,int featuredTotal,int featuredRemaining,long created,long availableAt) {
        public Listing(long id,int seller,WorldProfile.Item item,int bid,int buy,int bidder,long expires,boolean ended,boolean cancelled,boolean mine,boolean participated,int fee,boolean watching,int featuredTotal,int featuredRemaining,long created){this(id,seller,item,bid,buy,bidder,expires,ended,cancelled,mine,participated,fee,watching,featuredTotal,featuredRemaining,created,0);}
        public boolean featured(){return featuredTotal>0;}
        public boolean stock(){return featuredTotal>1;}
        /** Native TimeLeft ordinal, using the archive's Auction_House.wiki:15-19 thresholds.
         * Last-minute extension length is not established by those approximate UI spans. */
        public int timeLeft(long now) {
            if(cancelled)return 6;if(ended||expires<=now)return 5;
            long remaining=expires-now;
            return remaining<300_000L?0:remaining<3_600_000L?1:remaining<14_400_000L?2:remaining<86_400_000L?3:4;
        }
        public Listing(long id,int seller,WorldProfile.Item item,int bid,int buy,int bidder,long expires,boolean ended,boolean cancelled,boolean mine,boolean participated,int fee,boolean watching){this(id,seller,item,bid,buy,bidder,expires,ended,cancelled,mine,participated,fee,watching,0,0,0);}
        public Listing(long id,int seller,WorldProfile.Item item,int bid,int buy,int bidder,long expires,boolean ended,boolean cancelled,boolean mine,boolean participated,int fee){this(id,seller,item,bid,buy,bidder,expires,ended,cancelled,mine,participated,fee,false);}
    }
    public record Page(int pages,List<Listing> listings){public Page{listings=List.copyOf(listings);}}
    public record Update(Listing listing,String message) { }
    /** Durable online refund notice, acknowledged by the native My Bids operation. */
    public record Outbid(long auction,String item,int crowns) { }
    /**
     * One inbox row. {@code sender} is a knight's world id, or {@code -ordinal} for a
     * {@code SystemSender} (mail uses the default auction-house sender when it is zero), and
     * {@code items} holds both an auction's single escrow item and a player mail's attachments.
     */
    public record Mail(long id,int world,long sent,String subject,String body,int crowns,int energy,
            List<WorldProfile.Item> items,boolean claimed,boolean read,int friendWorld,String friendName,
            int senderRef,int type,String senderName) {
        public Mail {
            items=List.copyOf(items);
            if(type<0||type>4)throw new IllegalArgumentException("Invalid mail type");
        }
        public Mail(long id,int world,long sent,String subject,String body,int crowns,WorldProfile.Item item,
                boolean claimed,boolean read,int friendWorld,String friendName) {
            this(id,world,sent,subject,body,crowns,0,item==null?List.of():List.of(item),claimed,read,friendWorld,friendName,0,3,null);
        }
        public boolean friendRequest(){return friendWorld>0;}
        /** First attachment, kept for auction and legacy callers. Player mail can carry more. */
        public WorldProfile.Item item(){return items.isEmpty()?null:items.getFirst();}
        public String sender(){
            if(friendRequest())return friendName;
            if(senderRef<0)return " "+(-senderRef);
            if(senderRef>0)return senderName;
            return " 7";
        }
        /** Friend requests keep their own wire type; all other mail carries the stored kind. */
        public int nativeType(){return friendRequest()?1:type;}
    }
}
