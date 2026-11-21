package org.tortodrone.protocol;

import static org.tortodrone.protocol.SessionCodec.*;
import static org.tortodrone.protocol.WireWriter.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.SlotUpgrades;
import org.tortodrone.core.world.KnightCombat;
import org.tortodrone.core.world.PlayerEmotes;
import org.tortodrone.core.world.MovementRules;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.SceneSources;
import org.tortodrone.core.world.SceneSource;
import org.tortodrone.core.world.SceneInstanceView;
import org.tortodrone.core.world.SceneCatalogView;
import org.tortodrone.core.world.MapScene;
import org.tortodrone.core.world.SceneEffect;
import org.tortodrone.core.world.SceneCombatEvent;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.party.PartyView;
import org.tortodrone.core.WireTypes;
import org.tortodrone.core.character.KnightProgress;
import org.tortodrone.core.character.KnightObjective;
import org.tortodrone.core.character.OpeningCompletion;
import org.tortodrone.core.character.OpeningMission;
import org.tortodrone.core.character.KnightMemory;
import org.tortodrone.core.mission.MissionCatalog;
import org.tortodrone.core.mission.MissionGates;
import org.tortodrone.core.world.SceneConversation;
import org.tortodrone.core.town.TownState;
import org.tortodrone.core.town.AuctionState;
import org.tortodrone.core.town.ExchangeState;
import org.tortodrone.core.town.TownCatalog;
import org.tortodrone.core.world.EquipmentCatalog;
import org.tortodrone.core.world.InteractionState;
import org.tortodrone.core.world.ProjectileState;
import org.tortodrone.core.world.SceneChat;
import org.tortodrone.core.world.SceneHealth;
import org.tortodrone.core.world.SceneLiftState;
import org.tortodrone.core.world.SceneStatus;
import org.tortodrone.core.world.ReadyRoom;
import org.tortodrone.core.mission.OpeningGate;
import org.tortodrone.core.mission.TutorialRoute;
import org.tortodrone.core.party.PartyView;

/** Town-owned downstream frames; shares the connection symbol tables. */
public final class TownCodec extends WireWriter {
    private int listingViewer;
    public TownCodec(WireContext context, ItemCatalog catalog){super(context,catalog);}
    public byte[] exchange(short messageId,org.tortodrone.core.town.ExchangeState.Book book)throws IOException{
        return frame(out->{objectHeader(out,messageId,"com.threerings.projectx.exchange.data.ExchangeObject",EXCHANGE_OID);
            type(out,"com.threerings.projectx.exchange.data.ExchangeMarshaller");out.writeInt(EXCHANGE_OID);out.writeInt(EXCHANGE_SERVICE);
            out.writeInt(book.lastPrice());exchangeLevels(out,book.buys());exchangeLevels(out,book.sells());});
    }

    public byte[] exchangeChanged(org.tortodrone.core.town.ExchangeState.Book book)throws IOException{
        return frame(out->{eventHeader(out,"CompoundEvent",EXCHANGE_OID);type(out,WireTypes.ARRAY_LIST);out.writeInt(3);
            attribute(out,EXCHANGE_OID,"lastPrice",o->value(o,book.lastPrice()));attribute(out,EXCHANGE_OID,"buyOffers",o->exchangeLevels(o,book.buys()));attribute(out,EXCHANGE_OID,"sellOffers",o->exchangeLevels(o,book.sells()));});
    }

    private void exchangeLevels(DataOutputStream out,List<org.tortodrone.core.town.ExchangeState.Level> levels)throws IOException{
        type(out,"[Lcom.threerings.projectx.exchange.data.ConsolidatedOffer;");out.writeInt(levels.size());
        for(var level:levels){type(out,"com.threerings.projectx.exchange.data.ConsolidatedOffer");out.writeInt(level.volume());out.writeInt(level.price());}
    }

