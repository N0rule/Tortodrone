package org.tortodrone.news;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Validated Uplink News packs; each client request shows one randomly chosen pack. */
public record NewsCatalog(boolean enabled, List<Pack> packs) {
    /** The client resolves banner images relative to the launcher's content_url, so every value is news/<file>. */
    public static final String IMAGE_ROOT="news/";
    /** The native promo buttons in ui/uplink/parts/interface_news.dat are 570x300 and 280x190. */
    public static final int FEATURED_WIDTH=570,FEATURED_HEIGHT=300,SPOTLIGHT_WIDTH=280,SPOTLIGHT_HEIGHT=190;
    /** Shared with the image server; startup refuses files it would not serve. */
    public static final long MAX_IMAGE_BYTES=8L*1024*1024;
    private static final NewsCatalog DISABLED=new NewsCatalog(false,List.of());
    /** One banner set: a featured banner with optional left/right spotlights. */
    public record Pack(Banner featured,Banner left,Banner right) { }
    /** One banner image with optional tooltip, link and supported native Depot action. */
    public record Banner(String image,String tooltip,String url,String action,String argument) {
        public Banner(String image,String tooltip,String url){this(image,tooltip,url,"","");}
        public Banner {
            Objects.requireNonNull(image);
            tooltip=tooltip==null?"":tooltip;url=url==null?"":url;
            action=action==null?"":action;argument=argument==null?"":argument;
        }
    }
    public NewsCatalog {
        // List.copyOf rejects null packs and defends against later mutation of the caller's list.
        packs=List.copyOf(packs);
    }
    public static NewsCatalog disabled(){return DISABLED;}
    public int size(){return packs.size();}
    /** Alternating property name/value pairs for one random pack; empty when disabled or empty. */
    public List<String> propertyList(){return propertyList(enabled&&!packs.isEmpty()?ThreadLocalRandom.current().nextInt(packs.size()):-1);}
    /** Alternating property name/value pairs for one specific pack; tests and tools use this. */
    public List<String> propertyList(int index) {
        if(!enabled||index<0||index>=packs.size())return List.of();
        var pack=packs.get(index);var pairs=new ArrayList<String>();
        banner(pairs,"top",pack.featured());banner(pairs,"left",pack.left());banner(pairs,"right",pack.right());
        return List.copyOf(pairs);
    }
    private static void banner(List<String> pairs,String prefix,Banner banner) {
        if(banner==null)return;
        pairs.add(prefix+".image");pairs.add(IMAGE_ROOT+banner.image());
        // The stock window prefers .action over .url and passes .argument to its scene controller.
        if(!banner.action().isEmpty()) {
            pairs.add(prefix+".action");pairs.add(banner.action());
            if(!banner.argument().isEmpty()){pairs.add(prefix+".argument");pairs.add(banner.argument());}
        }
        if(!banner.tooltip().isEmpty()){pairs.add(prefix+".tip");pairs.add(banner.tooltip());}
        if(!banner.url().isEmpty()){pairs.add(prefix+".url");pairs.add(banner.url());}
    }
}
