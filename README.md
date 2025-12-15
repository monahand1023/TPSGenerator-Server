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

- Java 11 or higher
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
# Default endpoint behavior
mock-server.default-min-delay=10
mock-server.default-max-delay=100
mock-server.default-error-rate=0.0

# Statistics logging interval (milliseconds)
mock-server.stats-log-interval-ms=10000

# Configuration persistence
mock-server.persistence.enabled=false
mock-server.persistence.file-path=./mock-server-config.json

# Actuator endpoints
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.health.show-details=always
```

### Endpoint Configuration

You can configure each endpoint with the following parameters:

- `minDelay`: Minimum response time in milliseconds (must be >= 0)
- `maxDelay`: Maximum response time in milliseconds (must be >= minDelay)
- `errorRate`: Probability of returning an error (0.0 to 1.0)
- `responseHeaders`: Custom headers to include in responses
- `responseMessage`: Custom message in the response body

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

Micrometer metrics are exposed for monitoring systems:

```
GET /actuator/metrics
GET /actuator/prometheus
```

Available custom metrics:
- `mock_server_requests_total` - Total requests received
- `mock_server_requests_success_total` - Successful requests
- `mock_server_requests_failed_total` - Failed requests
- `mock_server_configured_endpoints` - Number of configured endpoints

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
    MockEndpointService.java            # Endpoint management
    StatisticsService.java              # Statistics tracking
    ConfigurationPersistenceService.java # Config persistence
  health/
    MockServerHealthIndicator.java      # Custom health checks
```

## License

This project is licensed under the MIT License - see the LICENSE file for details.
