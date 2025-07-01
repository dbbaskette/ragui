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

---

*See also: gotchas.md for edge cases and warnings.*

