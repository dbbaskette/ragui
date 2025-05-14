# Backend TODOs for ragui

## What’s Left to Do (Backend)

### 1. Test the End-to-End Chat Flow
- Ensure Ollama server, Postgres/pgvector, and Spring Boot app are all running.
- Test `/api/chat` with a POST request (e.g., using Postman or curl).
- Debug any runtime issues (e.g., connection errors, missing beans, misconfigurations).

### 2. Optional: Add Features for Robustness and Usability
- Error Handling: Return user-friendly errors for DB/LLM/network issues.
- Logging: Add logging for incoming requests, errors, and LLM responses.
- CORS Configuration: If your frontend SPA will be served from a different port during development.
- Chat History: Optionally persist chat history per user/session.
- Source Attribution: Return retrieved context or sources alongside the LLM answer.
- Health Check Endpoint: Useful for monitoring and ops.
- Security: Add basic auth, API keys, or OAuth if needed.

### 3. Database Table Check
- Ensure the `vector_store` table exists in your Postgres DB with the correct schema for pgvector.

### 4. Documentation
- Document your API endpoints (Swagger/OpenAPI or simple markdown).

---

## Minimum for "Backend Complete"
- You can POST to `/api/chat` and get a RAG-powered LLM response, with errors handled gracefully.
