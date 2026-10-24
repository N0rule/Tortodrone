package org.tortodrone.server;

import java.net.*;
import java.nio.file.Path;
import java.util.*;
import org.tortodrone.character.*;
import org.tortodrone.town.*;
import org.tortodrone.world.*;
import static org.tortodrone.support.TestWire.check;

/** The WebUI can inspect only a running session's current owned scene membership. */
public final class CompanionInspectionTest {
    public static void main(String[] args)throws Exception {
        var catalog=SceneCatalog.load(org.tortodrone.support.ContentFixture.path("maps"));
        var scenes=new SceneRegistry(catalog);
        scenes.town(TownCatalog.load(org.tortodrone.support.ContentFixture.path("town/catalog.json")));
        var map=catalog.scenes().stream().filter(m->!m.town()).findFirst().orElseThrow();
        var instance=scenes.destination(map.id(),10);
        var actor=new ActorState(10,1001,"Fixture",0,SceneClock.now(),map.spawnX(),map.spawnY(),0,0,0,new StarterCatalog.Model("Character/PC/Level",Map.of()));
        var sprite=new TownState.Sprite(7,"Drakon","Sparky",0,0,null,0,0,Set.of(),null,Collections.nCopies(9,0),Collections.nCopies(3,0),0);
        var profile=new WorldProfile(10,1,1,0,0,1,List.of(),KnightProgress.RECRUIT,new TownState(List.of(),List.of(sprite),7,List.of(),List.of()));
        var member=instance.simulation().join(actor,snapshot->{});
        var control=new ServerControl("fixture",Path.of("/tmp"));
        control.resources(scenes,0);
        control.sessions().connected(1,new InetSocketAddress("127.0.0.1",1234));
        var events=control.sessions().observe(1,message->{});
        events.authenticated(1,"Fixture");events.selected(1,10,"Fixture");events.scene(map.id(),instance.oid(),map.name());
        try(var socket=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            member.combat(WeaponCatalog.load(org.tortodrone.support.ContentFixture.path("combat/weapons.tsv")),profile);member.equipment(profile);member.enter();
            check(control.companion(1,1,10)==null,"stopped listener cannot expose an old scene");
            control.awaitStart(false);control.listening(socket,null);
            check(control.companion(1,1,10).deployed().sprite().id()==7,"current running session reads its scene-owned companion");
            check(control.companion(1,2,10)==null&&control.companion(1,1,11)==null&&control.companion(2,1,10)==null,"foreign account, Knight and session reads are rejected");
            events.scene(map.id(),-1,"Travelling");
            check(control.companion(1,1,10)==null,"a changed destination cannot read the previous scene");
            events.selected(1,10,"Fixture");
            check(control.companion(1,1,10)==null,"selection without scene entry is unavailable");
            events.scene(map.id(),instance.oid(),map.name());control.sessions().disconnected(1);
            check(control.companion(1,1,10)==null,"disconnected sessions cannot read a retained membership");
            control.stop();check(control.companion(1,1,10)==null,"stopping invalidates scene inspection");
        } finally { member.close();scenes.release(instance,10);control.stopped(); }
        System.out.println("PASS: running companion inspection, ownership, travel, selection and disconnect guards");
    }
}
