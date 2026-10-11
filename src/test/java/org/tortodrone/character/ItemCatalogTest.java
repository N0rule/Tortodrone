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
            var catalog=ItemCatalog.starter();Path file=home.resolve("catalog.json");ItemCatalog.write(file,catalog.entries());
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
            for(String invalid:List.of(original.replace("\"version\": 2","\"version\": 999"),
                    original.replaceFirst("\"stockId\":0","\"stockId\":2147483648"),
                    original.replaceFirst("\"bindOnEquip\":true","\"bindOnEquip\":\"true\""),
                    original.replaceFirst("\"type\":\"model\"","\"type\":\"unknown\""),
                    "{\"format\":null,\"format\":\"tortodrone-items\",\"version\":1,\"items\":[]}",
                    original.substring(0,original.length()/2))) {
                Files.writeString(file,invalid);
                try{ItemCatalog.load(file);throw new AssertionError("Invalid JSON accepted");}catch(IOException expected){}
            }
            Path duplicates=home.resolve("duplicates.json");ItemCatalog.write(duplicates,List.of(sword,sword));
            try{ItemCatalog.load(duplicates);throw new AssertionError("Duplicate catalogue accepted");}catch(IOException expected){}
            var typed=new ItemCatalog.Entry("Unicode/雪 \"Item\"",-3,"Trinket","m.item",2,false,WorldProfile.Slot.TRINKET,true,true,
                Map.of("float",1.25f,"integer",-8,"flag",true,"literal","playerColor", "color",new StarterCatalog.Colorization(5),
                    "trans",new StarterCatalog.TransColorization(2,4),"model",new StarterCatalog.Model("nested",Map.of("Colorization",ItemCatalog.PlayerColor.VALUE))));
            Path types=home.resolve("types.json");ItemCatalog.write(types,List.of(typed));
            check(ItemCatalog.load(types).entries().equals(List.of(typed)),"JSON preserves float/integer, Unicode, booleans, colors, nested models and permanence");
            var artifact=new ItemCatalog.Entry("Item/Artifact/Test",9,"Artifact","m.artifact",4,false,WorldProfile.Slot.OTHER,
                false,true,true,ItemCatalog.Variant.SEED_ITEM,0x123456789abcdef0L,Map.of());
            var energy=new ItemCatalog.Entry("Item/Energy/Test",10,"Energy","m.energy",1,false,WorldProfile.Slot.OTHER,
                false,false,true,ItemCatalog.Variant.ITEM,null,Map.of());
            var upgrade=new ItemCatalog.Entry("Item/Upgrade/Test",11,"Upgrade","m.upgrade",2,false,WorldProfile.Slot.OTHER,
                false,false,false,ItemCatalog.Variant.UPGRADE_ITEM,null,Map.of());
            var mineral=new ItemCatalog.Entry("Mineral/Dungeon/Meta Test",12,"MetaMineral","m.mineral",2,false,WorldProfile.Slot.OTHER,
                false,false,false,ItemCatalog.Variant.LEVEL_ITEM,null,Map.of());
            var present=new ItemCatalog.Entry("Gear/Dungeon/Event/Winterfest/Present/Test",13,"Present","m.present",2,false,WorldProfile.Slot.OTHER,
                false,false,false,ItemCatalog.Variant.LEVEL_ITEM,null,Map.of());
            var instant=new ItemCatalog.Entry("Instant/Test",14,"InstantAction","m.instant",1,false,WorldProfile.Slot.OTHER,
                false,false,false,ItemCatalog.Variant.ITEM,null,Map.of());
            Path variants=home.resolve("variants.json");ItemCatalog.write(variants,List.of(artifact,energy,upgrade,mineral,present,instant));
            var variantCatalog=ItemCatalog.load(variants);
            check(variantCatalog.require(artifact.config()).variant()==ItemCatalog.Variant.SEED_ITEM
                &&variantCatalog.require(artifact.config()).seed().equals(artifact.seed()),"artifact seed subtype metadata round trips and remains grantable");
            check(variantCatalog.require(energy.config()).locked()&&variantCatalog.find(upgrade.config()).variant()==ItemCatalog.Variant.UPGRADE_ITEM
                &&!variantCatalog.find(upgrade.config()).grantable(),"locked generic metadata grants while expiring upgrade variants remain indexed only");
            check(variantCatalog.require(mineral.config()).variant()==ItemCatalog.Variant.LEVEL_ITEM,
                "MetaMineral uses the stock LevelItem variant in the generic inventory slot");
            check(variantCatalog.require(present.config()).variant()==ItemCatalog.Variant.LEVEL_ITEM
                &&variantCatalog.require(instant.config()).variant()==ItemCatalog.Variant.ITEM,
                "Present uses LevelItem while InstantAction uses the common Item payload");
            Path legacy=home.resolve("legacy.json");Files.writeString(legacy,"{\"format\":\"tortodrone-items\",\"version\":1,\"items\":[{\"config\":\"Legacy/Token\",\"stockId\":1,\"type\":\"Token\",\"nameKey\":\"m.token\",\"rarity\":0,\"stackable\":false,\"slot\":\"OTHER\",\"bindOnEquip\":false,\"permanent\":false,\"modelArguments\":{}}]}");
            check(ItemCatalog.load(legacy).require("Legacy/Token").variant()==ItemCatalog.Variant.ITEM
                &&ItemCatalog.load(legacy).require("Legacy/Token").locked(),
                "version 1 catalogues remain readable with stock-derived lock defaults");
            Path oldArtifact=home.resolve("legacy-artifact.json");Files.writeString(oldArtifact,"{\"format\":\"tortodrone-items\",\"version\":1,\"items\":[{\"config\":\"Item/Artifact/Legacy\",\"stockId\":2,\"type\":\"Artifact\",\"nameKey\":\"m.artifact\",\"rarity\":1,\"stackable\":false,\"slot\":\"OTHER\",\"bindOnEquip\":false,\"permanent\":true,\"modelArguments\":{}}]}");
            check(!ItemCatalog.load(oldArtifact).find("Item/Artifact/Legacy").grantable(),
                "version 1 artifact records without seed metadata stay indexed only");
            System.out.println("PASS: item catalogue round trip, typed models/colors, search, multiword commands, unsupported/invalid references and duplicate/version rejection");
        }finally{try(var paths=Files.walk(home)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
