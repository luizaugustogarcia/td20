package br.unb.cic.td20.verification;

import br.unb.cic.td20.model.PrimitivePair;
import lombok.val;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CertificateBatchVerifierTest {

    private static final PrimitivePair GOOD = PrimitivePair.of(new int[] { 1, 2, 0 }, new int[] { 0, 1, 2 });
    private static final PrimitivePair MISSING = PrimitivePair.of(new int[] { 1, 2, 3, 4, 0 }, new int[] { 0, 1, 2, 3, 4 });

    @Test
    void unevenBuffersProgressAtTheMemoryLimitAndPreserveEveryDuplicateAndMiss() {
        assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> {
                    val lookedUp = new AtomicInteger();
                    val missing = new AtomicInteger();
                    val expectedMissing = new AtomicInteger();

                    try (val io = Executors.newFixedThreadPool(2);
                        val producers = Executors.newFixedThreadPool(4);
                        val verifier = new CertificateBatchVerifier(batch -> CompletableFuture.supplyAsync(() -> {
                                            lookedUp.addAndGet(batch.size());

                                            val present = new boolean[batch.size()];
                                            for (int i = 0; i < present.length; i++) {
                                                present[i] = batch.get(i) != MISSING;
                                            }

                                            return present;
                                        },
                                        io),
                                pair -> {
                                    assertSame(MISSING, pair);
                                    missing.incrementAndGet();
                                },
                                7,
                                3,
                                7 * 512L)) {
                        val tasks = new ArrayList<java.util.concurrent.Future<?>>();
                        for (int w = 0; w < 4; w++) {
                            final int worker = w;
                            tasks.add(producers.submit(() -> {
                                for (int i = 0; i < 17; i++) {
                                    final boolean absent = (worker + i) % 3 == 0;
                                    if (absent) {
                                        expectedMissing.incrementAndGet();
                                    }

                                    verifier.accept(absent ? MISSING : GOOD);
                                }
                            }));
                        }
                        for (val task : tasks) {
                            task.get();
                        }
                    }

                    assertEquals(68, lookedUp.get());
                    assertEquals(expectedMissing.get(), missing.get());
                });
    }

    @Test
    void closeDrainsAllProducerPrivateTails() {
        val lookedUp = new AtomicInteger();
        val missing = new AtomicInteger();

        try (val workers = Executors.newFixedThreadPool(6);
            val verifier = new CertificateBatchVerifier(batch -> {
                        lookedUp.addAndGet(batch.size());
                        return CompletableFuture.completedFuture(new boolean[batch.size()]);
                    },
                    pair -> missing.incrementAndGet(),
                    8,
                    4,
                    64 * 512L)) {
            val tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 6; i++) {
                tasks.add(workers.submit(() -> {
                    for (int j = 0; j < 3; j++) {
                        verifier.accept(GOOD);
                    }
                }));
            }
            for (val task : tasks) {
                assertDoesNotThrow(() -> task.get());
            }
        }

        assertEquals(18, lookedUp.get());
        assertEquals(18, missing.get());
    }

    @Test
    void closeWaitsForTheFinalAsynchronousReplay() throws Exception {
        val started = new CountDownLatch(1);
        val result = new CompletableFuture<boolean[]>();
        val verifier = new CertificateBatchVerifier(batch -> {
                    started.countDown();
                    return result;
                },
                pair -> fail("Unexpected missing witness"),
                4,
                2,
                8 * 512L);

        try (val closer = Executors.newSingleThreadExecutor()) {
            for (int i = 0; i < 4; i++) {
                verifier.accept(GOOD);
            }

            val closing = closer.submit(verifier::close);

            try {
                assertTrue(started.await(2, TimeUnit.SECONDS));
                assertFalse(closing.isDone());
                result.complete(new boolean[] { true, true, true, true });
                closing.get(2, TimeUnit.SECONDS);
                assertTrue(verifier.progress().contains("checked=4"));
                assertTrue(verifier.progress().contains("bufferedMiB=0/"));
            } finally {
                result.complete(new boolean[] { true, true, true, true });
            }
        }
    }

    @Test
    void asynchronousFailureAndIncompleteResultsCannotCompleteVerification() {
        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> {
                    assertThrows(IllegalArgumentException.class,
                            () -> {
                                try (val verifier = new CertificateBatchVerifier(batch -> CompletableFuture.failedFuture(new IllegalArgumentException("invalid witness")),
                                        pair -> fail("No results exist"),
                                        4,
                                        2,
                                        8 * 512L)) {
                                    for (int i = 0; i < 4; i++) {
                                        verifier.accept(GOOD);
                                    }
                                }
                            });
                    assertThrows(IllegalStateException.class,
                            () -> {
                                try (val verifier = new CertificateBatchVerifier(batch -> CompletableFuture.completedFuture(new boolean[batch.size() - 1]),
                                        pair -> {
                                        },
                                        4,
                                        2,
                                        8 * 512L)) {
                                    for (int i = 0; i < 4; i++) {
                                        verifier.accept(GOOD);
                                    }
                                }
                            });
                });
    }
}
