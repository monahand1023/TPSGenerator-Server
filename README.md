# TPS Generator Server

A configurable mock HTTP server for simulating API behavior with controlled response times, error rates, and custom responses. This is the companion for the TPS Generator load testing tool, found here: https://github.com/monahand1023/TPSGenerator

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Building and Running](#building-and-running)
- [Configuration](#configuration)
  - [Application Properties](#application-properties)
  - [Endpoint Configuration](#endpoint-configuration)
  - [Default Settings](#default-settings)
- [Admin API](#admin-api)
  - [Configure Endpoints](#configure-endpoints)
  - [Get Endpoint Configuration](#get-endpoint-configuration)
  - [List All Endpoints](#list-all-endpoints)
  - [Delete Endpoint Configuration](#delete-endpoint-configuration)
  - [Clear All Configurations](#clear-all-configurations)
  - [Configure Defaults](#configure-defaults)
  - [Get Current Defaults](#get-current-defaults)
  - [View Statistics](#view-statistics)
  - [Reset Statistics](#reset-statistics)
  - [Persistence](#persistence)
- [API Versioning](#api-versioning)
- [Monitoring](#monitoring)
  - [Health Checks](#health-checks)
  - [Metrics](#metrics)
- [Using with TPS Generator](#using-with-tps-generator)
- [Examples](#examples)
- [Project Structure](#project-structure)

## Security

The Admin API is protected by **HTTP Basic Auth**. Both prefixes — `/admin/**` and `/api/v1/admin/**` — require authentication; there is no unauthenticated alias.

- `ADMIN_PASSWORD` is **required** at startup. The application fails fast if it is unset or blank.
- `ADMIN_USERNAME` defaults to `admin`.
- The mock endpoints themselves (`/{path}/**`), `/health`, and `/actuator/**` are public so load generators and probes can reach them without credentials.

```bash
export ADMIN_USERNAME=admin
export ADMIN_PASSWORD='choose-a-strong-secret'
java -jar target/mock-http-server-1.0.0.jar
# admin calls then require:  curl -u admin:choose-a-strong-secret ...
```

Even with auth, treat the admin surface as privileged: an authenticated caller can change all endpoint behaviors, reset statistics, and overwrite persisted configurations. Prefer keeping it on a trusted network.

## Overview

This Mock HTTP Server provides a flexible environment for simulating API behavior during load and performance testing. It works seamlessly with the TPS Generator to create realistic testing scenarios with controlled response characteristics.

The server lets you configure how each endpoint behaves, including response times, error rates, and response payloads, making it ideal for testing how your applications handle various API behaviors.

## Features

- **Configurable Endpoints**: Create mock endpoints with specific behavior characteristics
- **Response Time Simulation**: Configure min/max response times to simulate network latency
- **Controlled Error Rates**: Set precise error rates to test error handling
- **Custom Headers and Responses**: Configure custom headers and response messages
- **Statistics Tracking**: Monitor request counts, success/failure rates in real-time
- **Admin API**: Manage configuration and view statistics through a REST API
- **Request Logging**: Detailed logging of requests and responses
- **API Versioning**: Versioned API endpoints (`/api/v1/admin/*`)
- **Configuration Persistence**: Save and load endpoint configurations to/from disk
- **Health Checks**: Spring Boot Actuator health endpoints with custom indicators
- **Metrics Integration**: Micrometer metrics for Prometheus and other monitoring systems
- **Path Normalization**: Case-insensitive endpoint matching with trailing slash handling
- **Memory Management**: LRU cache with configurable limits to prevent unbounded memory growth

## Getting Started

### Prerequisites

- Java 21 or higher (Spring Boot 3.2)
- Maven 3.6 or higher

### Building and Running

1. Build the project:

```bash
mvn clean package
```

2. Run the server:

```bash
java -jar target/mock-http-server-1.0.0.jar
```

By default, the server runs on port 8080. You can change this by setting the `server.port` property:

```bash
java -jar target/mock-http-server-1.0.0.jar --server.port=9090
```

## Configuration

### Application Properties

Configure the server via `application.properties` or environment variables:

```properties
# Virtual threads (Java 21) — strongly recommended for this sleep-bound server
spring.threads.virtual.enabled=true

# Default endpoint behavior
mock-server.default-min-delay=10
mock-server.default-max-delay=100
mock-server.default-error-rate=0.0

# Statistics logging interval (milliseconds)
mock-server.stats-log-interval-ms=10000

# Per-endpoint request history (debugging). Off by default — it allocates per request.
mock-server.history.enabled=false

# Configuration persistence (single mechanism — see Persistence below).
# When enabled: loaded on startup, auto-saved on every change, and the
# /admin/persistence/* endpoints can save/reload on demand.
mock-server.persistence.enabled=false
mock-server.persistence.file-path=./mock-server-config.json

# Actuator endpoints
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.health.show-details=always
```

> **Note on metric cardinality:** per-endpoint metrics are tagged only for *configured* endpoints; all unconfigured paths collapse to a single `endpoint="(unmatched)"` series, so a load test against many random paths cannot blow up the meter registry. Per-endpoint "received" counts are exposed as `mock_server_endpoint_requests_received_total{endpoint}`, separate from the `mock_server_endpoint_requests_total{endpoint,result}` success/failure counter.

### Endpoint Configuration

You can configure each endpoint with the following parameters:

- `minDelay`: Minimum response time in milliseconds (must be >= 0)
- `maxDelay`: Maximum response time in milliseconds (must be >= minDelay)
- `errorRate`: Probability of returning an error (0.0 to 1.0)
- `responseHeaders`: Custom headers to include in responses
- `responseMessage`: Custom message in the response body
- `delayDistribution`: How the delay is drawn between min/max — `uniform` (default), `normal`, or `lognormal` (long-tailed, more realistic p99s)
- `statusDistribution`: Optional weighted map of status code → weight, e.g. `{"200": 70, "429": 20, "503": 10}`. When set it takes precedence over `errorRate`; any 2xx counts as success, anything >= 400 as a failure
- `responseBody`: Optional raw response body for success responses (replaces the JSON envelope). Supports `${requestId}`, `${timestamp}`, `${random}` placeholders
- `responseSizeBytes`: Optional minimum body size in bytes; the body is padded with filler to at least this size (stresses client deserialization/bandwidth)
- `faultMode` + `faultRate`: Inject network-level faults on successful responses — `faultMode` is `none` (default), `empty` (empty body), or `malformed` (invalid/truncated JSON); `faultRate` (0.0–1.0) is the probability a given response is replaced by the fault
- `degradeAfterRequests` + `degradedErrorRate`: Stateful degradation — after the endpoint has served `degradeAfterRequests` requests, its error rate switches to `degradedErrorRate` (models a backend degrading over time: cache exhaustion, resource leak). 0 disables it
- `rules`: Ordered request-matching rules. The first rule whose criteria all match the request supplies the response; otherwise the endpoint default applies. Each rule may match on `method`, `headerMatch` (name→value, case-insensitive name), `queryMatch` (name→value), `bodyContains` (substring), and returns a `status` (default 200), `responseBody` (raw, templated) or `responseMessage` (envelope), and `responseHeaders`

Example with rules (WireMock-style):

```json
{
  "rules": [
    { "method": "POST", "bodyContains": "ping", "status": 202, "responseBody": "pong" },
    { "headerMatch": { "X-Tier": "premium" }, "responseMessage": "premium response" }
  ]
}
```

Example with a realistic status mix and long-tailed latency:

```json
{
  "minDelay": 20,
  "maxDelay": 500,
  "delayDistribution": "lognormal",
  "statusDistribution": { "200": 90, "429": 7, "503": 3 }
}
```

Example configuration:

```json
{
  "minDelay": 50,
  "maxDelay": 200,
  "errorRate": 0.1,
  "responseHeaders": {
    "X-Custom-Header": "CustomValue"
  },
  "responseMessage": "This is a custom response"
}
```

**Path Normalization**: Endpoint paths are normalized for consistent matching:
- Leading and trailing slashes are removed
- Matching is case-insensitive
- Example: `/API/Users/`, `api/users`, and `API/USERS` all match the same endpoint

### Default Settings

Default behavior for all unconfigured endpoints:

- `defaultMinDelay`: 10 ms
- `defaultMaxDelay`: 100 ms
- `defaultErrorRate`: 0.0 (no errors)

## Admin API

The server provides a comprehensive admin API for configuration and monitoring.

### Configure Endpoints

Configure a specific endpoint path:

```
POST /admin/config/{path}
```

Request body:
```json
{
  "minDelay": 50,
  "maxDelay": 300,
  "errorRate": 0.05,
  "responseHeaders": {
    "Content-Type": "application/json",
    "X-Rate-Limit": "100"
  },
  "responseMessage": "Custom response for users endpoint"
}
```

Response:
```json
{
  "status": "success",
  "message": "Endpoint configured: /users",
  "config": { ... }
}
```

### Get Endpoint Configuration

Retrieve the configuration for a specific endpoint:

```
GET /admin/config/{path}
```

Returns `404 Not Found` if the endpoint is not configured.

### List All Endpoints

Get all configured endpoints:

```
GET /admin/config
```

Response:
```json
{
  "status": "success",
  "endpoints": {
    "users": { ... },
    "orders": { ... }
  },
  "count": 2
}
```

### Delete Endpoint Configuration

Delete a specific endpoint configuration:

```
DELETE /admin/config/{path}
```

Response:
```json
{
  "status": "success",
  "message": "Endpoint configuration deleted: /users"
}
```

Returns `404 Not Found` if the endpoint doesn't exist.

### Clear All Configurations

Delete all endpoint configurations:

```
DELETE /admin/config
```

Response:
```json
{
  "status": "success",
  "message": "All endpoint configurations cleared",
  "deletedCount": 5
}
```

### Configure Defaults

Set default behavior for all unconfigured endpoints:

```
POST /admin/defaults?minDelay=20&maxDelay=150&errorRate=0.02
```

All parameters are optional - only provided values will be updated.

### Get Current Defaults

Retrieve current default settings:

```
GET /admin/defaults
```

Response:
```json
{
  "status": "success",
  "defaultMinDelay": 10,
  "defaultMaxDelay": 100,
  "defaultErrorRate": 0.0
}
```

### View Statistics

Get current server statistics:

```
GET /admin/stats
```

Response:
```json
{
  "status": "success",
  "totalRequests": 1000,
  "successfulRequests": 950,
  "failedRequests": 50,
  "successRate": 0.95
}
```

### Reset Statistics

Reset all server statistics:

```
POST /admin/stats/reset
```

### Persistence

Save and load endpoint configurations to/from disk.

**Check persistence status:**
```
GET /admin/persistence/status
```

**Save current configurations:**
```
POST /admin/persistence/save
```

**Load configurations from file:**
```
POST /admin/persistence/load
```

Note: Persistence must be enabled in `application.properties` for save/load operations to work.

### Import from OpenAPI

Bulk-create endpoints from an OpenAPI/Swagger document (JSON):

```
POST /admin/openapi/import
```

The request body is the OpenAPI document. Each entry under `paths` becomes a mock endpoint using
the current defaults, with the response message taken from the operation `summary` and, when present,
the response body taken from a declared `application/json` `example`. Returns the imported paths.

> Paths are matched exactly, so templated paths (e.g. `/users/{id}`) are registered literally and
> won't match concrete request paths — static paths import cleanly.

## API Versioning

All admin endpoints are available with a versioned prefix:

| Standard Endpoint | Versioned Endpoint |
|-------------------|-------------------|
| `/admin/config/{path}` | `/api/v1/admin/config/{path}` |
| `/admin/config` | `/api/v1/admin/config` |
| `/admin/defaults` | `/api/v1/admin/defaults` |
| `/admin/stats` | `/api/v1/admin/stats` |
| `/admin/stats/reset` | `/api/v1/admin/stats/reset` |
| `/admin/persistence/*` | `/api/v1/admin/persistence/*` |

## Monitoring

### Health Checks

The server exposes Spring Boot Actuator health endpoints:

```
GET /actuator/health
```

Response:
```json
{
  "status": "UP",
  "components": {
    "diskSpace": { "status": "UP" },
    "mockServer": {
      "status": "UP",
      "details": {
        "configuredEndpoints": 5,
        "totalRequests": 1000,
        "successRate": 95.0
      }
    },
    "ping": { "status": "UP" }
  }
}
```

### Metrics

Micrometer metrics are exposed for monitoring systems. The `micrometer-registry-prometheus` dependency is included so Prometheus metrics are available out of the box:

```
GET /actuator/metrics
GET /actuator/prometheus
```

Available custom metrics:
- `mock_server_requests_total` - Lifetime total requests received (monotonically increasing, correct Prometheus counter semantics)
- `mock_server_requests_successful` - Lifetime successful requests
- `mock_server_requests_failed` - Lifetime failed requests
- `mock_server_request_duration` - Request processing time (timer)
- `mock_server_success_rate` - Current success rate, resets with `/admin/stats/reset` (gauge)
- `mock_server_requests_current_total` - Current total requests, resettable (gauge)

## Using with TPS Generator

The Mock HTTP Server integrates perfectly with the TPS Generator for comprehensive load testing:

1. Start the Mock HTTP Server
2. Configure the endpoints to match your test scenarios
3. Configure TPS Generator to target the mock server
4. Run your load tests
5. Analyze both client-side (TPS Generator) and server-side (Mock Server) metrics

An example TPS Generator configuration to use with the mock server can be found here: https://github.com/monahand1023/TPSGenerator/?tab=readme-ov-file#configuration

## Examples

### Setting Up a Realistic API Scenario

1. Configure the `/users` endpoint with normal behavior:

```bash
curl -X POST http://localhost:8080/admin/config/users \
  -H "Content-Type: application/json" \
  -d '{
    "minDelay": 20,
    "maxDelay": 100,
    "errorRate": 0.01,
    "responseHeaders": {"Content-Type": "application/json"},
    "responseMessage": "User profile data"
  }'
```

2. Configure the `/orders` endpoint with higher latency:

```bash
curl -X POST http://localhost:8080/admin/config/orders \
  -H "Content-Type: application/json" \
  -d '{
    "minDelay": 100,
    "maxDelay": 500,
    "errorRate": 0.05,
    "responseHeaders": {"Content-Type": "application/json"},
    "responseMessage": "Order created successfully"
  }'
```

3. Configure the `/search` endpoint with occasional timeouts:

```bash
curl -X POST http://localhost:8080/admin/config/search \
  -H "Content-Type: application/json" \
  -d '{
    "minDelay": 200,
    "maxDelay": 2000,
    "errorRate": 0.1,
    "responseHeaders": {"Content-Type": "application/json"},
    "responseMessage": "Search results"
  }'
```

4. Save configurations for later:

```bash
curl -X POST http://localhost:8080/admin/persistence/save
```

5. View current statistics:

```bash
curl http://localhost:8080/admin/stats
```

6. Check server health:

```bash
curl http://localhost:8080/actuator/health
```

## Project Structure

```
src/main/java/io/kunkun/mockserver/
  MockHttpServerApplication.java        # Application entry point
  config/
    MockServerProperties.java           # Configuration properties
  controller/
    AdminController.java                # Admin API endpoints
    MockRequestController.java          # Mock request handling
    GlobalExceptionHandler.java         # Error handling
  dto/
    MockEndpointConfig.java             # Endpoint configuration DTO
    ApiResponse.java                    # Response builder
  service/
    MockEndpointService.java            # Endpoint management (Caffeine LRU cache) + config persistence
    StatisticsService.java              # Statistics tracking
    RequestHistoryService.java          # Per-endpoint request history (debug)
  health/
    MockServerHealthIndicator.java      # Custom health checks
```

## Framework Notes

- **Spring Boot 3.x** — uses the Jakarta EE 10 namespace (`jakarta.*`). All Java EE/`javax.*` imports have been migrated.
- **Caffeine LRU cache** — `MockEndpointService` uses Caffeine instead of `LinkedHashMap` + `ReentrantReadWriteLock`. The old implementation had a concurrency bug: access-order `LinkedHashMap.get()` mutates internal state and is not safe under a shared read lock. Caffeine provides correct concurrent LRU semantics.

## License

This project is licensed under the MIT License - see the LICENSE file for details.
