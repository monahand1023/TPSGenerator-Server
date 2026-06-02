package io.kunkun.mockserver.dashboard;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * In-memory store of load-test runs reported by TPS Generator clients.
 *
 * <p>Backed by a bounded Caffeine cache so a long-lived dashboard server can't accumulate runs
 * without limit. Payloads are accepted as loosely-typed maps (the client sends plain JSON objects)
 * and coerced defensively.
 */
@Service
public class DashboardService {

    private final Cache<String, TestRun> runs;

    public DashboardService(@Value("${dashboard.max-runs:100}") int maxRuns) {
        this.runs = Caffeine.newBuilder().maximumSize(Math.max(1, maxRuns)).build();
    }

    /** Registers (or replaces) a run. Requires a non-blank {@code testId}. */
    public void register(Map<String, Object> payload) {
        String testId = str(payload.get("testId"));
        if (testId == null) {
            throw new IllegalArgumentException("testId is required");
        }
        TestRun run = new TestRun();
        run.setTestId(testId);
        run.setTestName(str(payload.get("testName")));
        run.setTargetServiceUrl(str(payload.get("targetServiceUrl")));
        run.setStartTime(lng(payload.get("startTime")));
        run.setTestDuration(lng(payload.get("testDuration")));
        run.setStatus("running");
        run.setLastUpdated(System.currentTimeMillis());
        runs.put(testId, run);
    }

    /** Applies a periodic metrics update. Returns false if the run is unknown. */
    public boolean update(Map<String, Object> payload) {
        TestRun run = runFor(payload);
        if (run == null) {
            return false;
        }
        run.setSummary(asMap(payload.get("summary")));
        run.setStatusCodes(asMap(payload.get("statusCodes")));
        run.setResources(asMap(payload.get("resources")));
        run.setLastUpdated(System.currentTimeMillis());
        return true;
    }

    /** Marks a run finished. Returns false if the run is unknown. */
    public boolean finish(Map<String, Object> payload) {
        TestRun run = runFor(payload);
        if (run == null) {
            return false;
        }
        run.setEndTime(lng(payload.get("endTime")));
        run.setStatus("finished");
        run.setLastUpdated(System.currentTimeMillis());
        return true;
    }

    /** Stores the final result, creating the run if it was never registered. */
    public boolean result(Map<String, Object> payload) {
        TestRun run = runFor(payload);
        if (run == null) {
            String testId = str(payload.get("testId"));
            if (testId == null) {
                return false;
            }
            run = new TestRun();
            run.setTestId(testId);
            run.setTestName(str(payload.get("testName")));
            run.setStartTime(lng(payload.get("startTime")));
            runs.put(testId, run);
        }
        run.setResult(payload);
        run.setEndTime(lng(payload.get("endTime")));
        run.setStatus("finished");
        run.setLastUpdated(System.currentTimeMillis());
        return true;
    }

    /** All runs, newest first. */
    public List<TestRun> list() {
        return runs.asMap().values().stream()
                .sorted(Comparator.comparingLong(TestRun::getStartTime).reversed())
                .toList();
    }

    public TestRun get(String testId) {
        return runs.getIfPresent(testId);
    }

    public int count() {
        return (int) runs.estimatedSize();
    }

    public void clear() {
        runs.invalidateAll();
    }

    // ----- helpers -----

    private TestRun runFor(Map<String, Object> payload) {
        String testId = str(payload.get("testId"));
        return testId == null ? null : runs.getIfPresent(testId);
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static long lng(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }
}
