package org.tortodrone.core.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;
import org.tortodrone.core.world.SceneActions.Choice;
import org.tortodrone.core.world.SceneActions.ClearDialog;
import org.tortodrone.core.world.SceneActions.Conversation;
import org.tortodrone.core.world.SceneActions.Count;
import org.tortodrone.core.world.SceneActions.DefaultEntrance;
import org.tortodrone.core.world.SceneActions.Delay;
import org.tortodrone.core.world.SceneActions.Destroy;
import org.tortodrone.core.world.SceneActions.DestroyMany;
import org.tortodrone.core.world.SceneActions.DestroySource;
import org.tortodrone.core.world.SceneActions.Failure;
import org.tortodrone.core.world.SceneActions.GameplayEffect;
import org.tortodrone.core.world.SceneActions.Impulse;
import org.tortodrone.core.world.SceneActions.Limited;
import org.tortodrone.core.world.SceneActions.Memory;
import org.tortodrone.core.world.SceneActions.NearestSignal;
import org.tortodrone.core.world.SceneActions.NextFloor;
import org.tortodrone.core.world.SceneActions.Objective;
import org.tortodrone.core.world.SceneActions.PawnStatus;
import org.tortodrone.core.world.SceneActions.ProgressEvent;
import org.tortodrone.core.world.SceneActions.Sequence;
import org.tortodrone.core.world.SceneActions.SetVariable;
import org.tortodrone.core.world.SceneActions.Signal;
import org.tortodrone.core.world.SceneActions.SourceEffect;
import org.tortodrone.core.world.SceneActions.SourceSignal;
import org.tortodrone.core.world.SceneActions.Spawn;
import org.tortodrone.core.world.SceneActions.Targeted;
import org.tortodrone.core.world.SceneActions.Timer;
import org.tortodrone.core.world.SceneActions.VariableCondition;

/** Tick-budgeted execution engine for one scene's authored action graphs. Owns the timer
 *  queue, ordering counter, signal-lifetime latches and every mutable sink; {@link SceneActions}
 *  owns the action vocabulary, validation and wire/JSON decoding. All state and ordering here
 *  are unchanged from the former in-class engine. */
final class SceneActionRunner {
    static final class Node {
        final SceneActions.Action action;
        final List<Node> children;
        int remaining;
        boolean started;

        Node(SceneActions.Action action) {
            this.action = action;
            children = switch (action) {
                case Delay delay -> List.of(new Node(delay.action()));
                case Sequence sequence ->
                    sequence.actions().stream().map(Node::new).toList();
                case Choice choice -> choice.actions().stream().map(Node::new).toList();
                case Limited limited -> {
                    remaining = limited.limit();
                    yield List.of(new Node(limited.action()), new Node(limited.otherwise()));
                }
                case Count counter -> {
                    remaining = counter.threshold();
                    yield List.of(new Node(counter.action()));
                }
                case VariableCondition condition ->
                    List.of(new Node(condition.action()), new Node(condition.otherwise()));
                case Timer timer -> {
                    remaining = timer.limit();
                    yield List.of(new Node(timer.action()));
                }
                case Targeted targeted -> List.of(new Node(targeted.action()));
                default -> List.of();
            };
        }
    }

    private static final class State {
        final SceneActions.Rule definition;
        final Node root;
        final Map<String, Boolean> active = new LinkedHashMap<>();
        final Set<String> removed = new HashSet<>();

        State(SceneActions.Rule rule) {
            definition = rule;
            root = new Node(rule.action());
        }
    }

    private record Compiled(SceneActions.Rule rule, State state) {}

    private record Pending(long due, long order, Node node, String source, String activator) {}

    private static final int MAX_PENDING = 4096, MAX_PER_TICK = 1024;

