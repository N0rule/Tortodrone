import com.samskivert.util.at;
import com.threerings.presents.client.*;
import com.threerings.presents.dobj.*;
import com.threerings.projectx.data.*;
import com.threerings.projectx.social.data.*;
import com.threerings.projectx.trade.data.*;
import com.threerings.projectx.town.data.TownSceneObject;
import com.threerings.util.Name;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Two-stock-client trade integration check. Item IDs and player identities are inputs from disposable setup. */
public final class TradeConnectionCheck {
    static final class Confirm implements A.a {
        boolean done; String reason;
        public void xx() { done = true; }
        public void aT(String value) { reason = value; done = true; }
    }
    static final class Endpoint implements AutoCloseable {
        final Client client;
        final PlayerObject player;
        TownSceneObject scene;
        com.threerings.presents.dobj.t<TownSceneObject> sceneSubscription;
        final List<Notification> notifications = new CopyOnWriteArrayList<>();
        final List<AttributeChangedEvent> playerAttributes = new CopyOnWriteArrayList<>();
        volatile boolean observing=true;
        Endpoint(String host, int port, Path key, at queue, String username, String password, String knight) throws Exception {
            client = new Client(new ProjectXCredentials(new Name(username), password), queue);
            client.b(host, new int[]{port}, new int[0]); client.dr(Files.readString(key).trim());
            client.ds("trade-check"); client.dt("projectx"); client.xg(); waitFor(client::isLoggedOn);
            player = (PlayerObject) client.xc();
            WorldConnectionCheck.registerSceneReceiver(client);
            player.a(new com.threerings.presents.dobj.s<Notification>() {
                public void a(EntryAddedEvent<Notification> event) { if(observing&&event.getName().equals("notifications"))notifications.add(event.xZ()); }
                public void a(EntryUpdatedEvent<Notification> event) { if(observing&&event.getName().equals("notifications"))notifications.add(event.xZ()); }
                public void a(EntryRemovedEvent<Notification> event) { if(observing&&event.getName().equals("notifications"))notifications.removeIf(n -> n.lj().equals(event.lj())); }
            });
            player.a(itemEvents(()->observing));
            player.a(new c() { public void a(AttributeChangedEvent event) { if(observing)playerAttributes.add(event); } });
            var selected = new Confirm(); ((com.threerings.projectx.client.aU) client.bU(com.threerings.projectx.client.aU.class))
                .b(new Name(knight), null, selected); waitFor(() -> selected.done);
            if (selected.reason != null) throw new AssertionError("Select " + knight + ": " + selected.reason);
            enterTown();
        }
        void enterTown() throws Exception {
            if (scene != null) { var old=scene;scene=null;client.xb().b(old.xL(),sceneSubscription); }
            var place = WorldConnectionCheck.moveScene(client, 1,TradeConnectionCheck::waitFor);
            sceneSubscription = new com.threerings.presents.dobj.t<>() {
                public void a(TownSceneObject object) { scene = object; }
                public void a(int oid, ObjectAccessException error) { throw new AssertionError(error); }
            };
            client.xb().a(place.oid, sceneSubscription); waitFor(() -> scene != null);
            scene.tudeySceneService.adB();
        }
        public void close() { observing=false;if (client.isLoggedOn()) client.Q(false); }
    }

