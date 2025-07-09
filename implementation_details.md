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
RAG UI is a Spring Boot application that provides a conversational interface for querying a vector database using RAG (Retrieval-Augmented Generation) techniques. The system supports multiple response modes and provides both streaming and non-streaming interfaces.

## Core Components

### RagService
The main service that handles all RAG operations. Located at `src/main/java/com/baskettecase/ragui/service/RagService.java`.

#### Key Features:
- **Multiple Response Modes**: Pure LLM, RAG + LLM Fallback, RAG Only, and Raw RAG
- **Streaming Support**: Real-time response streaming with progress updates
- **Token Management**: Prevents LLM truncation through token-aware context management
- **Query Processing**: Intelligent query cleaning and expansion
- **Simple System Prompts**: Clean, direct prompts for each mode without complex filtering

#### Response Modes:

1. **Pure LLM Mode**
   - Uses only the LLM's knowledge
   - System prompt: "You are a helpful AI assistant. Answer the user's question directly and clearly using your knowledge."

2. **RAG + LLM Fallback Mode**
   - Combines retrieved context with LLM knowledge
   - System prompt: "You are a helpful AI assistant. Use the provided context and your knowledge to answer the user's question directly and clearly."

3. **RAG Only Mode**
   - Uses only retrieved context, no external knowledge
   - System prompt: "You are a helpful AI assistant. Answer the user's question using ONLY the provided context. If the context doesn't contain enough information, simply state that the information is not available in the provided context."

4. **Raw RAG Mode**
   - Returns raw retrieved documents without LLM processing
   - Used for debugging and document inspection

#### Simplified Answer Extraction:
- Removed complex filtering and reasoning pattern removal
- Simple `extractAnswer()` method that returns the LLM response as-is
- Clean, direct responses without post-processing

### Configuration

#### Token Management (application.properties):
```properties
# Token limits to prevent LLM truncation
ragui.token.max-total-tokens=8000
ragui.token.max-context-tokens=3000
ragui.token.max-response-tokens=2000
```

#### Context Management:
```properties
# Context size limits
ragui.context.max-chars=4000
ragui.context.min-doc-chars=200
```

#### Vector Search:
```properties
# Vector similarity and retrieval settings
ragui.vector.similarity-threshold=0.6
ragui.vector.top-k=5
```

### Query Processing

#### Query Cleaning:
- Uses LLM to clean and rephrase user queries
- Extracts length constraints for better responses
- Can be bypassed with `ragui.debug.skip-query-cleaning=true`

#### Query Expansion:
- Optional feature to improve retrieval
- Controlled by `QueryExpansionController.isEnabled()`

### Streaming Implementation

#### Simulated Streaming:
- Uses `simulateStreamingResponse()` for consistent UX
- Breaks responses into word-level chunks with small delays
- Provides progress updates through `RagStatusListener`

#### Progress Tracking:
- Query cleaning: 15-18%
- Vector search: 20-40%
- LLM processing: 70-90%
- Completion: 100%

### Error Handling

#### Timeout Management:
- 180-second timeout for all async operations
- Uses `CompletableFuture` with timeout executor
- Graceful error messages for timeouts

#### Exception Handling:
- Comprehensive try-catch blocks
- Detailed logging for debugging
- User-friendly error messages

### Performance Optimizations

#### Token-Aware Context Management:
- Estimates tokens using character count (1 token ≈ 4 characters)
- Prevents exceeding LLM token limits
- Balances context inclusion with response space

#### Async Processing:
- Non-blocking operations using `CompletableFuture`
- Shared timeout executor for resource efficiency
- Concurrent vector search and LLM calls

### Debugging Features

#### Logging:
- Detailed debug logs for vector search
- Token estimation logging
- Response mode tracking

#### Configuration Flags:
- `ragui.debug.skip-query-cleaning`: Bypass query processing
- Query expansion enable/disable
- Verbose logging controls

## Architecture Patterns

### Service Layer:
- `RagService`: Main business logic
- `JobService`: Background job processing
- `EmbedProcMonitoringService`: Embedding process monitoring

### Controller Layer:
- `ChatController`: Main chat interface
- `JobController`: Background job management
- `StatusController`: System status endpoints

### Configuration:
- `AiConfig`: AI service configuration
- `SecurityConfig`: Authentication setup
- `CloudConfig`: Cloud Foundry integration

## Integration Points

### Vector Database:
- PostgreSQL with pgvector extension
- Cosine distance similarity
- Configurable similarity thresholds

### AI Services:
- OpenAI-compatible API endpoints
- Configurable models for chat and embedding
- Token-aware prompt management

### Cloud Foundry:
- Service binding for database and AI services
- Environment-specific configuration
- Health check endpoints

## Security

### Authentication:
- Basic authentication with configurable credentials
- Default user: `tanzu` / `t@nzu123`
- Configurable via `app.security.default-user.*`

### CORS:
- Configured for web interface access
- Cross-origin request handling

## Monitoring

### Health Checks:
- Actuator endpoints for health monitoring
- Detailed health information exposure
- Cloud Foundry integration

### Metrics:
- Embedding process monitoring
- Response time tracking
- Error rate monitoring

## Deployment

### Cloud Foundry:
- `manifest.yml` for deployment configuration
- Service bindings for database and AI services
- Environment-specific properties

### Build:
- Maven-based build system
- Spring Boot executable JAR
- Maven wrapper included

## Development Workflow

### Local Development:
- H2 database for local testing
- Embedded vector store
- Hot reload support

### Testing:
- Unit tests for service components
- Integration tests for API endpoints
- Mock services for external dependencies

### Documentation:
- Comprehensive inline documentation
- Implementation details tracking
- Gotchas and edge cases documented

