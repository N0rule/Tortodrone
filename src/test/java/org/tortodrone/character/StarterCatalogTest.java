package org.tortodrone.character;

import java.util.*;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.support.TestWire;
import static org.tortodrone.support.TestWire.check;

public final class StarterCatalogTest {
    public static void main(String[] args) throws Exception {
        Knight choices=new Knight(1,"Preview-Knight","Gear/Helm/Spiral Round Helm (Red)","Gear/Armor/Spiral Flak Jacket (Red)","Helm/Top/Headband/Headband","Helm/Back/Plume/Plume","Red",7,4,29,1);
        check(StarterCatalog.accepts(choices),"actual creator kit and accessories accepted");
        var model=StarterCatalog.preview(choices);
        check(model.arguments().get("Face Texture").equals("character/pc/face/eye_circuit.png") && model.arguments().get("Texture - Closed").equals("character/pc/face/eye_circuit_closed.png"),"eye and special blink textures");
        var helm=(StarterCatalog.Model)model.arguments().get("Helmet");
        check(helm.name().equals("item/gear/helm/valkyrie/model_v2-recolor.dat") && helm.arguments().get("Colorization").equals(263),"chosen helm reference and player color");
        check(helm.arguments().get("Blue").equals(new StarterCatalog.Colorization(3332)),"costume recolor metadata");
        check(model.arguments().containsKey("Armor") && model.arguments().containsKey("helm_top") && model.arguments().containsKey("helm_back"),"equipment and both accessory choices applied");
        var wire=new TestWire();var codec=new SessionCodec();
        var player=wire.snapshot(wire.frame(codec.player((short)1,1001,1,"Alice",Map.of(),List.of(choices))),(short)1,1001);
        var preview=(Map<?,?>)((Map<?,?>)((List<?>)player.get("availableKnights")).get(0)).get("model");
        check(((Map<?,?>)preview.get("_arguments")).containsKey("Helmet"),"nested equipment models serialize with ordered class and intern tables");
        for(int eye=0;eye<47;eye++) {
            var k=new Knight(1,"Eye-Knight",choices.helm(),choices.armor(),null,null,"Red",1,2,eye,1);
            var argsMap=StarterCatalog.preview(k).arguments();
            check(argsMap.get("Face Texture") instanceof String && argsMap.containsKey("Texture - Closed")==Set.of(19,29,46).contains(eye),"verified eye and blink schema for ordinal "+eye);
        }
        Knight mismatched=new Knight(0,"Bad-Knight",choices.helm(),choices.armor(),null,null,"Blue",1,2,0,0);
        check(!StarterCatalog.accepts(mismatched),"creator cannot request gear from a different costume");
        Knight unknown=new Knight(0,"Old-Knight","unknown-helm","unknown-armor",null,null,"unknown",1,2,0,1);
        check(!StarterCatalog.accepts(unknown) && !StarterCatalog.preview(unknown).arguments().containsKey("Helmet"),"unknown legacy choices do not turn into guessed assets");
        var store=new CharacterStore();
        try {store.create(unknown);throw new AssertionError("Unsupported equipment granted");}catch(CharacterStore.Refused expected){}
        check(store.roster().isEmpty(),"rejected choices cannot grant items or consume slots");
        var knight=store.create(new Knight(0,"Town-Knight","Gear/Helm/Spiral Sallet (Blue)",
            "Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
        var profile=new WorldProfile(1,1,knight.id(),0,0,1,List.of(
            new WorldProfile.Item(1,WorldProfile.Slot.HELM,knight.helm(),null,0,0),
            new WorldProfile.Item(2,WorldProfile.Slot.ARMOR,knight.armor(),null,0,1),
            new WorldProfile.Item(3,WorldProfile.Slot.SHIELD,"Gear/Shield/Proto Shield",null,0,2),
            new WorldProfile.Item(4,WorldProfile.Slot.SWORD,"Weapon/Sword/Proto Sword",null,0,6),
            new WorldProfile.Item(5,WorldProfile.Slot.GUN,"Weapon/Handgun/Proto Gun",null,0,7)));
        var town=StarterCatalog.town(knight,profile,ItemCatalog.starter());
        check(town.arguments().get("Shield Away").equals("True") && town.arguments().containsKey("Shield")
            && !town.arguments().containsKey("Weapon") && !town.arguments().containsKey("Weapon Idle"),
            "town carries shield on back with empty hands");
        check(StarterCatalog.equipped(knight,profile).arguments().containsKey("Weapon")
            && profile.equipment()[6]!=0 && profile.equipment()[7]!=0,"peaceful stance preserves equipped weapons and preview");
        var withoutShield=new WorldProfile(1,1,knight.id(),0,0,2,
            profile.items().stream().filter(i->i.slot()!=WorldProfile.Slot.SHIELD).toList());
        check(!StarterCatalog.town(knight,withoutShield,ItemCatalog.starter()).arguments().containsKey("Shield"),
            "town model removes unequipped shield");
        System.out.println("PASS: verified starter equipment, costume validation, nested wire preview, accessories, 47 eyes and blink textures");
    }
}
