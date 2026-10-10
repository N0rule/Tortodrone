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
        String reference="Weapon/Sword/Proto Sword";
        check(ItemCommands.chat("giveitem Alice-Knight 2 "+reference,catalog,(u,k,c,n)->{
            check(u==null&&k.equals("Alice-Knight")&&c.equals(reference)&&n==2,"name-only chat grant passes global target and multiword item");return null;
        }).contains("to Alice-Knight (unequipped)"),"name-only GM syntax and feedback");
        check(ItemCommands.execute("giveitem Alice Alice-Knight 2 "+reference,catalog,(u,k,c,n)->{
            check(u.equals("Alice")&&k.equals("Alice-Knight")&&n==2,"legacy target kept");return null;
        }).contains("Alice/Alice-Knight"),"legacy account-qualified form retained");
        for(String command:new String[]{"giveitem Bad--Name 1 "+reference,"giveitem Alice-Knight 0 "+reference,"giveitem Alice-Knight 21 "+reference,"giveitem Alice-Knight 1 Unknown/Item"}) {
            try{ItemCommands.chat(command,catalog,grant);throw new AssertionError("Invalid new grant accepted");}catch(IOException expected){}
        }
        check(grants[0]==0,"invalid name-only forms never reach storage");
        System.out.println("PASS: GM help, command names, unknown errors and existing item syntax");
    }
}
