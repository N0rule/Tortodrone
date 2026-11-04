package org.tortodrone.world;

/** One terminal-floor report, owned by the membership that activated its native exit.
 * Time is the scene timestamp: no independent scheduler or client-selected destination. */
public final class FloorReport {
    public static final int TIMEOUT=70_000,LEAVE_DELAY=2_400;
    private final SceneSimulation.Membership member;
    private final SceneSimulation.FloorAdvanceRequest request;
    private final int started;
    private Integer reported;
    private boolean descend,finished;
    public FloorReport(SceneSimulation.Membership member,SceneSimulation.FloorAdvanceRequest request,int stamp){
        if(!member.ownsFloorAdvance(request))throw new IllegalArgumentException("Unowned floor request");
        this.member=member;this.request=request;started=stamp;
    }
    public boolean valid(){return !finished&&member.ownsFloorAdvance(request);}
    /** Repeated responses cannot change an earlier vote or shorten its authored leave delay. */
    public boolean report(boolean descend,int stamp){
        if(!valid()||reported!=null)return false;
        reported=stamp;this.descend=descend;return true;
    }
    public boolean ready(int stamp){return valid()&&(stamp-started>=TIMEOUT||reported!=null&&(descend||stamp-reported>=LEAVE_DELAY));}
    /** Physical elevators own their drop delay; do not add the portal's leave delay twice. */
    public boolean votedOrTimedOut(int stamp){return valid()&&(reported!=null||stamp-started>=TIMEOUT);}
    public boolean returning(){return reported!=null&&!descend;}
    public SceneSimulation.FloorAdvanceRequest request(){return request;}
    public void finish(){finished=true;}
}