    public byte[] townDialog(int playerOid,int sceneOid,org.tortodrone.core.town.TownCatalog.Station station,ActorState source,long created,int service)throws IOException {
        return frame(out->{eventHeader(out,"CompoundEvent",playerOid);type(out,WireTypes.ARRAY_LIST);out.writeInt(2);
            attribute(out,playerOid,"sceneDialogCreated",o->value(o,created));
            attribute(out,playerOid,"sceneDialog",o->{
                if(station==null){o.writeShort(0);return;}
                String kind=station.kind();
                if(kind.equals("OpenDepot")){type(o,"com.threerings.projectx.exchange.data.DepotDialogInfo");o.writeShort(0);o.writeShort(0);o.writeFloat(0);o.writeShort(0);o.writeShort(0);string(o,station.type());return;}
                if(kind.equals("OpenAuction")){type(o,"com.threerings.projectx.auction.data.AuctionDialogInfo");if(source==null){o.writeShort(0);o.writeShort(0);o.writeFloat(0);}else{sourceKey(o,source);vector(o,source.x(),source.y());o.writeFloat(source.rotation());}o.writeShort(0);o.writeShort(0);return;}
                if(kind.equals("OpenShop")){
                    type(o,"com.threerings.projectx.shop.data.ShopDialogInfo");
                    if(source==null){o.writeShort(0);o.writeShort(0);o.writeFloat(0);}else{sourceKey(o,source);vector(o,source.x(),source.y());o.writeFloat(source.rotation());}
                    o.writeShort(0);o.writeShort(0);value(o,station.model().isEmpty()?null:new StarterCatalog.Model(station.model(),Map.of()));o.writeShort(0);string(o,station.name());string(o,station.title());
                    o.writeInt(station.shop().level());value(o,new org.tortodrone.core.wire.EnumValue("com.threerings.projectx.shop.data.ShopDialogInfo$Type",station.type().equals("ENERGY")?1:station.type().equals("TOKEN")?2:0));
                    int preparer=List.of("BASIC","GREMLIN","LOCKED","LOCK_NON_STACKABLE","DEPOT","STATUE").indexOf(station.shop().preparer());value(o,new org.tortodrone.core.wire.EnumValue("com.threerings.projectx.shop.util.ItemPreparer",Math.max(0,preparer)));o.writeBoolean(station.shop().perKnight());
                    type(o,"com.threerings.projectx.shop.data.ShopMarshaller");o.writeInt(sceneOid);o.writeInt(service);return;
                }
                if(kind.equals("OpenHarnessInstaller")){
                    type(o,"com.threerings.projectx.sprites.data.HarnessDialogInfo");
                    if(source==null){o.writeShort(0);o.writeShort(0);o.writeFloat(0);}else{sourceKey(o,source);vector(o,source.x(),source.y());o.writeFloat(source.rotation());}
                    o.writeShort(0);o.writeShort(0);value(o,station.model().isEmpty()?null:new StarterCatalog.Model(station.model(),Map.of()));o.writeShort(0);string(o,station.name());string(o,station.title());
                    type(o,"com.threerings.projectx.sprites.data.HarnessInstallerMarshaller");o.writeInt(sceneOid);o.writeInt(service);return;
                }
                String dialog=kind.equals("OpenUnbinder")?"UnbindDialogInfo":kind.equals("OpenAccessorizer")?"AccessoryDialogInfo":kind.equals("OpenVariantMaker")?"VariantMakerDialogInfo":"CrafterDialogInfo";
                type(o,"com.threerings.projectx.craft.data."+dialog);
                if(source==null){o.writeShort(0);o.writeShort(0);o.writeFloat(0);}else{sourceKey(o,source);vector(o,source.x(),source.y());o.writeFloat(source.rotation());}
                o.writeShort(0);o.writeShort(0);
                value(o,station.model().isEmpty()?null:new StarterCatalog.Model(station.model(),Map.of()));o.writeShort(0);string(o,station.name());string(o,station.title());
                if(dialog.equals("CrafterDialogInfo")){
                    type(o,"com.threerings.projectx.craft.data.CrafterDialogInfo$CrafterGroup");o.writeByte(station.group().equals("SPRITE")?1:0);
                    string(o,station.type().isEmpty()?null:station.type());o.writeShort(0);
                    type(o,"com.threerings.projectx.craft.data.CraftMarshaller");o.writeInt(sceneOid);o.writeInt(service);
                    if(station.recipes().isEmpty())o.writeShort(0);else{type(o,"[Lcom.threerings.projectx.craft.config.RecipeConfig$RecipeConfigReference;");o.writeInt(station.recipes().size());for(String recipe:station.recipes()){type(o,"com.threerings.projectx.craft.config.RecipeConfig$RecipeConfigReference");value(o,new StarterCatalog.Model(recipe,Map.of()));}}
                    o.writeShort(0);
                }else{String marshaller=dialog.equals("UnbindDialogInfo")?"UnbindMarshaller":dialog.equals("AccessoryDialogInfo")?"AccessoryMarshaller":"VariantMarshaller";type(o,"com.threerings.projectx.craft.data."+marshaller);o.writeInt(sceneOid);o.writeInt(service);}
            });});
    }

