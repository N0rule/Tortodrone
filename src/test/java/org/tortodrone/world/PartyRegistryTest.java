package org.tortodrone.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.tortodrone.character.*;
import static org.tortodrone.support.TestWire.check;

/** Authorization, invitation lifetime, party-owned leases and leader/disconnect cleanup. */
public class PartyRegistryTest {
    public static void main(String[] args)throws Exception {
        long[] clock={0};var parties=new PartyRegistry(()->clock[0]);
        try(var a=member(parties,1);var b=member(parties,2);var c=member(parties,3)){
            int party=parties.view(a).oid();refused(()->parties.respond(b,"PARTY_INV:"+party,1));
            parties.invite(a,"Knight-B");parties.respond(b,"PARTY_INV:"+party,0);check(parties.view(b).oid()!=party,"declining preserves isolation");
            parties.invite(a,"Knight-B");clock[0]=61_000_000_000L;refused(()->parties.respond(b,"PARTY_INV:"+party,1));
            parties.invite(a,"Knight-B");parties.respond(b,"PARTY_INV:"+party,1);check(parties.owner(a,1)==parties.owner(b,2)&&parties.owner(a,1)!=0,"accepted members share a server-owned identity");
            refused(()->parties.boot(b,"Knight-A"));refused(()->parties.invite(b,"Knight-C"));refused(()->parties.respond(c,"PARTY_INV:"+party,1));
            parties.invite(a,"Knight-C");c.close();try(var reconnect=member(parties,3)){refused(()->parties.respond(reconnect,"PARTY_INV:"+party,1));}
            a.close();check(parties.view(b).members().size()==1&&parties.owner(b,2)==party,"leader disconnect preserves the remaining member's party and ownership");
        }
        var capacity=new PartyRegistry();var members=new ArrayList<PartyRegistry.Member>();
        try{
            for(int id=1;id<=9;id++)members.add(member(capacity,id));var leader=members.getFirst();int oid=capacity.view(leader).oid();
            for(int i=1;i<8;i++){capacity.invite(leader,"Knight-"+(char)('A'+i));capacity.respond(members.get(i),"PARTY_INV:"+oid,1);}
            check(capacity.view(leader).members().size()==8,"additional invitations support the native eight-player capacity");
            refused(()->capacity.invite(leader,"Knight-I"));capacity.boot(leader,"Knight-D");
            check(capacity.view(leader).members().size()==7&&capacity.view(members.get(3)).oid()!=oid,"leader removal splits only the authorized target");
        }finally{members.forEach(PartyRegistry.Member::close);}
        var scenes=new SceneRegistry(SceneCatalog.load(org.tortodrone.support.ContentFixture.path("maps")));var p=scenes.parties();
        try(var a=member(p,1);var b=member(p,2);var c=member(p,3)){
            p.invite(a,"Knight-B");p.respond(b,"PARTY_INV:"+p.view(a).oid(),1);
            var shared=scenes.destination(294,1,a);check(shared==scenes.destination(294,2,b),"accepted party routes into one simulation");
            var privateRoom=scenes.destination(294,3,c);check(privateRoom!=shared&&!scenes.authorized(shared,3,c),"unrelated player cannot authorize a guessed party room");
            scenes.release(shared,2);check(scenes.destination(294,1,a)==shared,"one member leaving cannot release a peer's instance lease");
            scenes.release(shared,1);check(scenes.statuses().stream().noneMatch(s->s.instanceId()==shared.oid()),"last lease releases the shared instance");
            scenes.release(privateRoom,3);
        }
        System.out.println("PASS: invite authorization/decline/expiry, reconnect invalidation, leader succession, shared/private ownership and lease cleanup");
    }
    private static PartyRegistry.Member member(PartyRegistry p,int id)throws IOException{return p.join(1000+id,new Knight(1,"Knight-"+(char)('A'+id-1),"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0),new WorldProfile(id,id,1,0,0,1,List.of()),e->{});}
    @FunctionalInterface private interface Operation{void run()throws Exception;}
    private static void refused(Operation operation)throws Exception{try{operation.run();throw new AssertionError("Unauthorized party operation accepted");}catch(IOException expected){}}
}
