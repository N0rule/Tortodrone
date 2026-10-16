package org.tortodrone.server;

import java.util.concurrent.*;
import org.tortodrone.storage.AuctionHouse;

/** One bounded batch every 30 seconds, including when no clients are connected. */
final class AuctionExpiry implements AutoCloseable {
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"AuctionExpiry");t.setDaemon(true);return t;});
    AuctionExpiry(AuctionHouse house,ServerLog log){
        worker.scheduleWithFixedDelay(()->{try{house.settleExpired();}catch(Exception failure){log.warn("Auction settlement failed: "+failure.getMessage());}},0,30,TimeUnit.SECONDS);
    }
    @Override public void close(){
        worker.shutdown();
        try{if(!worker.awaitTermination(10,TimeUnit.SECONDS))worker.shutdownNow();}
        catch(InterruptedException interrupted){worker.shutdownNow();Thread.currentThread().interrupt();}
    }
}
