package br.unb.cic.td20.fleet;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded publications, including reused tasks in the mandatory complete-scope barrier. */
final class FleetTaskQueue {
    private final Map<String,FleetTask> expected = new HashMap<>();
    private final Set<String> reused;
    private final Set<String> published = ConcurrentHashMap.newKeySet();
    private final ArrayBlockingQueue<FleetTask> ready;
    // At most one failed task per computing server can await a retry.
    private final Queue<FleetTask> retries = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    FleetTaskQueue(List<FleetTask> tasks,Set<String> reused,int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Positive publication capacity required");
        for (var task : tasks) if (expected.put(task.id(),task) != null) throw new IllegalArgumentException("Duplicate expected task");
        if (!expected.keySet().containsAll(reused)) throw new IllegalArgumentException("Unknown reused task");
        this.reused = Set.copyOf(reused); ready = new ArrayBlockingQueue<>(capacity);
    }
    void publish(FleetTask task) {
        check();
        if (!task.equals(expected.get(task.id())) || !published.add(task.id())) throw new IllegalArgumentException("Unexpected or duplicate published task");
        if (reused.contains(task.id())) return;
        try { while (!ready.offer(task,250,TimeUnit.MILLISECONDS)) check(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("Publication interrupted",error); }
        check();
    }
    FleetTask poll() throws InterruptedException {
        check(); var task = retries.poll(); return task == null ? ready.poll(250,TimeUnit.MILLISECONDS) : task;
    }
    void retry(FleetTask task) { check(); retries.add(task); }
    void finish() {
        check();
        if (published.size() != expected.size()) throw new IllegalStateException("Missing reconstruction batch publications");
    }
    void abort(Throwable error) { failure.compareAndSet(null,error); }
    void check() { if (failure.get() != null) throw new IllegalStateException("Batch publication failed",failure.get()); }
}