    private final List<Compiled> rules;
    private final List<State> states;
    private final SceneActions.SourcedSignals signals;
    private final SceneActions.DestroyActors destroy;
    private final SceneActions.SourcedSpawnActors spawn;
    private final SceneActions.PawnStatuses pawnStatuses;
    private final SceneActions.Conversations conversations;
    private final SceneActions.Effects effects;
    private final SceneActions.FloorAdvances floorAdvances;
    private final SceneActions.Memories memories;
    private final RandomGenerator random;
    private final PriorityQueue<Pending> pending =
            new PriorityQueue<>(Comparator.comparingLong(Pending::due).thenComparingLong(Pending::order));
    private long order, rejected;
    private int activeSources;
    private boolean closed, advanced;
    private int lastAdvanced;

    private SceneActions.TargetResolution targetResolution = (target, stamp, source, activator) -> List.of();
    private SceneActions.SourceSignals sourceSignals = (name, stamp, source) -> false;
    private SceneActions.Entrances entrances = (target, stamp, source, activator) -> false;
    private SceneActions.SourceDestruction sourceDestruction = (stamp, source) -> false;
    private SceneActions.Variables variables = (operation, stamp, source, activator) -> false;
    private SceneActions.VariableReads variableReads = (name, target, stamp, source, activator) -> false;
    private SceneActions.DialogClears dialogClears = (only, stamp, source, activator) -> false;
    private SceneActions.GameplayEffects gameplayEffects = (effect, stamp, source) -> false;
    private SceneActions.Impulses impulses = (impulse, stamp, source) -> false;
    private SceneActions.NearestSignals nearestSignals = (signal, stamp, source) -> false;
    private SceneActions.Objectives objectives = (operation, stamp, source, activator) -> false;

    SceneActionRunner(
            List<SceneActions.Rule> rules,
            SceneActions.SourcedSignals signals,
            RandomGenerator random,
            SceneActions.DestroyActors destroy,
            SceneActions.SourcedSpawnActors spawn,
            SceneActions.PawnStatuses pawnStatuses,
            SceneActions.Conversations conversations,
            SceneActions.Effects effects,
            SceneActions.FloorAdvances floorAdvances,
            SceneActions.Memories memories) {
        this.memories = Objects.requireNonNull(memories);
        this.floorAdvances = Objects.requireNonNull(floorAdvances);
        this.effects = Objects.requireNonNull(effects);
        this.conversations = Objects.requireNonNull(conversations);
        this.pawnStatuses = Objects.requireNonNull(pawnStatuses);
        this.spawn = Objects.requireNonNull(spawn);
        this.destroy = Objects.requireNonNull(destroy);
        this.random = Objects.requireNonNull(random);
        this.signals = Objects.requireNonNull(signals);
        if (rules.size() > 2048) throw new IllegalArgumentException("Too many scene handlers");
        var keys = new HashSet<String>();
        for (var rule : rules) {
            if (!keys.add(rule.key())) throw new IllegalArgumentException("Duplicate scene handler");
            SceneActions.validate(rule.action(), 0, new int[] {0});
        }
        var shared = new LinkedHashMap<String, State>();
        var compiled = new ArrayList<Compiled>();
        for (var rule : rules) {
            var state = shared.computeIfAbsent(rule.stateKey(), key -> new State(rule));
            var original = state.definition;
            if (!original.event().equals(rule.event())
                    || !original.name().equals(rule.name())
                    || original.all() != rule.all()
                    || !original.source().equals(rule.source())
                    || !original.action().equals(rule.action())
                    || !original.removedGroup().equals(rule.removedGroup()))
                throw new IllegalArgumentException("Conflicting scene handler aliases");
            compiled.add(new Compiled(rule, state));
        }
        this.rules = List.copyOf(compiled);
        states = List.copyOf(shared.values());
    }

    void targets(SceneActions.TargetResolution sink) {
        targetResolution = Objects.requireNonNull(sink);
    }

    void sourceSignals(SceneActions.SourceSignals sink) {
        sourceSignals = Objects.requireNonNull(sink);
    }

    void entrances(SceneActions.Entrances sink) {
        entrances = Objects.requireNonNull(sink);
    }

