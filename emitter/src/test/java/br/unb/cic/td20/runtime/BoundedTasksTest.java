package br.unb.cic.td20.runtime;

import lombok.val;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BoundedTasksTest {

    @Test
    void callbacksOverlapAndCloseWaitsForEveryResult() {
        val entered = new CountDownLatch(2);
        val complete = new AtomicInteger();

        try (val tasks = new BoundedTasks(2, "test-workers-")) {
            for (int i = 0; i < 20; i++) {
                tasks.submit(() -> {
                    entered.countDown();

                    try {
                        assertTrue(entered.await(5, TimeUnit.SECONDS), "Two searches must run concurrently");
                    } catch (final InterruptedException error) {
                        throw new AssertionError(error);
                    }

                    complete.incrementAndGet();
                });
            }
        }

        assertEquals(20, complete.get());
    }

    @Test
    void workerFailurePreventsSuccessfulCompletion() {
        assertThrows(IllegalStateException.class,
                () -> {
                    try (val tasks = new BoundedTasks(2, "test-workers-")) {
                        tasks.submit(() -> {
                            throw new IllegalArgumentException("unresolved worker failure");
                        });
                    }
                });
    }
}
