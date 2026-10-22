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
        long[] grace={0};var persistent=new PartyRegistry(()->grace[0]);
        try(var a=member(persistent,1);var b=member(persistent,2)){
            persistent.invite(a,"Knight-B");persistent.respond(b,"PARTY_INV:"+persistent.view(a).oid(),1);int party=persistent.view(a).oid();
            a.close();check(persistent.view(b).members().size()==1&&persistent.owner(b,2)==party,"a disconnected seat leaves the active party intact");
            try(var again=member(persistent,1)){
                check(persistent.view(again).oid()==party&&persistent.view(again).members().size()==2,"reconnect inside the grace window rejoins the same party");
                refused(()->persistent.boot(again,"Knight-B"));persistent.boot(b,"Knight-A");
                check(persistent.view(b).members().size()==1,"the reconnected member does not displace the active leader");
            }
        }
        long[] late={0};var expiring=new PartyRegistry(()->late[0]);
        try(var a=member(expiring,1);var b=member(expiring,2)){
            expiring.invite(a,"Knight-B");expiring.respond(b,"PARTY_INV:"+expiring.view(a).oid(),1);int party=expiring.view(a).oid();
            b.close();late[0]=120_000_000_000L;
            try(var again=member(expiring,2)){check(expiring.view(again).oid()!=party,"an expired reconnect starts a fresh party");}
        }
        var automatic=new PartyRegistry();
        try(var a=member(automatic,1)){
            automatic.enterTraining(a);int group=automatic.view(a).oid();a.close();
            try(var again=member(automatic,1)){check(automatic.view(again).oid()!=group,"Training groups never hold a disconnected seat");}
        }
        var capacity=new PartyRegistry();var members=new ArrayList<PartyRegistry.Member>();
        try{
            for(int id=1;id<=9;id++)members.add(member(capacity,id));var leader=members.getFirst();int oid=capacity.view(leader).oid();
            for(int i=1;i<8;i++){capacity.invite(leader,"Knight-"+(char)('A'+i));capacity.respond(members.get(i),"PARTY_INV:"+oid,1);}
            check(capacity.view(leader).members().size()==8,"additional invitations support the native eight-player capacity");
            refused(()->capacity.invite(leader,"Knight-I"));refused(()->capacity.joinParty(members.get(8),leader,true));capacity.boot(leader,"Knight-D");
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
        var publicScenes=new SceneRegistry(SceneCatalog.load(org.tortodrone.support.ContentFixture.path("maps")));
        var publicParties=publicScenes.parties();var visitors=new ArrayList<PartyRegistry.Member>();
        try {
            for(int id=1;id<=9;id++)visitors.add(member(publicParties,id));
            var room=publicScenes.destination(294,1,visitors.get(0));
            for(int i=1;i<8;i++)check(publicScenes.destination(294,i+1,visitors.get(i))==room,"independent players reuse the public Training simulation");
            var overflow=publicScenes.destination(294,9,visitors.get(8));
            check(overflow!=room&&publicParties.view(visitors.get(0)).automatic(),"ninth player gets a new public group and instance");
            refused(()->publicParties.boot(visitors.get(0),"Knight-B"));
            refused(()->publicParties.invite(visitors.get(0),"Knight-I"));
            check(!publicScenes.authorized(room,9,visitors.get(8)),"public matching still authorizes the exact assigned group");
            publicScenes.release(room,2);publicParties.leaveTraining(visitors.get(1));
            check(publicScenes.destination(294,2,visitors.get(1))==room,"leaving and returning reuses a compatible vacancy");
            for(int i=0;i<8;i++){publicScenes.release(room,i+1);publicParties.leaveTraining(visitors.get(i));}
            publicScenes.release(overflow,9);publicParties.leaveTraining(visitors.get(8));
            check(publicScenes.statuses().stream().noneMatch(status->status.templateId()==294),"empty public instances release all reservations");
            var labA=publicScenes.destination(30893,1,visitors.get(0));var labB=publicScenes.destination(30893,2,visitors.get(1));
            check(labA!=labB,"Laboratory allocation stays per knight");publicScenes.release(labA,1);publicScenes.release(labB,2);
        }finally{visitors.forEach(PartyRegistry.Member::close);}
        // Native open/private controls and direct friend follows into a shared room.
        var social=new PartyRegistry();
        try(var leader=member(social,1);var friend=member(social,2);var follower=member(social,3);var outsider=member(social,4)){
            social.invite(leader,"Knight-B");social.respond(friend,"PARTY_INV:"+social.view(leader).oid(),1);
            int room=social.view(leader).oid();
            check(!social.view(leader).inviteOnly()&&!social.view(leader).allowFriends(),"new explicit parties publish an open native party");
            social.joinParty(follower,friend,true);check(social.owner(follower,3)==room,"a friend follows an open party into its room identity");
            check(!social.joinParty(follower,friend,true),"following the party already held is a no-op");
            refused(()->social.setInviteOnly(friend,true));
            social.setInviteOnly(leader,true);check(social.view(leader).inviteOnly(),"the leader makes the party private");
            try(var newcomer=member(social,5)){
                refused(()->social.joinParty(newcomer,friend,true));
                social.setAllowFriends(leader,true);social.joinParty(newcomer,friend,true);
                check(social.owner(newcomer,5)==room,"private parties accept friends only after the leader opts in");
            }
            try(var stranger=member(social,7)){refused(()->social.joinParty(stranger,friend,false));}
            try(var solo=member(social,6)){refused(()->social.joinParty(outsider,solo,true));}
        }
        var publicGroups=new PartyRegistry();
        try(var a=member(publicGroups,1);var b=member(publicGroups,2)){
            publicGroups.enterTraining(a);
            check(publicGroups.view(a).automatic()&&!publicGroups.view(a).inviteOnly(),"public Training groups are open");
            publicGroups.joinParty(b,a,true);check(publicGroups.view(b).oid()==publicGroups.view(a).oid(),"a friend follows a public Training group");
            refused(()->publicGroups.setInviteOnly(a,true));
        }
        System.out.println("PASS: invite authorization/decline/expiry, reconnect invalidation, disconnected-party grace/expiry, Training seat release, leader succession, shared/private ownership, open/private controls, direct friend follows and lease cleanup");
    }
    private static PartyRegistry.Member member(PartyRegistry p,int id)throws IOException{return p.join(1000+id,new Knight(1,"Knight-"+(char)('A'+id-1),"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0),new WorldProfile(id,id,1,0,0,1,List.of()),e->{});}
    @FunctionalInterface private interface Operation{void run()throws Exception;}
    private static void refused(Operation operation)throws Exception{try{operation.run();throw new AssertionError("Unauthorized party operation accepted");}catch(IOException expected){}}
}