    void sourceDestruction(SceneActions.SourceDestruction sink) {
        sourceDestruction = Objects.requireNonNull(sink);
    }

    void variables(SceneActions.Variables writes, SceneActions.VariableReads reads) {
        variables = Objects.requireNonNull(writes);
        variableReads = Objects.requireNonNull(reads);
    }

    void dialogClears(SceneActions.DialogClears sink) {
        dialogClears = Objects.requireNonNull(sink);
    }

    void gameplayEffects(SceneActions.GameplayEffects sink) {
        gameplayEffects = Objects.requireNonNull(sink);
    }

    void impulses(SceneActions.Impulses sink) {
        impulses = Objects.requireNonNull(sink);
    }

    void nearestSignals(SceneActions.NearestSignals sink) {
        nearestSignals = Objects.requireNonNull(sink);
    }

    void objectives(SceneActions.Objectives sink) {
        objectives = Objects.requireNonNull(sink);
    }

    void removed(Set<String> tags, Predicate<String> remaining, int stamp) {
        removed(tags, remaining, stamp, "removed");
    }

    void removed(Set<String> tags, Predicate<String> remaining, int stamp, String activator) {
        if (closed) return;
        for (var compiled : rules) {
            var rule = compiled.rule();
            if (!rule.event().equals("removed")
                    || !tags.contains(rule.tag())
                    || rule.all() && remaining.test(rule.tag())) continue;
            // Native two-tag marker: each ActorRemoved(Limit(1)) increments its
            // shared counter once; VariableChanged runs the action at counter=2.
            // A cleared wave stays latched even if that tag is spawned again.
            var state = compiled.state();
            if (!rule.removedGroup().isEmpty()
                    && (!state.removed.add(rule.tag()) || !state.removed.containsAll(rule.removedGroup()))) continue;
            enqueue(state.root, stamp, rule.source(), activator);
        }
    }

    void signal(String tag, String name, int stamp) {
        signal(tag, name, stamp, "external");
    }

    void signal(String tag, String name, int stamp, String source) {
        signalTo(tag, name, stamp, source, null);
    }
    /** Native subset selection addresses a receiver logic, not every logic sharing its tag. */
    void signalTo(String tag, String name, int stamp, String source, String receiver) {
        if (closed) return;
        SceneActions.text(source);
        for (var compiled : rules) {
            var rule = compiled.rule();
            var state = compiled.state();
            if (rule.event().equals("removed")
                    || !rule.tag().equals(tag)
                    || !rule.name().equals(name)
                    || receiver != null && !rule.source().equals(receiver)) continue;
            if (rule.event().equals("signal")) {
                enqueue(state.root, stamp, rule.source(), source);
                continue;
            }
            boolean already = state.active.containsKey(source);
            if (!already && activeSources >= MAX_PENDING) {
                rejected++;
                continue;
            }
            if (!already) activeSources++;
            state.active.put(source, true);
            if (!already && rule.event().equals("signal-start")) enqueue(state.root, stamp, rule.source(), source);
        }
    }
    /** Signal(Source) addresses each of the receiver's handlers once, regardless of tag aliases. */
    void signalSource(String receiver, String name, int stamp, String source) {
        if (closed) return;
        SceneActions.text(receiver);
        SceneActions.text(name);
        SceneActions.text(source);
        for (var state : states) {
            var rule = state.definition;
            if (rule.event().equals("removed")
                    || !rule.source().equals(receiver)
                    || !rule.name().equals(name)) continue;
            if (rule.event().equals("signal")) {
                enqueue(state.root, stamp, rule.source(), source);
                continue;
            }
            boolean already = state.active.containsKey(source);
            if (!already && activeSources >= MAX_PENDING) {
                rejected++;
                continue;
            }
            if (!already) activeSources++;
            state.active.put(source, true);
            if (!already && rule.event().equals("signal-start")) enqueue(state.root, stamp, rule.source(), source);
        }
    }
    /** Equal-time actions retain authored order; overdue work executes on the current scene tick. */
    void advance(int stamp) {
        if (closed) return;
        if (advanced && SceneClock.before(stamp, lastAdvanced))
            throw new IllegalArgumentException("Scene action clock moved backwards");
        int[] budget = {MAX_PER_TICK};
        drain(stamp, budget);
        if (!advanced || stamp != lastAdvanced) {
            for (var state : states)
                for (var iterator = state.active.entrySet().iterator(); iterator.hasNext(); ) {
                    var entry = iterator.next();
                    if (entry.getValue()) entry.setValue(false);
                    else {
                        iterator.remove();
                        activeSources--;
                        if (state.definition.event().equals("signal-stop"))
                            enqueue(state.root, stamp, state.definition.source(), entry.getKey());
                    }
                }
            advanced = true;
            lastAdvanced = stamp;
            drain(stamp, budget);
        }
    }

