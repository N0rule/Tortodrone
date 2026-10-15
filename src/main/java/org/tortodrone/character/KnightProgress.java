package org.tortodrone.character;

/** Saved mission rank and prestige; these never fabricate mission completions. */
public record KnightProgress(int rank,int subrank,int prestige) {
    public static final KnightProgress RECRUIT=new KnightProgress(1,1,0);
    public KnightProgress {
        if(rank<1||rank>10||subrank<1||subrank>4||prestige<0)throw new IllegalArgumentException("Invalid rank or prestige");
    }
    public int prestigeRank(){int result=0;for(int threshold:new int[]{5000,10000,15000,25000,45000})if(prestige>=threshold)result++;return result;}
    public String rankPair(){return rank+"-"+subrank;}
    public int equipmentFlags(){return (rank>=3?64:0)|(rank>=5?128:0)|(rank>=6?256:0)|(rank>=8?512:0);}
}
