package io.kunkun.mockserver.service;

import io.kunkun.mockserver.dto.RequestRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class RequestHistoryServiceTest {

    private RequestHistoryService service;

    @BeforeEach
    void setUp() {
        service = new RequestHistoryService();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private RequestRecord makeRecord(String path, long timestamp) {
        return new RequestRecord(timestamp, "GET", path, Collections.emptyMap(), null, 200, 1);
    }

    // -----------------------------------------------------------------------
    // Basic record / retrieve
    // -----------------------------------------------------------------------

    @Test
    void record_storesRequestForEndpoint() {
        RequestRecord rec = makeRecord("/api/test", System.currentTimeMillis());
        service.record(rec);

        List<RequestRecord> history = service.getHistory("/api/test");
        assertEquals(1, history.size());
        assertSame(rec, history.get(0));
    }

    @Test
    void getHistory_returnsEmptyForUnknownEndpoint() {
        List<RequestRecord> history = service.getHistory("/unknown");
        assertTrue(history.isEmpty());
    }

    @Test
    void getHistory_isUnmodifiable() {
        service.record(makeRecord("/x", 1L));
        List<RequestRecord> history = service.getHistory("/x");
        assertThrows(UnsupportedOperationException.class, () -> history.add(makeRecord("/x", 2L)));
    }

    // -----------------------------------------------------------------------
    // Ordering: newest-first
    // -----------------------------------------------------------------------

    @Test
    void getHistory_returnsNewestFirst() {
        String path = "/ordered";
        service.record(makeRecord(path, 1_000L));
        service.record(makeRecord(path, 2_000L));
        service.record(makeRecord(path, 3_000L));

        List<RequestRecord> history = service.getHistory(path);
        assertEquals(3, history.size());
        assertEquals(3_000L, history.get(0).getTimestamp());
        assertEquals(2_000L, history.get(1).getTimestamp());
        assertEquals(1_000L, history.get(2).getTimestamp());
    }

    // -----------------------------------------------------------------------
    // LRU / eviction at MAX_HISTORY_PER_ENDPOINT
    // -----------------------------------------------------------------------

    @Test
    void record_evictsOldestWhenOverLimit() {
        String path = "/evict";
        int limit = RequestHistoryService.MAX_HISTORY_PER_ENDPOINT;

        // Fill to the limit using timestamps 1..limit
        for (int i = 1; i <= limit; i++) {
            service.record(makeRecord(path, (long) i));
        }
        assertEquals(limit, service.getHistory(path).size());

        // Add one more — timestamp limit+1
        service.record(makeRecord(path, (long) (limit + 1)));

        List<RequestRecord> history = service.getHistory(path);
        assertEquals(limit, history.size());
        // Newest first: most recent is at index 0
        assertEquals((long) (limit + 1), history.get(0).getTimestamp());
        // Oldest (timestamp 1) should have been evicted
        assertTrue(history.stream().noneMatch(r -> r.getTimestamp() == 1L));
    }

    // -----------------------------------------------------------------------
    // getAllHistory
    // -----------------------------------------------------------------------

    @Test
    void getAllHistory_returnsAllEndpoints() {
        service.record(makeRecord("/a", 1L));
        service.record(makeRecord("/b", 2L));
        service.record(makeRecord("/a", 3L));

        Map<String, List<RequestRecord>> all = service.getAllHistory();
        assertTrue(all.containsKey("/a"));
        assertTrue(all.containsKey("/b"));
        assertEquals(2, all.get("/a").size());
        assertEquals(1, all.get("/b").size());
    }

    @Test
    void getAllHistory_excludesEndpointsWithNoHistory() {
        service.record(makeRecord("/nonempty", 1L));
        // Force-create an entry for /empty via clear (triggers lock+deque creation but leaves deque empty)
        // Actually clearHistory on unknown endpoint is a no-op, so we just record then clear:
        service.record(makeRecord("/empty", 1L));
        service.clearHistory("/empty");

        Map<String, List<RequestRecord>> all = service.getAllHistory();
        assertFalse(all.containsKey("/empty"));
        assertTrue(all.containsKey("/nonempty"));
    }

    // -----------------------------------------------------------------------
    // clearHistory / clearAllHistory
    // -----------------------------------------------------------------------

    @Test
    void clearHistory_removesOnlyTargetEndpoint() {
        service.record(makeRecord("/keep", 1L));
        service.record(makeRecord("/clear", 2L));

        service.clearHistory("/clear");

        assertTrue(service.getHistory("/clear").isEmpty());
        assertFalse(service.getHistory("/keep").isEmpty());
    }

    @Test
    void clearAllHistory_emptiesEverything() {
        service.record(makeRecord("/a", 1L));
        service.record(makeRecord("/b", 2L));

        service.clearAllHistory();

        assertTrue(service.getHistory("/a").isEmpty());
        assertTrue(service.getHistory("/b").isEmpty());
    }

    // -----------------------------------------------------------------------
    // Thread safety
    // -----------------------------------------------------------------------

    @Test
    void concurrent_recording_doesNotThrow() throws InterruptedException {
        int threads = 10;
        int recordsPerThread = 20;
        String path = "/concurrent";
        AtomicBoolean errorOccurred = new AtomicBoolean(false);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads * recordsPerThread; i++) {
            final long ts = i;
            executor.submit(() -> {
                try {
                    service.record(makeRecord(path, ts));
                } catch (Exception e) {
                    errorOccurred.set(true);
                }
            });
        }
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertFalse(errorOccurred.get(), "Exception thrown during concurrent recording");
        // Eviction should keep us at or below MAX_HISTORY_PER_ENDPOINT
        assertTrue(service.getHistory(path).size() <= RequestHistoryService.MAX_HISTORY_PER_ENDPOINT);
    }

    @Test
    void concurrent_multipleEndpoints_doesNotThrow() throws InterruptedException {
        int threads = 8;
        AtomicBoolean errorOccurred = new AtomicBoolean(false);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < 80; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    String path = "/endpoint-" + (idx % threads);
                    service.record(makeRecord(path, idx));
                } catch (Exception e) {
                    errorOccurred.set(true);
                }
            });
        }
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertFalse(errorOccurred.get(), "Exception thrown during concurrent multi-endpoint recording");
    }
}
