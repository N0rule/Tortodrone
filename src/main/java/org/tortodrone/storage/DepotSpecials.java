package org.tortodrone.storage;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.tortodrone.town.TownCatalog;

/** Captured daily sales plus an explicitly reconstructed policy for uncaptured days.
 * No administrator catalog or promotion rows are changed by this projection. */
public final class DepotSpecials {
    public static final ZoneId ZONE=ZoneId.of("America/Los_Angeles");
    public record Window(LocalDate day,long starts,long ends) { }
    private record Row(String item,int price,int original) { }
    private record Data(Map<LocalDate,List<Row>> captured,List<DepotStore.Offer> pool) { }
    private static volatile Data data;
    private DepotSpecials() { }

    /** Noon Pacific is captured during daylight time; its winter interpretation is reconstructed. */
    public static Window window(long now) {
        var instant=Instant.ofEpochMilli(now);var day=instant.atZone(ZONE).toLocalDate();
        if(instant.isBefore(day.atTime(12,0).atZone(ZONE).toInstant()))day=day.minusDays(1);
        return new Window(day,day.atTime(12,0).atZone(ZONE).toInstant().toEpochMilli(),
                day.plusDays(1).atTime(12,0).atZone(ZONE).toInstant().toEpochMilli());
    }
    public static boolean automatic(DepotStore.Offer offer){return offer.promotionId()<0;}
    public static String policy(long now)throws IOException {
        return defaults().captured().containsKey(window(now).day())?"Captured daily selection":"Reconstructed deterministic daily selection";
    }
    private static Data defaults()throws IOException {
        var result=data;if(result!=null)return result;
        synchronized(DepotSpecials.class) {
            if(data!=null)return data;
            var captured=new LinkedHashMap<LocalDate,List<Row>>();
            try(var stream=DepotSpecials.class.getResourceAsStream("/seeds/depot-specials.tsv")) {
                if(stream==null)throw new IOException("Missing captured Depot sales");
                try(var reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))) {
                    for(String line;(line=reader.readLine())!=null;) {
                        if(line.isBlank()||line.startsWith("#"))continue;
                        var fields=line.split("\t",-1);
                        if(fields.length!=5)throw new IOException("Invalid captured Depot sale");
                        try {
                            var row=new Row(fields[1],Integer.parseInt(fields[2]),Integer.parseInt(fields[3]));
                            if(row.price()<=0||row.price()>=row.original()||fields[4].isBlank())throw new IllegalArgumentException();
                            captured.computeIfAbsent(LocalDate.parse(fields[0]),unused->new ArrayList<>()).add(row);
                        }catch(RuntimeException invalid){throw new IOException("Invalid captured Depot sale",invalid);}
                    }
                }
            }
            for(var rows:captured.values())if(rows.size()!=2||rows.getFirst().item().equals(rows.getLast().item()))throw new IOException("Invalid captured Depot pair");
            data=new Data(Collections.unmodifiableMap(captured),DepotSeed.bundled().offers().stream().filter(DepotSpecials::eligible).toList());
            return data;
        }
    }
    private static boolean eligible(DepotStore.Offer offer) {
        var g=offer.good();return offer.enabled()&&!offer.promotionOnly()&&g.count()==1&&
                (g.item().startsWith("Weapon/")||g.item().startsWith("Gear/"))&&
                (g.energy()==150||g.energy()==500||g.energy()==1600||g.energy()==3500);
    }
    /** Stable pool comes from the immutable seed: unrelated admin edits cannot reroll the pair.
     * SHA-256 picks and the discount draw are local reconstruction, not official server RNG. */
    private static List<Row> generated(Data source,LocalDate day) {
        var rows=new ArrayList<Row>();
        for(boolean low:new boolean[]{true,false}) {
            var choice=source.pool().stream().filter(o->(o.good().energy()<=500)==low)
                    .min(Comparator.comparing(o->digest(day+"/pick/"+o.good().item()))).orElse(null);
            if(choice==null)continue;
            var g=choice.good();int basis=200+Integer.parseInt(digest(day+"/discount/"+g.item()).substring(0,4),16)%601;
            int discount=Math.max(5,(int)Math.round(g.energy()*basis/10000.0));
            rows.add(new Row(g.item(),g.energy()-discount,g.energy()));
        }
        return rows;
    }
    private static String digest(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    static Map<Integer,DepotStore.Offer> offers(DepotStore.Snapshot base,long now)throws IOException {
        var source=defaults();var window=window(now);
        var rows=source.captured().get(window.day());if(rows==null)rows=generated(source,window.day());
        var result=new HashMap<Integer,DepotStore.Offer>();
        for(var row:rows) {
            // Preserve administrator changes, disabled/temporary offers and quantities. One handle per item.
            for(var offer:base.offers()) {
                var g=offer.good();if(!eligible(offer)||!g.item().equals(row.item())||g.energy()!=row.original())continue;
                var good=new TownCatalog.Good(g.item(),g.count(),0,row.price(),g.limit(),"",0);
                result.put(offer.id(),new DepotStore.Offer(offer.id(),good,true,true,false,row.original(),true,
                        -Math.addExact(window.starts(),1),window.ends()));break;
            }
        }
        return result;
    }
}
