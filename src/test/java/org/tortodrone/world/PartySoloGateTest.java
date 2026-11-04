package org.tortodrone.world;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.character.*;
import static org.tortodrone.support.TestWire.check;

public final class PartySoloGateTest {
    public static void main(String[] args)throws Exception {
        var parties=new PartyRegistry();
        try(var owner=member(parties,1);var peer=member(parties,2)){
            int oid=parties.view(owner).oid();
            refuse(()->parties.soloGate(peer,oid,()->Boolean.TRUE));
            refuse(()->parties.soloGate(owner,oid,()->{throw new IOException("fixture rollback");}));
            check(parties.soloGate(owner,oid,()->41)==41,"failed callback does not corrupt the party lease");
            parties.enterOpening(owner);var view=parties.view(owner);
            check(view.inviteOnly()&&!view.allowFriends()&&!view.allowGuild()&&!view.automatic(),"opening seat is genuinely private");
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var bootStarted=new CountDownLatch(1);
            var payout=CompletableFuture.supplyAsync(()->{
                try{return parties.soloGate(owner,oid,()->{entered.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new IOException("fixture timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}return 42;});}catch(IOException e){throw new CompletionException(e);}
            });
            check(entered.await(3,TimeUnit.SECONDS),"callback entered");
            var boot=CompletableFuture.runAsync(()->{bootStarted.countDown();try{parties.boot(owner,"Gate-1");}catch(IOException e){throw new CompletionException(e);}});
            try{check(bootStarted.await(3,TimeUnit.SECONDS),"concurrent abandonment started");Thread.sleep(100);check(!boot.isDone(),"party cannot abandon between payout authorization and commit");}finally{release.countDown();}
            check(payout.get(3,TimeUnit.SECONDS)==42,"pinned transaction finishes");boot.get(3,TimeUnit.SECONDS);
            refuse(()->parties.soloGate(owner,oid,()->Boolean.TRUE));
            check(parties.view(owner).oid()!=oid,"abandonment replaces the old authorized party seat");
            parties.enterTraining(owner);refuse(()->parties.soloGate(owner,parties.view(owner).oid(),()->Boolean.TRUE));
        }
        System.out.println("PASS: solo gate party identity, foreign/stale/automatic refusal, callback failure, real private settings and concurrent abandonment pinning");
    }
    private static PartyRegistry.Member member(PartyRegistry registry,int id)throws IOException{return registry.join(1000+id,new Knight(id,"Gate-"+id,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0),new WorldProfile(id,id,id,0,0,1,List.of()),event->{});}
    private static void refuse(Operation work)throws Exception{try{work.run();throw new AssertionError("Unauthorized gate operation accepted");}catch(IOException expected){}}
    private interface Operation{void run()throws Exception;}
}
