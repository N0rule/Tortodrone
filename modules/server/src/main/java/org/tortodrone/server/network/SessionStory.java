package org.tortodrone.server.network;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.tortodrone.core.character.KnightObjective;
import org.tortodrone.core.character.OpeningCompletion;
import org.tortodrone.core.character.OpeningMission;
import org.tortodrone.core.mission.ObjectiveEntry;
import org.tortodrone.core.world.SceneConversation;
import org.tortodrone.world.OpeningComlinks;

/** Knight-persistent story progress for one connection: memories, objectives, completed
 *  opening missions and the arrival comlink already spent. Settlements commit through the
 *  stores first and publish only the changed sets; the mission bar is derived from the owned
 *  run's own {@code o.<id>} key. */
final class SessionStory {
    private final WorldSession session;
    private Set<String> memories=Set.of();
    private Map<String,OpeningCompletion> completed=Map.of();
    private Map<String,KnightObjective.State> objectives=Map.of();
    private String missionObjective;
    private OpeningMission arrivalComlink;
    private OpeningMission deliveredComlink;
    private long missionDialogCreated;

    SessionStory(WorldSession session) {
        this.session = session;
    }

    Set<String> memories() {
        return memories;
    }

    void memories(Set<String> value) {
        memories = value;
    }

    Map<String,KnightObjective.State> objectives() {
        return objectives;
    }

    void objectives(Map<String,KnightObjective.State> value) {
        objectives = value;
    }

    Set<String> completedKeys() {
        return completed.keySet();
    }

    boolean completedEmpty() {
        return completed.isEmpty();
    }

    Collection<OpeningCompletion> completedValues() {
        return completed.values();
    }

    void completed(Map<String,OpeningCompletion> value) {
        completed = value;
    }

    boolean isCompleted(String mission,OpeningCompletion completion) {
        return completed.containsKey(mission) && completion.equals(completed.get(mission));
    }

    void recordCompletion(String mission,OpeningCompletion completion) {
        if (isCompleted(mission,completion)) return;
        var next = new LinkedHashMap<>(completed);
        next.put(mission,completion);
        completed = Map.copyOf(next);
    }

    void load() throws IOException {
        completed = session.charactersStore().services().openingMissions(
            session.sceneRegistry().town(),session.sceneRegistry().missions())
            .load(session.charactersStore().accountId(),session.selectedKnight().id(),session.authRevision());
    }

    void applyMemories(Set<String> fresh) throws IOException {
        if (memories.equals(fresh)) return;
        var previous = memories;
        memories = fresh;
        session.output().send(() -> session.codec().knightMemoryChanges(session.connectionOid(),previous,memories));
    }

    void applyObjectives(Map<String,KnightObjective.State> fresh) throws IOException {
        if (objectives.equals(fresh)) return;
        objectives = fresh;
        publishObjectives();
    }

    void applySettlementStory(Set<String> freshMemories,Map<String,KnightObjective.State> freshObjectives) throws IOException {
        applyMemories(freshMemories);
        applyObjectives(freshObjectives);
    }

    long noteMissionDialog() {
        missionDialogCreated = session.conversations().touch();
        return missionDialogCreated;
    }

    long missionDialogCreated() {
        return missionDialogCreated;
    }

    void missionDialogCreated(long value) {
        missionDialogCreated = value;
    }

    OpeningMission arrivalComlink() {
        return arrivalComlink;
    }

    void arrivalComlink(OpeningMission value) {
        arrivalComlink = value;
    }

    OpeningMission deliveredComlink() {
        return deliveredComlink;
    }

    void deliveredComlink(OpeningMission value) {
        deliveredComlink = value;
    }

    SceneConversation comlink(String missionId) {
        var opening = OpeningMission.byId(missionId);
        if (opening != null && OpeningComlinks.get(opening) != null) return OpeningComlinks.get(opening);
        return session.sceneRegistry().comlinks().get(missionId);
    }

    private java.util.List<ObjectiveEntry> wireObjectives() {
        int next = 0;
        var result = new java.util.ArrayList<ObjectiveEntry>(objectives.size()+1);
        for (var value : objectives.values()) {
            result.add(new ObjectiveEntry(value.index(),value.name(),value.text(),ObjectiveEntry.KNIGHT));
            next = Math.max(next,value.index()+1);
        }
        // The HUD resolves the key through the default (projectx) bundle, so the mission
        // bullet names its own bundle explicitly, exactly like native mission strings.
        if (missionObjective != null)
            result.add(new ObjectiveEntry(next,missionObjective,"%mission:o."+missionObjective,ObjectiveEntry.PARTY));
        return result;
    }

    void publishObjectives() throws IOException {
        var wire = wireObjectives();
        session.output().send(() -> session.codec().objectives(session.connectionOid(),wire));
    }

    /** The mission bar follows the owned run: the imported mission's {@code o.<id>} objective
     *  while it is active, and nothing outside a mission. */
    void syncMissionObjective() throws IOException {
        var run = session.runtime().scene().run();
        String next = null;
        if (run != null) {
            var mission = session.sceneRegistry().missions().mission(run.missionId());
            if (mission != null && mission.requirement().hasObjective()) next = run.missionId();
        }
        if (!java.util.Objects.equals(next,missionObjective)) {
            missionObjective = next;
            publishObjectives();
        }
    }
}