    static final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    static void waitFor(BooleanSupplier ready) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (!ready.getAsBoolean() && System.nanoTime() < end) {
            Runnable task = tasks.poll(100, TimeUnit.MILLISECONDS); if (task != null) task.run();
        }
        if (!ready.getAsBoolean()) throw new AssertionError("Trade fixture timeout");
    }
    static Confirm confirm(Consumer<Confirm> invoke) throws Exception {
        Confirm confirm = new Confirm(); invoke.accept(confirm); waitFor(() -> confirm.done);
        if (confirm.reason != null) throw new AssertionError("Trade RPC refused: " + confirm.reason);
        return confirm;
    }
    static Notification.TradeRequest request(Endpoint endpoint, String name) {
        return endpoint.notifications.stream().filter(n -> n instanceof Notification.TradeRequest tr &&
            tr.name.toString().equals(name)).map(n -> (Notification.TradeRequest)n).findFirst().orElse(null);
    }
    static int count(DSet<com.threerings.projectx.item.data.Item> items, long id) {
        var item = items.f(Long.valueOf(id)); return item == null ? 0 : item.getCount();
    }
    static int offered(DSet<com.threerings.projectx.item.data.Item> items, long id) {
        var item = items.f(Long.valueOf(id)); return item == null ? 0 : item.getCount();
    }
    static int total(DSet<com.threerings.projectx.item.data.Item> items,String config) {
        int count=0;for(var item:items)if(item.Vx().getName().equals(config))count+=item.getCount();return count;
    }
    static void eligible(PlayerObject player,long id) {
        var item=player.items.f(Long.valueOf(id));
        if(item==null||item.Cs()||item.VC()||Arrays.stream(player.equipment).anyMatch(e->e==id))
            throw new AssertionError("Configured trade item must be unbound, non-permanent and unequipped: "+id);
    }

    public static void main(String[] args) throws Exception {
        if(args.length>0&&args[0].equals("--seed")){
            if(args.length!=3)throw new IllegalArgumentException("Usage: TradeConnectionCheck --seed <disposable-data-dir> <seed-properties>");
            seed(Path.of(args[1]),Path.of(args[2]));return;
        }
        if (args.length < 3 || args.length > 13) throw new IllegalArgumentException(
            "Usage: TradeConnectionCheck --seed <disposable-data-dir> <seed-properties> | port public-key seed.properties [host user1 password1 knight1 user2 password2 knight2 item1 item2 crowns energy]");
        System.setProperty("com.threerings.io.enumPolicy", "ORDINAL");
        boolean defaults=args.length==3;
        Properties seeded=new Properties();if(defaults)try(var input=Files.newInputStream(Path.of(args[2]))){seeded.load(input);}
        String host=defaults?"127.0.0.1":args.length>2?args[2]:"127.0.0.1",
            user1=args.length>3?args[3]:"LanPlayer",pass1=args.length>4?args[4]:"lan-test-password",
            knight1=args.length>5?args[5]:"Town-Knight",user2=args.length>6?args[6]:"PeerPlayer",
            pass2=args.length>7?args[7]:"lan-test-password",knight2=args.length>8?args[8]:"Peer-Knight";
        long item1=defaults?Long.parseLong(seeded.getProperty("lanItemId")):args.length>9?Long.parseLong(args[9]):0,
            item2=defaults?Long.parseLong(seeded.getProperty("peerItemId")):args.length>10?Long.parseLong(args[10]):0;
        Thread dispatch=Thread.currentThread(); at queue=new at(){
            public void postRunnable(Runnable task){tasks.add(task);}
            public boolean isDispatchThread(){return Thread.currentThread()==dispatch;}
            public boolean isRunning(){return true;}
        };
        try (var first=new Endpoint(host,Integer.parseInt(args[0]),Path.of(args[1]),queue,user1,pass1,knight1);
             var second=new Endpoint(host,Integer.parseInt(args[0]),Path.of(args[1]),queue,user2,pass2,knight2)) {
            if (first.scene.xL()!=second.scene.xL()) throw new AssertionError("Players did not enter same scene instance");
            long item1Id=item1,item2Id=item2;
            int initial1=item1Id==0?0:count(first.player.items,item1Id), initial2=item2Id==0?0:count(second.player.items,item2Id);
            String config1=item1Id==0?null:first.player.items.f(item1Id).Vx().getName();
            String config2=item2Id==0?null:second.player.items.f(item2Id).Vx().getName();
            int firstTotal1=item1Id==0?0:total(first.player.items,config1),secondTotal1=item1Id==0?0:total(second.player.items,config1);
            int firstTotal2=item2Id==0?0:total(first.player.items,config2),secondTotal2=item2Id==0?0:total(second.player.items,config2);
            if(item1Id!=0&&initial1<3)throw new AssertionError("First configured stack must have >=3 units for delta add/remove; found "+initial1);
            if(item2Id!=0&&initial2<1)throw new AssertionError("Second configured item is absent");
            if(item1Id!=0)eligible(first.player,item1Id);
            if(item2Id!=0)eligible(second.player,item2Id);

            var invite=new Confirm(); first.scene.projectXSceneService.i(new Name(knight2),invite); waitFor(()->invite.done);
            if(invite.reason!=null)throw new AssertionError("Invite refused: "+invite.reason);
            waitFor(()->request(second,knight1)!=null);
            var incoming=request(second,knight1); var accepted=new Confirm();
            second.client.bU(SocialMarshaller.class).a(incoming.getKey(),1,accepted); waitFor(()->accepted.done);
            if(accepted.reason!=null)throw new AssertionError("Accept refused: "+accepted.reason);
            waitFor(()->request(second,knight1)==null);
            waitFor(()->first.player.sceneDialog instanceof TradeDialogInfo && second.player.sceneDialog instanceof TradeDialogInfo);
            var firstInfo=(TradeDialogInfo)first.player.sceneDialog; var secondInfo=(TradeDialogInfo)second.player.sceneDialog;
            if(firstInfo.tradeOid!=secondInfo.tradeOid||!Arrays.equals(firstInfo.names,secondInfo.names))
                throw new AssertionError("Trade scene dialog identity mismatch");
            var firstBox=new Box();var secondBox=new Box();
            first.client.xb().a(firstInfo.tradeOid,firstBox);second.client.xb().a(secondInfo.tradeOid,secondBox);
            waitFor(()->firstBox.trade!=null&&secondBox.trade!=null);
            listen(firstBox.trade);listen(secondBox.trade);
            tradeAttributes.clear();tradeElements.clear();tradeEntries.clear();
            if(firstBox.trade.revision!=1||secondBox.trade.revision!=1||firstBox.trade.completed||secondBox.trade.completed)
                throw new AssertionError("Unexpected initial trade snapshot");

            if(item1Id!=0) {
                confirm(c->offer(firstBox.trade,item1Id,2,c));waitFor(()->offered(firstBox.trade.firstItems,item1Id)==2);
                if(firstBox.trade.revision!=2||firstBox.trade.orderings.size()!=1||order(firstBox.trade,item1Id)!=0)
                    throw new AssertionError("Add did not publish offer/order/revision");
                confirm(c->offer(firstBox.trade,item1Id,1,c));waitFor(()->offered(firstBox.trade.firstItems,item1Id)==3);
                if(firstBox.trade.revision!=3)throw new AssertionError("Add amount did not accumulate as a delta");
                confirm(c->remove(firstBox.trade,item1Id,1,c));waitFor(()->offered(firstBox.trade.firstItems,item1Id)==2);
                if(firstBox.trade.revision!=4)throw new AssertionError("Remove amount did not subtract as a delta");
            }
            if(item2Id!=0) { confirm(c->offer(secondBox.trade,item2Id,1,c));waitFor(()->secondBox.trade.secondItems.size()==1); }

            int crowns=defaults?12:args.length>11?Integer.parseInt(args[11]):0,
                energy=defaults?7:args.length>12?Integer.parseInt(args[12]):0;
            int firstCrowns=first.player.crowns,secondCrowns=second.player.crowns,firstEnergy=first.player.energy,secondEnergy=second.player.energy;
            if(crowns<0||crowns>firstCrowns||energy<0||energy>firstEnergy)throw new AssertionError("Configured currency offer exceeds sender balance");
            if(crowns>0) confirm(c->firstBox.trade.tradeService.q(crowns,c));
            if(energy>0) confirm(c->firstBox.trade.tradeService.r(energy,c));
            if(crowns>0||energy>0)waitFor(()->firstBox.trade.revision>=(item1Id==0?2:5));
            if(firstBox.trade.crowns[0]!=crowns||firstBox.trade.energy[0]!=energy)
                throw new AssertionError("Trade currency element values were not observed");
            int revision=firstBox.trade.revision;
            confirm(c->firstBox.trade.tradeService.p(revision,c));
            waitFor(()->firstBox.trade.accepted[0]==revision&&secondBox.trade.accepted[0]==revision);
            confirm(c->secondBox.trade.tradeService.q(secondBox.trade.crowns[1],c));
            int revised=secondBox.trade.revision;
            waitFor(()->firstBox.trade.revision==revised&&firstBox.trade.accepted[0]==0&&firstBox.trade.accepted[1]==0);
            Confirm stale=new Confirm();firstBox.trade.tradeService.p(revision,stale);waitFor(()->stale.done);
            if(stale.reason==null||!stale.reason.contains("review it"))throw new AssertionError("Stale trade acceptance was not refused");
            confirm(c->firstBox.trade.tradeService.p(revised,c));
            waitFor(()->firstBox.trade.accepted[0]==revised&&secondBox.trade.accepted[0]==revised);
            confirm(c->secondBox.trade.tradeService.p(revised,c));
            waitFor(()->firstBox.trade.completed&&secondBox.trade.completed);
            if(firstBox.trade.revision!=revised)throw new AssertionError("Accept changed offer revision");
            if(item1Id!=0)waitFor(()->count(first.player.items,item1Id)==initial1-2&&total(first.player.items,config1)==firstTotal1-2&&total(second.player.items,config1)==secondTotal1+2);
            if(item2Id!=0)waitFor(()->total(first.player.items,config2)==firstTotal2+1&&total(second.player.items,config2)==secondTotal2-1);
            if(crowns>0)waitFor(()->first.player.crowns==firstCrowns-crowns&&second.player.crowns==secondCrowns+crowns);
            if(energy>0)waitFor(()->first.player.energy==firstEnergy-energy&&second.player.energy==secondEnergy+energy);
            if(item1Id!=0&&(!tradeEntries.contains("firstItems:added")||!tradeEntries.contains("orderings:added")))
                throw new AssertionError("Stock observer missed offer/order entry events: "+tradeEntries);
            if((item1Id!=0||item2Id!=0)&&(!tradeEntries.contains("items:updated")||!tradeEntries.contains("items:added")))
                throw new AssertionError("Inventory observers missed transferred stack events: "+tradeEntries);
            if(!tradeAttributes.stream().anyMatch(e->e.getName().equals("revision"))||
                !tradeAttributes.stream().anyMatch(e->e.getName().equals("completed"))||
                !tradeElements.stream().anyMatch(e->e.getName().equals("accepted")))
                throw new AssertionError("Stock observer missed revision/accepted/completed changes");
            System.out.println("PASS: native stock invite/accept, trade dialog and object subscription; revision="+revised+
                " offers/orderings="+firstBox.trade.orderings.size()+" observed="+tradeEntries+
                " completed="+firstBox.trade.completed+" inventory/currency changes observed live");

            first.scene.projectXSceneService.R(first.player.sceneDialogCreated);
            waitFor(()->first.player.sceneDialog==null&&second.player.sceneDialog==null);

            // An offer edit after one party accepts resets the accepted array for both readers.
            confirm(c->first.scene.projectXSceneService.i(new Name(knight2),c));
            waitFor(()->request(second,knight1)!=null);
            var rejected=new Confirm();second.client.bU(SocialMarshaller.class).a("TRADE_REQ:"+knight1,0,rejected);waitFor(()->rejected.done);
            if(rejected.reason!=null)throw new AssertionError("Reject notification response failed: "+rejected.reason);
            waitFor(()->request(second,knight1)==null);
            System.out.println("PASS: native trade invitation rejection removes notification");

            confirm(c->first.scene.projectXSceneService.i(new Name(knight2),c));
            waitFor(()->request(second,knight1)!=null);
            confirm(c->second.client.bU(SocialMarshaller.class).a("TRADE_REQ:"+knight1,1,c));
            waitFor(()->first.player.sceneDialog instanceof TradeDialogInfo&&second.player.sceneDialog instanceof TradeDialogInfo);
            first.scene.projectXSceneService.R(first.player.sceneDialogCreated);
            waitFor(()->first.player.sceneDialog==null&&second.player.sceneDialog==null);
            System.out.println("PASS: scene-dialog close cancels an open, incomplete trade for both parties");

            confirm(c->first.scene.projectXSceneService.i(new Name(knight2),c));
            waitFor(()->request(second,knight1)!=null);
            confirm(c->second.client.bU(SocialMarshaller.class).a("TRADE_REQ:"+knight1,1,c));
            waitFor(()->first.player.sceneDialog instanceof TradeDialogInfo&&second.player.sceneDialog instanceof TradeDialogInfo);
            second.close();
            waitFor(()->first.player.sceneDialog==null);
            System.out.println("PASS: participant disconnect cancels the other party's open trade dialog");
        }
    }
    static final class Box implements com.threerings.presents.dobj.t<TradeObject> {
        TradeObject trade;
        public void a(TradeObject value){trade=value;}
        public void a(int oid,ObjectAccessException error){throw new AssertionError(error);}
    }
    static void listen(TradeObject trade) {
        trade.a(new c(){public void a(AttributeChangedEvent event){tradeAttributes.add(event);}});
        trade.a(new j(){public void a(ElementUpdatedEvent event){tradeElements.add(event);}});
        trade.a(itemEvents()); trade.a(orderEvents());
    }
    static final List<AttributeChangedEvent> tradeAttributes=new CopyOnWriteArrayList<>();
    static final List<ElementUpdatedEvent> tradeElements=new CopyOnWriteArrayList<>();
    static final List<String> tradeEntries=new CopyOnWriteArrayList<>();
    static com.threerings.presents.dobj.s<com.threerings.projectx.item.data.Item> itemEvents(){return itemEvents(()->true);}
    static com.threerings.presents.dobj.s<com.threerings.projectx.item.data.Item> itemEvents(BooleanSupplier observing){return new com.threerings.presents.dobj.s<>(){
        public void a(EntryAddedEvent<com.threerings.projectx.item.data.Item> e){if(observing.getAsBoolean())tradeEntries.add(e.getName()+":added");}
        public void a(EntryUpdatedEvent<com.threerings.projectx.item.data.Item> e){if(observing.getAsBoolean())tradeEntries.add(e.getName()+":updated");}
        public void a(EntryRemovedEvent<com.threerings.projectx.item.data.Item> e){if(observing.getAsBoolean())tradeEntries.add(e.getName()+":removed");}
    };}
    static com.threerings.presents.dobj.s<TradeObject.ItemOrder> orderEvents(){return new com.threerings.presents.dobj.s<>(){
        public void a(EntryAddedEvent<TradeObject.ItemOrder> e){tradeEntries.add("orderings:added");}
        public void a(EntryUpdatedEvent<TradeObject.ItemOrder> e){tradeEntries.add("orderings:updated");}
        public void a(EntryRemovedEvent<TradeObject.ItemOrder> e){tradeEntries.add("orderings:removed");}
    };}
    static void offer(TradeObject trade,long item,int count,Confirm listener){trade.tradeService.c(item,count,listener);}
    static void remove(TradeObject trade,long item,int count,Confirm listener){trade.tradeService.d(item,count,listener);}
    static int order(TradeObject trade,long item){var entry=trade.orderings.f(Long.valueOf(item));return entry==null?-1:entry.a(new TradeObject.ItemOrder(item,0));}

    static void seed(Path data,Path propertiesFile)throws Exception {
        data=data.toAbsolutePath().normalize();Path deployment=data.getParent();propertiesFile=propertiesFile.toAbsolutePath().normalize();
        if(deployment==null||!Files.isRegularFile(deployment.resolve("tortodrone.jar"))||
            !Files.isRegularFile(data.resolve("tortodrone.db"))||!Files.isRegularFile(deployment.resolve("content/items/weapons.yml"))||
            !propertiesFile.startsWith(deployment))
            throw new IllegalArgumentException("Seed mode only accepts a disposable deployment data directory and an output properties path inside that deployment");
        Properties result=new Properties();
        try(var accounts=new org.tortodrone.account.AccountStore(data)) {
            var first=accounts.worldProfile("LanPlayer","Town-Knight");
            var dust=first.items().stream().filter(i->i.config().equals("Food/Cosmic Dust")&&!i.bound()&&!i.permanent()&&i.equippedSlot()==null).findFirst().orElse(null);
            if(dust==null)first=accounts.grantItems("LanPlayer","Town-Knight","Food/Cosmic Dust",10,-1,-1);
            else if(dust.count()<10)first=accounts.grantItems("LanPlayer","Town-Knight","Food/Cosmic Dust",10-dust.count(),-1,-1);
            first=accounts.worldProfile("LanPlayer","Town-Knight");
            var stack=first.items().stream().filter(i->i.config().equals("Food/Cosmic Dust")&&!i.bound()&&!i.permanent()&&i.equippedSlot()==null).findFirst().orElseThrow();
            var second=accounts.worldProfile("PeerPlayer","Peer-Knight");
            var sword=second.items().stream().filter(i->i.config().equals("Weapon/Sword/Calibur")&&!i.bound()&&!i.permanent()&&i.equippedSlot()==null).findFirst().orElse(null);
            if(sword==null){second=accounts.grantItems("PeerPlayer","Peer-Knight","Weapon/Sword/Calibur",1,-1,-1);
                second=accounts.worldProfile("PeerPlayer","Peer-Knight");
                sword=second.items().stream().filter(i->i.config().equals("Weapon/Sword/Calibur")&&!i.bound()&&!i.permanent()&&i.equippedSlot()==null).findFirst().orElseThrow();}
            for(var target:List.of(new Object[]{"LanPlayer","Town-Knight",first},new Object[]{"PeerPlayer","Peer-Knight",second})) {
                String user=(String)target[0],knight=(String)target[1];var profile=(org.tortodrone.character.WorldProfile)target[2];
                if(profile.crowns()!=100)profile=accounts.currency(user,knight,org.tortodrone.character.Currency.CROWNS,100L-profile.crowns(),-1,-1);
                if(profile.energy()!=100)profile=accounts.currency(user,knight,org.tortodrone.character.Currency.ENERGY,100L-profile.energy(),-1,-1);
            }
            result.setProperty("lanItemId",Long.toString(stack.id()));result.setProperty("peerItemId",Long.toString(sword.id()));
            result.setProperty("lanItemCount",Integer.toString(stack.count()));result.setProperty("peerItemConfig",sword.config());
        }
        Files.createDirectories(propertiesFile.getParent());
        try(var output=Files.newOutputStream(propertiesFile)){result.store(output,"Disposable stock trade fixture IDs");}
        System.out.println("PASS: seeded disposable trade fixture LanPlayer/Town-Knight and PeerPlayer/Peer-Knight; item IDs saved to "+propertiesFile);
    }
}
