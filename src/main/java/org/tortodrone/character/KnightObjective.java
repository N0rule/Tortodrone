package org.tortodrone.character;

/** Allowlisted KNIGHT-persistent simple objective; null text removes the named objective. */
public record KnightObjective(String name,String text) {
    public KnightObjective {
        new KnightMemory(name,true);
        if(text!=null&&!text.matches("m\\.[A-Za-z0-9_.:-]{1,200}"))throw new IllegalArgumentException("Invalid objective message key");
    }
    public record State(int index,String name,String text) {
        public State {if(index<0)throw new IllegalArgumentException("Invalid objective index");new KnightObjective(name,text);if(text==null)throw new IllegalArgumentException("Missing objective text");}
    }
}
