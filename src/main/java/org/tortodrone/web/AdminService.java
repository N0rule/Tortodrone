package org.tortodrone.web;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.AccountStore;
import org.tortodrone.character.*;
import org.tortodrone.server.*;
import org.tortodrone.storage.*;
import org.tortodrone.town.TownCatalog;

/** Typed allowlist over authoritative domain services; never interprets console, SQL or paths. */
public final class AdminService implements AutoCloseable {
    private final AccountStore accounts;
    private final ServerControl control;
    private final Path deployment;
    private final ArrayDeque<ServerLog.Event> logs=new ArrayDeque<>();
    private final AutoCloseable subscription;
    private final NewsEditor news;
    private final org.tortodrone.resources.ContentRegistry validatedContent;
    public AdminService(AccountStore accounts,ServerControl control,ServerLog log,Path deployment)throws IOException {
        this(accounts,control,log,deployment,null);
    }
    public AdminService(AccountStore accounts,ServerControl control,ServerLog log,Path deployment,org.tortodrone.resources.ContentRegistry validatedContent)throws IOException {
        this.validatedContent=validatedContent;
        this.accounts=accounts;this.control=control;this.deployment=deployment;news=new NewsEditor(deployment.resolve("content/shared/news"));
        subscription=log.subscribe(e->{synchronized(logs){if(logs.size()==1000)logs.removeFirst();logs.addLast(e);}});
    }
    public Object read(String route,Input p)throws IOException {
        return switch(route) {
            case "status"->{var s=control.snapshot();yield Map.of("version",s.version(),"state",s.state(),"endpoint",s.endpoint(),"uptimeSeconds",s.uptimeSeconds(),"metrics",s.metrics(),"database",Database.diagnostics(),"accounts",accounts.list().size(),"sessions",s.sessions(),"scenes",s.scenes(),"failure",s.failure());}
            case "accounts"->accounts.list();
            case "knights"->accounts.knights(p.text("username"));
            case "knight"->accounts.worldProfile(p.text("username"),p.text("knight"));
            case "items"->accounts.catalog().entries().stream().filter(e->(e.config()+" "+e.displayName()).toLowerCase(Locale.ROOT).contains(p.optional("search").toLowerCase(Locale.ROOT))).limit(250).map(e->Map.of("config",e.config(),"name",e.displayName(),"category",accounts.catalog().category(e),"grantable",e.grantable(),"reason",e.unavailableReason())).toList();
            case "depot"->Map.of("snapshot",accounts.depot().snapshot(),"promotions",accounts.depot().promotions(),"effective",accounts.depot().catalog());
            case "auctions"->accounts.auctions().overview();
            case "deliveries"->accounts.auctions().deliveries(p.number("id"));
            case "logs"->{synchronized(logs){yield List.copyOf(logs);}}
            case "news"->news.read();
            case "content"->{var c=validatedContent==null?org.tortodrone.resources.ContentManager.load(deployment):validatedContent;yield Map.of("warnings",c.warnings(),"maps",c.scenes().scenes().size(),"items",c.items().entries().size(),"news",c.news(),"merchants",c.town().stations());}
            default->throw new IllegalArgumentException("Unknown resource");
        };
    }
    public static WebIdentityStore.Role permission(String route,boolean write){
        if(!write)return switch(route){case "status","logs"->WebIdentityStore.Role.VIEWER;case "accounts","knights","knight","items"->WebIdentityStore.Role.MODERATOR;default->WebIdentityStore.Role.ADMINISTRATOR;};
        return route.equals("kick")?WebIdentityStore.Role.MODERATOR:WebIdentityStore.Role.ADMINISTRATOR;
    }
    public Object write(String route,Input p)throws IOException {
        // Browser confirmation is repeated server-side for every mutation.
        if(!p.text("confirmation").equals(route))throw new IllegalArgumentException("Confirmation did not match the action");
        switch(route){
            case "start"->control.start();
            case "stop"->control.stop();
            case "restart"->control.restart();
            case "shutdown"->{if(!p.text("shutdown").equals("SHUT DOWN PROCESS"))throw new IllegalArgumentException("Process shutdown confirmation required");control.exit();}
            case "kick"->{if(!control.kick(p.number("session")))throw new IllegalArgumentException("Session already disconnected");}
            case "account-create"->password(p,pw->accounts.create(p.text("username"),pw,p.flag("gm")));
            case "account-check-password"->{char[] pw=p.text("password").toCharArray();try{return Map.of("matches",accounts.checkPassword(p.text("username"),pw));}finally{Arrays.fill(pw,'\0');}}
            case "account-password"->password(p,pw->accounts.setPassword(p.text("username"),pw,p.positive("accountId")));
            case "account-gm"->accounts.setGameMaster(p.text("username"),p.flag("gm"),p.positive("accountId"));
            case "account-delete"->{if(!p.text("username").equals(p.text("nameConfirmation")))throw new IllegalArgumentException("Account name confirmation required");accounts.delete(p.text("username"),p.positive("accountId"));}
            case "knight-delete"->{if(!p.text("knight").equals(p.text("nameConfirmation")))throw new IllegalArgumentException("Knight name confirmation required");accounts.deleteKnight(p.text("username"),p.text("knight"),p.positive("accountId"),p.positive("worldId"));}
            case "knight-progress"->{return accounts.progress(p.text("username"),p.text("knight"),p.integer("rank"),p.integer("subrank"),p.integer("prestige"),p.positive("accountId"),p.positive("worldId"),p.optional("sprite").isEmpty()?null:p.text("sprite"),p.number("revision"));}
            case "knight-currency"->{
                String operation=p.text("operation");if(!Set.of("set","delta").contains(operation))throw new IllegalArgumentException("Invalid currency operation");
                return accounts.administrativeCurrency(p.text("username"),p.text("knight"),org.tortodrone.character.Currency.valueOf(p.text("currency")),p.number("amount"),operation.equals("set"),p.positive("accountId"),p.positive("worldId"),p.number("revision"));
            }
            case "item-grant"->{return accounts.grantItems(p.text("username"),p.text("knight"),p.text("config"),p.positive("quantity"),p.positive("accountId"),p.positive("worldId"),modifiers(p),p.flag("bound"),p.integer("expireDays"));}
            case "item-quantity"->{return accounts.itemQuantity(p.text("username"),p.text("knight"),p.number("itemId"),p.integer("quantity"),p.positive("accountId"),p.positive("worldId"),p.number("revision"));}
            case "item-accessory"->{return accounts.itemAccessory(p.text("username"),p.text("knight"),p.number("itemId"),p.optional("accessory").isEmpty()?null:p.text("accessory"),p.positive("accountId"),p.positive("worldId"),p.number("revision"));}
            case "item-modifiers"->{return accounts.itemModifiers(p.text("username"),p.text("knight"),p.number("itemId"),modifiers(p),p.positive("accountId"),p.positive("worldId"),p.number("revision"));}
            case "depot-save"->{return accounts.depot().save(p.number("revision"),new DepotStore.Offer(p.integer("id"),new TownCatalog.Good(p.text("config"),p.positive("quantity"),p.integer("crowns"),p.integer("energy"),p.integer("limit"),"",0),p.flag("featured"),p.flag("enabled"),p.flag("promotionOnly")));}
            case "promotion-create"->{return accounts.depot().schedule(p.number("revision"),new DepotStore.Promotion(0,p.integer("offerId"),p.number("starts"),p.number("ends"),p.integer("crowns"),p.integer("energy"),p.optional("referencePrice").isEmpty()?null:p.integer("referencePrice"),p.flag("featured"),false));}
            case "promotion-cancel"->{return accounts.depot().cancelPromotion(p.number("revision"),p.number("id"));}
            case "auction-create"->{return accounts.auctions().scheduleFeatured(p.text("config"),p.positive("quantity"),p.integer("bid"),p.integer("buy"),p.integer("stock"),p.number("starts"),p.number("expires"));}
            case "auction-cancel"->{return accounts.auctions().cancel(p.number("id"));}
            case "auction-settle"->accounts.auctions().settleExpired();
            case "news-save"->{return news.save(p.text("yaml"),p.text("revision"));}
            default->throw new IllegalArgumentException("Unknown action");
        }
        return Map.of("ok",true);
    }
    private static ItemModifiers modifiers(Input p){return new ItemModifiers(p.integer("level"),Float.parseFloat(p.text("heat")),p.optional("variants").isBlank()?List.of():Arrays.stream(p.text("variants").split("\n")).map(String::strip).filter(s->!s.isEmpty()).toList());}
    @FunctionalInterface private interface PasswordAction{void run(char[] password)throws IOException;}
    private static void password(Input p,PasswordAction action)throws IOException{char[] pw=p.text("password").toCharArray();try{action.run(pw);}finally{Arrays.fill(pw,'\0');}}
    public record Input(Map<String,String> values){
        public String text(String key){String s=values.get(key);if(s==null||s.isEmpty())throw new IllegalArgumentException("Missing "+key);return s;}
        public String optional(String key){return values.getOrDefault(key,"");}
        public long number(String key){return Long.parseLong(text(key));}
        public int integer(String key){return Math.toIntExact(number(key));}
        public int positive(String key){int n=integer(key);if(n<=0)throw new IllegalArgumentException("Invalid "+key);return n;}
        public boolean flag(String key){String v=text(key);if(!v.equals("true")&&!v.equals("false"))throw new IllegalArgumentException("Invalid "+key);return Boolean.parseBoolean(v);}
    }
    @Override public void close()throws Exception{subscription.close();}
}
