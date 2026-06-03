package io.kunkun.mockserver.dashboard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory store of load-test runs reported by TPS Generator clients.
 *
 * <p>Backed by a bounded Caffeine cache so a long-lived dashboard server can't accumulate runs
 * without limit. Payloads are accepted as loosely-typed maps (the client sends plain JSON objects)
 * and coerced defensively.
 *
 * <p>Optional flat-file persistence ({@code dashboard.persist}) survives restarts: finished runs are
 * written to a JSON file on terminal events and reloaded on startup. This is a simple file snapshot,
 * not a database — the authoritative run history remains the client's JSON/CSV exports.
 */
@Service
public class DashboardService {

    private static final Logger logger = LoggerFactory.getLogger(DashboardService.class);

    private final Cache<String, TestRun> runs;
    private final boolean persist;
    private final String persistFile;
    private final ObjectMapper objectMapper;
    /** Serializes file writes so two terminal events never collide on the .tmp file. */
    private final ReentrantLock persistLock = new ReentrantLock();

    public DashboardService(
            @Value("${dashboard.max-runs:100}") int maxRuns,
            @Value("${dashboard.persist:false}") boolean persist,
            @Value("${dashboard.persist-file:./dashboard-runs.json}") String persistFile) {
        this.runs = Caffeine.newBuilder().maximumSize(Math.max(1, maxRuns)).build();
        this.persist = persist;
        this.persistFile = persistFile;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /** Loads persisted runs on startup when persistence is enabled. */
    @PostConstruct
    public void loadOnStartup() {
        if (!persist) {
            return;
        }
        File file = new File(persistFile);
        if (!file.exists()) {
            logger.info("No persisted dashboard runs at: {}", file.getAbsolutePath());
            return;
        }
        try {
            Map<String, TestRun> loaded = objectMapper.readValue(
                    file, new TypeReference<Map<String, TestRun>>() {});
            loaded.forEach((id, run) -> {
                if (id != null && run != null) {
                    runs.put(id, run);
                }
            });
            logger.info("Loaded {} dashboard runs from: {}", loaded.size(), file.getAbsolutePath());
        } catch (IOException e) {
            logger.warn("Could not read dashboard runs from '{}', starting empty: {}",
                    file.getAbsolutePath(), e.getMessage());
        }
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
        save();
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
        save();
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
        save();
    }

    // ----- persistence -----

    /** Writes the current run snapshot to the persist file (no-op unless persistence is enabled). */
    private void save() {
        if (!persist) {
            return;
        }
        persistLock.lock();
        try {
            File target = new File(persistFile);
            File tmp = new File(persistFile + ".tmp");
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            Map<String, TestRun> snapshot = new HashMap<>(runs.asMap());
            objectMapper.writeValue(tmp, snapshot);
            try {
                Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.warn("Failed to persist dashboard runs to '{}': {}", persistFile, e.getMessage());
            new File(persistFile + ".tmp").delete();
        } finally {
            persistLock.unlock();
        }
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
