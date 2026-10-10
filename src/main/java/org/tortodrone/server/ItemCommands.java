package org.tortodrone.server;

import java.io.IOException;
import org.tortodrone.character.*;
import org.tortodrone.account.AccountStore;

/** Shared console/chat syntax. The caller supplies its trusted permission boundary. */
public final class ItemCommands {
    private ItemCommands(){}
    @FunctionalInterface public interface Grant { WorldProfile give(String user,String knight,String config,int count)throws IOException; }
    public static String chat(String line,ItemCatalog catalog,Grant grant)throws IOException {
        String command=line.strip().split("\\s+",2)[0].toLowerCase(java.util.Locale.ROOT);
        return switch(command) {
            case "help" -> "GM commands: !help; !items <search>; !giveitem <knight-name> <1-20> <catalogue ID or exact item config>; /broadcast <message>.";
            case "items", "giveitem" -> execute(line,catalog,grant);
            default -> throw new IOException("Unknown command. Use !help for available commands.");
        };
    }
    public static String execute(String line,ItemCatalog catalog,Grant grant)throws IOException {
        var head=line.strip().split("\\s+",2);
        if(head[0].equalsIgnoreCase("items")) {
            String query=head.length==2?head[1]:"";var matches=catalog.search(query);
            String rows=String.join("; ",matches.stream().limit(5).map(e->e.config()+" [id="+e.stockId()+"]"+(e.grantable()?"":" [index only]")).toList());
            return matches.size()+" matches"+(rows.isEmpty()?"":"; "+rows)+(matches.size()>5?"; narrow the search or consult items/catalog.json":"");
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
        return "Granted "+count+" x "+entry.config()+" to "+(user==null?"":user+"/")+knight+" (unequipped).";
    }
    private static IOException usage() {
        return new IOException("Usage: giveitem <knight-name> <1-20> <catalogue ID or exact item config>; legacy: giveitem <username> <knight-name> <1-20> <item>; items <search>");
    }
}
