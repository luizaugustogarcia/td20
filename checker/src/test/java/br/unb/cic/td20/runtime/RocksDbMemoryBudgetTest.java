package br.unb.cic.td20.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RocksDbMemoryBudgetTest {
    private final Map<String, String> previous = new HashMap<>();
    @BeforeEach void configure() {
        for (String key : new String[]{"td20.rocksDbMemoryMB", "td20.rocksDbWriteBufferMB",
                "td20.rocksDbStrictCapacity", "td20.certificateCacheIndexAndFilterBlocks",
                "td20.rocksDbFillCache", "td20.certificateRootFillCache",
                "td20.certificateEndpointFillCache", "td20.certificateReconstructionFillCache"}) previous.put(key, System.getProperty(key));
        System.setProperty("td20.rocksDbMemoryMB", "8");
        System.setProperty("td20.rocksDbWriteBufferMB", "1");
        System.setProperty("td20.rocksDbStrictCapacity", "true");
        System.setProperty("td20.certificateCacheIndexAndFilterBlocks", "true");
        System.setProperty("td20.rocksDbFillCache", "true");
        System.clearProperty("td20.certificateRootFillCache");
        System.clearProperty("td20.certificateEndpointFillCache");
        System.clearProperty("td20.certificateReconstructionFillCache");
    }
    @AfterEach void restore() {
        previous.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
    }
    @Test void sharesOneBudgetUntilEveryDatabaseReleasesIt() {
        try (var first = RocksDbMemoryBudget.acquire(); var second = RocksDbMemoryBudget.acquire()) {
            assertSame(first.cache(), second.cache());
            assertSame(first.writeBuffers(), second.writeBuffers());
            first.close();
            assertDoesNotThrow(() -> second.cache().getUsage());
            assertTrue(RocksDbMemoryBudget.progress().contains("/8"));
        }
        assertEquals("rocksDbMemory=closed", RocksDbMemoryBudget.progress());
        System.setProperty("td20.rocksDbMemoryMB", "16");
        try (var next = RocksDbMemoryBudget.acquire()) {
            assertTrue(RocksDbMemoryBudget.progress().contains("/16"));
        }
    }
    @Test void chargesMetadataWithoutPermanentlyPinningIt() {
        try (var budget = RocksDbMemoryBudget.acquire()) {
            var tables = budget.tables();
            assertTrue(tables.cacheIndexAndFilterBlocks());
            assertTrue(tables.cacheIndexAndFilterBlocksWithHighPriority());
            assertFalse(tables.pinL0FilterAndIndexBlocksInCache());
            assertFalse(tables.pinTopLevelIndexAndFilter());
        }
    }
    @Test void rejectsUnsafeOrMisspelledBudgetSettings() {
        System.setProperty("td20.rocksDbWriteBufferMB", "8");
        assertThrows(IllegalArgumentException.class, RocksDbMemoryBudget::acquire);
        System.setProperty("td20.rocksDbWriteBufferMB", "1");
        System.setProperty("td20.rocksDbStrictCapacity", "tru");
        assertThrows(IllegalArgumentException.class, RocksDbMemoryBudget::acquire);
        assertEquals("rocksDbMemory=closed", RocksDbMemoryBudget.progress());
    }
    @Test void phaseReadPoliciesAreIndependentAndOverridable() {
        try (var budget = RocksDbMemoryBudget.acquire();
             var roots = budget.reads("td20.certificateRootFillCache", true);
             var endpoints = budget.reads("td20.certificateEndpointFillCache", true);
             var reconstruction = budget.reads("td20.certificateReconstructionFillCache", true)) {
            assertTrue(roots.fillCache());
            assertTrue(endpoints.fillCache());
            assertTrue(reconstruction.fillCache());
            assertTrue(budget.tables().cacheIndexAndFilterBlocks());
            System.setProperty("td20.certificateRootFillCache", "false");
            try (var override = budget.reads("td20.certificateRootFillCache", true)) { assertFalse(override.fillCache()); }
            assertTrue(roots.fillCache());
            System.setProperty("td20.rocksDbFillCache", "false");
            try (var disabled = budget.reads("td20.certificateEndpointFillCache", true)) { assertFalse(disabled.fillCache()); }
        }
    }
}
