package org.tortodrone.world;

import java.io.IOException;
import java.util.*;

/** Reusable chat membership/routing; recipients enqueue without holding the routing lock. */
public final class SceneChat {
    public static final int SERVICE=12,MAX_CHARACTERS=256;
    public record Message(String speaker,String text,byte mode) { }
    @FunctionalInterface public interface Endpoint { void deliver(Message message)throws IOException; }
    private record Member(int scene,int client,String name,RateLimit rate,Endpoint endpoint) { }
    private final Map<Integer,Member> members=new HashMap<>();
    public final class Membership implements AutoCloseable {
        private final Member member;
        private Membership(Member member){this.member=member;}
        @Override public void close(){synchronized(SceneChat.this){members.remove(member.client(),member);}}
    }
    public synchronized Membership join(int scene,int client,String name,RateLimit rate,Endpoint endpoint) {
        if(scene<=0||client<=0||name==null||name.isBlank())throw new IllegalArgumentException("Invalid chat identity");
        var member=new Member(scene,client,name,Objects.requireNonNull(rate),Objects.requireNonNull(endpoint));
        members.put(client,member);return new Membership(member);
    }
    /** False means rate limited; sender name and target scene are authoritative membership state. */
    public boolean speak(Membership sender,String text,byte mode)throws IOException {
        text=validate(text);
        if(mode<0||mode>3)throw new IOException("Unsupported scene chat mode");
        List<Member> recipients;Member source;
        synchronized(this) {
            source=require(sender);
            if(!source.rate().allow(System.nanoTime()))return false;
            recipients=members.values().stream().filter(m->m.scene()==source.scene()).toList();
        }
        deliver(recipients,new Message(source.name(),text,mode));return true;
    }
    private Member require(Membership sender)throws IOException {
        if(sender==null||members.get(sender.member.client())!=sender.member)throw new IOException("Chat membership is no longer current");
        return sender.member;
    }
    /** Staff permission is checked by the authenticated caller; targets all ready memberships. */
    public boolean broadcast(Membership sender,String text)throws IOException {
        text=validate(text);
        List<Member> recipients;Member source;
        synchronized(this) {
            source=require(sender);
            if(!source.rate().allow(System.nanoTime()))return false;
            recipients=List.copyOf(members.values());
        }
        deliver(recipients,new Message(source.name(),text,(byte)4));return true;
    }
    private void deliver(List<Member> recipients,Message message) {
        for(var member:recipients) {
            try{member.endpoint().deliver(message);}
            catch(IOException failure){synchronized(this){members.remove(member.client(),member);}}
        }
    }
    public static String validate(String value)throws IOException {
        if(value==null)throw new IOException("Chat message is empty");
        String text=value.strip();
        if(text.isBlank())throw new IOException("Chat message is empty");
        if(text.length()>MAX_CHARACTERS*2||text.codePointCount(0,text.length())>MAX_CHARACTERS)
            throw new IOException("Chat messages are limited to 256 characters");
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(Character.isISOControl(c))throw new IOException("Chat message contains control characters");
            if(Character.isHighSurrogate(c)) {
                if(++i>=text.length()||!Character.isLowSurrogate(text.charAt(i)))throw new IOException("Chat message contains invalid text");
            }else if(Character.isLowSurrogate(c))throw new IOException("Chat message contains invalid text");
        }
        return text;
    }
    /** Five-message burst, one new message per second; retained across scene changes. */
    public static final class RateLimit {
        private double tokens=5;
        private long previous;
        public RateLimit(){this(System.nanoTime());}
        public RateLimit(long now){previous=now;}
        public synchronized boolean allow(long now) {
            tokens=Math.min(5,tokens+Math.max(0,now-previous)/1_000_000_000d);previous=Math.max(previous,now);
            if(tokens<1)return false;tokens--;return true;
        }
    }
}
