# TPS Generator Server

A configurable mock HTTP server for simulating API behavior with controlled response times, error rates, and custom responses. This is the companion for the TPS Generator load testing tool, found here: https://github.com/monahand1023/TPSGenerator





## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Building and Running](#building-and-running)
- [Configuration](#configuration)
  - [Endpoint Configuration](#endpoint-configuration)
  - [Default Settings](#default-settings)
- [Admin API](#admin-api)
  - [Configure Endpoints](#configure-endpoints)
  - [Get Endpoint Configuration](#get-endpoint-configuration)
  - [Configure Defaults](#configure-defaults)
  - [View Statistics](#view-statistics)
  - [Reset Statistics](#reset-statistics)
- [Using with TPS Generator](#using-with-tps-generator)
- [Examples](#examples)

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



### Endpoint Configuration

You can configure each endpoint with the following parameters:

- `minDelay`: Minimum response time in milliseconds
- `maxDelay`: Maximum response time in milliseconds
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

### Get Endpoint Configuration

Retrieve the configuration for a specific endpoint:

```
GET /admin/config/{path}
```

### Configure Defaults

Set default behavior for all unconfigured endpoints:

```
POST /admin/defaults?minDelay=20&maxDelay=150&errorRate=0.02
```

### View Statistics

Get current server statistics:

```
GET /admin/stats
```

Response:
```json
{
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

## Using with TPS Generator

The Mock HTTP Server integrates perfectly with the TPS Generator for comprehensive load testing:

1. Start the Mock HTTP Server
2. Configure the endpoints to match your test scenarios
3. Configure TPS Generator to target the mock server
4. Run your load tests
5. Analyze both client-side (TPS Generator) and server-side (Mock Server) metrics

Example TPS Generator configuration to use with the mock server:

```json
{
  "name": "Mock API Load Test",
  "targetServiceUrl": "http://localhost:8080",
  "testDuration": "5m",
  
  "trafficPattern": {
    "type": "rampUp",
    "startTps": 10,
    "targetTps": 100,
    "rampDuration": "1m"
  },
  
  "requestTemplates": [
    {
      "name": "getUserProfile",
      "weight": 70,
      "method": "GET",
      "urlTemplate": "http://localhost:8080/users/${userId}"
    },
    {
      "name": "createOrder",
      "weight": 30,
      "method": "POST",
      "urlTemplate": "http://localhost:8080/orders",
      "bodyTemplate": "{\"productId\":\"${productId}\",\"quantity\":${quantity}}"
    }
  ]
}
```

## Examples

### Setting Up a Realistic API Scenario

1. Configure the `/users` endpoint with normal behavior:

```bash
curl -X POST http://localhost:8080/admin/config/users -H "Content-Type: application/json" -d '{
  "minDelay": 20,
  "maxDelay": 100,
  "errorRate": 0.01,
  "responseHeaders": {
    "Content-Type": "application/json"
  },
  "responseMessage": "User profile data"
}'
```

2. Configure the `/orders` endpoint with higher latency:

```bash
curl -X POST http://localhost:8080/admin/config/orders -H "Content-Type: application/json" -d '{


  "minDelay": 100,
  "maxDelay": 500,
  "errorRate": 0.05,
  "responseHeaders": {
    "Content-Type": "application/json"
  },
  "responseMessage": "Order created successfully"
}'
```

3. Configure the `/search` endpoint with occasional timeouts:

```bash
curl -X POST http://localhost:8080/admin/config/search -H "Content-Type: application/json" -d '{
  "minDelay": 200,
  "maxDelay": 2000,
  "errorRate": 0.1,
  "responseHeaders": {
    "Content-Type": "application/json"
  },
  "responseMessage": "Search results"
}'
```

4. Run your load test with TPS Generator targeting these endpoints and observe how your system handles the different response characteristics.

This setup allows you to:
- Simulate realistic API behavior
- Test how your application handles varying response times
- Validate error handling
























































































- Analyze performance under different load patterns
