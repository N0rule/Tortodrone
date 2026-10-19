package org.tortodrone.server.network;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.tortodrone.character.*;
import org.tortodrone.protocol.SessionCodec;
import org.tortodrone.storage.Database;

/** Deployment-local trade ownership. No callback acquires a world or account lock. */
final class PlayerTrades {
    private static final Map<Path,PlayerTrades> DEPLOYMENTS=new HashMap<>();
    // Player objects use even IDs >=1002; trade objects use odd IDs >=1001.
    private static final AtomicInteger NEXT_OID=new AtomicInteger(1001);
    static synchronized PlayerTrades forStore(CharacterStore store){
        return DEPLOYMENTS.computeIfAbsent(store.worldStorageFile().toAbsolutePath().normalize(),p->new PlayerTrades());
    }
    static final class Member {
        final CharacterStore store;final Knight knight;final int worldId,sceneOid,playerOid;final long auth;
        final SessionOutput output;final SessionCodec codec;final java.net.Socket socket;
        boolean live=true;volatile long dialogGeneration;
        Member(CharacterStore store,Knight knight,WorldProfile profile,long auth,int sceneOid,int playerOid,SessionOutput output,SessionCodec codec,java.net.Socket socket){
            this.store=store;this.knight=knight;worldId=profile.worldId();this.auth=auth;this.sceneOid=sceneOid;this.playerOid=playerOid;this.output=output;this.codec=codec;this.socket=socket;
        }
        WorldProfile current()throws IOException {
            if(!live||socket.isClosed())throw new IOException("Trade participant left the scene");
            var profile=store.tradeProfile(knight.id(),auth);
            if(profile.worldId()!=worldId)throw new IOException("Trade participant changed");return profile;
        }
    }
    private record Invite(Member sender,Member recipient,long expires) { }
    private static final class Trade {
        final Member[] members;final WorldProfile[] owners;final Map<Long,Integer>[] items;
        final Set<Member> subscribers=new HashSet<>();
        final int oid;final long created,expires;final int[] crowns={0,0},energy={0,0},accepted={0,0};
        int revision=1;boolean completed;
        @SuppressWarnings("unchecked") Trade(Member a,Member b,WorldProfile ap,WorldProfile bp,int oid,long created){
            members=new Member[]{a,b};owners=new WorldProfile[]{ap,bp};items=new Map[]{new LinkedHashMap<>(),new LinkedHashMap<>()};
            this.oid=oid;this.created=created;expires=System.nanoTime()+300_000_000_000L;
        }
        int side(Member m)throws IOException {if(m==members[0])return 0;if(m==members[1])return 1;throw new IOException("Not a trade participant");}
        SessionCodec.TradeView view(){return new SessionCodec.TradeView(oid,members[0].knight,owners[0],offered(0),members[1].knight,owners[1],offered(1),crowns[0],crowns[1],energy[0],energy[1],revision,accepted[0],accepted[1],completed);}
        List<WorldProfile.Item> offered(int side){return owners[side].items().stream().filter(i->items[side].containsKey(i.id())).map(i->new WorldProfile.Item(i.id(),i.slot(),i.config(),i.accessory(),i.heat(),null,i.bound(),i.bindOnEquip(),i.starter(),items[side].get(i.id()),i.permanent(),i.level(),i.heatProgress(),i.variants(),i.additionalAccessories()).withExpiration(i.expiresAt())).toList();}
    }
    private final Map<String,Member> members=new HashMap<>();
    private final Map<Member,Invite> invitations=new HashMap<>(); // At most one outstanding request per participant.
    private final Map<Member,Trade> trades=new HashMap<>();
    private long lastCreated;
    synchronized Member join(CharacterStore store,Knight knight,WorldProfile profile,long auth,int scene,int oid,SessionOutput output,SessionCodec codec,java.net.Socket socket){
        var member=new Member(store,knight,profile,auth,scene,oid,output,codec,socket);members.put(key(knight.name()),member);return member;
    }
    synchronized void leave(Member member){
        if(member==null)return;member.live=false;members.remove(key(member.knight.name()),member);
        var invite=invitations.get(member);if(invite!=null)remove(invite);
        var trade=trades.get(member);if(trade!=null)cancel(trade);
    }
    synchronized void invite(Member sender,String name)throws IOException {
        expire();sender.current();var recipient=members.get(key(name));
        if(recipient==null||recipient.sceneOid!=sender.sceneOid)throw new IOException("Trade recipient is not in this scene");
        if(sender==recipient||sender.store.accountId()==recipient.store.accountId())throw new IOException("Cannot trade with yourself");
        recipient.current();
        if(busy(sender)||busy(recipient))throw new IOException("A trade or invitation is already open");
        var invite=new Invite(sender,recipient,System.nanoTime()+60_000_000_000L);
        invitations.put(sender,invite);invitations.put(recipient,invite);
        try{recipient.output.send(()->recipient.codec.tradeNotification(recipient.playerOid,sender.knight.name(),true));}
        catch(IOException failed){remove(invite);throw failed;}
    }
    synchronized void respond(Member recipient,String notification,int choice)throws IOException {
        expire();recipient.current();var invite=invitations.get(recipient);
        if(invite==null||invite.recipient!=recipient||!notification.equals("TRADE_REQ:"+invite.sender.knight.name()))throw new IOException("Trade request expired");
        if(choice!=0&&choice!=1)throw new IOException("Invalid trade response");
        remove(invite);if(choice==0)return;
        var a=invite.sender;var b=invite.recipient;var ap=a.current();var bp=b.current();
        if(a.sceneOid!=b.sceneOid||trades.containsKey(a)||trades.containsKey(b))throw new IOException("Trade participant changed");
        int oid=NEXT_OID.getAndUpdate(n->n<=Integer.MAX_VALUE-2?n+2:Integer.MAX_VALUE);
        if(oid==Integer.MAX_VALUE)throw new IOException("Trade object identity space exhausted");
        long created=lastCreated=Math.max(System.currentTimeMillis(),lastCreated+1);
        var trade=new Trade(a,b,ap,bp,oid,created);trades.put(a,trade);trades.put(b,trade);
        var view=trade.view();a.dialogGeneration=b.dialogGeneration=created;
        try{a.output.send(()->a.codec.tradeDialog(a.playerOid,view,created));b.output.send(()->b.codec.tradeDialog(b.playerOid,view,created));}
        catch(IOException failed){cancel(trade);throw failed;}
    }
    synchronized boolean active(Member member){return member!=null&&trades.containsKey(member);}
    synchronized boolean owns(Member member,int oid){var t=trades.get(member);return member!=null&&member.live&&t!=null&&t.oid==oid;}
    synchronized void subscribe(Member member,int oid,short message)throws IOException {
        expire();var t=require(member,oid);check(t);var view=t.view();
        member.output.send(()->member.codec.tradeObject(message,view));t.subscribers.add(member);
    }
    synchronized void unsubscribe(Member member,int oid){if(owns(member,oid))cancel(trades.get(member));}
    synchronized void close(Member member,long created){var trade=trades.get(member);if(trade!=null&&trade.created==created)cancel(trade);}
    synchronized void changed(Member member,Database.WorldState state){
        expire();var trade=trades.get(member);if(trade==null||trade.completed)return;
        try {int side=trade.side(member);if(state==null||state.authRevision()!=member.auth||state.worldId()!=member.worldId||state.revision()!=trade.owners[side].revision())cancel(trade);}
        catch(IOException invalid){cancel(trade);}
    }
    synchronized void operate(Member member,int oid,int method,List<Object> args)throws IOException {
        expire();var trade=require(member,oid);check(trade);
        if(trade.completed)throw new IOException("Trade has already completed");
        int side=trade.side(member);var before=trade.view();
        switch(method) {
            case 1 -> {
                int revision=(Integer)args.get(0);
                if(revision!=trade.revision)throw new IOException("Trade offer changed; review it before accepting");
                trade.accepted[side]=revision;
                if(trade.accepted[0]==revision&&trade.accepted[1]==revision) {
                    try {trade.members[0].store.trade(trade.owners[0],trade.members[0].auth,trade.items[0],trade.crowns[0],trade.energy[0],
                        trade.owners[1],trade.members[1].auth,trade.items[1],trade.crowns[1],trade.energy[1]);trade.completed=true;}
                    catch(IOException refused){cancel(trade);throw refused;}
                }
            }
            case 2,3 -> {
                long id=(Long)args.get(0);int quantity=(Integer)args.get(1);
                if(quantity<1||quantity>9999)throw new IOException("Invalid trade quantity");
                var proposed=new LinkedHashMap<>(trade.items[side]);int previous=proposed.getOrDefault(id,0);
                long count=(long)previous+(method==2?quantity:-quantity);
                if(count<0||count>9999)throw new IOException("Invalid trade quantity");
                if(count==0)proposed.remove(id);else proposed.put(id,(int)count);
                validate(trade.owners[side],proposed,trade.crowns[side],trade.energy[side]);
                trade.items[side].clear();trade.items[side].putAll(proposed);edit(trade);
            }
            case 4,5 -> {
                int quantity=(Integer)args.get(0);
                validate(trade.owners[side],trade.items[side],method==4?quantity:trade.crowns[side],method==5?quantity:trade.energy[side]);
                if(method==4)trade.crowns[side]=quantity;else trade.energy[side]=quantity;edit(trade);
            }
            default -> throw new IOException("Unknown trade operation");
        }
        publish(trade,before);
    }
    private static void validate(WorldProfile owner,Map<Long,Integer> items,int crowns,int energy)throws IOException {
        if(items.size()>15)throw new IOException("A trade can offer at most 15 item stacks");
        if(crowns<0||crowns>owner.crowns()||energy<0||energy>owner.energy())throw new IOException("Insufficient trade currency");
        for(var offer:items.entrySet()) {
            var item=owner.items().stream().filter(i->i.id()==offer.getKey()).findFirst().orElseThrow(()->new IOException("Item is not owned"));
            if(item.bound()||item.permanent()||item.starter()||item.equippedSlot()!=null)throw new IOException("Bound, starter and equipped items cannot be traded");
            if(offer.getValue()<1||offer.getValue()>item.count())throw new IOException("Invalid trade item quantity");
        }
    }
    private static void edit(Trade trade)throws IOException {if(trade.revision==Integer.MAX_VALUE)throw new IOException("Trade revision exhausted");trade.revision++;Arrays.fill(trade.accepted,0);}
    private Trade require(Member member,int oid)throws IOException {var trade=trades.get(member);if(member==null||!member.live||trade==null||trade.oid!=oid)throw new IOException("No such trade");return trade;}
    private void check(Trade t)throws IOException {
        try{for(int side=0;side<2;side++){var actual=t.members[side].current();if(!t.completed&&actual.revision()!=t.owners[side].revision())throw new IOException("Inventory changed; start a new trade");}}
        catch(IOException stale){cancel(t);throw stale;}
    }
    private void publish(Trade t,SessionCodec.TradeView before)throws IOException {
        var after=t.view();IOException failure=null;
        for(var member:List.copyOf(t.subscribers))try{member.output.send(()->member.codec.tradeChanges(before,after));}catch(IOException failed){failure=failed;}
        if(failure!=null){cancel(t);throw failure;}
    }
    private boolean busy(Member m){return trades.containsKey(m)||invitations.containsKey(m);}
    private void remove(Invite i){invitations.remove(i.sender,i);invitations.remove(i.recipient,i);
        if(i.recipient.live)try{i.recipient.output.send(()->i.recipient.codec.tradeNotification(i.recipient.playerOid,i.sender.knight.name(),false));}catch(IOException ignored){} }
    private void cancel(Trade t){
        for(var member:t.members){trades.remove(member,t);if(member.live)try{member.output.send(()->member.codec.tradeDialog(member.playerOid,null,t.created));}catch(IOException ignored){} }
    }
    private void expire(){long now=System.nanoTime();for(var i: new HashSet<>(invitations.values()))if(now-i.expires>=0)remove(i);
        for(var t:new HashSet<>(trades.values()))if(!t.completed&&now-t.expires>=0)cancel(t);}
    private static String key(String name){return name.toLowerCase(Locale.ROOT);}
}
