package org.tortodrone.server.network;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.protocol.*;
import org.tortodrone.resources.ContentManager;
import org.tortodrone.storage.*;
import org.tortodrone.world.*;
import static org.tortodrone.support.TestWire.check;

/** Native Use/applyStyle routing and atomic cosmetic ownership on disposable SQLite state. */
public final class StyleSessionTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-styles-");
        try {
            var content=ContentManager.load(Path.of("."));var items=content.items();
            check(items.entries().stream().filter(i->StyleStore.kit(i.config())!=null).count()==75,"all 75 playable kit mappings resolve");
            var db=new Database(home,false);var password=new Passwords.Protection(new byte[16],new byte[32]);
            int account=db.create("Alice",password,false);var characters=new CharacterStore(db,account,"Alice",items);
            var knight=characters.create(choices("Style-Alice"));
            int bob=db.create("Bob",password,false);new CharacterStore(db,bob,"Bob",items).create(choices("Style-Bob"));
            for(String config:List.of("Upgrade/Eye/Almond","Upgrade/Eye/Happy","Upgrade/Height/Tall","Upgrade/Color/Amber"))
                db.grantItems("Alice",knight.name(),-1,-1,items.require(config),2,-1,-1);
            db.currency("Alice",knight.name(),account,-1,org.tortodrone.character.Currency.CROWNS,3000);
            var store=characters.styles();var initial=db.worldProfile(account,1,1);long almond=id(initial,"Upgrade/Eye/Almond");
            refused(()->store.change(account,1,-1,initial.revision(),almond,null));
            refused(()->store.change(account,1,99,initial.revision(),almond,null));
            refused(()->store.change(account,1,1,initial.revision()-1,almond,null));
            refused(()->store.change(bob,1,1,db.worldProfile(bob,1,1).revision(),almond,null));
            refused(()->store.change(account,1,1,initial.revision(),null,new StyleStore.Style(StyleStore.Kind.Eye,1)));
            var beforeRoster=db.roster(account);
            db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER fail_style BEFORE UPDATE OF state_revision ON world_characters BEGIN SELECT RAISE(ABORT,'fixture'); END");}return null;});
            refused(()->store.change(account,1,1,initial.revision(),almond,null));
            check(db.worldProfile(account,1,1).equals(initial)&&db.roster(account).equals(beforeRoster)&&store.saved(account,1,1).eyes().isEmpty(),"late failure rolls back inventory, saved style and appearance");
            db.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER fail_style");}return null;});
            var scenes=content.createScenes();var socket=new Socket(){public OutputStream getOutputStream(){return OutputStream.nullOutputStream();}};
            try(var output=new SessionOutput(socket);var world=new WorldSession(socket,characters,output,new SessionCodec(items),1001,1,m->{},scenes)){
                world.select(knight.name(),Map.of(SceneRegistry.ZONE_RECEIVER,(short)0));world.subscribe((short)1);world.entered();
                short request=20;
                for(String config:List.of("Upgrade/Eye/Almond","Upgrade/Eye/Happy","Upgrade/Height/Tall","Upgrade/Color/Amber")){
                    var profile=db.worldProfile(account,1,1);long item=id(profile,config);
                    check(world.townInvocation(new SessionDecoder.Invocation(SessionCodec.INVOCATION_OID,9,14,Arrays.asList(item,null,listener(request++)))),"native Use is dispatched");
                    var after=db.worldProfile(account,1,1);
                    check(after.items().stream().filter(i->i.id()==item).findFirst().orElseThrow().count()==1,"one kit consumed: "+config);
                    check(after.crowns()==3000,"first use has no reapplication fee");
                }
                var saved=store.saved(account,1,1);
                check(saved.eyes().equals(List.of(1,7))&&saved.colors().equals(List.of(264))&&saved.heights().equals(List.of(3)),"saved native style values");
                var current=db.worldProfile(account,1,1);
                refused(()->store.change(account,1,1,current.revision(),almond,null));
                db.write(c->{try(var st=c.createStatement()){st.execute("CREATE TRIGGER fail_reapply BEFORE UPDATE OF state_revision ON world_characters BEGIN SELECT RAISE(ABORT,'fixture'); END");}return null;});
                var rosterBeforeFee=db.roster(account);
                refused(()->store.change(account,1,1,current.revision(),null,new StyleStore.Style(StyleStore.Kind.Eye,1)));
                check(db.worldProfile(account,1,1).equals(current)&&db.roster(account).equals(rosterBeforeFee),"failed reapply rolls back fee and roster");
                db.write(c->{try(var st=c.createStatement()){st.execute("DROP TRIGGER fail_reapply");}return null;});
                check(world.townInvocation(new SessionDecoder.Invocation(SessionCodec.INVOCATION_OID,1,1,List.of(new SessionDecoder.EnumValue("com.threerings.projectx.data.ProjectXCodes$EyeType",1),listener(request++)))),"native style kit dispatch");
                var changed=db.worldProfile(account,1,1);check(changed.crowns()==2000&&db.roster(account).getFirst().eye()==1,"reapply charges exactly 1000 Crowns and changes appearance");
                check(db.roster(account).getFirst().color()==8&&db.roster(account).getFirst().height()==3,"native personal color normalized to existing roster and height applied");
                refused(()->store.change(account,1,1,changed.revision(),null,new StyleStore.Style(StyleStore.Kind.Eye,1)));
                check(db.worldProfile(account,1,1).equals(changed),"already worn style cannot charge twice");
                try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
                    var gate=new java.util.concurrent.CountDownLatch(1);var attempts=new ArrayList<java.util.concurrent.Future<Boolean>>();
                    for(int n=0;n<2;n++)attempts.add(pool.submit(()->{gate.await();try{store.change(account,1,1,changed.revision(),null,new StyleStore.Style(StyleStore.Kind.Eye,7));return true;}catch(IOException refused){return false;}}));
                    gate.countDown();int successes=0;for(var attempt:attempts)if(attempt.get())successes++;
                    check(successes==1&&db.worldProfile(account,1,1).crowns()==1000,"concurrent reapplication charges once");
                }
                var reopened=new StyleStore(new Database(home,false),items);
                check(reopened.saved(account,1,1).equals(saved)&&reopened.saved(bob,1,1).eyes().isEmpty(),"unlocks persist and stay owned across restart");
            }
            System.out.println("PASS: 75 style mappings; native Use/reapply; one-item consumption; Crown fee; persistence; ownership, auth, revision and rollback");
        }finally{org.tortodrone.support.ContentFixture.delete(home);}
    }
    private static SessionDecoder.Listener listener(short id){return new SessionDecoder.Listener("com.threerings.presents.data.InvocationMarshaller$ConfirmMarshaller",id);}
    private static long id(WorldProfile p,String config){return p.items().stream().filter(i->i.config().equals(config)).findFirst().orElseThrow().id();}
    private static Knight choices(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0);}
    @FunctionalInterface private interface Work{void run()throws Exception;}
    private static void refused(Work work)throws Exception{try{work.run();throw new AssertionError("Expected refusal");}catch(IOException expected){}}
}
