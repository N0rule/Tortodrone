package org.tortodrone.town;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import org.tortodrone.character.Currency;
import org.tortodrone.storage.*;
import static org.tortodrone.support.TestWire.check;

/** Escrow conservation, matching, rollback and ownership over a disposable SQLite order book. */
public final class EnergyExchangeTest {
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("tortodrone-energy-");
        try{
            var db=new Database(root,true);var password=new Passwords.Protection(new byte[16],new byte[32]);var catalog=ItemCatalog.starter();
            for(String name:List.of("Alice","Bob","Carol")){int account=db.create(name,password,false);new CharacterStore(db,account,name,catalog).create(knight(name+"-Knight"));db.currency(name,name+"-Knight",account,-1,Currency.CROWNS,100000);db.currency(name,name+"-Knight",account,-1,Currency.ENERGY,3000);}
            var exchange=new EnergyExchange(db);check(exchange.book().buys().isEmpty()&&exchange.book().sells().isEmpty(),"new market contains no invented liquidity");
            var buy=(ExchangeState.Offer)exchange.trade(1,1,1,3,6000,true,false).result();check(balance(db,1).crowns()==82000&&buy.volume()==3,"buy limit escrows its full Crown commitment");
            refused(()->exchange.cancel(2,1,1,buy.id()));refused(()->exchange.trade(1,1,999,1,6000,true,false));refused(()->exchange.trade(1,1,1,999,Integer.MAX_VALUE,true,false));
            check(exchange.trade(2,1,1,1,5500,false,false).result()==null&&balance(db,2).crowns()==106000&&balance(db,1).energy()==3100,"incoming seller fills best resting bid at the maker price");
            var sell=(ExchangeState.Offer)exchange.trade(3,1,1,1,6100,false,false).result();
            int crowns=balance(db,1).crowns();exchange.trade(1,1,1,1,6200,true,true);check(balance(db,1).crowns()==crowns-6100&&balance(db,3).crowns()==106100,"incoming buyer receives price improvement refund and seller gets actual proceeds");
            exchange.cancel(1,1,1,buy.id());refused(()->exchange.cancel(1,1,1,buy.id()));refused(()->exchange.cancel(3,1,1,sell.id()));conserved(db);
            var first=(ExchangeState.Offer)exchange.trade(2,1,1,1,5000,false,false).result();var second=(ExchangeState.Offer)exchange.trade(3,1,1,1,5000,false,false).result();
            check(exchange.book().sells().getFirst().volume()==2,"public order book consolidates same-price lots");exchange.trade(1,1,1,1,5500,true,true);
            check(own(exchange,2).isEmpty()&&own(exchange,3).getFirst().id()==second.id(),"same-price offers fill in creation order");refused(()->exchange.cancel(2,1,1,first.id()));
            var self=(ExchangeState.Offer)exchange.trade(1,1,1,1,4800,false,false).result();var before=balance(db,1);refused(()->exchange.trade(1,1,1,1,4800,true,true));check(balance(db,1).equals(before),"own escrow cannot self-match or spend a failed immediate quote");
            exchange.trade(2,1,1,1,4900,true,true);check(own(exchange,1).stream().noneMatch(o->o.id()==self.id()),"other players can fill the resting seller");conserved(db);
            var book=exchange.book();var alice=balance(db,1);var carol=balance(db,3);int carolWorld=carol.worldId();
            db.write(c->{try(var s=c.createStatement()){s.execute("CREATE TRIGGER reject_energy BEFORE INSERT ON currency_events WHEN NEW.world_id="+carolWorld+" BEGIN SELECT RAISE(ABORT,'fixture'); END");}return null;});
            refused(()->exchange.trade(1,1,1,1,5000,true,true));check(exchange.book().equals(book)&&balance(db,1).equals(alice)&&balance(db,3).equals(carol),"counterparty failure rolls back debit, delivery, order remainder and market revision");
            db.write(c->{try(var s=c.createStatement()){s.execute("DROP TRIGGER reject_energy");}return null;});
            var workers=Executors.newFixedThreadPool(2);try{var barrier=new CyclicBarrier(2);var results=new ArrayList<Future<Boolean>>();for(int account:new int[]{1,2})results.add(workers.submit(()->{barrier.await();try{new EnergyExchange(new Database(root,false)).trade(account,1,1,1,5000,true,true);return true;}catch(IOException unavailable){return false;}}));int wins=0;for(var result:results)if(result.get())wins++;check(wins==1,"two concurrent takers cannot fill the same remaining lot twice");}finally{workers.shutdownNow();}
            conserved(db);check(exchange.book().sells().isEmpty(),"last offer is exhausted");
            var pending=(ExchangeState.Offer)exchange.trade(1,1,1,2,4500,true,false).result();var reopened=new EnergyExchange(new Database(root,false));check(own(reopened,1).getFirst().equals(pending),"pending funded offers survive restart");reopened.cancel(1,1,1,pending.id());conserved(db);
            carol=balance(db,3);int oldCrowns=carol.crowns();db.setCurrency("Carol","Carol-Knight",3,carol.worldId(),Currency.CROWNS,Integer.MAX_VALUE);var overflow=(ExchangeState.Offer)exchange.trade(3,1,1,1,50,false,false).result();book=exchange.book();alice=balance(db,1);
            refused(()->exchange.trade(1,1,1,1,50,true,true));check(exchange.book().equals(book)&&balance(db,1).equals(alice),"seller balance overflow preserves escrow and buyer funds");exchange.cancel(3,1,1,overflow.id());db.setCurrency("Carol","Carol-Knight",3,carol.worldId(),Currency.CROWNS,oldCrowns);conserved(db);
            // Exact known predecessor: no Energy Exchange table in schema 11.
            var saved=balance(db,1);db.write(c->{try(var s=c.createStatement()){s.execute("DROP TABLE town_energy_offers");s.execute("DELETE FROM metadata WHERE key LIKE 'energy-exchange-%'");s.execute("PRAGMA user_version=11");}return null;});var upgraded=new Database(root,false);check(balance(upgraded,1).equals(saved)&&new EnergyExchange(upgraded).book().sells().isEmpty(),"schema-11 upgrade preserves balances and starts with empty liquidity");
            System.out.println("PASS: Energy escrow conservation, price/time matching, refunds, cancellation ownership/replay, concurrent fills, rollback, overflow, restart and schema-11 upgrade");
        }finally{try(var files=Files.walk(root)){for(var path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    @SuppressWarnings("unchecked") private static List<ExchangeState.Offer> own(EnergyExchange exchange,int account)throws IOException{var groups=(List<List<ExchangeState.Offer>>)exchange.offers(account,1,1).result();var result=new ArrayList<ExchangeState.Offer>();result.addAll(groups.get(0));result.addAll(groups.get(1));return result;}
    private static WorldProfile balance(Database db,int account)throws IOException{return db.worldProfile(account,1,1);}
    private static void conserved(Database db)throws IOException{db.read(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT (SELECT SUM(crowns) FROM world_characters)+COALESCE((SELECT SUM(price*volume) FROM town_energy_offers WHERE buy=1),0),(SELECT SUM(energy) FROM world_characters)+COALESCE((SELECT SUM(volume*100) FROM town_energy_offers WHERE buy=0),0)")){r.next();check(r.getLong(1)==300000&&r.getLong(2)==9000,"funded Energy and Crown totals are conserved, including escrow");}return null;});}
    private static Knight knight(String name){return new Knight(0,name,"Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",0,2,0,0);}
    @FunctionalInterface private interface Work{void run()throws Exception;}
    private static void refused(Work work)throws Exception{try{work.run();throw new AssertionError("Expected refusal");}catch(IOException expected){}}
}
