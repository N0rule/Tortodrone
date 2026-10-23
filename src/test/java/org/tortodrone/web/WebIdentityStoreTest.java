package org.tortodrone.web;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.tortodrone.support.TestWire.check;

/** Atomic setup, authorization, last-administrator protection, recovery and audit rollback. */
public final class WebIdentityStoreTest {
    private static final char[] PASSWORD="testpass".toCharArray();
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("tortodrone-identities-");
        try {
            var store=new WebIdentityStore(root);
            check(store.bootstrapAvailable(),"fresh deployment offers setup");
            try(var pool=Executors.newFixedThreadPool(2)) {
                var gate=new CountDownLatch(1);var outcomes=new ArrayList<Future<Boolean>>();
                for(String name:List.of("FirstOne","FirstTwo"))outcomes.add(pool.submit(()->{gate.await();try{store.bootstrap(name,PASSWORD);return true;}catch(IOException refused){return false;}}));
                gate.countDown();check(outcomes.get(0).get()!=outcomes.get(1).get()&&store.list().size()==1,"competing first-run setup creates exactly one administrator");
            }
            var first=store.list().getFirst();check(first.role()==WebIdentityStore.Role.ADMINISTRATOR&&!store.bootstrapAvailable(),"first role is always administrator and setup closes");
            refused(()->store.bootstrap("Replay",PASSWORD));
            store.create(first,"Viewer",PASSWORD,WebIdentityStore.Role.VIEWER);
            var viewer=store.find("Viewer");
            refused(()->store.create(viewer,"Forbidden",PASSWORD,WebIdentityStore.Role.ADMINISTRATOR));
            refused(()->store.remove(first,first.username(),first.revision()));
            refused(()->store.role(first,first.username(),WebIdentityStore.Role.VIEWER,first.revision()));
            var before=store.find("Viewer");
            refused(()->store.put("TooShort","seven77".toCharArray(),WebIdentityStore.Role.VIEWER));
            store.password(first,"Viewer","changed-viewer-password".toCharArray(),before.revision());
            check(!store.current(before)&&store.authenticate("Viewer",PASSWORD)==null,"password change revokes credentials and sessions");
            refused(()->store.role(first,"Viewer",WebIdentityStore.Role.MODERATOR,before.revision()));
            var after=store.find("Viewer");
            store.role(first,"Viewer",WebIdentityStore.Role.MODERATOR,after.revision());
            check(!store.current(after)&&store.find("Viewer").role()==WebIdentityStore.Role.MODERATOR,"role change publishes a fresh revision");
            var moderator=store.find("Viewer");
            refused(()->store.password(moderator,first.username(),PASSWORD,first.revision()));
            store.remove(first,"Viewer",moderator.revision());check(store.list().size()==1,"authorized deletion");
            store.revoke(first.username());check(store.list().isEmpty()&&!store.bootstrapAvailable(),"revoking every account never reopens public setup");
            store.put("Recovery",PASSWORD,WebIdentityStore.Role.ADMINISTRATOR);
            var recovery=store.find("Recovery");
            store.password("Recovery","console-recovered-password".toCharArray(),recovery.revision());
            check(!store.current(recovery)&&store.authenticate("Recovery","console-recovered-password".toCharArray())!=null,"local console password recovery");
            refused(()->store.create(recovery,"StaleActor",PASSWORD,WebIdentityStore.Role.VIEWER));
            var current=store.find("Recovery");
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("webui.sqlite"));var s=c.createStatement()){s.execute("CREATE TRIGGER reject_identity_audit BEFORE INSERT ON audit BEGIN SELECT RAISE(ABORT,'fixture'); END");}
            refused(()->store.create(current,"Rollback",PASSWORD,WebIdentityStore.Role.VIEWER));
            refused(()->store.password(current,"Recovery",PASSWORD,current.revision()));
            check(store.list().size()==1&&store.current(current),"failed audit rolls back identity creation and reset");
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("webui.sqlite"));var s=c.createStatement()){s.execute("DROP TRIGGER reject_identity_audit");}
            store.create(current,"SecondAdmin",PASSWORD,WebIdentityStore.Role.ADMINISTRATOR);
            var second=store.find("SecondAdmin");
            try(var pool=Executors.newFixedThreadPool(2)) {
                Callable<Boolean> one=()->{try{store.role(current,current.username(),WebIdentityStore.Role.VIEWER,current.revision());return true;}catch(IOException refused){return false;}};
                Callable<Boolean> two=()->{try{store.role(second,second.username(),WebIdentityStore.Role.VIEWER,second.revision());return true;}catch(IOException refused){return false;}};
                var results=pool.invokeAll(List.of(one,two));check(results.get(0).get()!=results.get(1).get(),"concurrent demotions preserve one administrator");
            }
            check(store.list().stream().filter(i->i.role()==WebIdentityStore.Role.ADMINISTRATOR).count()==1,"last-administrator invariant");
            check(new WebIdentityStore(root).bootstrapAvailable()==false,"setup closure survives restart without migration");
            check(store.audit().stream().anyMatch(e->e.get("action").equals("identity.bootstrap")),"bootstrap is persistently audited");
            Path failed=root.resolve("failed-bootstrap");var candidate=new WebIdentityStore(failed);
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+failed.resolve("webui.sqlite"));var s=c.createStatement()){s.execute("CREATE TRIGGER reject_setup_audit BEFORE INSERT ON audit BEGIN SELECT RAISE(ABORT,'fixture'); END");}
            refused(()->candidate.bootstrap("NotCommitted",PASSWORD));check(candidate.list().isEmpty()&&candidate.bootstrapAvailable(),"failed first-run audit rolls back and leaves setup retryable");
            System.out.println("PASS: atomic first-run setup, identity authorization/revisions, concurrent last-admin protection, console recovery and audit rollback");
        }finally{try(var paths=Files.walk(root)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    @FunctionalInterface private interface Action{void run()throws Exception;}
    private static void refused(Action action)throws Exception{try{action.run();throw new AssertionError("Unsafe identity operation accepted");}catch(IOException expected){}}
}
