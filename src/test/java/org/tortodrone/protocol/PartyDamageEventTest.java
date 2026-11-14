package org.tortodrone.protocol;

import java.io.IOException;
import org.tortodrone.support.TestWire;
import static org.tortodrone.support.TestWire.check;

/** PartyObject$DamageEvent must stream the native combat-feedback layout verbatim: the party
 *  object carries the named event (MonsterLogic posts a monster's dealt amount with the
 *  attacking pawn; DungeoneerLogic posts a knight's packed type breakdown with a literal zero
 *  source), the 2026 client's party-object listeners routing it by actorId. */
public final class PartyDamageEventTest {
    public static void main(String[] args)throws Exception {
        var wire=new TestWire();
        // A knight's lost hit: packed amount, literal zero source.
        var in=wire.frame(new SessionCodec().partyDamage(3002,41300,211,28856833,0,"NORMAL"));
        check(wire.type(in).equals(TestWire.NET+"EventNotification")&&in.readShort()==-1,"unsolicited network envelope");
        check(wire.type(in).equals("com.threerings.projectx.dungeon.data.PartyObject$DamageEvent")&&in.readInt()==3002,"party object target");
        check(TestWire.string(in).equals("damage"),"named damage kind");
        check(in.readInt()==41300&&in.readInt()==211,"hit timestamp and knight actor");
        check(in.readInt()==28856833&&in.readInt()==0,"packed type breakdown and literal zero source");
        check(wire.type(in).equals("com.threerings.projectx.dungeon.util.DamageSummary$Performance")&&in.readUnsignedByte()==1,"NORMAL performance ordinal");
        TestWire.end(in);
        // A knight's sword on a monster: plain dealt amount, attributed source.
        var wire2=new TestWire();var in2=wire2.frame(new SessionCodec().partyDamage(3002,41400,402,13,211,"NORMAL_LARGE"));
        check(wire2.type(in2).equals(TestWire.NET+"EventNotification")&&in2.readShort()==-1,"monster envelope");
        check(wire2.type(in2).equals("com.threerings.projectx.dungeon.data.PartyObject$DamageEvent")&&in2.readInt()==3002,"monster event rides the same party object");
        check(TestWire.string(in2).equals("damage"),"monster damage kind");
        check(in2.readInt()==41400&&in2.readInt()==402&&in2.readInt()==13&&in2.readInt()==211,"dealt amount and the attacking pawn as source");
        check(wire2.type(in2).equals("com.threerings.projectx.dungeon.util.DamageSummary$Performance")&&in2.readUnsignedByte()==8,"NORMAL_LARGE performance ordinal");
        TestWire.end(in2);
        // Only native performance names stream; anything else must refuse, not send garbage.
        try{new SessionCodec().partyDamage(3002,0,0,0,0,"SPARKLY");check(false,"unsupported performance refused");}catch(IOException expected){}
        System.out.println("PASS: PartyObject$DamageEvent envelope, name, four native fields and performance ordinal, refusing unknown performances");
    }
}
