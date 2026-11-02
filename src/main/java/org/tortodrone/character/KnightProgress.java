package org.tortodrone.character;

/** Saved mission rank and prestige; these never fabricate mission completions. */
public record KnightProgress(int rank,int subrank,int prestige,int openingStage) {
    /** Missing opening metadata grandfathers an existing/imported knight. */
    public KnightProgress(int rank,int subrank,int prestige){this(rank,subrank,prestige,-1);}
    public static final KnightProgress RECRUIT=new KnightProgress(1,1,0);
    public KnightProgress {
        if(rank<1||rank>10||subrank<1||subrank>4||prestige<0||openingStage< -1||openingStage>3)throw new IllegalArgumentException("Invalid rank or prestige");
    }
    public int prestigeRank(){int result=0;for(int threshold:new int[]{5000,10000,15000,25000,45000})if(prestige>=threshold)result++;return result;}
    public String rankPair(){return rank+"-"+subrank;}
    public int equipmentFlags(){return (rank>=3?64:0)|(rank>=5?128:0)|(rank>=6?256:0)|(rank>=8?512:0);}
    public boolean crashSiteIncomplete(){return openingStage==0;}
    public boolean tutorialComplete(){return openingStage!=0;}
    /** Live's town-travel bit is awarded on reaching Haven, not on leaving Crash Site. */
    public int progressFlags(){return equipmentFlags()|(openingStage<0||openingStage==3?4:0);}
}
