package br.unb.cic.td20.avoidance;

import br.unb.cic.td20.runtime.BoundedTasks;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.rocksdb.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Exact logical-key inventory over disjoint snapshot ranges; keys remain opaque. */
final class ParallelKeyCounter {
    private static final Logger log=LoggerFactory.getLogger(ParallelKeyCounter.class);
    private record Interval(byte[] start,byte[] end,long estimatedBytes) { }
    private ParallelKeyCounter() { }

    static long count(RocksDB database,int workers,int rangesPerWorker,long expected,String phase) {
        var snapshot=database.getSnapshot();
        if(snapshot==null) throw new IllegalStateException("Cannot acquire reconstruction inventory snapshot");
        try {
            log.info("{}: planning disjoint key ranges; configuredWorkers={}, rangesPerWorker={}",phase,workers,rangesPerWorker);
            var ranges=plan(database,snapshot,Math.multiplyExact(workers,rangesPerWorker));
            int effectiveWorkers=Math.min(workers,Math.max(1,ranges.size()));
            var completed=new LongAdder();
            var finishedRanges=new AtomicLong();
            var result=new AtomicLong();
            log.info("{}: workers={}, ranges={}, snapshotSequence={}",phase,effectiveWorkers,ranges.size(),snapshot.getSequenceNumber());
            try(var progress=new ReconstructionProgress(phase,expected,completed::sum,
                        () -> "workers="+effectiveWorkers+", rangesCompleted="+finishedRanges.get()+"/"+ranges.size());
                var tasks=new BoundedTasks(effectiveWorkers,"key-count-")) {
                for(var range:ranges) tasks.submit(() -> {
                    long count=countRange(database,snapshot,range,completed);
                    result.updateAndGet(value -> Math.addExact(value,count));
                    finishedRanges.incrementAndGet();
                });
            }
            long count=result.get();
            if(finishedRanges.get()!=ranges.size() || count!=completed.sum())
                throw new IllegalStateException("Incomplete reconstruction key-range count");
            if(expected>=0 && count!=expected)
                throw new IllegalStateException("Reconstruction scan missed states: expected "+expected+", counted "+count);
            log.info("{} complete: uniqueKeys={}, rangesCompleted={}/{}",phase,count,finishedRanges.get(),ranges.size());
            return count;
        } catch(RocksDBException failure) {
            throw new IllegalStateException("Cannot count reconstruction state keys",failure);
        } finally { database.releaseSnapshot(snapshot); }
    }

    private static List<Interval> plan(RocksDB database,Snapshot snapshot,int target) throws RocksDBException {
        byte[] last;
        try(var reads=new ReadOptions().setSnapshot(snapshot).setFillCache(false);
            var iterator=database.newIterator(reads)) {
            iterator.seekToLast();
            if(!iterator.isValid()) { iterator.status(); return List.of(); }
            last=iterator.key(); iterator.status();
        }
        // Appending a zero byte is strictly above the largest snapshot key.
        var end=Arrays.copyOf(last,last.length+1);
        var pending=new PriorityQueue<Interval>(Comparator.<Interval>comparingLong(Interval::estimatedBytes).reversed()
                .thenComparing(Interval::start,Arrays::compareUnsigned));
        pending.add(new Interval(new byte[0],end,estimate(database,new byte[0],end)));
        var terminal=new ArrayList<Interval>();
        while(pending.size()+terminal.size()<target && !pending.isEmpty()) {
            var range=pending.remove();
            byte[] split=split(database,snapshot,range);
            if(split==null) { terminal.add(range); continue; }
            long[] estimates=estimates(database,range.start(),split,range.end());
            pending.add(new Interval(range.start(),split,estimates[0]));
            pending.add(new Interval(split,range.end(),estimates[1]));
        }
        terminal.addAll(pending);
        terminal.sort(Comparator.comparing(Interval::start,Arrays::compareUnsigned));
        return List.copyOf(terminal);
    }

    /** Only range probes materialize keys; the full counting loop never does. */
    private static byte[] split(RocksDB database,Snapshot snapshot,Interval range) throws RocksDBException {
        try(var reads=new ReadOptions().setSnapshot(snapshot).setFillCache(false);
            var iterator=database.newIterator(reads)) {
            iterator.seek(range.start());
            if(!iterator.isValid()) { iterator.status(); return null; }
            byte[] first=iterator.key();
            if(Arrays.compareUnsigned(first,range.end())>=0) { iterator.status(); return null; }
            iterator.seekForPrev(range.end());
            if(iterator.isValid() && Arrays.equals(iterator.key(),range.end())) iterator.prev();
            if(!iterator.isValid()) throw new IllegalStateException("Missing key-range endpoint");
            byte[] last=iterator.key(); iterator.status();
            if(Arrays.compareUnsigned(first,last)>=0) return null;
            int prefix=Arrays.mismatch(first,last);
            byte[] boundary=Arrays.copyOf(last,prefix+1);
            if(prefix<first.length) boundary[prefix]=(byte)(((first[prefix]&255)+(last[prefix]&255)+1)/2);
            if(Arrays.compareUnsigned(boundary,range.start())<=0 || Arrays.compareUnsigned(boundary,range.end())>=0)
                throw new IllegalStateException("Invalid disjoint key-range boundary");
            return boundary;
        }
    }

    private static long estimate(RocksDB database,byte[] start,byte[] end) {
        try(var lower=new Slice(start);var upper=new Slice(end)) {
            return database.getApproximateSizes(List.of(new Range(lower,upper)),SizeApproximationFlag.INCLUDE_FILES)[0];
        }
    }
    private static long[] estimates(RocksDB database,byte[] start,byte[] middle,byte[] end) {
        try(var lower=new Slice(start);var split=new Slice(middle);var upper=new Slice(end)) {
            return database.getApproximateSizes(List.of(new Range(lower,split),new Range(split,upper)),SizeApproximationFlag.INCLUDE_FILES);
        }
    }

    private static long countRange(RocksDB database,Snapshot snapshot,Interval range,LongAdder completed) {
        long count=0,pending=0;
        try(var lower=new Slice(range.start());var upper=new Slice(range.end());
            var reads=new ReadOptions().setSnapshot(snapshot).setFillCache(false)
                    .setIterateLowerBound(lower).setIterateUpperBound(upper);
            var iterator=database.newIterator(reads)) {
            for(iterator.seek(range.start());iterator.isValid();iterator.next()) {
                count=Math.addExact(count,1);
                if(++pending==4096) {
                    completed.add(pending); pending=0;
                    if(Thread.currentThread().isInterrupted()) throw new IllegalStateException("Key-range count interrupted");
                }
            }
            iterator.status(); completed.add(pending);
            return count;
        } catch(RocksDBException failure) {
            throw new IllegalStateException("Cannot count reconstruction key range",failure);
        }
    }
}
