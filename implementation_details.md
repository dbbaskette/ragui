# Implementation Details: Real-time Backend Status Updates

## Context
- The backend exposes `/api/status` which returns the current backend status via the `AppStatus` singleton.
- `RagService.chat()` emits status updates via a `RagStatusListener`.
- Previously, `ChatController` passed a no-op listener, so status updates were not reflected in `AppStatus`.

## Change Summary (2025-06-06)
- `AppStatus` is now injected into `ChatController`.
- The lambda passed to `ragService.chat` now calls `appStatus.setStatus(status)`, updating the backend status on every status change during chat.
- This enables `/api/status` to reflect real-time backend processing states, improving frontend feedback and user experience.

## Backward Compatibility
- No existing functionality is removed or changed; this is an additive improvement.
- No breaking changes to API or data formats.

## Example
When a chat request is processed, the backend status will update as follows:
- "Received request"
- "Querying vector DB for raw context"
- "Calling LLM with prompt"
- etc.

Frontend polling `/api/status` will see these updates in real-time.

## Security Configuration Fix (2025-07-01)
- **Issue 1**: 403 Forbidden errors when frontend tries to access API endpoints in Cloud Foundry
- **Root Cause 1**: Spring Security was requiring authentication for all requests including API endpoints
- **Issue 2**: Blank white screen with MIME type errors for CSS/JS files
- **Root Cause 2**: Static files (`style.css`, `main.js`) were being redirected to login page instead of being served
- **Solution**: Modified `SecurityConfig.java` and `application.properties` to:
  - Permit all `/api/**` endpoints and disable CSRF for API calls
  - Allow root-level static files (`/*.css`, `/*.js`, `/*.png`, `/*.jpg`, `/*.ico`)
  - Allow actuator endpoints (`/actuator/**`) for monitoring
  - Enable health check details (`management.endpoint.health.show-details=always`)
  - Preserve existing static resource paths (`/css/**`, `/js/**`, `/images/**`)
- **Impact**: Frontend loads properly, API calls work, and monitoring endpoints are accessible
- **Code Analysis**: No authentication-dependent code exists - all controllers and services work anonymously
- **Security Note**: This is a temporary fix for development/demo purposes. For production, implement proper API authentication (JWT, API keys, etc.)

## Deployment Script (2025-07-01)
- **New Script**: Added `deploy.sh` for streamlined build and deployment workflow
- **Features**:
  - Combines Maven build (`mvn clean package`) and Cloud Foundry deployment (`cf push`)
  - Auto-detects current version from `pom.xml` and updates JAR path in manifest
  - Validates CF CLI availability and login status
  - Provides colored output with clear status indicators
  - Supports `--skip-build` for quick redeploys
  - Supports `--app-name` for deploying to different app instances
- **Usage Examples**:
  - `./deploy.sh` - Full build and deploy
  - `./deploy.sh --skip-build` - Deploy existing JAR (faster)
  - `./deploy.sh --app-name ragui-prod` - Deploy to production instance
- **Benefits**: Simplifies the deploy process vs. manual `mvn clean package` + `cf push`

---

*See also: gotchas.md for edge cases and warnings.*

# RAG UI Implementation Details

## Overview
This document contains technical implementation details, configuration notes, and lessons learned during the development and deployment of the RAG UI application.

## EmbedProc RabbitMQ Monitoring Integration (Latest Update)

### Overview
Added comprehensive RabbitMQ integration to monitor embedProc instances in real-time. This feature consumes metrics from the `embedproc.metrics` queue and provides REST endpoints to view processing status.

### New Components Added

#### Dependencies
- **spring-boot-starter-amqp**: Added to `pom.xml` for RabbitMQ support

#### Configuration Properties
**Local Development (`application.properties`)**:
```properties
# RabbitMQ Configuration for embedProc streams (local development)
spring.rabbitmq.host=${RABBIT_HOST:localhost}
spring.rabbitmq.port=${RABBIT_PORT:5672}
spring.rabbitmq.username=${RABBIT_USER:guest}
spring.rabbitmq.password=${RABBIT_PASSWORD:guest}
spring.rabbitmq.virtual-host=${RABBIT_VHOST:/}

# EmbedProc Monitoring Configuration
app.monitoring.rabbitmq.queue-name=embedproc.metrics
app.monitoring.rabbitmq.enabled=${EMBEDPROC_MONITORING_ENABLED:true}
app.monitoring.rabbitmq.cache-duration-minutes=${EMBEDPROC_CACHE_DURATION:30}
```

