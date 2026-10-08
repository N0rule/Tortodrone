package org.tortodrone.character;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.server.ItemCommands;
import static org.tortodrone.support.TestWire.check;

public final class ItemCatalogTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-item-catalog-");
        try {
            var catalog=ItemCatalog.starter();Path file=home.resolve("catalog.tsv");ItemCatalog.write(file,catalog.entries());
            var loaded=ItemCatalog.load(file);check(loaded.entries().equals(catalog.entries()),"catalogue round trip preserves references/model argument types");
            var sword=loaded.require("weapon/sword/proto sword");
            check(((StarterCatalog.Model)sword.arguments(263).get("Weapon")).arguments().get("Colorization").equals(263),"dynamic player color resolved within nested model");
            check(loaded.search("proto sword").size()==1,"catalogue search handles spaces/case");
            int[] calls={0};
            check(ItemCommands.execute("giveitem Alice Alice-Knight 2 Weapon/Sword/Proto Sword",loaded,(u,k,c,n)->{calls[0]++;check(n==2&&c.equals(sword.config()),"exact multiword reference parsed");return null;}).contains("Granted 2"),"shared command grants");
            for(String line:List.of("giveitem Alice Alice-Knight 0 Weapon/Sword/Proto Sword","giveitem Alice Alice-Knight 999 Weapon/Sword/Proto Sword","giveitem Alice Alice-Knight 1 Unknown/Item","giveitem Alice","giveitem Alice Bad--Name 1 Weapon/Sword/Proto Sword")) {
                try{ItemCommands.execute(line,loaded,(u,k,c,n)->{calls[0]++;return null;});throw new AssertionError("Invalid command accepted");}catch(IOException expected){}
            }
            check(calls[0]==1,"invalid commands never reach mutation boundary");
            var byId=new ItemCatalog(List.of(new ItemCatalog.Entry(sword.config(),-123,"Sword","m.sword",0,false,sword.slot(),true,sword.modelArgs())));
            check(byId.require("-123").config().equals(sword.config()),"signed catalogue IDs resolve canonical references");
            check(ItemCommands.execute("items -123",byId,(u,k,c,n)->null).contains("[id=-123]"),"search displays and accepts IDs");
            ItemCommands.execute("giveitem Alice Alice-Knight 2 -123",byId,(u,k,c,n)->{check(c.equals(sword.config()),"ID resolved before storage");return null;});
            for(var c:List.of(loaded,new ItemCatalog(List.of(
                new ItemCatalog.Entry("One",7,"Sword","",0,false,sword.slot(),true,sword.modelArgs()),
                new ItemCatalog.Entry("Two",7,"Sword","",0,false,sword.slot(),true,sword.modelArgs()))))) {
                for(String id:List.of("0","7","999999999999999999999","99999")) {
                    try{c.require(id);throw new AssertionError("Invalid/ambiguous ID accepted");}catch(IOException expected){}
                }
            }
            var unsupported=new ItemCatalog(List.of(new ItemCatalog.Entry("Recipe/Test",1,"Recipe","m.recipe",2,false,null,false,Map.of())));
            try{unsupported.require("Recipe/Test");throw new AssertionError("Unsupported type accepted");}catch(IOException expected){}
            String original=Files.readString(file);
            var legacy=new StringBuilder("# Tortodrone item catalogue v1\n");
            original.lines().filter(l->!l.startsWith("#")).forEach(l->{var f=new ArrayList<>(List.of(l.split("\\t",-1)));f.remove(8);legacy.append(String.join("\t",f)).append('\n');});
            Files.writeString(file,legacy);check(ItemCatalog.load(file).entries().equals(catalog.entries()),"v1 deployed catalogues remain readable");
            Files.writeString(file,original+original.lines().filter(l->!l.startsWith("#")).findFirst().orElseThrow()+"\n");
            try{ItemCatalog.load(file);throw new AssertionError("Duplicate catalogue accepted");}catch(IOException expected){}
            Files.writeString(file,"# Tortodrone item catalogue v999\n");
            try{ItemCatalog.load(file);throw new AssertionError("Unknown version accepted");}catch(IOException expected){}
            System.out.println("PASS: item catalogue round trip, typed models/colors, search, multiword commands, unsupported/invalid references and duplicate/version rejection");
        }finally{try(var paths=Files.walk(home)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
