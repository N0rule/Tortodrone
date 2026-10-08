package org.tortodrone.server;

import java.io.IOException;
import org.tortodrone.character.*;
import org.tortodrone.account.AccountStore;

/** Shared console/chat syntax. The caller supplies its trusted permission boundary. */
public final class ItemCommands {
    private ItemCommands(){}
    @FunctionalInterface public interface Grant { WorldProfile give(String user,String knight,String config,int count)throws IOException; }
    public static String execute(String line,ItemCatalog catalog,Grant grant)throws IOException {
        var head=line.strip().split("\\s+",2);
        if(head[0].equalsIgnoreCase("items")) {
            String query=head.length==2?head[1]:"";var matches=catalog.search(query);
            String rows=String.join("; ",matches.stream().limit(5).map(e->e.config()+(e.grantable()?"":" [index only]")).toList());
            return matches.size()+" matches"+(rows.isEmpty()?"":"; "+rows)+(matches.size()>5?"; narrow the search or consult items/catalog.tsv":"");
        }
        var words=line.strip().split("\\s+",5);
        if(words.length!=5||!words[0].equalsIgnoreCase("giveitem"))throw new IOException("Usage: giveitem <username> <knight-name> <1-20> <exact item config>; items <search>");
        AccountStore.validateUsername(words[1]);if(!words[2].matches("[A-Za-z]+(?:-[A-Za-z]+)*")||words[2].length()<3||words[2].length()>16)throw new IOException("Invalid knight name");
        int count;try{count=Integer.parseInt(words[3]);}catch(NumberFormatException invalid){throw new IOException("Quantity must be between 1 and 20");}
        if(count<1||count>ItemCatalog.GRANT_LIMIT)throw new IOException("Quantity must be between 1 and 20");
        var entry=catalog.require(words[4]);grant.give(words[1],words[2],entry.config(),count);
        return "Granted "+count+" x "+entry.config()+" to "+words[1]+"/"+words[2]+" (unequipped).";
    }
}
