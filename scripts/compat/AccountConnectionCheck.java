import java.nio.file.*;
import java.util.*;
import com.samskivert.util.at;
import com.threerings.presents.client.Client;
import com.threerings.presents.dobj.*;
import com.threerings.projectx.client.aU;
import com.threerings.projectx.data.*;
import com.threerings.projectx.town.data.TownSceneObject;
import com.threerings.tudey.dobj.SceneDeltaEvent;
import com.threerings.util.Name;

/** Stock login/selection across a same-account takeover. Disposable harness only. */
public final class AccountConnectionCheck {
    public static void main(String[] args)throws Exception {
        if(args[0].equals("seed")) {
            try(var store=new org.tortodrone.account.AccountStore(Path.of(args[1]))) {
                char[] hash=org.tortodrone.account.AccountStore.transform("lan-test-password".toCharArray());
                try {
                    var account=store.authenticate("LanPlayer",hash);
                    account.characters().create(new org.tortodrone.character.Knight(0,"Second-Knight",
                        "Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
                }finally{Arrays.fill(hash,'\0');}
            }
            return;
        }
        System.setProperty("com.threerings.io.enumPolicy","ORDINAL");
        Thread thread=Thread.currentThread();at queue=new at(){
            public void postRunnable(Runnable task){WorldConnectionCheck.tasks.add(task);}
            public boolean isDispatchThread(){return Thread.currentThread()==thread;}public boolean isRunning(){return true;}};
        List<Client> clients=new ArrayList<>();
        try {
            Client first=connect(args,queue);clients.add(first);int original=select(first,"Town-Knight");
            enter(first,original);
            Client replacement=connect(args,queue);clients.add(replacement);
            WorldConnectionCheck.waitFor(()->!first.isLoggedOn());
            int next=select(replacement,"Second-Knight");
            if(next==original)throw new AssertionError("Separate knight identities missing");
            enter(replacement,next);
            Client rapid=connect(args,queue);clients.add(rapid);
            WorldConnectionCheck.waitFor(()->!replacement.isLoggedOn());
            if(select(rapid,"Second-Knight")!=next)throw new AssertionError("Reconnect identity changed");
            enter(rapid,next);
            System.out.println("PASS: stock same-account login replaces the previous character/controller and rapid reconnect enters without duplicate actors");
        }finally{for(var client:clients)if(client.isLoggedOn())client.Q(false);}
    }
    private static Client connect(String[] args,at queue)throws Exception {
        Client client=new Client(new ProjectXCredentials(new Name("LanPlayer"),"lan-test-password"),queue);
        client.b("127.0.0.1",new int[]{Integer.parseInt(args[0])},new int[0]);
        client.dr(Files.readString(Path.of(args[1])).trim());client.ds("account-session-check");client.dt("projectx");client.xg();
        WorldConnectionCheck.waitFor(client::isLoggedOn);
        Class<?> receiver=Class.forName("com.threerings.whirled.zone.a.f");
        Object handler=java.lang.reflect.Proxy.newProxyInstance(receiver.getClassLoader(),new Class[]{receiver},(proxy,method,values)->null);
        var decoder=Class.forName("com.threerings.whirled.zone.a.a").getConstructor(receiver).newInstance(handler);
        client.xd().a((com.threerings.presents.client.w)decoder);
        return client;
    }
    private static int select(Client client,String name)throws Exception {
        var reply=new WorldConnectionCheck.Reply();((aU)client.bU(aU.class)).b(new Name(name),null,reply);
        WorldConnectionCheck.waitFor(()->reply.done);if(reply.reason!=null)throw new AssertionError(reply.reason);
        return ((PlayerObject)client.xc()).pawnId;
    }
    private static void enter(Client client,int actorId)throws Exception {
        var place=WorldConnectionCheck.moveScene(client,1);TownSceneObject[] scene={null};SceneDeltaEvent[] delta={null};
        ((PlayerObject)client.xc()).a(new com.threerings.tudey.dobj.a(){public void c(SceneDeltaEvent event){delta[0]=event;}});
        client.xb().a(place.oid,new t<TownSceneObject>(){
            public void a(TownSceneObject object){scene[0]=object;}public void a(int oid,ObjectAccessException error){throw new AssertionError(error);}});
        WorldConnectionCheck.waitFor(()->scene[0]!=null);scene[0].tudeySceneService.adB();
        WorldConnectionCheck.waitFor(()->delta[0]!=null);
        if(delta[0].aej()==null||delta[0].aej().length!=1||delta[0].aej()[0].vw()!=actorId||scene[0].occupantInfo.size()!=1)
            throw new AssertionError("Replaced character remains in world");
    }
}
