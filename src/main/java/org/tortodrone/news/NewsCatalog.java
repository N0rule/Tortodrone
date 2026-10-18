package org.tortodrone.news;

import java.util.*;

/** One validated Uplink News snapshot; the stock client reads it as alternating property pairs. */
public record NewsCatalog(boolean enabled, Banner featured, Banner left, Banner right) {
    /** The client resolves banner images relative to the launcher's content_url, so every value is news/<file>. */
    public static final String IMAGE_ROOT="news/";
    private static final NewsCatalog DISABLED=new NewsCatalog(false,null,null,null);
    /** One banner image with an optional tooltip and link shown by the stock Uplink window. */
    public record Banner(String image,String tooltip,String url) {
        public Banner {
            Objects.requireNonNull(image);
            tooltip=tooltip==null?"":tooltip;url=url==null?"":url;
        }
    }
    public static NewsCatalog disabled(){return DISABLED;}
    /** Alternating property name/value pairs; empty when disabled so the client shows no banner. */
    public List<String> propertyList() {
        if(!enabled)return List.of();
        var pairs=new ArrayList<String>();
        banner(pairs,"top",featured);banner(pairs,"left",left);banner(pairs,"right",right);
        return List.copyOf(pairs);
    }
    private static void banner(List<String> pairs,String prefix,Banner banner) {
        if(banner==null)return;
        pairs.add(prefix+".image");pairs.add(IMAGE_ROOT+banner.image());
        if(!banner.tooltip().isEmpty()){pairs.add(prefix+".tip");pairs.add(banner.tooltip());}
        if(!banner.url().isEmpty()){pairs.add(prefix+".url");pairs.add(banner.url());}
    }
}