**Cloud Deployment (`application-cloud.properties`)**:
```properties
# RabbitMQ Configuration for embedProc streams
spring.rabbitmq.host=${vcap.services.rabbitmq.credentials.host:localhost}
spring.rabbitmq.port=${vcap.services.rabbitmq.credentials.port:5672}
spring.rabbitmq.username=${vcap.services.rabbitmq.credentials.username:guest}
spring.rabbitmq.password=${vcap.services.rabbitmq.credentials.password:guest}
spring.rabbitmq.virtual-host=${vcap.services.rabbitmq.credentials.vhost:/}
```

#### New Classes Created

1. **EmbedProcMetrics.java** - DTO for embedProc metrics message format
   - Maps JSON from RabbitMQ queue: `instanceId`, `timestamp`, `totalChunks`, `processedChunks`, `errorCount`, `processingRate`, `uptime`, `status`
   - Includes calculated `progressPercentage` property
   - Tracks `lastUpdated` timestamp for cache management

2. **EmbedStatusResponse.java** - Response DTO for `/embed-status` endpoint
   - Contains list of current instances and summary statistics
   - Summary includes: total/active instances, processed chunks, error counts, processing rates, status distribution

3. **RabbitMQConfig.java** - RabbitMQ configuration
   - Declares `embedproc.metrics` queue as durable
   - Configures Jackson JSON message converter
   - Only activated when `app.monitoring.rabbitmq.enabled=true`

4. **EmbedProcMonitoringService.java** - Core monitoring service
   - `@RabbitListener` for consuming metrics from queue
   - Thread-safe cache using `ConcurrentHashMap`
   - Automatic cleanup of stale entries (configurable duration)
   - Generates summary statistics and status counts

5. **EmbedStatusController.java** - REST controller
   - Main endpoint: `GET /api/embed-status` - Returns all instance statuses
   - Instance-specific: `GET /api/embed-status/{instanceId}` - Single instance status
   - Debug endpoints: `/embed-status/debug/cache` and `/embed-status/debug/clear-cache`
   - Health check: `/embed-status/health`

### Features Implemented

- **Real-time Monitoring**: Automatically consumes RabbitMQ messages as embedProc instances send status updates
- **Intelligent Caching**: Maintains recent status of all instances with configurable expiration (default 30 minutes)
- **Summary Statistics**: Aggregates data across all instances (total processed, error counts, average rates)
- **Status Tracking**: Counts instances by status (PROCESSING, ERROR, COMPLETED, etc.)
- **Error Handling**: Graceful handling of RabbitMQ connection issues and malformed messages
- **Conditional Activation**: Can be disabled via `app.monitoring.rabbitmq.enabled=false`
- **Debug Capabilities**: Cache inspection and manual cache clearing for troubleshooting

### API Endpoints

- `GET /api/embed-status` - Current status of all embedProc instances
- `GET /api/embed-status/{instanceId}` - Status of specific instance  
- `GET /api/embed-status/health` - Health check for monitoring system
- `GET /api/embed-status/debug/cache` - Cache statistics and debug info
- `POST /api/embed-status/debug/clear-cache` - Manual cache clearing

### Example Response Format
```json
{
  "instances": [
    {
      "instanceId": "embedProc-worker-1",
      "timestamp": "2025-01-03T10:30:00Z",
      "totalChunks": 1500,
      "processedChunks": 750,
      "errorCount": 2,
      "processingRate": 12.5,
      "uptime": "2h 15m",
      "status": "PROCESSING",
      "lastUpdated": "2025-01-03T10:30:05Z",
      "progressPercentage": 50.0
    }
  ],
  "summary": {
    "totalInstances": 1,
    "activeInstances": 1,
    "totalProcessedChunks": 750,
    "totalErrorCount": 2,
    "averageProcessingRate": 12.5,
    "lastRefresh": "2025-01-03T10:30:05Z",
    "statusCounts": {
      "PROCESSING": 1
    }
  }
}
```

### Integration Benefits
- **Non-intrusive**: Additive feature, no existing functionality modified
- **Configurable**: All RabbitMQ settings via properties, can be disabled
- **Cloud-ready**: Supports Cloud Foundry VCAP services binding
- **Production-ready**: Proper error handling, logging, and health checks
- **RESTful**: Standard REST API patterns with appropriate HTTP status codes

## RAG Mode Improvements (Previous Update)

### RAG Only Mode Improvements (Latest Update)

### RAG Only Response Quality Fix
**Problem**: RAG Only mode was returning verbose LLM reasoning steps instead of clean, direct answers. Users were seeing all the internal thinking process like "Looking through the context", "Let me check again", etc.