    public byte[] townShop(int oid,List<org.tortodrone.core.town.TownCatalog.Good> goods,List<Integer> available)throws IOException {
        return frame(out->{eventHeader(out,"AttributeChangedEvent",oid);string(out,"uniqueDialog");type(out,"com.threerings.projectx.shop.data.UniqueShopInfo");out.writeBoolean(true);out.writeInt(goods.size());
            for(int i=0;i<goods.size();i++){var good=goods.get(i);type(out,"com.threerings.projectx.shop.data.UniqueShopInfo$GoodCount");type(out,"com.threerings.projectx.shop.config.GoodConfig$"+(good.token().isEmpty()?"Item":"TokenItem"));out.writeInt(good.price());out.writeInt(good.energy());out.writeInt(0);out.writeBoolean(false);out.writeInt(good.count());out.writeInt(good.limit());value(out,new StarterCatalog.Model(good.item(),Map.of()));if(!good.token().isEmpty()){value(out,new StarterCatalog.Model(good.token(),Map.of()));out.writeInt(good.tokenCount());}out.writeInt(available.get(i));}
        });
    }

    public byte[] townState(int oid,WorldProfile profile)throws IOException {
        return frame(out->{eventHeader(out,"CompoundEvent",oid);type(out,WireTypes.ARRAY_LIST);out.writeInt(6);
            attribute(out,oid,"outbid",o->value(o,!profile.town().outbids().isEmpty()));
            attribute(out,oid,"newMailInfo",o->{type(o,WireTypes.DSET);var mail=profile.town().mail().stream().filter(m->!m.read()).toList();o.writeInt(mail.size());for(var m:mail){type(o,"com.threerings.projectx.uplink.data.NewMailInfo");o.writeLong(m.id());name(o,m.sender());value(o,new org.tortodrone.core.wire.EnumValue("com.threerings.projectx.uplink.data.MailType",m.nativeType()));}});
            attribute(out,oid,"recipes",o->{type(o,WireTypes.DSET);o.writeInt(profile.town().recipes().size());for(var recipe:profile.town().recipes()){type(o,"com.threerings.projectx.craft.data.Recipe");o.writeBoolean(true);o.writeLong(recipe.id());string(o,recipe.config());}});
            attribute(out,oid,"harnesses",o->{type(o,WireTypes.DSET);o.writeInt(profile.town().harnesses().size());for(var h:profile.town().harnesses())harness(o,h);});
            attribute(out,oid,"sprites",o->{type(o,WireTypes.DSET);o.writeInt(profile.town().sprites().size());for(var sprite:profile.town().sprites())sprite(o,profile,sprite);});
            attribute(out,oid,"equippedSprite",o->value(o,profile.town().equippedSprite()));});
    }

    public byte[] recipeAdded(int oid,org.tortodrone.core.town.TownState.LearnedRecipe recipe)throws IOException {
        return frame(out->{eventHeader(out,"EntryAddedEvent",oid);string(out,"recipes");
            type(out,"com.threerings.projectx.craft.data.Recipe");out.writeBoolean(true);out.writeLong(recipe.id());string(out,recipe.config());});
    }

