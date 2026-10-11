import java.nio.file.*;
import java.util.*;
import com.samskivert.util.at;
import com.threerings.presents.dobj.*;
import com.threerings.projectx.item.data.*;
import com.threerings.crowd.chat.data.SystemMessage;
import org.tortodrone.character.*;

/** Actual stock speak/equip readers and DSet observer callbacks, with disposable accounts only. */
public final class ItemGrantConnectionCheck {
    public static void main(String[] args)throws Exception {
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");Thread thread=Thread.currentThread();
        at queue=new at(){public void postRunnable(Runnable task){WorldConnectionCheck.tasks.add(task);}public boolean isDispatchThread(){return Thread.currentThread()==thread;}public boolean isRunning(){return true;}};
        Path data=Path.of(args[1]).getParent().getParent();
        try(var tools=new org.tortodrone.account.AccountStore(data)) {
            tools.create("ItemStaff","lan-test-password".toCharArray(),true);tools.create("ItemPlayer","lan-test-password".toCharArray());
            seed(tools,"ItemStaff","Staff-Knight");seed(tools,"ItemPlayer","Grant-Knight");seed(tools,"ItemPlayer","Offline-Knight");
            String sword="Weapon/Sword/Calibur";tools.catalog().require(sword);
            try(var staff=new GameMasterConnectionCheck.Endpoint(args,queue,"ItemStaff","Staff-Knight");
                    var player=new GameMasterConnectionCheck.Endpoint(args,queue,"ItemPlayer","Grant-Knight")) {
                var feedback=new ArrayList<String>();var denial=new ArrayList<String>();int[] additions={0};
                listen(staff.scene,feedback);listen(player.scene,denial);
                player.player.a(new s<Item>() {
                    public void a(EntryAddedEvent<Item> e){if(e.getName().equals("items"))additions[0]++;}
                    public void a(EntryRemovedEvent<Item> e){}public void a(EntryUpdatedEvent<Item> e){}
                });
                String command="!giveitem ItemPlayer Grant-Knight 2 "+tools.catalog().require(sword).stockId();
                player.scene.speakService.a(command,(byte)0);WorldConnectionCheck.waitFor(()->player.messages.size()==1);
                if(!denial.isEmpty()||!player.messages.get(0).message.equals(command)||player.player.items.size()!=5)throw new AssertionError("Normal player command must remain ordinary chat");
                for(String text:List.of("!items Calibur","!help","!unknown")) {
                    int heard=player.messages.size();player.scene.speakService.a(text,(byte)1);
                    WorldConnectionCheck.waitFor(()->player.messages.size()>heard);
                    if(!denial.isEmpty()||!player.messages.get(heard).message.equals(text)||player.messages.get(heard).mode!=1)throw new AssertionError("Normal ! text/mode was intercepted");
                }
                staff.messages.clear();player.messages.clear();
                staff.scene.speakService.a("!help",(byte)0);WorldConnectionCheck.waitFor(()->feedback.size()==1);
                if(!feedback.get(0).contains("!giveitem")||!feedback.get(0).contains("!items"))throw new AssertionError("GM help missing supported commands");
                staff.scene.speakService.a("!unknown",(byte)0);WorldConnectionCheck.waitFor(()->feedback.size()==2);
                if(!feedback.get(1).contains("Unknown command")||!staff.messages.isEmpty()||!player.messages.isEmpty())throw new AssertionError("Unknown GM command must give private error");
                feedback.clear();
                player.enter(2); // Grant across scenes, command feedback remains private to staff.
                staff.scene.speakService.a(command,(byte)0);WorldConnectionCheck.waitFor(()->player.player.items.size()==7&&additions[0]==2&&!feedback.isEmpty());
                if(!feedback.get(0).startsWith("Granted 2")||!staff.messages.isEmpty()||!player.messages.isEmpty())throw new AssertionError("Command leaked to chat or no stock feedback");
                var granted=new ArrayList<Item>();for(var item:player.player.items)if(item.Vx().getName().equals(sword))granted.add(item);
                if(granted.size()!=2||granted.stream().anyMatch(Item::Cs)||granted.get(0).VA()==granted.get(1).VA())throw new AssertionError("Distinct unbound LevelItems missing");
                var service=player.client.bU(com.threerings.projectx.item.client.L.class);var reply=new WorldConnectionCheck.Reply();
                var initial=player.actor.Mv();service.a(granted.get(0).VA(),6,reply);WorldConnectionCheck.waitFor(()->reply.done);
                if(reply.reason!=null||player.player.equipment[6]!=granted.get(0).VA()||!WorldConnectionCheck.bound(player.player,granted.get(0).VA()))throw new AssertionError("Granted equipment failed Arsenal RPC/binding");
                WorldConnectionCheck.peaceful((com.threerings.projectx.data.actor.Knight)player.actor);
                if(player.actor.Mv()!=initial||player.player.loadouts.f("").equipment[6]!=granted.get(0).VA())
                    throw new AssertionError("Weapon equip reset peaceful town model or missed current loadout");
                // Catalogue-only types and bad references refuse without changing the target.
                int heard=feedback.size();staff.scene.speakService.a("!giveitem ItemPlayer Grant-Knight 1 Weapon/Bomb/Parts/Base",(byte)0);
                WorldConnectionCheck.waitFor(()->feedback.size()>heard);if(player.player.items.size()!=7)throw new AssertionError("Unsupported item granted");
                tools.setGameMaster("ItemStaff",false);int rev=feedback.size();staff.scene.speakService.a(command,(byte)0);
                WorldConnectionCheck.waitFor(()->staff.messages.size()==1);if(feedback.size()!=rev||!staff.messages.get(0).message.equals(command)||tools.worldProfile("ItemPlayer","Grant-Knight").items().size()!=7)throw new AssertionError("Revoked GM must use ordinary chat without grants");
                tools.setGameMaster("ItemStaff",true);
                // Drain one second so this fixture respects the shared chat/command rate budget.
                long end=System.nanoTime()+1_100_000_000L;while(System.nanoTime()<end){var task=WorldConnectionCheck.tasks.poll(50,java.util.concurrent.TimeUnit.MILLISECONDS);if(task!=null)task.run();}
                int offline=feedback.size();staff.scene.speakService.a("!giveitem ItemPlayer Offline-Knight 1 "+sword,(byte)0);
                WorldConnectionCheck.waitFor(()->feedback.size()>offline);if(tools.worldProfile("ItemPlayer","Offline-Knight").items().size()!=6)throw new AssertionError("Offline grant failed");
                if(player.player.items.size()!=7)throw new AssertionError("Offline grant reached wrong knight");
                // Expanded catalogue: stock LevelItem costume and plain Item stack/recipe readers.
                var costume=tools.catalog().require("Costume/Helm/Steamvalve Mask");
                tools.grantItems("ItemPlayer","Grant-Knight",costume.config(),1,-1,-1);
                String material="Material/Brimstone";
                tools.catalog().require(material);
                tools.grantItems("ItemPlayer","Grant-Knight",material,7,-1,-1);
                tools.grantItems("ItemPlayer","Grant-Knight","Recipe/Sword/Calibur Recipe",1,-1,-1);
                WorldConnectionCheck.waitFor(()->player.player.items.size()==10);
                var hat=find(player,costume.config());var stack=find(player,material);
                if(!hat.VC()||!hat.Cs()||stack.getClass()!=Item.class||stack.getCount()!=7)throw new AssertionError("Costume/stack stock type/flags/count mismatch");
                var costumeReply=new WorldConnectionCheck.Reply();service.a(hat.VA(),3,costumeReply);
                WorldConnectionCheck.waitFor(()->costumeReply.done&&player.player.equipment[3]==hat.VA());
                if(costumeReply.reason!=null)throw new AssertionError("Stock costume equip refused: "+costumeReply.reason);
                tools.grantItems("ItemPlayer","Grant-Knight",material,3,-1,-1);
                WorldConnectionCheck.waitFor(()->find(player,material).getCount()==10);
                var invalidReply=new WorldConnectionCheck.Reply();service.a(stack.VA(),6,invalidReply);
                WorldConnectionCheck.waitFor(()->invalidReply.done);
                if(invalidReply.reason==null||player.player.equipment[6]==stack.VA())throw new AssertionError("Material equipped as a weapon");
                // Local inventory administration must reach the unchanged client's native observers.
                var stored=tools.worldProfile("ItemPlayer","Grant-Knight");
                tools.itemQuantity("ItemPlayer","Grant-Knight",stack.VA(),6,stored.accountId(),stored.worldId());
                WorldConnectionCheck.waitFor(()->find(player,material).getCount()==6);
                tools.itemQuantity("ItemPlayer","Grant-Knight",stack.VA(),10,stored.accountId(),stored.worldId());
                WorldConnectionCheck.waitFor(()->find(player,material).getCount()==10);
                var recipe=find(player,"Recipe/Sword/Calibur Recipe");
                tools.itemQuantity("ItemPlayer","Grant-Knight",recipe.VA(),0,stored.accountId(),stored.worldId());
                WorldConnectionCheck.waitFor(()->player.player.items.size()==9);
                if(java.util.stream.StreamSupport.stream(player.player.items.spliterator(),false).anyMatch(i->i.VA()==recipe.VA()))throw new AssertionError("Removed recipe remains in client inventory");
                tools.grantItems("ItemPlayer","Grant-Knight","Recipe/Sword/Calibur Recipe",1,stored.accountId(),stored.worldId());
                WorldConnectionCheck.waitFor(()->player.player.items.size()==10);
                for(var slot:List.of(org.tortodrone.character.WorldProfile.Slot.ARMOR_COSTUME,org.tortodrone.character.WorldProfile.Slot.SHIELD_COSTUME)) {
                    var definition=tools.catalog().entries().stream().filter(e->e.slot()==slot&&e.grantable()).findFirst().orElseThrow();
                    int beforeCount=player.player.items.size();tools.grantItems("ItemPlayer","Grant-Knight",definition.config(),1,-1,-1);
                    WorldConnectionCheck.waitFor(()->player.player.items.size()==beforeCount+1);
                    var cosmetic=find(player,definition.config());var equipCosmetic=new WorldConnectionCheck.Reply();service.a(cosmetic.VA(),slot.initialIndex(),equipCosmetic);
                    WorldConnectionCheck.waitFor(()->equipCosmetic.done&&player.player.equipment[slot.initialIndex()]==cosmetic.VA());
                    if(equipCosmetic.reason!=null)throw new AssertionError("Native costume equip refused: "+equipCosmetic.reason);
                }
                var invalidCostume=new WorldConnectionCheck.Reply();service.a(hat.VA(),0,invalidCostume);WorldConnectionCheck.waitFor(()->invalidCostume.done);
                if(invalidCostume.reason==null||player.player.equipment[3]!=hat.VA())throw new AssertionError("Costume helmet accepted in ordinary helmet slot");
                for(int slot:new int[]{3,4,15}) {
                    long id=player.player.equipment[slot];var unequip=new WorldConnectionCheck.Reply();service.a(id,com.threerings.projectx.item.data.ItemCodes.Location.ARSENAL,0,unequip);
                    WorldConnectionCheck.waitFor(()->unequip.done&&player.player.equipment[slot]==0);
                    // The native saved loadout covers only its first twelve slots;
                    // shield costume lives in the separate player equipment array.
                    var loadout=player.player.loadouts.f("").equipment;
                    if(unequip.reason!=null||(slot<loadout.length&&loadout[slot]!=0))throw new AssertionError("Costume unequip missed native loadout observer");
                    var reEquip=new WorldConnectionCheck.Reply();service.a(id,slot,reEquip);WorldConnectionCheck.waitFor(()->reEquip.done&&player.player.equipment[slot]==id);
                    if(reEquip.reason!=null)throw new AssertionError("Costume re-equip refused");
                }
                var currentKnight=tools.knights("ItemPlayer").stream().filter(k->k.name().equals("Grant-Knight")).findFirst().orElseThrow();
                var appearance=WorldCompatibility.reference(org.tortodrone.character.StarterCatalog.town(currentKnight,tools.worldProfile("ItemPlayer","Grant-Knight"),tools.catalog()));
                WorldConnectionCheck.waitFor(()->appearance.equals(player.actor.Mv().kW().get("Model")));
            }
            try(var reconnect=new GameMasterConnectionCheck.Endpoint(args,queue,"ItemPlayer","Grant-Knight")) {
                if(reconnect.player.items.size()!=12||find(reconnect,"Material/Brimstone").getCount()!=10||(reconnect.player.equipment[3]==0||reconnect.player.equipment[4]==0||reconnect.player.equipment[15]==0)||!WorldConnectionCheck.bound(reconnect.player,reconnect.player.equipment[6]))throw new AssertionError("Reconnect lost owned grant/binding/loadout");
                // The picker renders the AvailableKnight model verbatim; it must show the current outfit, not the creation one.
                var knight=tools.knights("ItemPlayer").stream().filter(k->k.name().equals("Grant-Knight")).findFirst().orElseThrow();
                var expected=WorldCompatibility.reference(org.tortodrone.character.StarterCatalog.equipped(knight,tools.worldProfile("ItemPlayer","Grant-Knight"),tools.catalog()));
                var actual=Arrays.stream(reconnect.player.availableKnights)
                    .filter(entry->entry.name.toString().equals("Grant-Knight")).findFirst().orElseThrow().model;
                if(!actual.equals(expected))
                    throw new AssertionError("Character select preview differs from the equipped appearance:\n actual "+actual+"\n expected "+expected);
            }
            System.out.println("PASS: stock GM help/unknown-command feedback, normal/revoked ! chat, private item commands, Arsenal observers, cross-map grants, equip/binding, offline/ID grants, costume slots 3/4/15 equip/unequip/live appearance, inventory adjustment/removal, stacks/recipes and reconnect persistence");
        }
    }
    static Item find(GameMasterConnectionCheck.Endpoint endpoint,String config) {
        for(var item:endpoint.player.items)if(item.Vx().getName().equals(config))return item;
        throw new AssertionError("Missing item: "+config);
    }
    static void listen(com.threerings.projectx.town.data.TownSceneObject scene,List<String> messages) {
        scene.a(new l(){public void a(MessageEvent e){if(e.getName().equals("crowd.chat")&&e.yd()[0] instanceof SystemMessage m)messages.add(m.message);}});
    }
    static void seed(org.tortodrone.account.AccountStore tools,String user,String name)throws Exception {
        char[] hash=org.tortodrone.account.AccountStore.transform("lan-test-password".toCharArray());
        try{tools.authenticate(user,hash).characters().create(new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));}finally{Arrays.fill(hash,'\0');}
    }
}
