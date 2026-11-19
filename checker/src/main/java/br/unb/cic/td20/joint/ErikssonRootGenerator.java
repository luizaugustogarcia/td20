package br.unb.cic.td20.joint;
import br.unb.cic.td20.model.CyclicTargetPair;
import br.unb.cic.td20.model.PrimitivePair;
import br.unb.cic.td20.compat.LegacyPairs;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** Independent rank placement, arbitrary support predicates, and two-cycle move replay. */
public final class ErikssonRootGenerator {
    private ErikssonRootGenerator() { }
    public record Counts(long unoriented,long oriented,long supportCleared,long prefixNodes,long realizableLeaves) {
        public long direct() { return Math.subtractExact(Math.addExact(unoriented,oriented),supportCleared); }
        Counts plus(Counts other) { return new Counts(Math.addExact(unoriented,other.unoriented),Math.addExact(oriented,other.oriented),
                Math.addExact(supportCleared,other.supportCleared),Math.addExact(prefixNodes,other.prefixNodes),Math.addExact(realizableLeaves,other.realizableLeaves)); }
    }
    public static Counts count(int[] partition,int workers,LongAdder progress) throws Exception {
        return execute(partition,workers,()->{},null,progress);
    }
    /** Operational counters; none controls generation or certificate coverage. */
    public static final class Progress {
        private final LongAdder nodes = new LongAdder(), completedPrefixes = new LongAdder();
        private volatile long discoveredPrefixes;
        public long prefixNodes() { return nodes.sum(); }
        public long completedPrefixes() { return completedPrefixes.sum(); }
        public long discoveredPrefixes() { return discoveredPrefixes; }
    }
    public static Counts generate(int[] partition,int workers,Runnable cancellation,Consumer<CyclicTargetPair> output) throws Exception {
        return generatePrimitive(partition,workers,cancellation,pair -> output.accept(LegacyPairs.toLegacy(pair).canonicalRepresentative()));
    }
    public static Counts generatePrimitive(int[] partition, int workers, Runnable cancellation,
                                           Consumer<PrimitivePair> output) throws Exception {
        return execute(partition, workers, cancellation, output, new LongAdder());
    }
    private static Counts execute(int[] partition,int workers,Runnable cancellation,Consumer<PrimitivePair> output,LongAdder progress) throws Exception {
        return execute(partition, workers, cancellation, output, progress, null);
    }
    public static Counts generatePrimitive(int[] partition, int workers, Runnable cancellation,
                                           Consumer<PrimitivePair> output, Progress progress) throws Exception {
        return execute(partition, workers, cancellation, output, progress.nodes, progress);
    }
    private static Counts execute(int[] partition,int workers,Runnable cancellation,Consumer<PrimitivePair> output,LongAdder progress,Progress operational) throws Exception {
        if (workers<1) throw new IllegalArgumentException("Positive worker count required");
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Runnable check=()->{ if (failure.get()!=null) throw new CancellationException("Regeneration failed: "+failure.get()); cancellation.run(); };
        Enumeration template=new Enumeration(partition,check,output); template.progress=progress;
        var pool=new ThreadPoolExecutor(workers,workers,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(Math.max(256,workers*16)),
                Thread.ofPlatform().daemon(true).name("independent-eriksson-",0).factory(),new ThreadPoolExecutor.CallerRunsPolicy());
        List<Future<Counts>> jobs=new ArrayList<>();
        try {
            template.frontierDepth=Math.min(5,template.n-1);
            template.frontier=branch -> {
                if (operational != null) operational.discoveredPrefixes++;
                jobs.add(pool.submit(()->{
                    try { Enumeration worker=new Enumeration(template,branch); worker.place(branch.rank(),branch.used()); return worker.result(); }
                    catch (RuntimeException|Error error) { failure.compareAndSet(null,error); throw error; }
                    finally { if (operational != null) operational.completedPrefixes.increment(); }
                }));
            };
            template.run(); Counts result=template.result();
            for (var job:jobs) result=result.plus(job.get());
            check.run(); return result;
        } finally {
            pool.shutdownNow();
            boolean interrupted=Thread.interrupted();
            for (;;) { try { if (pool.awaitTermination(1,TimeUnit.SECONDS)) break; } catch (InterruptedException error) { interrupted=true; } }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    private record Triple(int a, int b, int c) {
        int depth() { return Math.max(a, Math.max(b, c)); }
    }
    private record Rule(Triple first, Triple second, List<Triple> thirds) { }
    private record Branch(int rank, int used, int[] position, int[] image, int[] inverse,
                          int[] target, int edges) { }
    private static final class Enumeration {
        final int n;
        final int[] parts, algebraic, predecessor, cycleOrigin, cycleLength, equalOrigin;
        final int[] position, image, inverse, target;
        final List<Triple>[] immediate;
        final List<Rule>[] joint;
        final byte[] orders, transformed;
        final Runnable cancellationCheck;
        final Consumer<PrimitivePair> consumer;
        int edges;
        long unoriented,oriented,cleared,nodes,leaves;
        LongAdder progress=new LongAdder();
        int frontierDepth;
        Consumer<Branch> frontier;
        @SuppressWarnings("unchecked")
        Enumeration(final int[] partition, final Runnable cancellationCheck,
                    final Consumer<PrimitivePair> consumer) {
            this.parts = partition.clone(); Arrays.sort(parts);
            for (int i = 0; i < parts.length / 2; i++) {
                final int value = parts[i]; parts[i] = parts[parts.length - 1 - i]; parts[parts.length - 1 - i] = value;
            }
            n = Arrays.stream(parts).sum();
            if (n < 3 || n > ErikssonRoute.ROOT_SYMBOLS || parts.length == 0 || parts[parts.length - 1] < 2
                    || Arrays.stream(parts).filter(x -> x % 2 == 0).count() % 2 != 0)
                throw new IllegalArgumentException("Expected an even fixed-point-free partition of 3.."+KnownDiameters.ROOT_SYMBOLS+" symbols");

            this.cancellationCheck = cancellationCheck; this.consumer = consumer;
            algebraic = new int[n]; predecessor = new int[n]; cycleOrigin = new int[n]; cycleLength = new int[n];
            equalOrigin = new int[n]; Arrays.fill(equalOrigin, -1);
            position = new int[n]; image = new int[n]; inverse = new int[n]; target = new int[n];
            Arrays.fill(image, -1); Arrays.fill(inverse, -1); Arrays.fill(target, -1);
            immediate = new List[n]; joint = new List[n];
            for (int i = 0; i < n; i++) { immediate[i] = new ArrayList<>(); joint[i] = new ArrayList<>(); }
            int origin = 0;
            final int[] precedingByLength = new int[n + 1];
            Arrays.fill(precedingByLength, -1);
            for (final int length : parts) {
                equalOrigin[origin] = precedingByLength[length];
                for (int r = 0; r < length; r++) {
                    algebraic[origin + r] = origin + (r + 1) % length;
                    cycleOrigin[origin + r] = origin; cycleLength[origin + r] = length;
                }
                precedingByLength[length] = origin;
                origin += length;
            }
            for (int i = 0; i < n; i++) predecessor[algebraic[i]] = i;
            final var supports = new ArrayList<Triple>();
            for (int a = 0; a < n - 2; a++) for (int b = a + 1; b < n - 1; b++)
                for (int c = b + 1; c < n; c++) { supports.add(new Triple(a,b,c)); supports.add(new Triple(a,c,b)); }
            for (final var first:supports) {
                int gain1=CyclicTargetMoves.gain(algebraic,first.a,first.b,first.c);
                if (gain1>=2) { immediate[first.depth()].add(first); continue; }
                int[] q=CyclicTargetMoves.apply(algebraic,first.a,first.b,first.c);
                for (final var second:supports) {
                    if (q[second.a]==second.a || q[second.b]==second.b || q[second.c]==second.c) continue;
                    int gain2=CyclicTargetMoves.gain(q,second.a,second.b,second.c);
                    if (gain1+gain2<3) continue;
                    int depth=Math.max(first.depth(),second.depth());
                    if (gain1+gain2>=4) { joint[depth].add(new Rule(first,second,List.of())); continue; }
                    int[] r=CyclicTargetMoves.apply(q,second.a,second.b,second.c);
                    final List<Triple>[] byDepth=new List[n];
                    for (int d=0;d<n;d++) byDepth[d]=new ArrayList<>();
                    for (final var third:supports) {
                        if (r[third.a]==third.a || r[third.b]==third.b || r[third.c]==third.c) continue;
                        if (CyclicTargetMoves.gain(r,third.a,third.b,third.c)>=2)
                            byDepth[Math.max(depth,third.depth())].add(third);
                    }
                    for (int d=0;d<n;d++) if (!byDepth[d].isEmpty()) joint[d].add(new Rule(first,second,List.copyOf(byDepth[d])));
                }
            }
            orders = new byte[n * n * n]; transformed = new byte[n * n * n * n];
            for (int a = 0; a < n; a++) for (int b = 0; b < n; b++) for (int c = 0; c < n; c++) {
                final int key = triple(a,b,c);
                if (a == b || b == c || c == a || Math.floorMod(b-a,n) >= Math.floorMod(c-a,n)) continue;
                orders[key] = 1;
                // Build the actual current-cycle writing after the transposition.
                // Rotate to a, then swap the first two cut segments.
                final int ab = Math.floorMod(b-a,n), ac = Math.floorMod(c-a,n);
                final int[] writing = new int[n];
                int offset = 0;
                for (int i = ab; i < ac; i++) writing[offset++] = (a+i)%n;
                for (int i = 0; i < ab; i++) writing[offset++] = (a+i)%n;
                for (int i = ac; i < n; i++) writing[offset++] = (a+i)%n;
                for (int i = 0; i < n; i++) transformed[key*n + writing[i]] = (byte)i;
            }
        }
        Enumeration(final Enumeration template, final Branch branch) {
            n = template.n; parts = template.parts; algebraic = template.algebraic;
            predecessor = template.predecessor; cycleOrigin = template.cycleOrigin;
            cycleLength = template.cycleLength; equalOrigin = template.equalOrigin;
            immediate = template.immediate; joint = template.joint; orders = template.orders;
            transformed = template.transformed; cancellationCheck = template.cancellationCheck;
            consumer = template.consumer; progress=template.progress;
            position = branch.position().clone(); image = branch.image().clone();
            inverse = branch.inverse().clone(); target = branch.target().clone();
            edges = branch.edges();
        }
        int triple(final int a, final int b, final int c) { return (a*n+b)*n+c; }
        boolean ordered(final Triple t) { return orders[triple(position[t.a], position[t.b], position[t.c])] != 0; }
        boolean predicates(final int depth) {

            for (final var first : immediate[depth]) if (ordered(first)) return false;
            for (final var rule:joint[depth]) {
                int first=triple(position[rule.first.a],position[rule.first.b],position[rule.first.c]);
                if (orders[first]==0) continue;
                int offset=first*n;
                int second=triple(transformed[offset+position[rule.second.a]],transformed[offset+position[rule.second.b]],transformed[offset+position[rule.second.c]]);
                if (orders[second]==0) continue;
                if (rule.thirds.isEmpty()) return false;
                int next=second*n;
                for (var third:rule.thirds) if (orders[triple(
                        transformed[next+transformed[offset+position[third.a]]],
                        transformed[next+transformed[offset+position[third.b]]],
                        transformed[next+transformed[offset+position[third.c]]])]!=0) return false;
            }
            return true;
        }
        Counts result() { return new Counts(unoriented,oriented,cleared,nodes,leaves); }
        void run() { position[0] = 0; place(1,1); }
        void place(final int rank, final int used) {
            if ((++nodes&1023)==0) { checkCancellation(); progress.add(1024); }
            if (frontier != null && rank == frontierDepth) {
                frontier.accept(new Branch(rank, used, position.clone(), image.clone(), inverse.clone(),
                        target.clone(), edges));
                return;
            }
            if (rank == n) {
                leaves++;
                if (ErikssonMoves.closesOrHasPrefix(image,CyclicTargetMoves.identity(n))) return;
                byte[] successors = new byte[n];
                for (int x=0;x<n;x++) successors[x]=(byte)image[x];
                // This recurrence minimizes image vectors, while certificates minimize encoding words.
                var pair=PrimitivePair.fromNormalizedImages(successors,false);
                if (CyclicTargetMoves.totallyUnoriented(image,CyclicTargetMoves.identity(n))) unoriented++;
                else { oriented++; if (n==ErikssonRoute.ROOT_SYMBOLS && SupportClearing.closes(pair)) cleared++; }
                if (consumer!=null) consumer.accept(pair);
                return;
            }
            final int minimum = cycleOrigin[rank] != rank ? position[cycleOrigin[rank]]+1
                    : equalOrigin[rank] >= 0 ? position[equalOrigin[rank]]+1 : 1;
            for (int value = minimum; value < n; value++) {
                if ((used & (1<<value)) != 0) continue;
                position[rank] = value;
                final boolean firstEdge = algebraic[rank] <= rank;
                final boolean secondEdge = predecessor[rank] < rank;
                boolean okay = true, secondAdded = false;
                if (firstEdge) okay = addEdge(rank);
                if (okay && secondEdge) { okay = addEdge(predecessor[rank]); secondAdded = true; }
                if (okay && canonicalPrefix(rank) && predicates(rank)) place(rank+1, used|(1<<value));
                if (secondAdded) removeEdge(predecessor[rank]);
                if (firstEdge) removeEdge(rank);
            }
        }
        void checkCancellation() {
            if (Thread.currentThread().isInterrupted())
                throw new CancellationException("Interrupted root regeneration");
            cancellationCheck.run();
        }
        boolean addEdge(final int rank) {
            final int from = position[rank], to = position[algebraic[rank]];
            image[from] = to; inverse[to] = from;
            final int source = (from+n-1)%n;
            target[source] = to; edges++;
            int cursor = to;
            while (cursor != source && target[cursor] >= 0) cursor = target[cursor];
            return cursor != source || edges == n;
        }
        void removeEdge(final int rank) {
            final int from = position[rank], to = position[algebraic[rank]];
            image[from] = -1; inverse[to] = -1; target[(from+n-1)%n] = -1; edges--;
        }
        boolean canonicalPrefix(final int depth) {
            for (int rank = 0; rank <= depth; rank++) {
                if (cycleLength[rank] != parts[0]) continue;
                final int anchor = position[rank];
                for (int reflection = 0; reflection < 2; reflection++) {
                    for (int i = 0; i < n; i++) {
                        if (image[i] < 0) break;
                        final int source = reflection == 0 ? (i+anchor)%n : Math.floorMod(anchor-i,n);
                        final int mapped = reflection == 0 ? image[source] : inverse[source];
                        if (mapped < 0) break;
                        final int value = reflection == 0 ? Math.floorMod(mapped-anchor,n) : Math.floorMod(anchor-mapped,n);
                        if (value < image[i]) return false;
                        if (value > image[i]) break;
                    }
                }
            }
            return true;
        }
    }
}
