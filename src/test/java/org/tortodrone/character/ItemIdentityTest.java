package org.tortodrone.character;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.tortodrone.server.ItemCommands;
import static org.tortodrone.support.TestWire.check;

/** Derived identity must be independent of exporter ordering and preserve grant boundaries. */
public final class ItemIdentityTest {
    public static void main(String[] args) throws Exception {
        var first=entry("Misleading/One/Shared Name",0,"Handgun",WorldProfile.Slot.GUN);
        var second=entry("Other/Two/Shared Name",0,"Handgun",WorldProfile.Slot.GUN);
        var unsupported=entry("Weapon/Unavailable",-123,"Upgrade",null);
        var catalogue=new ItemCatalog(List.of(first,second,unsupported));
        var reverse=new ItemCatalog(List.of(unsupported,second,first));
        check(first.displayName().equals("Shared Name")&&first.category().equals("Handgun"),"name derives from leaf and category from extracted type");
        check(catalogue.alias(first).startsWith("gun:shared-name@0-"),"colliding aliases have type, zero ID and path digest");
        check(!catalogue.alias(first).equals(catalogue.alias(second)),"zero stock IDs do not collapse identities");
        check(catalogue.alias(first).equals(reverse.alias(first))&&catalogue.alias(second).equals(reverse.alias(second)),"collision identities independent of insertion order");
        var natural=entry("Third/"+catalogue.alias(first).substring(4).replace('@','-'),7,"Handgun",WorldProfile.Slot.GUN);
        var expanded=new ItemCatalog(List.of(first,second,natural));
        check(!expanded.alias(first).equals(expanded.alias(natural)),"reserved delimiter prevents generated/natural alias collisions");
        var many=new java.util.ArrayList<ItemCatalog.Entry>();for(int n=0;n<1000;n++)many.add(entry("Branch"+n+"/Shared",0,"Sword",WorldProfile.Slot.SWORD));
        try {new ItemCatalog(many).inspect("0");throw new AssertionError("Ambiguous base ID accepted");}catch(IOException expected){check(expected.getMessage().length()<2000&&expected.getMessage().contains("1000 choices"),"ambiguity feedback bounded");}
        check(catalogue.inspect(first.config()).equals(first)&&catalogue.inspect(catalogue.alias(first)).equals(first),"canonical path and derived alias resolve");
        ambiguous(catalogue,"Shared Name");ambiguous(catalogue,"0");
        check(catalogue.inspect("-123").equals(unsupported)&&catalogue.inspect("Unavailable").equals(unsupported),"indexed unsupported entries inspect by ID and friendly name");
        check(!unsupported.grantable()&&unsupported.unavailableReason().contains("slot"),"unsupported entry has concrete reason");
        try { catalogue.require("Unavailable");throw new AssertionError("Unsupported grant accepted"); } catch(IOException expected) {check(expected.getMessage().contains(unsupported.unavailableReason()),"grant refusal explains availability");}
        check(catalogue.search("handgun").size()==2&&catalogue.search(catalogue.alias(first)).equals(List.of(first)),"search includes type and alias");
        var page=catalogue.page("","Handgun",2,1);
        check(page.total()==2&&page.entries().size()==1&&!page.entries().equals(catalogue.page("","Handgun",1,1).entries()),"category pagination visits distinct rows");
        check(catalogue.page("",null,Integer.MAX_VALUE,5).entries().isEmpty(),"large page does not overflow");
        ItemCommands.Grant noGrant=(user,knight,config,count)->{throw new AssertionError("Inspection mutated inventory");};
        check(ItemCommands.execute("items info -123",catalogue,noGrant).contains("[id=-123]"),"info retains stock ID and never grants");
        check(ItemCommands.execute("items category Handgun --page 2",catalogue,noGrant).contains("2 matches; page 2/1"),"category command supports pagination");
        check(ItemCommands.execute("items Unavailable",catalogue,noGrant).contains("index only"),"search includes unsupported entries");
        System.out.println("PASS: derived item identity, deterministic collisions, zero-ID ambiguity, inspection boundaries, search and paging");
    }
    private static ItemCatalog.Entry entry(String path,int id,String type,WorldProfile.Slot slot) {
        return new ItemCatalog.Entry(path,id,type,"m.test",0,false,slot,true,Map.of("Model","verified"));
    }
    private static void ambiguous(ItemCatalog catalogue,String reference)throws Exception {
        try {catalogue.inspect(reference);throw new AssertionError("Ambiguous reference accepted");}
        catch(IOException expected) {
            check(expected.getMessage().contains("Ambiguous")&&expected.getMessage().contains(catalogue.alias(catalogue.entries().get(0)))
                &&expected.getMessage().contains("Other/Two/Shared Name"),"ambiguity lists explicit choices");
        }
    }
}
