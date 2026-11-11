package org.tortodrone.server;

import java.io.IOException;
import java.util.*;
import static org.tortodrone.support.TestWire.check;

/** Owned API shapes: no stock classes, installed resources or preferences. */
public final class ClientArtworkApiTest {
    public static void main(String[] args) throws Exception {
        check(ClientArtworkApi.groupAccessor(Renamed.class, Group.class, Base.class).getName().equals("renamed"), "Renamed exact group, not array/raw/static decoys");
        check(ClientArtworkApi.namedConfig(Renamed.class, Base.class).getName().equals("lookup"), "Generic lookup, not base-returning lookup");
        check(ClientArtworkApi.typedValues(Group.class, Base.class).getName().equals("resolved"), "Typed values, not raw base values");
        check(ClientArtworkApi.method(Renamed.class, "setter", false, void.class, String.class).getName().equals("changed"), "Renamed instance setter, not static/other-result decoys");
        var bridge = ClientArtworkApi.method(Bridge.class, "factory", false, Value.class);
        check(ClientArtworkApi.invoke(bridge, new Bridge()) instanceof Value, "Bridge method excluded; checked invocation works");
        reject(() -> ClientArtworkApi.method(Bridge.class, "erased bridge", false, Object.class), "found 0");
        reject(() -> ClientArtworkApi.groupAccessor(Ambiguous.class, Group.class, Base.class), "found 2");
        reject(() -> ClientArtworkApi.groupAccessor(RawLookup.class, Group.class, Base.class), "found 0");
        reject(() -> ClientArtworkApi.groupAccessor(WrongBound.class, Group.class, Base.class), "found 0");
        reject(() -> ClientArtworkApi.namedConfig(BaseLookup.class, Base.class), "found 0");
        reject(() -> ClientArtworkApi.typedValues(RawValues.class, Base.class), "found 0");
        reject(() -> ClientArtworkApi.typedValues(AmbiguousValues.class, Base.class), "found 2");
        reject(() -> ClientArtworkApi.method(AmbiguousSetter.class, "setter", false, void.class, String.class), "found 2");
        reject(() -> ClientArtworkApi.method(Renamed.class, "missing", false, int.class, String.class), "found 0");
        reject(() -> ClientArtworkApi.invoke(ClientArtworkApi.method(Failing.class, "failure", false, Value.class), new Failing()), "operation failed");
        System.out.println("PASS: artwork API renaming, generic identity, typed/raw iteration, static/bridge filtering, missing and ambiguous rejection");
    }
    @FunctionalInterface private interface Attempt { void run() throws Exception; }
    private static void reject(Attempt attempt, String message) throws Exception {
        try { attempt.run(); throw new AssertionError("Unsupported API accepted"); }
        catch (IOException expected) { check(expected.getMessage().contains(message), "Useful checked-signature diagnostic: " + expected); }
    }
    public static class Base { }
    public static class Group<T extends Base> {
        public Iterable<T> resolved() { return List.of(); }
        public Iterable<Base> rawEntries() { return List.of(); }
    }
    public static class Renamed {
        public <T extends Base> Group<T> renamed(Class<T> type) { return new Group<>(); }
        public <T extends Base> Group<T>[] arrays(Class<T> type) { return null; }
        @SuppressWarnings("rawtypes") public Group raw(Class type) { return null; }
        public static <T extends Base> Group<T> staticGroup(Class<T> type) { return null; }
        public <T extends Base> T lookup(Class<T> type, String name) { return null; }
        public Base baseLookup(Class<? extends Base> type, String name) { return null; }
        public void changed(String path) { }
        public static void staticSetter(String path) { }
        public String otherResult(String path) { return path; }
    }
    public static class Ambiguous extends Renamed { public <T extends Base> Group<T> duplicate(Class<T> type) { return null; } }
    public static class RawLookup { @SuppressWarnings("rawtypes") public Group raw(Class type) { return null; } }
    public static class WrongBound { public <T extends Base & Runnable> Group<T> wrong(Class<T> type) { return null; } }
    public static class BaseLookup { public Base lookup(Class<? extends Base> type, String name) { return null; } }
    public static class RawValues<T extends Base> { public Iterable<Base> entries() { return List.of(); } }
    public static class AmbiguousValues<T extends Base> {
        public Iterable<T> first() { return List.of(); }
        public Iterable<T> second() { return List.of(); }
    }
    public static class AmbiguousSetter extends Renamed { public void duplicate(String path) { } }
    public interface Factory<T> { T make(); }
    public static class Value { }
    public static class Bridge implements Factory<Value> { public Value make() { return new Value(); } }
    public static class Failing { public Value failure() { throw new IllegalStateException("fixture failure"); } }
}
