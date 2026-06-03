package io.kunkun.mockserver.dto;

import java.util.Map;

/**
 * A single request-matching rule for an endpoint. When a request matches ALL of the specified
 * criteria (any unspecified criterion is ignored), the rule's response is returned instead of the
 * endpoint's default behaviour. Rules are evaluated in order; the first match wins.
 */
public class MockRule {

    // ----- match criteria (all optional; unspecified = "don't care") -----

    /** Required HTTP method (case-insensitive), e.g. "POST". */
    private String method;

    /** Required request headers (name → exact value); header-name match is case-insensitive. */
    private Map<String, String> headerMatch;

    /** Required query parameters (name → exact value). */
    private Map<String, String> queryMatch;

    /** Substring the request body must contain. */
    private String bodyContains;

    // ----- response when this rule matches -----

    /** Status code to return (default 200). */
    private int status = 200;

    /** Raw response body (overrides the JSON envelope) — supports ${requestId}/${timestamp}/${random}. */
    private String responseBody;

    /** Message placed in the JSON envelope when {@link #responseBody} is not set. */
    private String responseMessage;

    /** Extra response headers. */
    private Map<String, Object> responseHeaders;

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public Map<String, String> getHeaderMatch() {
        return headerMatch;
    }

    public void setHeaderMatch(Map<String, String> headerMatch) {
        this.headerMatch = headerMatch;
    }

    public Map<String, String> getQueryMatch() {
        return queryMatch;
    }

    public void setQueryMatch(Map<String, String> queryMatch) {
        this.queryMatch = queryMatch;
    }

    public String getBodyContains() {
        return bodyContains;
    }

    public void setBodyContains(String bodyContains) {
        this.bodyContains = bodyContains;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public String getResponseMessage() {
        return responseMessage;
    }

    public void setResponseMessage(String responseMessage) {
        this.responseMessage = responseMessage;
    }

    public Map<String, Object> getResponseHeaders() {
        return responseHeaders;
    }

    public void setResponseHeaders(Map<String, Object> responseHeaders) {
        this.responseHeaders = responseHeaders;
    }
}
