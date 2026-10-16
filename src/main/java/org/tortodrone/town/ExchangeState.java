package org.tortodrone.town;

import java.util.List;

/** Native Energy Exchange prices and volumes are per lot of 100 Energy. */
public final class ExchangeState {
    private ExchangeState() { }
    public record Level(int volume,int price) { }
    public record Offer(int id,boolean buy,int price,int volume) { }
    public record Book(long revision,int lastPrice,List<Level> buys,List<Level> sells){public Book{buys=List.copyOf(buys);sells=List.copyOf(sells);}}
}