    private void drain(int stamp, int[] budget) {
        while (!pending.isEmpty() && pending.peek().due() <= stamp && budget[0] > 0) {
            var task = pending.remove();
            execute(task.node(), stamp, task.source(), task.activator(), budget);
        }
    }

    private boolean execute(Node node, long due, String source, String activator, int[] budget) {
        if (--budget[0] < 0) {
            rejected++;
            return false;
        }
        return switch (node.action) {
            case Signal signal -> signals.apply(signal.tag(), signal.name(), Math.toIntExact(due), source);
            case SourceSignal signal -> sourceSignals.signal(signal.name(), Math.toIntExact(due), source);
            case Failure ignored -> false;
            case Count ignored -> {
                // Saturate after crossing the threshold; only equality runs the child.
                // Native false Conditional succeeds, and a failed child does not undo Increment.
                if (node.remaining < 0) yield true;
                yield --node.remaining == 0 ? execute(node.children.getFirst(), due, source, activator, budget) : true;
            }
            case DefaultEntrance entrance -> entrances.set(entrance.target(), Math.toIntExact(due), source, activator);
            case Targeted targeted -> {
                var targets = targetResolution.resolve(targeted.target(), Math.toIntExact(due), source, activator);
                if (targets.size() > 256) {
                    rejected++;
                    yield false;
                }
                // TargetedAction executes in target order, ignores each child's result
                // and succeeds even when its target set is empty (native ActionLogic).
                for (String target : targets) {
                    SceneActions.text(target);
                    execute(node.children.getFirst(), due, source, target, budget);
                }
                yield true;
            }
            case NearestSignal signal -> nearestSignals.apply(signal, Math.toIntExact(due), source);
            case Conversation conversation ->
                conversations.apply(conversation, Math.toIntExact(due), source, activator);
            case ClearDialog clear ->
                dialogClears.clear(clear.activatorOnly(), Math.toIntExact(due), source, activator);
            case SetVariable variable -> variables.set(variable, Math.toIntExact(due), source, activator);
            case VariableCondition condition ->
                execute(
                        node.children.get(
                                variableReads.get(
                                                condition.name(),
                                                condition.target(),
                                                Math.toIntExact(due),
                                                source,
                                                activator)
                                        ? 0
                                        : 1),
                        due,
                        source,
                        activator,
                        budget);
            case Timer timer -> {
                if (!node.started) {
                    node.started = true;
                    yield enqueue(
                            node,
                            Math.addExact(due, Math.max(0, timer.interval() + timer.offset())),
                            source,
                            activator);
                }
                boolean success = execute(node.children.getFirst(), due, source, activator, budget);
                if (timer.limit() == 0 || --node.remaining > 0)
                    success = enqueue(node, Math.addExact(due, timer.interval()), source, activator) && success;
                yield success;
            }
            case SourceEffect effect -> effects.apply(effect, Math.toIntExact(due), source);
            case GameplayEffect effect -> gameplayEffects.apply(effect, Math.toIntExact(due), source);
            case Impulse impulse -> impulses.apply(impulse, Math.toIntExact(due), source);
            case PawnStatus status -> pawnStatuses.apply(status, Math.toIntExact(due), source);
            case Spawn actor -> spawn.apply(actor, Math.toIntExact(due), source, activator);
            case ProgressEvent event -> {
                System.getLogger(SceneActions.class.getName())
                        .log(
                                System.Logger.Level.DEBUG,
                                "Scene analytics: level={0}, progress={1}, stamp={2}, source={3}",
                                event.level(),
                                event.progress(),
                                due,
                                source);
                yield true;
            }
            case NextFloor ignored -> floorAdvances.apply(Math.toIntExact(due), source, activator);
            case Memory memory -> memories.apply(memory.mutation(), Math.toIntExact(due), source, activator);
            case Objective objective -> objectives.apply(objective.mutation(), Math.toIntExact(due), source, activator);
            case Destroy destruction -> destroy.apply(destruction.tag(), Math.toIntExact(due), source);
            case DestroySource ignored -> sourceDestruction.destroy(Math.toIntExact(due), source);
            case DestroyMany destruction -> {
                boolean found = false;
                for (String tag : destruction.tags()) found = destroy.apply(tag, Math.toIntExact(due), source) | found;
                yield found;
            }
            case Delay delay ->
                enqueue(
                        node.children.getFirst(),
                        Math.addExact(
                                due,
                                delay.variance() == 0
                                        ? delay.millis()
                                        : Math.max(
                                                0,
                                                delay.millis()
                                                        + random.nextInt(delay.variance() * 2)
                                                        - delay.variance())),
                        source,
                        activator);
            case Sequence sequence -> {
                boolean success = true;
                for (var child : node.children)
                    if (!execute(child, due, source, activator, budget)) {
                        success = false;
                        if (sequence.stopOnFailure()) break;
                    }
                yield success;
            }
            case Limited limited -> {
                boolean allowed = node.remaining > 0;
                if (allowed) node.remaining--;
                yield execute(node.children.get(allowed ? 0 : 1), due, source, activator, budget);
            }
            case Choice choice -> {
                double
                        total =
                                choice.weights().stream()
                                        .mapToDouble(Float::doubleValue)
                                        .sum(),
                        draw = random.nextDouble(total);
                int selected = -1;
                for (int i = 0; i < choice.weights().size(); i++)
                    if (choice.weights().get(i) > 0) {
                        selected = i;
                        draw -= choice.weights().get(i);
                        if (draw < 0) break;
                    }
                yield execute(node.children.get(selected), due, source, activator, budget);
            }
        };
    }

