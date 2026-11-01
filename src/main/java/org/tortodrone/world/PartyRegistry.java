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
    public record Participant(int playerOid,int worldId,int order,Knight knight,WorldProfile profile,SceneHealth.State health,SceneStatus.State status,float heat) {
        public Participant(int playerOid,int worldId,int order,Knight knight,WorldProfile profile,SceneHealth.State health,SceneStatus.State status){this(playerOid,worldId,order,knight,profile,health,status,0);}
        public Participant {if(!Float.isFinite(heat)||heat<0||heat>1_000_000)throw new IllegalArgumentException("Invalid floor heat");}
    }
    public record View(int oid,long revision,List<Participant> members,boolean automatic,boolean inviteOnly,boolean allowFriends,boolean allowGuild) { public View{members=List.copyOf(members);} }
    public final class Member implements AutoCloseable {
        public final int playerOid,worldId;
        private Knight knight;private WorldProfile profile;
        private SceneHealth.State health=new SceneHealth.State(1,1,0);
        private SceneStatus.State status=SceneStatus.State.NORMAL;
        private float heat;
        private final Consumer<Event> endpoint;
        private Party party;private int order;private boolean town=true;
        private Member(int oid,Knight knight,WorldProfile profile,Consumer<Event> endpoint){playerOid=oid;worldId=profile.worldId();this.knight=knight;this.profile=profile;this.endpoint=endpoint;}
        /**
         * A disconnected player keeps a rejoin claim for a short grace window. Training groups
         * are automatic matchmaking, not a player party, so they release the seat immediately.
         */
        @Override public void close(){synchronized(PartyRegistry.this){
            if(!online.remove(worldId,this))return;
            invalidate(this);
            var old=party;
            if(old==null)return;
            old.members.remove(this);old.revision++;this.party=null;
            if(old.members.isEmpty())training.remove(old);
            if(!old.automatic)detached.put(worldId,new Detached(old,clock.getAsLong()+RECONNECT_GRACE_NANOS));
            if(!old.members.isEmpty())publish(old);
        }}
    }
    private static final class Party {final int oid;long revision;int nextOrder;boolean shared,automatic;boolean inviteOnly,allowFriends,allowGuild;final LinkedHashSet<Member> members=new LinkedHashSet<>();Party(int oid){this.oid=oid;} }
    private record Invite(Member target,Member sender,Party party,long expires) { }
    private record Detached(Party party,long expires) { }
    private static final long RECONNECT_GRACE_NANOS=120_000_000_000L;
    private final Map<Integer,Member> online=new HashMap<>();
    private final Map<Integer,Detached> detached=new HashMap<>();
    private final LinkedHashSet<Party> training=new LinkedHashSet<>();
    private final Map<Member,Map<Integer,Invite>> invitations=new HashMap<>();
    private final LongSupplier clock;
    private int nextOid=1_600_000_000;
    public PartyRegistry(){this(System::nanoTime);}
    public PartyRegistry(LongSupplier clock){this.clock=Objects.requireNonNull(clock);}
    public synchronized Member join(int oid,Knight knight,WorldProfile profile,Consumer<Event> endpoint)throws IOException {
        purge();
        if(online.containsKey(profile.worldId()))throw new IOException("Knight already has a party session");
        var member=new Member(oid,knight,profile,Objects.requireNonNull(endpoint));online.put(member.worldId,member);
        var prior=detached.remove(member.worldId);
        if(prior!=null&&prior.party().members.size()<CAPACITY){
            var party=prior.party();member.party=party;member.order=party.nextOrder++;party.members.add(member);party.revision++;publish(party);
        }else solo(member);
        return member;
    }
    private void purge(){long now=clock.getAsLong();detached.values().removeIf(claim->now>=claim.expires());}
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
    /** Tutorial gates create one private seat; they never borrow a social/training group. */
    public synchronized void enterTutorial(Member member)throws IOException {
        require(member);var party=member.party;
        if(party.automatic||party.members.size()!=1)throw new IOException("The tutorial requires a private solo party");
        // An offline social peer must not regain this now-private tutorial seat.
        detached.values().removeIf(claim->claim.party==party);
        if(party.inviteOnly&&!party.allowFriends&&!party.allowGuild&&!party.shared)return;
        invalidate(member);party.inviteOnly=true;party.allowFriends=party.allowGuild=party.shared=false;
        party.revision++;publish(party);
    }
    private void require(Member member)throws IOException{if(member==null||online.get(member.worldId)!=member)throw new IOException("Party session is no longer current");}
    public synchronized View view(Member member)throws IOException{purge();require(member);return view(member.party);}
    private View view(Party party){return new View(party.oid,party.revision,party.members.stream().map(m->new Participant(m.playerOid,m.worldId,m.order,m.knight,m.profile,m.health,m.status,m.heat)).toList(),party.automatic,party.inviteOnly,party.allowFriends,party.allowGuild);}
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
        state(member,knight,profile,health,status,town,0);
    }
    public synchronized void state(Member member,Knight knight,WorldProfile profile,SceneHealth.State health,SceneStatus.State status,boolean town,float heat)throws IOException {
        require(member);if(!Float.isFinite(heat)||heat<0||heat>1_000_000)throw new IOException("Invalid floor heat");
        boolean changed=!member.knight.equals(knight)||!member.profile.equals(profile)||!member.health.equals(health)||!member.status.equals(status)||member.heat!=heat;
        member.knight=knight;member.profile=profile;member.health=health;member.status=status;member.heat=heat;member.town=town;if(changed)publish(member.party);
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
    /** Method 2 on the walk-in party service: a friend follows a party member into a shared party room. */
    public synchronized boolean joinParty(Member joiner,Member target,boolean friend)throws IOException {
        require(joiner);require(target);var party=target.party;
        if(party==joiner.party)return false;
        if(!party.shared)throw new IOException("That knight is not in a joinable party");
        if(party.members.size()>=CAPACITY)throw new IOException("The party is full");
        if(party.inviteOnly&&(!party.allowFriends||!friend))throw new IOException("That party is invite only");
        var current=joiner.party;if(current.members.size()>1&&!current.automatic)throw new IOException("Leave your current party first");
        invalidate(joiner);remove(joiner,false);
        joiner.party=party;joiner.order=party.nextOrder++;party.members.add(joiner);party.revision++;publish(party);
        return true;
    }
    /** Roll back a follow that could not complete its travel. */
    public synchronized void abandon(Member member)throws IOException{require(member);remove(member,true);}
    /** Open/private party controls; only the player leader owns them. */
    public synchronized void setInviteOnly(Member caller,boolean inviteOnly)throws IOException{requireLeader(caller);caller.party.inviteOnly=inviteOnly;caller.party.revision++;publish(caller.party);}
    public synchronized void setAllowFriends(Member caller,boolean allowFriends)throws IOException{requireLeader(caller);caller.party.allowFriends=allowFriends;caller.party.revision++;publish(caller.party);}
    public synchronized void setAllowGuild(Member caller,boolean allowGuild)throws IOException{requireLeader(caller);caller.party.allowGuild=allowGuild;caller.party.revision++;publish(caller.party);}
    private void requireLeader(Member caller)throws IOException{require(caller);var party=caller.party;if(party.automatic)throw new IOException("Training groups do not have party settings");if(party.members.iterator().next()!=caller)throw new IOException("Only the party leader can change party settings");}
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
