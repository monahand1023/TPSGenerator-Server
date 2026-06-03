package io.kunkun.mockserver.dashboard;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * An in-memory record of a single load-test run reported by a TPS Generator client.
 * Populated incrementally: register -> periodic metric updates -> finish / final result.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TestRun {

    private String testId;
    private String testName;
    private String targetServiceUrl;
    private long startTime;
    private long testDuration;
    private volatile long endTime;
    private volatile String status = "running";
    private volatile long lastUpdated;

    // Latest periodic update (replaced wholesale on each update for lock-free reads).
    private volatile Map<String, Object> summary;
    private volatile Map<String, Object> statusCodes;
    private volatile Map<String, Object> resources;

    // Final result payload (set once the run finishes).
    private volatile Map<String, Object> result;

    public String getTestId() {
        return testId;
    }

    public void setTestId(String testId) {
        this.testId = testId;
    }

    public String getTestName() {
        return testName;
    }

    public void setTestName(String testName) {
        this.testName = testName;
    }

    public String getTargetServiceUrl() {
        return targetServiceUrl;
    }

    public void setTargetServiceUrl(String targetServiceUrl) {
        this.targetServiceUrl = targetServiceUrl;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getTestDuration() {
        return testDuration;
    }

    public void setTestDuration(long testDuration) {
        this.testDuration = testDuration;
    }

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public Map<String, Object> getSummary() {
        return summary;
    }

    public void setSummary(Map<String, Object> summary) {
        this.summary = summary;
    }

    public Map<String, Object> getStatusCodes() {
        return statusCodes;
    }

    public void setStatusCodes(Map<String, Object> statusCodes) {
        this.statusCodes = statusCodes;
    }

    public Map<String, Object> getResources() {
        return resources;
    }

    public void setResources(Map<String, Object> resources) {
        this.resources = resources;
    }

    public Map<String, Object> getResult() {
        return result;
    }

    public void setResult(Map<String, Object> result) {
        this.result = result;
    }
}