    private boolean enqueue(Node node, long due, String source, String activator) {
        if (closed || pending.size() >= MAX_PENDING || due > Integer.MAX_VALUE) {
            rejected++;
            return false;
        }
        pending.add(new Pending(due, order++, node, source, activator));
        return true;
    }

    boolean perform(Node node, int stamp, String source, String activator) {
        SceneActions.text(source);
        SceneActions.text(activator);
        return enqueue(node, stamp, source, activator);
    }

    boolean perform(SceneActions.Action action, int stamp, String source, String activator) {
        SceneActions.text(source);
        SceneActions.text(activator);
        SceneActions.validate(action, 0, new int[] {0});
        return enqueue(new Node(action), stamp, source, activator);
    }

    int pending() {
        return pending.size();
    }

    void cancelSource(String source) {
        SceneActions.text(source);
        pending.removeIf(task -> task.source().equals(source));
        for (var state : states) if (state.active.remove(source) != null) activeSources--;
    }

    long rejected() {
        return rejected;
    }

    Set<String> workSources() {
        var result = new HashSet<String>();
        if (closed) return result;
        for (var p : pending) {
            result.add(p.source());
            result.add(p.activator());
        }
        for (var state : states) result.addAll(state.active.keySet());
        return result;
    }

    void close() {
        closed = true;
        pending.clear();
        for (var state : states) {
            state.active.clear();
            state.removed.clear();
        }
        activeSources = 0;
    }
}
