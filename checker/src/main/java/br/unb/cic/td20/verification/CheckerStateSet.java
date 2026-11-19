package br.unb.cic.td20.verification;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** Independent checker-owned scratch storage; certificate databases remain read-only. */
final class CheckerStateSet implements AutoCloseable {
    private final CheckerStateStorage storage;
    record Inventory(long count) { }
    CheckerStateSet(Path directory) { storage = new CheckerStateStorage(directory); }
    void add(SearchState state) {
        storage.addGeneratedPairs(state.maximumMoves(), output -> output.accept(state.pair()));
    }
    void addGeneratedPrimitive(int maximumMoves, Consumer<br.unb.cic.td20.joint.PrimitiveInverseReconstruction.RawConsumer> generator) {
        storage.addGeneratedPrimitive(maximumMoves,generator);
    }
    Inventory seal() { return new Inventory(storage.seal()); }
    boolean contains(SearchState state) {
        return storage.contains(new CheckerStateStorage.State(state.pair(), state.maximumMoves()));
    }
    private static SearchState convert(CheckerStateStorage.State state) {
        return new SearchState(state.maximumMoves(), state.pair());
    }
    void forEachBatch(Consumer<List<SearchState>> consumer) {
        storage.forEachBatch(batch -> consumer.accept(batch.stream().map(CheckerStateSet::convert).toList()));
    }
    void forEachConcurrently(int workers, Consumer<SearchState> consumer) {
        storage.forEachConcurrently(workers, state -> consumer.accept(convert(state)));
    }
    void forEachConcurrently(int workers, Function<List<SearchState>, boolean[]> lookup,
                            Consumer<SearchState> present, Consumer<SearchState> missing) {
        storage.forEachConcurrently(workers,
                batch -> lookup.apply(batch.stream().map(CheckerStateSet::convert).toList()),
                state -> present.accept(convert(state)), state -> missing.accept(convert(state)));
    }
    @Override public void close() { storage.close(); }
}