    public byte[] itemAcquireDelay(int oid,int millis)throws IOException {
        return frame(out->{eventHeader(out,"AttributeChangedEvent",oid);string(out,"itemAcquireDelay");value(out,millis);});
    }

    public byte[] townResult(int oid,short request,Object result,WorldProfile profile)throws IOException {
        listingViewer=profile==null?0:profile.worldId();
        return frame(out->{eventHeader(out,"InvocationResponseEvent",oid);out.writeShort(request);out.writeByte(1);out.writeBoolean(true);out.writeInt(1);
            if(result instanceof org.tortodrone.core.town.TownState.Sprite s)sprite(out,profile,s);else townValue(out,result);});
    }

    private void townValue(DataOutputStream out,Object value)throws IOException {
        if(value instanceof org.tortodrone.core.wire.Multiset multiset){type(out,"com.google.common.collect.HashMultiset");out.writeInt(multiset.counts().size());for(var entry:multiset.counts().entrySet()){value(out,entry.getKey());out.writeInt(entry.getValue());}}
        else if(value instanceof java.util.List<?> list){type(out,"java.util.ArrayList");out.writeInt(list.size());for(var element:list)townValue(out,element);}
        else if(value instanceof org.tortodrone.core.town.ExchangeState.Offer offer){type(out,"com.threerings.coin.data.CoinExOfferInfo");out.writeInt(offer.id());out.writeBoolean(offer.buy());out.writeInt(offer.price());out.writeInt(offer.volume());}
        else if(value instanceof org.tortodrone.core.town.TownCatalog.DepotOffers offers){
            type(out,"java.util.HashMap");out.writeInt(offers.goods().size());for(var entry:new java.util.TreeMap<>(offers.goods()).entrySet()){
                value(out,entry.getKey());type(out,"com.threerings.projectx.mission.data.RankGood");var g=entry.getValue().good();
                type(out,"com.threerings.projectx.shop.config.GoodConfig$Item");out.writeInt(g.price());out.writeInt(g.energy());out.writeInt(entry.getValue().originalPrice());out.writeBoolean(entry.getValue().sale());out.writeInt(g.count());out.writeInt(g.limit());value(out,new StarterCatalog.Model(g.item(),Map.of()));
                out.writeBoolean(entry.getValue().featured());string(out,entry.getValue().promoHeader());string(out,entry.getValue().promoDetail());
            }
        }
        else if(value instanceof org.tortodrone.core.town.AuctionState.Page page){type(out,"com.threerings.projectx.auction.data.ListingsPage");out.writeInt(page.pages());out.writeBoolean(true);out.writeInt(page.listings().size());for(var listing:page.listings())listing(out,listing);}
        else if(value instanceof org.tortodrone.core.town.AuctionState.Listing listing)listing(out,listing);
        else if(value instanceof org.tortodrone.core.town.AuctionState.Update update){type(out,"com.threerings.projectx.auction.data.ListingUpdate");listing(out,update.listing());string(out,update.message());}
        else if(value instanceof org.tortodrone.core.town.AuctionState.Mail m){type(out,"com.threerings.projectx.uplink.data.ReceivedMail");out.writeLong(m.id());name(out,m.sender());out.writeLong(m.sent());string(out,auctionMailText(m.subject()));string(out,auctionMailText(m.body()));
            if(m.friendRequest()){type(out,"com.threerings.projectx.uplink.data.FriendRequest");out.writeInt(m.friendWorld());}
            else if(m.claimed()||m.items().isEmpty()&&m.crowns()==0&&m.energy()==0)out.writeShort(0);else{type(out,"com.threerings.projectx.uplink.data.MultiAttachment");
                out.writeBoolean(!m.items().isEmpty());if(!m.items().isEmpty()){out.writeInt(m.items().size());for(var item:m.items()){value(out,new StarterCatalog.Model(item.config(),Map.of()));out.writeInt(item.count());}}out.writeInt(m.crowns());out.writeInt(m.energy());}
            value(out,new org.tortodrone.core.wire.EnumValue("com.threerings.projectx.uplink.data.MailType",m.nativeType()));out.writeByte((m.read()?0:1)|(m.claimed()?2:0));
        }else value(out,value);
    }

