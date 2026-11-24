package br.unb.cic.td20.base;

import lombok.val;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GpuSearchStatsTest {

    @Test
    void timeoutsHaveSeparateCountsAndResetWithTheirWindow() {
        val stats = new GpuSearchStats(new GpuDeviceLayout(new int[] { 1, 3 }, 2), 0);
        for (int slot = 0; slot < 4; slot++) {
            stats.acquired(slot, 0, 10);
            stats.released(slot, 20, false, slot);
        }

        val first = stats.snapshot(30);
        assertEquals(4, first.completed());
        assertEquals(1, first.completeWitnesses());
        assertEquals(1, first.diameterClosures());
        assertEquals(1, first.misses());
        assertEquals(1, first.timeouts());
        assertEquals(0, first.failed());
        assertEquals(1, java.util.Arrays.stream(first.devices()).mapToLong(GpuSearchStats.DeviceSnapshot::timeouts).sum());

        val second = stats.snapshot(40);
        assertEquals(0, second.completed());
        assertEquals(0, second.timeouts());
    }

    @Test
    void separatesCompleteWitnessesDiameterClosuresMissesAndErrors() {
        val stats = new GpuSearchStats(new GpuDeviceLayout(new int[] { 0 }, 1), 0);
        for (int kind = 0; kind < 3; kind++) {
            stats.acquired(0, kind * 10, kind * 10);
            stats.released(0, kind * 10 + 1, false, kind);
        }

        stats.acquired(0, 30, 30);
        stats.released(0, 31, true, 0);

        val snapshot = stats.snapshot(40);
        assertEquals(4, snapshot.completed());
        assertEquals(1, snapshot.completeWitnesses());
        assertEquals(1, snapshot.diameterClosures());
        assertEquals(1, snapshot.misses());
        assertEquals(1, snapshot.failed());
        assertEquals(0, stats.snapshot(50).diameterClosures());
    }

    @Test
    void attributesQueueWaitBusyTimeAndCompletionsPerDevice() {
        val layout = new GpuDeviceLayout(new int[] { 0, 1 }, 2);
        val stats = new GpuSearchStats(layout, 0);

        stats.acquired(0, 5, 10);
        stats.acquired(1, 10, 20);
        stats.released(0, 60, false);

        val first = stats.snapshot(100);
        assertEquals(2, first.started());
        assertEquals(1, first.completed());
        assertEquals(0, first.failed());
        assertEquals(15, first.queueWaitNanos());
        assertEquals(130, first.busyNanos());
        assertEquals(1, first.devices()[0].completed());
        assertEquals(50, first.devices()[0].busyNanos());
        assertEquals(0, first.devices()[1].completed());
        assertEquals(80, first.devices()[1].busyNanos());

        stats.released(1, 180, true);

        val second = stats.snapshot(200);
        assertEquals(0, second.started());
        assertEquals(1, second.completed());
        assertEquals(1, second.failed());
        assertEquals(80, second.busyNanos());
        assertEquals(0, second.devices()[0].busyNanos());
        assertEquals(80, second.devices()[1].busyNanos());
    }

    @Test
    void resetsCompletedWindowWithoutLosingAnActiveSearch() {
        val stats = new GpuSearchStats(new GpuDeviceLayout(new int[] { 3 }, 1), 1_000);

        stats.acquired(0, 1_000, 1_100);

        val first = stats.snapshot(1_500);
        val second = stats.snapshot(1_800);
        stats.released(0, 2_000, false);

        val third = stats.snapshot(2_100);

        assertEquals(400, first.busyNanos());
        assertEquals(300, second.busyNanos());
        assertEquals(200, third.busyNanos());
        assertEquals(1, third.completed());
        assertEquals(0, third.started());
    }
}
