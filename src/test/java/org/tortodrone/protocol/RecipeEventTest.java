package org.tortodrone.protocol;

import org.tortodrone.support.TestWire;
import org.tortodrone.town.TownState;
import static org.tortodrone.support.TestWire.check;

/** Standalone recipe additions must be framed network notifications, never raw nested events. */
public final class RecipeEventTest {
    public static void main(String[] args)throws Exception {
        var wire=new TestWire();var in=wire.frame(new SessionCodec().recipeAdded(1001,new TownState.LearnedRecipe(42,"Armor/Padded Armor",0,0)));
        check(wire.type(in).equals(TestWire.NET+"EventNotification")&&in.readShort()==-1,"unsolicited network envelope");
        check(wire.type(in).equals(TestWire.DOB+"EntryAddedEvent")&&in.readInt()==1001,"recipe owner");
        check(TestWire.string(in).equals("recipes"),"native recipe set");
        check(wire.type(in).equals("com.threerings.projectx.craft.data.Recipe")&&in.readBoolean()&&in.readLong()==42&&TestWire.string(in).equals("Armor/Padded Armor"),"native recipe entry fields");
        TestWire.end(in);
        System.out.println("PASS: native recipe EntryAddedEvent envelope, owner and fields");
    }
}
