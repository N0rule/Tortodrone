package org.tortodrone.web;
import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.*;
import org.tortodrone.character.*;
import org.tortodrone.resources.*;
import org.tortodrone.server.*;
import org.tortodrone.storage.*;
import static org.tortodrone.support.TestWire.check;

/** Native tooltip examples plus offline equipment ownership, rollback and attachment safety. */
public final class WebGearTest {
    static final String BAN="Weapon/Sword/The Ban Stick",GUN="Weapon/Handgun/Fiery Diskgun";
    public static void main(String[] args)throws Exception {
        var content=ContentManager.load(Path.of("."));var root=Files.createTempDirectory("web-gear-test-");var data=root.resolve("data");
        try(var accounts=new AccountStore(data,false,content.items(),content.town());var log=new ServerLog(data.resolve("logs"));var service=new AdminService(accounts,new ServerControl("fixture",data),log,root,content)) {
            var unsupported=(AdminViews.Item)service.read("item",new AdminService.Input(Map.of("config","Weapon/Bomb/Big Angry Bomb")));
            check(!unsupported.grantable()&&unsupported.compatibleSlots().isEmpty(),"unsupported native weapon remains inspectable without inventing a slot");
            var uv=List.of("Weapon/Attack Speed 4","Weapon/Beast Damage 4","Weapon/Charge Time 4");
            var preview=preview(service,BAN,4,uv);String display=Json.stringify(preview.get("effects"));
            for(String label:List.of("Attack Speed Increased: Very High","Damage Bonus vs Beast: Very High","Charge Time Reduction: Very High","Charge Time Reduction: Low"))check(display.contains(label),"native Ban Stick effect: "+label);
            var gun=preview(service,GUN,0,List.of());var stats=(Map<?,?>)gun.get("stats");
            check(!stats.containsKey("health-bonus")&&((Number)stats.get("damage.elemental")).floatValue()==226,"Diskgun native elemental damage, no zero health");
            var shield=content.weapons().shields().stream().filter(x->content.items().find(x.config())!=null).findFirst().orElseThrow();
            check(((Number)((Map<?,?>)preview(service,shield.config(),0,List.of()).get("stats")).get("shield-health")).intValue()==shield.health(),"shield health comes from authoritative shield definition");
            check(((Number)((Map<?,?>)preview.get("stats")).get("attack-speed")).floatValue()>5,"UV changes calculated attack speed");
            refused(()->preview(service,BAN,0,List.of("Weapon/Charge Time 4","Weapon/Charge Time 1")));
            accounts.create("Fixture","fixture-password".toCharArray(),false);
            int account=accounts.list().getFirst().id();var db=new Database(data,false);
            new CharacterStore(db,account,"Fixture",content.items()).create(new Knight(0,"Gear-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            var p=accounts.worldProfile("Fixture","Gear-Knight");
            p=accounts.administrativeEquipment("Fixture","Gear-Knight",account,p.worldId(),p.revision(),0,6,BAN);
            var item=p.items().stream().filter(i->i.config().equals(BAN)).findFirst().orElseThrow();
            check(item.equippedSlot()==6&&(!item.bindOnEquip()||item.bound()),"atomic grant/equip binds");
            final var before=p;final long id=item.id();
            refused(()->accounts.administrativeEquipment("Fixture","Gear-Knight",account,before.worldId(),before.revision()-1,id,null,""));
            refused(()->accounts.administrativeEquipment("Fixture","Gear-Knight",account,before.worldId(),before.revision(),id,0,""));
            refused(()->accounts.administrativeEquipment("Fixture","Gear-Knight",account,before.worldId(),before.revision(),0,8,GUN));
            refused(()->accounts.administrativeEquipment("Fixture","Gear-Knight",account+1,before.worldId(),before.revision(),id,6,""));
            refused(()->accounts.administrativeEquipment("Fixture","Gear-Knight",account,before.worldId(),before.revision(),Long.MAX_VALUE,6,""));
            check(accounts.worldProfile("Fixture","Gear-Knight").equals(before),"failed grant/equip and ownership checks roll back all state");
            char[] hash=Passwords.transform("fixture-password".toCharArray());
            var identity=accounts.authenticate("Fixture",hash);Arrays.fill(hash,'\0');
            try(var lease=accounts.attach(identity,new Socket())) {
                refused(()->accounts.administrativeEquipment("Fixture","Gear-Knight",account,before.worldId(),before.revision(),id,null,""));
                var view=(AdminViews.KnightView)service.read("knight-view",new AdminService.Input(Map.of("username","Fixture","knight","Gear-Knight")));
                check(!view.equipmentEditable(),"attached account explicitly read-only for equipment");
            }
            p=accounts.administrativeEquipment("Fixture","Gear-Knight",account,p.worldId(),p.revision(),id,null,"");
            check(p.items().stream().filter(i->i.id()==id).findFirst().orElseThrow().equippedSlot()==null,"offline unequip");
            check(accounts.knightState("Fixture","Gear-Knight").revision()==p.revision(),"lightweight probe sees mutation revision");
            p=accounts.progress("Fixture","Gear-Knight",3,2,1000,account,p.worldId(),"Drakon",p.revision());
            var view=(AdminViews.KnightView)service.read("knight-view",new AdminService.Input(Map.of("username","Fixture","knight","Gear-Knight")));
            check(Json.stringify(view.sprites().getFirst().display()).contains("Firebolt"),"official skill names");
            check(view.sprites().getFirst().perkNames().get("BOMB_DAMAGE_MED").equals("Mighty Boom II"),"official perk names");
            check(view.sprites().getFirst().spentPoints()+view.sprites().getFirst().availablePoints()==view.sprites().getFirst().skillPoints(),"authoritative skill point balance");
            System.out.println("PASS: native item/Sprite presentation, equipment grant/equip/unequip, binding, revision, ownership, rollback and online refusal");
        }finally{try(var walk=Files.walk(root)){for(var p:walk.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    static Map<?,?> preview(AdminService service,String config,int level,List<String> uv)throws IOException{return (Map<?,?>)service.read("item-preview",new AdminService.Input(Map.of("config",config,"level",""+level,"heat","0","variants",String.join("\n",uv))));}
    interface Action{void run()throws Exception;}
    static void refused(Action action)throws Exception{try{action.run();throw new AssertionError("Unsafe action accepted");}catch(IOException expected){}}
}