    private void listing(DataOutputStream out,org.tortodrone.core.town.AuctionState.Listing a)throws IOException{
        type(out,"com.threerings.projectx.auction.data.ListingInfo");out.writeLong(a.id());int status=a.bidder()==0?0:a.participated()?(a.mine()?1:3):1;
        // The caller's winning status is carried explicitly by the view, separate from seller ownership.
        if(a.participated()&&a.bidder()!=0&&!a.mine())status=a.bidder()==listingViewer?2:3;else if(!a.participated()&&a.watching())status=4;
        value(out,new org.tortodrone.core.wire.EnumValue("com.threerings.projectx.auction.data.BidStatus",status));
        int time=a.timeLeft(System.currentTimeMillis());
        value(out,new org.tortodrone.core.wire.EnumValue("com.threerings.projectx.auction.data.TimeLeft",time));out.writeInt(a.buy());out.writeInt(a.bid());
        // The current ListingInfo carries a numeric config id plus the export-encoded
        // config arguments. Torodrone items are identified by name and do not carry
        // per-instance config arguments, so send the stock id and a null argument map.
        var definition=itemCatalog.find(a.item().config());out.writeInt(definition==null?0:definition.stockId());out.writeBoolean(false);
        out.writeInt(a.item().count());out.writeBoolean(a.mine());if(a.featured()){type(out,"com.threerings.projectx.auction.data.FeaturedInfo");out.writeShort(a.featuredRemaining());out.writeShort(a.featuredTotal());}else out.writeShort(0);
    }

    private static String auctionMailText(String text){
        return text!=null&&text.matches("%auction:[sm]\\.(auction|bid)_.*")?"%uplink:"+text.substring(9):text;
    }

    public byte[] conversationDialog(int playerOid,int sceneOid,org.tortodrone.core.world.SceneConversation conversation,
            String nodeId,ActorState source,long created)throws IOException {
        return frame(out->{eventHeader(out,"CompoundEvent",playerOid);type(out,WireTypes.ARRAY_LIST);out.writeInt(2);
            attribute(out,playerOid,"sceneDialogCreated",o->value(o,created));
            attribute(out,playerOid,"sceneDialog",o->{
                if(conversation==null){o.writeShort(0);return;}
                var node=conversation.nodes().get(nodeId);
                type(o,"com.threerings.projectx.data.ConversationDialogInfo");
                sourceKey(o,source);
                vector(o,source.x(),source.y());o.writeFloat(source.rotation());value(o,node.transientModel());value(o,conversation.sourceCloseAnimation());
                value(o,node.userInterface());value(o,conversation.model());value(o,node.animation());value(o,conversation.music());
                string(o,conversation.name());string(o,conversation.bundle());string(o,node.message());
                type(o,"[Ljava.lang.String;");int count=node.responses().size();o.writeInt(count);
                o.writeShort((count+7)/8);
                for(int i=0;i<count;i+=8)o.writeByte((1<<Math.min(8,count-i))-1);
                for(var response:node.responses())o.writeUTF(response.text());
                o.writeBoolean(true);o.writeInt(count);for(var response:node.responses())o.writeBoolean(response.destination().isEmpty());
                type(o,"com.threerings.projectx.data.ConversationMarshaller");o.writeInt(sceneOid);o.writeInt(org.tortodrone.core.world.SceneConversation.SERVICE);o.writeLong(0);
            });});
    }

    public byte[] homeTown(int oid,WorldProfile profile)throws IOException {
        return frame(out->{eventHeader(out,"AttributeChangedEvent",oid);string(out,"homeTown");homeTown(out,profile);});
    }

}
