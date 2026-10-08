package org.tortodrone.account;

import java.io.IOException;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.tortodrone.character.CharacterStore;
import static org.tortodrone.support.TestWire.check;

public final class AccountSessionTest {
    public static void main(String[] args)throws Exception {
        try(var accounts=new AccountStore(new CharacterStore())) {
            var identity=AccountStoreTest.login(accounts,"tortodrone","tortodrone");
            var cleaned=new AtomicInteger();Socket first=new Socket();
            var a=accounts.attach(identity,first);a.onInvalidated(cleaned::incrementAndGet);
            check(AccountStoreTest.login(accounts,"tortodrone","wrong-password")==null&&a.current(),"failed login cannot evict a session");
            Socket second=new Socket();var b=accounts.attach(identity,second);
            check(first.isClosed()&&!a.current()&&b.current()&&cleaned.get()==1,"new authenticated session replaces old socket/controller");
            a.close();check(b.current()&&cleaned.get()==1,"late cleanup cannot release replacement lease");
            b.close();check(second.isClosed()&&!b.current(),"normal/abrupt cleanup releases lease");
            // Replaced before its world controller exists: registration still cleans up once.
            var c=accounts.attach(identity,new Socket());var d=accounts.attach(identity,new Socket());
            c.onInvalidated(cleaned::incrementAndGet);check(cleaned.get()==2&&d.current(),"rapid handshake replacement cleans late controller registration");
            var workers=Executors.newFixedThreadPool(4);var sockets=new CopyOnWriteArrayList<Socket>();
            try {
                var blocked=accounts.attach(identity,new Socket());
                var cleaning=new CountDownLatch(1);var release=new CountDownLatch(1);
                blocked.onInvalidated(()->{cleaning.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
                var replacing=workers.submit(()->accounts.attach(identity,new Socket()));
                check(cleaning.await(3,TimeUnit.SECONDS),"old controller cleanup started");
                var third=workers.submit(()->accounts.attach(identity,new Socket()));
                try{third.get(100,TimeUnit.MILLISECONDS);throw new AssertionError("Third login overtook cleanup");}
                catch(TimeoutException expected){ }
                finally{release.countDown();}
                replacing.get();var newest=third.get();check(newest.current(),"third login waits for old controller cleanup");
                var calls=new ArrayList<Callable<AccountStore.Session>>();
                for(int index=0;index<12;index++)calls.add(()->{var socket=new Socket();sockets.add(socket);return accounts.attach(identity,socket);});
                var results=workers.invokeAll(calls);var leases=new ArrayList<AccountStore.Session>();
                for(var result:results)leases.add(result.get());
                check(leases.stream().filter(AccountStore.Session::current).count()==1,"concurrent logins leave exactly one connection");
                for(var lease:leases)if(!lease.current())lease.close();
                check(leases.stream().filter(AccountStore.Session::current).count()==1,"stale close preserves winner");
                accounts.setPassword("tortodrone","replacement-password".toCharArray());
                try{accounts.attach(identity,new Socket());throw new AssertionError("Stale revision attached");}catch(IOException expected) { }
                check(leases.stream().filter(AccountStore.Session::current).count()==1,"stale authenticated revision cannot evict current owner");
                accounts.watchSessions();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(leases.stream().anyMatch(AccountStore.Session::current)&&System.nanoTime()<deadline)Thread.sleep(10);
                check(leases.stream().noneMatch(AccountStore.Session::current),"password reset watcher revokes current session");
                var updated=AccountStoreTest.login(accounts,"tortodrone","replacement-password");
                var reconnected=accounts.attach(updated,new Socket());check(reconnected.current(),"reconnect after revocation succeeds");reconnected.close();
            }finally{workers.shutdownNow();for(var socket:sockets)socket.close();d.close();}
        }
        System.out.println("PASS: single account leases, failed authentication, rapid/concurrent takeover, stale cleanup and reset/reconnect");
    }
}
