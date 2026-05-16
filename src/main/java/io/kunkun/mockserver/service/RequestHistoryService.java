package io.kunkun.mockserver.service;

import io.kunkun.mockserver.dto.RequestRecord;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Stores the most-recent requests per endpoint for post-hoc debugging.
 *
 * Thread-safety model:
 *   - A {@link ConcurrentHashMap} is used for the top-level endpoint → deque mapping so
 *     that read/write to *different* endpoints never contend.
 *   - A second {@link ConcurrentHashMap} holds one {@link ReentrantLock} per endpoint.
 *     All mutations (append, clear) on a single endpoint's deque are serialised through
 *     that endpoint's lock, while reads copy the deque under the same lock so callers
 *     always see a consistent snapshot.
 */
@Service
public class RequestHistoryService {

    /** Maximum number of {@link RequestRecord}s retained per endpoint path. */
    static final int MAX_HISTORY_PER_ENDPOINT = 100;

    private final ConcurrentHashMap<String, ArrayDeque<RequestRecord>> history =
            new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, ReentrantLock> locks =
            new ConcurrentHashMap<>();

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /** Returns the lock for {@code endpoint}, creating it atomically if absent. */
    private ReentrantLock lockFor(String endpoint) {
        return locks.computeIfAbsent(endpoint, k -> new ReentrantLock());
    }

    /** Returns the deque for {@code endpoint}, creating it atomically if absent. */
    private ArrayDeque<RequestRecord> dequeFor(String endpoint) {
        return history.computeIfAbsent(endpoint, k -> new ArrayDeque<>());
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Records {@code record} for the endpoint identified by {@link RequestRecord#getPath()}.
     * If the deque is already at {@link #MAX_HISTORY_PER_ENDPOINT} entries, the oldest
     * entry is dropped before the new one is appended.
     */
    public void record(RequestRecord record) {
        String endpoint = record.getPath();
        ReentrantLock lock = lockFor(endpoint);
        lock.lock();
        try {
            ArrayDeque<RequestRecord> deque = dequeFor(endpoint);
            if (deque.size() >= MAX_HISTORY_PER_ENDPOINT) {
                deque.pollFirst(); // drop the oldest
            }
            deque.addLast(record);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an unmodifiable, newest-first snapshot of the request history for
     * {@code endpoint}.  Returns an empty list if the endpoint has no history.
     */
    public List<RequestRecord> getHistory(String endpoint) {
        ReentrantLock lock = lockFor(endpoint);
        lock.lock();
        try {
            ArrayDeque<RequestRecord> deque = history.get(endpoint);
            if (deque == null || deque.isEmpty()) {
                return Collections.emptyList();
            }
            // Copy and reverse so callers get newest-first order
            List<RequestRecord> copy = new ArrayList<>(deque);
            Collections.reverse(copy);
            return Collections.unmodifiableList(copy);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a snapshot of the history for every endpoint that has at least one record.
     * Each list is newest-first (same ordering as {@link #getHistory(String)}).
     */
    public Map<String, List<RequestRecord>> getAllHistory() {
        Map<String, List<RequestRecord>> result = new HashMap<>();
        for (String endpoint : history.keySet()) {
            List<RequestRecord> records = getHistory(endpoint);
            if (!records.isEmpty()) {
                result.put(endpoint, records);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Clears the stored history for a single {@code endpoint}.
     */
    public void clearHistory(String endpoint) {
        ReentrantLock lock = lockFor(endpoint);
        lock.lock();
        try {
            ArrayDeque<RequestRecord> deque = history.get(endpoint);
            if (deque != null) {
                deque.clear();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Clears the stored history for all endpoints.
     */
    public void clearAllHistory() {
        for (String endpoint : history.keySet()) {
            clearHistory(endpoint);
        }
    }
}
