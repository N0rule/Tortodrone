package org.tortodrone.storage;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.tortodrone.resources.*;
import org.tortodrone.town.TownCatalog;
import static org.tortodrone.resources.Yaml.*;

/** Immutable captured defaults, bundled in both executables; never administrator configuration. */
public final class DepotSeed {
    public record Data(int version,String provenance,List<DepotStore.Offer> offers,Map<Integer,String> unsupported) {
        public Data { offers=List.copyOf(offers);unsupported=Map.copyOf(unsupported); }
    }
    public static final String SHA256="929d61960cffb14712d998879be29180e96870e2e0b01021df2cb7a7bbd14f9f";
    private DepotSeed() { }
    public static Data bundled()throws IOException {
        try(var in=DepotSeed.class.getResourceAsStream("/seeds/supply-depot.json")) {
            if(in==null)throw new IOException("Missing bundled Supply Depot seed");
            byte[] bytes=in.readAllBytes();
            try{if(!java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).equals(SHA256))throw new IOException("Bundled Depot seed fingerprint mismatch");}
            catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
            return parse(new String(bytes,StandardCharsets.UTF_8));
        }
    }
    static Data parse(String json)throws IOException {
        var root=map(Json.parseCatalog(json),"Depot seed");fields(root,"Depot seed","version","provenance","offers");
        int version=integer(root.get("version"),"seed version");if(version!=1)throw new IOException("Unsupported Depot seed version: "+version);
        String provenance=text(root.get("provenance"),"seed provenance");if(provenance.isBlank())throw new IOException("Missing Depot provenance");
        var offers=new ArrayList<DepotStore.Offer>();var unsupported=new LinkedHashMap<Integer,String>();int expected=0;
        for(Object raw:list(root.get("offers"),"seed offers")) {
            var r=map(raw,"seed offer");fields(r,"seed offer","id","item","stockId","count","sourcePrice","crowns","energy","limit","featured","enabled","support");
            int id=integer(r.get("id"),"offer ID"),stockId=integer(r.get("stockId"),"stock ID"),source=integer(r.get("sourcePrice"),"source price");
            String item=text(r.get("item"),"seed item"),support=text(r.get("support"),"grant support");
            if(id!=expected++||stockId==0||item.isBlank()||source< -1||support.isBlank())throw new IOException("Invalid seed identity/provenance at offer #"+id);
            var good=new TownCatalog.Good(item,integer(r.get("count"),"count"),integer(r.get("crowns"),"crowns"),integer(r.get("energy"),"energy"),integer(r.get("limit"),"limit"),"",0);
            DepotStore.values(good);boolean enabled=flag(r.get("enabled"),"enabled");
            if(enabled!=support.equals("Available"))throw new IOException("Seed support mismatch #"+id);
            if(!enabled)unsupported.put(id,item+": "+support);
            offers.add(new DepotStore.Offer(id,good,flag(r.get("featured"),"featured"),enabled));
        }
        if(offers.isEmpty())throw new IOException("Empty bundled Depot seed");
        return new Data(version,provenance,offers,unsupported);
    }
}
