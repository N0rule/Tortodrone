package org.tortodrone.character;
import java.io.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;
import org.tortodrone.account.AccountStore;import org.tortodrone.storage.Database;
import static org.tortodrone.support.TestWire.check;
public final class CurrencyEquipmentTest {
 public static void main(String[]args)throws Exception {
  Path home=Files.createTempDirectory("tortodrone-balances-");
  try(var accounts=new AccountStore(home)) {
   accounts.create("Alice","alice-password".toCharArray());accounts.create("Bob","bob-password".toCharArray());
   var db=new Database(home,false);var characters=new CharacterStore(db,1,"Alice");
   var knight=characters.create(new Knight(0,"Alice-Knight","Gear/Helm/Spiral Sallet (Blue)","Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
   var original=characters.worldProfile(knight.name(),1);check(original.crowns()==0&&original.energy()==0,"zero starting balances");check(Arrays.stream(original.equipment()).filter(id->id!=0).count()==5,"starter kit equipped once");
   var funded=accounts.currency("Alice",knight.name(),Currency.CROWNS,100,1,original.worldId());check(funded.crowns()==100&&funded.energy()==0,"give crowns");
   funded=accounts.currency("Alice",knight.name(),Currency.ENERGY,40,1,original.worldId());check(funded.crowns()==100&&funded.energy()==40,"separate currency columns");
   funded=accounts.currency("Alice",knight.name(),Currency.CROWNS,-50,1,original.worldId());check(funded.crowns()==50,"withdraw crowns");
   refuse(()->accounts.currency("Alice",knight.name(),Currency.CROWNS,-51,1,original.worldId()));
   refuse(()->accounts.currency("Alice",knight.name(),Currency.CROWNS,Integer.MAX_VALUE,1,original.worldId()));
   refuse(()->accounts.currency("Alice",knight.name(),Currency.CROWNS,1,2,original.worldId()));
   refuse(()->accounts.currency("Alice",knight.name(),Currency.CROWNS,1,1,original.worldId()+1));
   refuse(()->accounts.currency("Alice",knight.name(),Currency.CROWNS,0,1,original.worldId()));
   refuse(()->accounts.currency("Alice",knight.name(),Currency.CROWNS,Long.MAX_VALUE,1,original.worldId()));
   long helm=original.equipment()[0],sword=original.equipment()[6],gun=original.equipment()[7];
   var state=db.equip(1,knight.id(),1,helm,null);check(state.equipment()[0]==0,"unequip persists");
   state=db.equip(1,knight.id(),1,sword,7);check(state.equipment()[6]==0&&state.equipment()[7]==sword,"move sword displaces gun atomically");
   refuse(()->db.equip(2,knight.id(),1,helm,0));refuse(()->db.equip(1,knight.id(),1,helm,7));refuse(()->db.equip(1,knight.id(),999,helm,0));refuse(()->db.equip(1,knight.id(),1,gun,15));
   characters.update(knight.name(),knight,1);check(db.worldProfile(1,knight.id(),1).equipment()[0]==0,"roster save cannot re-equip removed item");
   var restart=new Database(home,false).worldProfile(1,knight.id(),1);check(restart.crowns()==50&&restart.energy()==40&&restart.equipment()[7]==sword,"restart preserves counters and equipment");
   var workers=Executors.newFixedThreadPool(4);
   try{var jobs=new ArrayList<Callable<Void>>();for(int n=0;n<20;n++)jobs.add(()->{db.currency("Alice",knight.name(),1,original.worldId(),Currency.CROWNS,1);return null;});for(var result:workers.invokeAll(jobs))result.get();}finally{workers.shutdownNow();}
   check(db.worldProfile(1,knight.id(),1).crowns()==70,"concurrent increments cannot be lost");
   check(db.read(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT count(*) FROM currency_events")){r.next();return r.getInt(1);}})==23,"only committed currency changes audited");
   System.out.println("PASS: persistent balances, give/withdraw, limits, concurrency, audit, ownership, equipment displacement and restart");
  }finally{try(var paths=Files.walk(home)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
 }
 private static void refuse(Action action)throws Exception{try{action.run();throw new AssertionError("Invalid mutation accepted");}catch(IOException expected){}}
 @FunctionalInterface private interface Action{void run()throws Exception;}
}
