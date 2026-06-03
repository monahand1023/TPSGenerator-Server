package io.kunkun.mockserver.dashboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the optional flat-file persistence of dashboard runs (survives a "restart").
 */
class DashboardServicePersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void finishedRunsSurviveRestartWhenPersistEnabled() {
        String file = tempDir.resolve("dashboard-runs.json").toString();

        DashboardService first = new DashboardService(100, true, file);
        first.register(Map.of("testId", "run-1", "testName", "T", "startTime", 1L, "testDuration", 60000L));
        first.update(Map.of("testId", "run-1", "summary", Map.of("totalRequests", 500, "successRate", 0.99)));
        first.finish(Map.of("testId", "run-1", "endTime", 61000L));

        // Simulate a restart: a fresh instance pointed at the same file loads the finished run.
        DashboardService reloaded = new DashboardService(100, true, file);
        reloaded.loadOnStartup();

        TestRun run = reloaded.get("run-1");
        assertNotNull(run, "finished run should survive restart");
        assertEquals("finished", run.getStatus());
        assertEquals("T", run.getTestName());
        assertEquals(61000L, run.getEndTime());
    }

    @Test
    void nothingPersistedWhenDisabled() {
        String file = tempDir.resolve("disabled.json").toString();

        DashboardService svc = new DashboardService(100, false, file);
        svc.register(Map.of("testId", "run-x", "startTime", 1L));
        svc.finish(Map.of("testId", "run-x", "endTime", 2L));

        assertFalse(new java.io.File(file).exists(), "no file should be written when persistence is disabled");
    }

    @Test
    void clearPersistsEmptyState() {
        String file = tempDir.resolve("cleared.json").toString();

        DashboardService svc = new DashboardService(100, true, file);
        svc.register(Map.of("testId", "run-1", "startTime", 1L));
        svc.finish(Map.of("testId", "run-1", "endTime", 2L));
        svc.clear();

        DashboardService reloaded = new DashboardService(100, true, file);
        reloaded.loadOnStartup();
        assertNull(reloaded.get("run-1"), "cleared state should persist (run gone after restart)");
    }
}
