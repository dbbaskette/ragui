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

---

*See also: gotchas.md for edge cases and warnings.*

