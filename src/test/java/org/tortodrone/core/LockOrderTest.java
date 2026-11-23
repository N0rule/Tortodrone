package org.tortodrone.core;

import static org.tortodrone.support.TestWire.check;

/** The documented monitor order is increasing and an inversion is detected, not ignored. */
public final class LockOrderTest {
    public static void main(String[] args) {
        check(LockOrder.CONTROL<LockOrder.REGISTRY&&LockOrder.REGISTRY<LockOrder.SCENE
            &&LockOrder.SCENE<LockOrder.CHARACTER&&LockOrder.CHARACTER<LockOrder.OUTPUT
            &&LockOrder.OUTPUT<LockOrder.STORAGE,"documented ranks are strictly increasing");

        LockOrder.enter(LockOrder.CONTROL);
        LockOrder.enter(LockOrder.REGISTRY);
        LockOrder.enter(LockOrder.SCENE);
        check(LockOrder.depth()==3,"nested acquisition follows the documented order");
        LockOrder.exit();LockOrder.exit();LockOrder.exit();
        check(LockOrder.depth()==0,"releasing restores the stack depth");

        boolean inverted=false;
        LockOrder.enter(LockOrder.CHARACTER);
        try { LockOrder.enter(LockOrder.SCENE); }
        catch(IllegalStateException expected){inverted=expected.getMessage().contains("character -> scene");}
        finally { LockOrder.exit(); }
        check(inverted,"acquiring a lower rank while a higher one is held is rejected");
        check(LockOrder.depth()==0,"the rejected acquisition leaves no stale entry");

        LockOrder.enter(LockOrder.SCENE);
        LockOrder.enter(LockOrder.STORAGE);
        check(LockOrder.depth()==2,"a named scene commit path may acquire storage after the scene");
        LockOrder.exit();LockOrder.exit();

        LockOrder.enter(LockOrder.OUTPUT);
        LockOrder.enter(LockOrder.OUTPUT);
        check(LockOrder.depth()==2,"the same rank may nest");
        LockOrder.exit();LockOrder.exit();

        // CharacterStore's monitor enters CHARACTER, so an output-held call is a real
        // inversion rather than a documentation-only rule.
        var store=new org.tortodrone.character.CharacterStore();
        LockOrder.enter(LockOrder.SCENE);
        store.roster();
        LockOrder.exit();
        boolean characterInverted=false;
        LockOrder.enter(LockOrder.OUTPUT);
        try { store.roster(); }
        catch(IllegalStateException expected){characterInverted=expected.getMessage().contains("output -> character");}
        finally { LockOrder.exit(); }
        check(characterInverted,"the roster monitor participates in the written order");
        check(LockOrder.depth()==0,"the rejected character acquisition leaves no stale entry");

        System.out.println("PASS: lock order is documented, increasing and inversion-checked");
    }
}