**Root Cause**: 
- **Streaming vs. Reasoning Conflict**: Reasoning models naturally generate thinking chunks first, and streaming sends these chunks immediately to the user
- Chunk-level filtering couldn't effectively separate reasoning from final answers  
- Fighting the natural flow of reasoning models instead of working with it

**Solution**:
- **Hybrid Approach - Non-Streaming Processing + Simulated Streaming**: Let the LLM complete its full response (including internal thinking), clean it, then simulate streaming to maintain consistent UX
- **Enhanced System Prompt**: Updated to explicitly allow internal thinking while requiring clean output: "You may think through the question internally, but your response must contain ONLY the final answer based on the provided context"
- **Improved Response Processing**: Post-process the complete response using `cleanRawLlmResponse()` to extract the final answer and remove any reasoning that slipped through
- **Streaming Simulation**: Added `simulateStreamingResponse()` method that chunks the clean response word-by-word to match the streaming experience of other modes
- **Better Error Handling**: Added proper timeout and error handling for the non-streaming approach

**Benefits**:
- **Better Answer Quality**: LLM can use full reasoning capabilities to analyze context
- **Clean User Experience**: Users only see the final, polished answer without reasoning steps
- **Consistent UX**: All response modes now have the same streaming appearance to users
- **Reliable Output**: No more reasoning chunks leaking through to the frontend
- **Optimal Performance**: Single LLM call with simulated streaming for best of both worlds

**Key Behavioral Changes**:
- **RAG Only**: Now provides clean, direct answers sourced only from retrieved context (appears streaming but internally processes non-streaming for quality)
- **RAG + LLM Fallback**: Still allows broader LLM knowledge when context is insufficient (true streaming)
- **Pure LLM**: Unchanged - uses full LLM capabilities without context restrictions (true streaming)

**User Experience**: All modes now appear to stream text naturally, maintaining consistent interface behavior while RAG Only delivers superior answer quality through internal processing.

**Files Modified**:
- `src/main/java/com/baskettecase/ragui/service/RagService.java`: Switched RAG Only to non-streaming, enhanced system prompts, improved response processing

## RAG Configuration Fixes (Previous Update)

### Issues Identified and Resolved

#### 1. Vector Search Similarity Threshold Too High
**Problem**: The `VectorStoreDocumentRetriever` was hardcoded with a similarity threshold of 0.7, which is very restrictive for Spring AI 1.0.0. This caused the vector search to return 0 results even for queries that should match existing documents.

**Root Cause**: Spring AI 1.0.0 documentation shows that the default threshold is 0.0 (accept all), and values closer to 1 indicate higher similarity. A threshold of 0.7 was excluding too many potentially relevant documents.

**Solution**: 
- Made similarity threshold configurable via `ragui.vector.similarity-threshold` property
- Set default to 0.5 (more permissive)
- Added `ragui.vector.top-k` property for configurable result count
- Updated both local and cloud configurations

**Configuration Added**:
```properties
# RAG Configuration - More permissive similarity threshold
ragui.vector.similarity-threshold=0.3
ragui.vector.top-k=5
```

#### 2. Vector Store Dimension Mismatch
**Problem**: Local development used 768 dimensions while cloud used 1536 dimensions, suggesting different embedding models.

**Solution**: 
- Standardized both environments to use 1536 dimensions (OpenAI text-embedding-ada-002 standard)
- Added explicit embedding model configuration
- Ensured consistency between local and cloud profiles

#### 3. Poor Error Message Formatting
**Problem**: Error messages showed technical details like "No relevant context was found to answer your question.\n\nSource: 0 (no context)" which is confusing for users.

**Solution**: Cleaned up all error messages to be user-friendly:
- "I couldn't find relevant information in the knowledge base to answer your question."
- "I couldn't find any relevant documents in the knowledge base for your question."

#### 4. Missing Embedding Model Configuration
**Problem**: No explicit embedding model configuration could lead to inconsistent vector dimensions.

**Solution**: Added explicit embedding model configuration in both profiles:
```properties
# Local
spring.ai.openai.embedding.options.model=text-embedding-ada-002

# Cloud
spring.ai.openai.embedding.options.model=${vcap.services.embed-model.credentials.model_name:text-embedding-ada-002}
```

### Files Modified
- `src/main/java/com/baskettecase/ragui/service/RagService.java`: Made similarity threshold configurable, improved error messages
- `src/main/resources/application.properties`: Added RAG config, fixed dimensions, added embedding model
- `src/main/resources/application-cloud.properties`: Added RAG config, added embedding model config

