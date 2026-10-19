package org.tortodrone.server;

import java.io.IOException;
import org.tortodrone.character.*;
import org.tortodrone.account.AccountStore;

/** Shared console syntax. The caller supplies its trusted permission boundary. */
public final class ItemCommands {
    private ItemCommands(){}
    @FunctionalInterface public interface Grant { WorldProfile give(String user,String knight,String config,int count)throws IOException; }
    public static String execute(String line,ItemCatalog catalog,Grant grant)throws IOException {
        var head=line.strip().split("\\s+",2);
        if(head[0].equalsIgnoreCase("items")) {
            String query=head.length==2?head[1].strip():"";
            if(query.toLowerCase(java.util.Locale.ROOT).startsWith("info ")) {
                var e=catalog.inspect(query.substring(5));
                return describe(catalog,e)+"; path="+e.config()+"; slot="+e.slot()+"; variant="+e.variant()+"; stackable="+e.stackable()+"; bindOnEquip="+e.bindOnEquip()+"; locked="+e.locked()+"; permanent="+e.permanent();
            }
            int page=1;var option=java.util.regex.Pattern.compile("(?:^|\\s)--page\\s+(\\S+)$").matcher(query);
            if(option.find()) {
                try { page=Integer.parseInt(option.group(1));if(page<1)throw new NumberFormatException(); }
                catch(NumberFormatException invalid){throw new IOException("Page must be a positive integer");}
                query=query.substring(0,option.start()).strip();
            }
            String category=null;
            if(query.toLowerCase(java.util.Locale.ROOT).startsWith("category ")) {category=query.substring(9).strip();query="";}
            var results=catalog.page(query,category,page,5);
            String rows=String.join("; ",results.entries().stream().map(e->describe(catalog,e)).toList());
            return results.total()+" matches; page "+page+"/"+Math.max(1,(results.total()+4)/5)+(rows.isEmpty()?"":"; "+rows);

        }
        var words=line.strip().split("\\s+",4);
        if(words.length!=4||!words[0].equalsIgnoreCase("giveitem"))throw usage();
        String user=null,knight=words[1],quantity=words[2],reference=words[3];
        // Legacy account-qualified form is unambiguous: a knight name cannot be numeric.
        if (!quantity.matches("[+-]?[0-9]+")) {
            words=line.strip().split("\\s+",5);
            if(words.length!=5)throw usage();
            user=words[1];AccountStore.validateUsername(user);
            knight=words[2];quantity=words[3];reference=words[4];
        }
        if(!knight.matches("[A-Za-z]+(?:-[A-Za-z]+)*")||knight.length()<3||knight.length()>16)throw new IOException("Invalid knight name");
        int count;try{count=Integer.parseInt(quantity);}catch(NumberFormatException invalid){throw new IOException("Quantity must be between 1 and 20");}
        if(count<1||count>ItemCatalog.GRANT_LIMIT)throw new IOException("Quantity must be between 1 and 20");
        var entry=catalog.require(reference);grant.give(user,knight,entry.config(),count);
        return "Granted "+count+" x "+entry.displayName()+" to "+(user==null?"":user+"/")+knight+" (unequipped).";
    }
    private static String describe(ItemCatalog catalog,ItemCatalog.Entry entry) {
        return catalog.displayName(entry)+" ["+catalog.category(entry)+"] [id="+entry.stockId()+"] "+catalog.alias(entry)
            +(entry.grantable()?"":" [index only: "+entry.unavailableReason()+"]");
    }
    private static IOException usage() {
        return new IOException("Usage: giveitem <knight-name> <1-20> <catalogue ID or exact item config>; legacy: giveitem <username> <knight-name> <1-20> <item>; items <search>");
    }
}
