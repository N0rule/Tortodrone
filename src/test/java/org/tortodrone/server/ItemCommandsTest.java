package org.tortodrone.server;

import java.io.IOException;
import org.tortodrone.character.ItemCatalog;
import static org.tortodrone.support.TestWire.check;

public final class ItemCommandsTest {
    public static void main(String[] args)throws Exception {
        var catalog=ItemCatalog.starter();int[] grants={0};
        ItemCommands.Grant grant=(u,k,c,n)->{grants[0]++;return null;};
        check(ItemCommands.chat("HeLP",catalog,grant).contains("!items <search>"),"case-insensitive help lists commands");
        check(ItemCommands.chat("items Proto",catalog,grant).contains("matches"),"existing item search retained");
        for(String command:new String[]{"unknown", "", "itemsExtra", "giveitemExtra"}) {
            try{ItemCommands.chat(command,catalog,grant);throw new AssertionError("Unknown command accepted");}
            catch(IOException expected){check(expected.getMessage().equals("Unknown command. Use !help for available commands."),"clear unknown command error");}
        }
        try{ItemCommands.chat("giveitem",catalog,grant);throw new AssertionError("Malformed grant accepted");}
        catch(IOException expected){check(expected.getMessage().startsWith("Usage:"),"known command keeps syntax feedback");}
        check(grants[0]==0,"help/errors/search never grant items");
        System.out.println("PASS: GM help, command names, unknown errors and existing item syntax");
    }
}