### Testing Recommendation
After these changes, test with:
1. Simple queries that should definitely match (e.g., if you have Spring Boot docs, query "What is Spring Boot?")
2. Verify that similarity threshold can be adjusted via configuration
3. Check that error messages are now user-friendly when no matches are found

## Previous Implementation Details

## Security Configuration Updates

### Spring Security Configuration Fix
**Issue**: After deploying to Cloud Foundry, the application returned 403 Forbidden errors for API calls.
**Cause**: Spring Security was requiring authentication for all requests, including the `/api/**` endpoints used by the frontend.
**Solution**: Updated `SecurityConfig.java` to permit API endpoints and static resources without authentication.

**Key Changes**:
- Added `.requestMatchers("/api/**").permitAll()` for API endpoints
- Added `.requestMatchers("/*.css", "/*.js", "/*.png", "/*.ico", "/*.html").permitAll()` for static files
- Disabled CSRF with `.csrf(csrf -> csrf.disable())`
- Added `/actuator/**` to permitted paths

### Static Resource Serving Fix
**Issue**: CSS and JavaScript files were being served as HTML content (redirected to login page).
**Solution**: Added specific matchers for root-level static files in security configuration.

## Actuator Configuration
**Change**: Set `management.endpoint.health.show-details=always` to display health check details after removing authentication.

## UI Layout Improvements

### Status Panel Optimization
**Issue**: With 8+ status messages, the status area and chat window grew so large that response mode controls were pushed off-screen.

**Solution**: Implemented comprehensive CSS improvements:
- Reduced chat window height from 450px to 320px on desktop
- Added status log panel with:
  - Maximum height of 100px with scrolling
  - Smaller font size (0.65em)
  - Compact spacing (2px between entries)
  - Monospace font for consistency
  - Automatic scroll to newest entries
- Enhanced mobile responsiveness with even smaller fonts and heights
- Status entries now show ellipsis for long text
- Most recent status entry is highlighted

**Files Modified**: `src/main/resources/static/style.css`

## Deployment Automation

### Deploy Script Creation
Created `deploy.sh` script for streamlined deployment process:
- Combines Maven build and Cloud Foundry deployment
- Auto-detects version from pom.xml
- Validates CF CLI installation and login status
- Updates manifest.yml with correct JAR path automatically
- Supports command-line options (`--skip-build`, `--app-name`)
- Provides colored output and status indicators
- Made executable with proper permissions

**Usage**: `./deploy.sh` or `./deploy.sh --skip-build --app-name my-app`

## Architecture Notes

### Vector Store
- Using PostgreSQL with pgvector extension
- Configured for COSINE_DISTANCE similarity
- Table name: `vector_store`
- HNSW index type for efficient similarity search

### Spring AI Integration
- Spring AI 1.0.0 with OpenAI integration
- Automatic configuration via Cloud Foundry VCAP services
- RAG implementation using VectorStoreDocumentRetriever
- Streaming and non-streaming chat support

### Job Management
- Asynchronous processing with ExecutorService
- Timeout handling (180 seconds default)
- Status tracking with progress indicators
- Support for multiple response modes (RAG-only, RAG+LLM fallback, Pure LLM, Raw RAG)

## Gotchas and Lessons Learned

### Cloud Foundry Deployment
1. **VCAP Services**: Ensure service names in manifest.yml match bound services
2. **Memory Allocation**: 2GB memory allocation needed for Spring AI + OpenAI integration
3. **Profile Activation**: Use `SPRING_PROFILES_ACTIVE: cloud` for environment-specific config
4. **Static Resources**: Spring Security can interfere with static file serving

### Spring Security
1. **API Endpoints**: Remember to explicitly permit API endpoints used by frontend
2. **Static Files**: Root-level static files need specific security matchers
3. **CSRF**: Disable CSRF for API-only applications to avoid token issues

### Spring AI Vector Search
1. **Similarity Thresholds**: Lower thresholds (0.3-0.5) work better than high thresholds (0.7+)
2. **Dimensions**: Ensure vector store dimensions match embedding model dimensions
3. **Error Handling**: Provide user-friendly messages for no-match scenarios
4. **Configuration**: Make similarity thresholds configurable rather than hardcoded
5. **RAG Only Mode**: Requires strong system prompts and response filtering to prevent LLM reasoning display

### UI Considerations
1. **Status Logging**: Implement scrollable, height-limited status panels for better UX
2. **Mobile Responsiveness**: Test with various screen sizes and status message counts
3. **Response Mode Controls**: Ensure critical UI elements remain visible with dynamic content

