package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.storage.Database;
import org.tortodrone.world.SceneRegistry;
import static org.tortodrone.support.TestWire.check;

/** Native Arsenal use/extend dispatch must not grant repeated duration or accept malformed listeners. */
public final class UpgradeSessionTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-upgrade-session-");
        try {
            var snapshot=ContentManager.load(Path.of("."));var items=snapshot.items();
            var db=new Database(home,true);int account=db.create("Alice",new Passwords.Protection(new byte[16],new byte[32]),false);
            var characters=new CharacterStore(db,account,"Alice",items);
            var knight=characters.create(new Knight(0,"Upgrade-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            var p=db.grantItems("Alice",knight.name(),-1,-1,items.require("weapon_slot"),2,-1,-1);
            var ids=p.items().stream().filter(i->i.config().equals("Upgrade/Weapon Slot")).map(WorldProfile.Item::id).toList();
            var socket=new Socket(){public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}};
            try(var output=new SessionOutput(socket);var world=new WorldSession(socket,characters,output,new SessionCodec(items),1001,1,m->{},snapshot.createScenes())) {
                world.select(knight.name(),Map.of(SceneRegistry.ZONE_RECEIVER,(short)0));world.subscribe((short)1);world.entered();
                var listener=new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",(short)12);
                var use=new SessionDecoder.Invocation(3,9,14,Arrays.asList(ids.getFirst(),null,listener));
                check(world.townInvocation(use),"native use dispatched");
                var active=db.worldProfile(account,1,1);check(SlotUpgrades.weapons(active)==3,"native use unlocks exactly one slot");
                world.townInvocation(use);check(db.worldProfile(account,1,1).equals(active),"use replay cannot restart a timer");
                var extend=new SessionDecoder.Invocation(3,9,5,List.of(ids.getFirst(),ids.get(1),listener));
                check(world.townInvocation(extend),"native extension dispatched");var extended=db.worldProfile(account,1,1);
                world.townInvocation(extend);check(db.worldProfile(account,1,1).equals(extended),"consumed source prevents extension replay");
                var malformed=new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ResultMarshaller",(short)13);
                try{world.townInvocation(new SessionDecoder.Invocation(3,9,5,List.of(ids.getFirst(),ids.get(1),malformed)));throw new AssertionError("Malformed listener accepted");}catch(SessionDecoder.Rejected expected){}
                check(db.worldProfile(account,1,1).equals(extended),"malformed listener cannot mutate upgrade");
                world.close();world.townInvocation(use);check(db.worldProfile(account,1,1).equals(extended),"closed session cannot use upgrades");
            }
            System.out.println("PASS: native upgrade use/extend, replay, malformed listener and closed-session rejection");
        }finally{org.tortodrone.support.ContentFixture.delete(home);}
    }
}
