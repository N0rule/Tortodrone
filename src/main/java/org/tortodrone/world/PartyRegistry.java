package org.tortodrone.world;

import java.io.IOException;
import java.util.*;
import java.util.function.*;
import org.tortodrone.character.*;

/** Transient, session-bound parties. Endpoints only enqueue work; they never perform network I/O. */
public final class PartyRegistry {
    public static final int CAPACITY=8;
    public sealed interface Event permits Refresh,Invitation,Chat { }
    public record Refresh() implements Event { }
    public record Invitation(int partyOid,List<String> names,int inviter,boolean add) implements Event { }
    public record Chat(int partyOid,SceneChat.Message message) implements Event { }
    public record Participant(int playerOid,int worldId,int order,Knight knight,WorldProfile profile,SceneHealth.State health,SceneStatus.State status) { }
    public record View(int oid,long revision,List<Participant> members,boolean automatic) { public View{members=List.copyOf(members);} }
    public final class Member implements AutoCloseable {
        public final int playerOid,worldId;
        private Knight knight;private WorldProfile profile;
        private SceneHealth.State health=new SceneHealth.State(1,1,0);
        private SceneStatus.State status=SceneStatus.State.NORMAL;
        private final Consumer<Event> endpoint;
        private Party party;private int order;private boolean town=true;
        private Member(int oid,Knight knight,WorldProfile profile,Consumer<Event> endpoint){playerOid=oid;worldId=profile.worldId();this.knight=knight;this.profile=profile;this.endpoint=endpoint;}
        @Override public void close(){synchronized(PartyRegistry.this){if(online.remove(worldId,this)){invalidate(this);remove(this,false);}}}
    }
    private static final class Party {final int oid;long revision;int nextOrder;boolean shared,automatic;final LinkedHashSet<Member> members=new LinkedHashSet<>();Party(int oid){this.oid=oid;} }
    private record Invite(Member target,Member sender,Party party,long expires) { }
    private final Map<Integer,Member> online=new HashMap<>();
    private final LinkedHashSet<Party> training=new LinkedHashSet<>();
    private final Map<Member,Map<Integer,Invite>> invitations=new HashMap<>();
    private final LongSupplier clock;
    private int nextOid=1_600_000_000;
    public PartyRegistry(){this(System::nanoTime);}
    public PartyRegistry(LongSupplier clock){this.clock=Objects.requireNonNull(clock);}
    public synchronized Member join(int oid,Knight knight,WorldProfile profile,Consumer<Event> endpoint)throws IOException {
        if(online.containsKey(profile.worldId()))throw new IOException("Knight already has a party session");
        var member=new Member(oid,knight,profile,Objects.requireNonNull(endpoint));online.put(member.worldId,member);solo(member);return member;
    }
    private void solo(Member member){if(nextOid==Integer.MAX_VALUE)throw new IllegalStateException("Party identity space exhausted");var p=new Party(nextOid++);p.members.add(member);member.party=p;member.order=p.nextOrder++;member.endpoint.accept(new Refresh());}
    /** Public Training groups have no player-controlled leader or invitation rights. */
    public synchronized void enterTraining(Member member)throws IOException {
        require(member);
        if(member.party.shared)return;
        var target=training.stream().filter(p->p.members.size()<CAPACITY).findFirst().orElse(null);
        invalidate(member);remove(member,false);
        if(target==null){solo(member);target=member.party;target.shared=target.automatic=true;training.add(target);}
        else{member.party=target;member.order=target.nextOrder++;target.members.add(member);target.revision++;}
        publish(target);
    }
    public synchronized void leaveTraining(Member member)throws IOException {
        require(member);if(!member.party.automatic)return;
        remove(member,true);
    }
    private void require(Member member)throws IOException{if(member==null||online.get(member.worldId)!=member)throw new IOException("Party session is no longer current");}
    public synchronized View view(Member member)throws IOException{require(member);return view(member.party);}
    private View view(Party party){return new View(party.oid,party.revision,party.members.stream().map(m->new Participant(m.playerOid,m.worldId,m.order,m.knight,m.profile,m.health,m.status)).toList(),party.automatic);}
    public synchronized int owner(Member member,int world)throws IOException{require(member);if(member.worldId!=world)throw new IOException("Foreign party owner");return member.party.shared?member.party.oid:0;}
    public synchronized boolean owns(Member member,int oid){return member!=null&&online.get(member.worldId)==member&&member.party.oid==oid;}
    @FunctionalInterface public interface ReviveTransaction<T>{T commit(int targetWorld)throws IOException;}
    /** Pin both current party memberships through the authenticated revive transaction. */
    public synchronized <T> T revive(Member payer,int oid,String name,ReviveTransaction<T> transaction)throws IOException {
        require(payer);if(payer.party.oid!=oid)throw new IOException("m.no_revive");
        var target=payer.party.members.stream().filter(m->m.knight.name().equalsIgnoreCase(name)).findFirst().orElseThrow(()->new IOException("m.no_revive"));
        return transaction.commit(target.worldId);
    }
    public synchronized void state(Member member,Knight knight,WorldProfile profile,SceneHealth.State health,SceneStatus.State status,boolean town)throws IOException {
        require(member);boolean changed=!member.knight.equals(knight)||!member.profile.equals(profile)||!member.health.equals(health)||!member.status.equals(status);
        member.knight=knight;member.profile=profile;member.health=health;member.status=status;member.town=town;if(changed)publish(member.party);
    }
    public synchronized void invite(Member sender,String name)throws IOException {
        require(sender);var party=sender.party;if(party.automatic)throw new IOException("Training groups do not allow invitations");if(party.members.iterator().next()!=sender)throw new IOException("Only the party leader can invite");
        if(party.members.size()>=CAPACITY)throw new IOException("The party is full");
        var target=online.values().stream().filter(m->m.knight.name().equalsIgnoreCase(name)).findFirst().orElseThrow(()->new IOException("That knight is not online"));
        if(target.party==party)throw new IOException("That knight is already in your party");
        if(!sender.town||!target.town)throw new IOException("Form parties in Haven before entering Training");
        if(target.party.members.size()!=1)throw new IOException("That knight is already in another party");
        var pending=invitations.computeIfAbsent(target,k->new HashMap<>());if(pending.size()>=8&&!pending.containsKey(party.oid))throw new IOException("That knight has too many pending invitations");
        var invitation=new Invite(target,sender,party,clock.getAsLong()+60_000_000_000L);pending.put(party.oid,invitation);
        target.endpoint.accept(new Invitation(party.oid,party.members.stream().map(m->m.knight.name()).toList(),new ArrayList<>(party.members).indexOf(sender),true));
    }
    public synchronized void respond(Member target,String key,int response)throws IOException {
        require(target);if(response!=0&&response!=1)throw new IOException("Invalid party invitation response");
        var pending=invitations.get(target);Invite invite=pending==null?null:pending.values().stream().filter(i->key.equals("PARTY_INV:"+i.party.oid)).findFirst().orElse(null);
        if(invite==null)throw new IOException("This party invitation is no longer available");
        pending.remove(invite.party.oid);target.endpoint.accept(new Invitation(invite.party.oid,List.of(),0,false));
        if(response==0)return;
        if(clock.getAsLong()>=invite.expires||online.get(invite.sender.worldId)!=invite.sender||invite.sender.party!=invite.party
                ||invite.party.members.iterator().next()!=invite.sender||target.party.members.size()!=1||!target.town||!invite.sender.town||invite.party.members.size()>=CAPACITY)
            throw new IOException("This party invitation has expired or is no longer valid");
        invalidate(target);remove(target,false);target.party=invite.party;target.order=invite.party.nextOrder++;invite.party.members.add(target);invite.party.shared=true;invite.party.revision++;publish(invite.party);
    }
    public synchronized void boot(Member caller,String name)throws IOException {
        require(caller);var party=caller.party;if(party.automatic){if(!caller.knight.name().equalsIgnoreCase(name))throw new IOException("Training groups have no player leader");remove(caller,true);return;}var target=party.members.stream().filter(m->m.knight.name().equalsIgnoreCase(name)).findFirst().orElseThrow(()->new IOException("That knight is not in your party"));
        if(target!=caller&&party.members.iterator().next()!=caller)throw new IOException("Only the party leader can remove members");
        invalidate(target);remove(target,true);
    }
    private void remove(Member member,boolean replace){var old=member.party;if(old==null)return;old.members.remove(member);old.revision++;if(old.members.isEmpty())training.remove(old);member.party=null;if(!old.members.isEmpty())publish(old);if(replace)solo(member);}
    private void invalidate(Member member){
        for(var iterator=invitations.entrySet().iterator();iterator.hasNext();){var entry=iterator.next();entry.getValue().values().removeIf(i->{if(i.target==member||i.sender==member){i.target.endpoint.accept(new Invitation(i.party.oid,List.of(),0,false));return true;}return false;});if(entry.getValue().isEmpty())iterator.remove();}
    }
    private void publish(Party party){for(var m:party.members)m.endpoint.accept(new Refresh());}
    public synchronized void speak(Member sender,String text,byte mode)throws IOException {
        require(sender);text=SceneChat.validate(text);if(mode<0||mode>3)throw new IOException("Unsupported party chat mode");
        var event=new Chat(sender.party.oid,new SceneChat.Message(sender.knight.name(),text,mode));for(var m:sender.party.members)m.endpoint.accept(event);
    }
}
