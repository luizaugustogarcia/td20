package br.unb.cic.td20.verification;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Independent checker-owned scratch storage; certificate databases remain read-only. */
final class CheckerStateSet implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CheckerStateSet.class);
    private final CheckerStateStorage storage;
    private final Path directory;
    record Inventory(long count) { }
    CheckerStateSet(Path directory) {
        this.directory = directory;
        // Storage creates this directory exclusively, so it belongs to this run.
        storage = new CheckerStateStorage(directory);
    }
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
    /** Called only after the exhaustive source scan and its callbacks have joined. */
    void retireAfter(CheckerStateSet successor) {
        if (successor == this) throw new IllegalArgumentException("A scratch wave cannot replace itself");
        // A failed downstream generation or writer keeps the preceding boundary.
        successor.seal();
        retire();
    }
    /** Explicit successful-boundary cleanup; ordinary failure unwinding only closes. */
    void retire() {
        storage.close();
        long[] removed = new long[2];
        try {
            // The default traversal never follows symlinks outside this owned directory.
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    removed[0]++;
                    removed[1] += attributes.size();
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path path, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    Files.delete(path);
                    return FileVisitResult.CONTINUE;
                }
            });
            log.info("Reclaimed completed checker scratch wave {}: files={}, logicalFileBytes={}",
                    directory.getFileName(), removed[0], removed[1]);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot reclaim completed checker scratch wave: " + directory, failure);
        }
    }
    @Override public void close() { storage.close(); }
}
