package org.tortodrone.resources;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.text.Normalizer;
import java.util.*;
import org.tortodrone.character.ItemCatalog;

/** IDs are assigned once from exact native identities, then stored in bindings (never from translated labels). */
final class ItemIds {
    private ItemIds() { }
    static Map<String,String> assign(ItemCatalog catalog)throws IOException {
        var groups=new TreeMap<String,List<ItemCatalog.Entry>>();
        for(var e:catalog.entries()) {
            String slug=Normalizer.normalize(e.displayName(),Normalizer.Form.NFKD).replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","_").replaceAll("^_|_$","");
            if(slug.isEmpty()||!Character.isLetter(slug.charAt(0)))slug="item_"+slug;
            if(slug.length()>110)slug=slug.substring(0,110);
            groups.computeIfAbsent(slug,k->new ArrayList<>()).add(e);
        }
        var result=new TreeMap<String,String>();var used=new HashSet<String>();
        for(var group:groups.entrySet())for(var e:group.getValue()) {
            String existing=catalog.referenceId(e),id=existing.matches("[a-z][a-z0-9_]{0,159}")?existing:group.getKey()+(group.getValue().size()>1?"_s"+Integer.toUnsignedString(e.stockId())+"_"+digest(e.config()).substring(0,12):"");
            if(!used.add(id))throw new IOException("Item ID collision requires explicit binding review: "+id);
            result.put(e.config(),id);
        }
        return result;
    }
    private static String digest(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
