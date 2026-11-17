package br.unb.cic.td20.fleet;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FleetDownloadsTest {
    @Test void nextComputationCanRunWhileDownloadIsBlockedAndPhaseWaitsForIt() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var downloads = new FleetDownloads(1,2); var coordinator = Executors.newSingleThreadExecutor()) {
            downloads.submit(() -> { started.countDown(); assertTrue(release.await(10,TimeUnit.SECONDS)); });
            assertTrue(started.await(5,TimeUnit.SECONDS));
            var remaining = new java.util.concurrent.atomic.AtomicInteger(1);
            var tasks = new ConcurrentLinkedQueue<>(List.of(new FleetTask(UUID.randomUUID().toString(),"root-001","roots",1,1,0)));
            var nextComputed = new CountDownLatch(1);
            FleetEmitter.drainTasks(tasks,remaining,task -> nextComputed.countDown(),error -> fail(error));
            assertEquals(0,nextComputed.getCount());
            var waiting = new CountDownLatch(1);
            var barrier = coordinator.submit(() -> { waiting.countDown(); downloads.await(); return null; });
            try {
                assertTrue(waiting.await(5,TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,() -> barrier.get(100,TimeUnit.MILLISECONDS));
            } finally { release.countDown(); }
            barrier.get(5,TimeUnit.SECONDS);
        }
    }
    @Test void backlogIsBoundedAndNeverExecutesDownloadsOnTheProducer() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var producer = new java.util.concurrent.atomic.AtomicReference<Thread>();
        try (var downloads = new FleetDownloads(1,1); var submissions = Executors.newSingleThreadExecutor()) {
            downloads.submit(() -> { started.countDown(); assertTrue(release.await(10,TimeUnit.SECONDS)); });
            assertTrue(started.await(5,TimeUnit.SECONDS));
            downloads.submit(() -> assertNotEquals(producer.get(),Thread.currentThread()));
            var entering = new CountDownLatch(1);
            var third = submissions.submit(() -> { producer.set(Thread.currentThread()); entering.countDown();
                downloads.submit(() -> assertNotEquals(producer.get(),Thread.currentThread())); });
            try {
                assertTrue(entering.await(5,TimeUnit.SECONDS));
                assertThrows(TimeoutException.class,() -> third.get(100,TimeUnit.MILLISECONDS));
            } finally { release.countDown(); }
            third.get(5,TimeUnit.SECONDS); downloads.await();
        }
    }
    @Test void barrierReportsFailuresAfterDrainingOtherTransfers() throws Exception {
        var drained = new java.util.concurrent.atomic.AtomicBoolean();
        try (var downloads = new FleetDownloads(1,2)) {
            downloads.submit(() -> { throw new java.io.IOException("missing receipt"); });
            downloads.submit(() -> drained.set(true));
            assertThrows(IllegalStateException.class,downloads::await);
            assertTrue(drained.get());
        }
    }
    @Test void finishingAnOldDownloadDoesNotClearTheNewComputingTask(@TempDir Path directory) throws Exception {
        try (var progress = new FleetProgress(directory.resolve("emission.log"))) {
            progress.phase("emission",3,0);
            progress.start("a","old"); progress.computed("a","old"); progress.downloadQueued("a","old");
            progress.downloadStage("a","old","downloading"); progress.start("a","new");
            progress.remote("a","old","processed=stale"); progress.remote("a","new","processed=42");
            progress.report();
            String before = Files.readString(directory.resolve("status.txt"));
            assertTrue(before.contains("backgroundDownloads=1")); assertTrue(before.contains("queued=1"));
            assertTrue(before.contains("download=old")); assertFalse(before.contains("processed=stale"));
            progress.downloadFailed("a","old",new java.io.IOException("interrupted"),false);
            progress.accepted("a","old",10,0,0); progress.report();
            String after = Files.readString(directory.resolve("status.txt"));
            assertTrue(after.contains("jobs=1/3")); assertTrue(after.contains("a job=new"));
            assertTrue(after.contains("latest=processed=42")); assertTrue(after.contains("backgroundDownloads=0"));
        }
    }
    @Test void persistedTransfersRejectWrongRouteAndMissingFields(@TempDir Path directory) throws Exception {
        var transfer = new FleetTransfer(UUID.randomUUID().toString(),"root-001","node",UUID.randomUUID().toString());
        Path file = directory.resolve("root-001.txt"); transfer.write(file);
        assertEquals(transfer,FleetTransfer.read(file));
        var lines = Files.readAllLines(file); lines.set(1,"route=td18-eriksson-three-v1"); Files.write(file,lines);
        assertThrows(IllegalArgumentException.class,() -> FleetTransfer.read(file));
        transfer.write(file); lines = Files.readAllLines(file); Files.write(file,lines.subList(0,lines.size()-1));
        assertThrows(IllegalArgumentException.class,() -> FleetTransfer.read(file));
    }
}
