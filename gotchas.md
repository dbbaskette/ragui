# Gotchas and Edge Cases

## LLM Response Truncation Issue

### Problem Description
**Issue**: LLM responses were getting truncated mid-sentence, affecting both Pure LLM and RAG modes. This was not a token limit issue but rather a response processing problem.

**Symptoms**:
- Responses cut off abruptly mid-sentence
- Incomplete answers that seemed to stop randomly
- No error messages, just incomplete responses
- Affected both Pure LLM and RAG modes equally

### Root Cause
- **Structured Prompting Issues**: Complex system prompts with `<thinking>` and `<answer>` blocks were causing the LLM to generate incomplete responses
- **Aggressive Response Cleaning**: The `extractAnswer()` method was too aggressive in cleaning up responses, potentially truncating valid content
- **Inconsistent Response Formatting**: LLM wasn't consistently formatting responses with the expected `<answer>` tags
- **Complex System Prompts**: Overly complex instructions were causing the LLM to generate incomplete responses

### Solution Implemented
1. **Simplified System Prompts**: Replaced complex structured prompting with direct, clear instructions
2. **Robust Answer Extraction**: Enhanced `extractAnswer()` method to handle various response formats gracefully
3. **Improved Logging**: Added comprehensive logging for response processing and debugging
4. **Token Management**: Maintained token management as secondary protection against actual token limits

### Prevention Strategies
- Use simple, direct system prompts instead of complex structured instructions
- Implement robust response extraction with multiple fallback strategies
- Monitor response processing logs for extraction method used
- Test with various response formats to ensure robustness
- Keep system prompts concise and clear to avoid incomplete responses

### Configuration
```properties
# Token Management - Prevent LLM truncation
ragui.token.max-total-tokens=8000
ragui.token.max-context-tokens=3000
ragui.token.max-response-tokens=2000
```

### Testing
To verify the fix works:
1. Test with large context documents
2. Monitor logs for token estimation messages
3. Verify responses complete fully without truncation
4. Check that context is intelligently truncated when needed

## Vector Search Similarity Threshold

### Problem Description
**Issue**: Vector search was returning 0 results even for queries that should match existing documents.

### Root Cause
Similarity threshold was set too high (0.7) for Spring AI 1.0.0, which uses cosine distance where values closer to 1 indicate higher similarity.

### Solution
- Made similarity threshold configurable via `ragui.vector.similarity-threshold`
- Set default to 0.5 (more permissive)
- Added `ragui.vector.top-k` property for configurable result count

### Prevention
- Always test vector search with known queries
- Monitor similarity scores in logs
- Adjust threshold based on document quality and embedding model

## Security Configuration Issues

### Problem Description
**Issue**: 403 Forbidden errors when frontend tries to access API endpoints in Cloud Foundry.

### Root Cause
Spring Security was requiring authentication for all requests including API endpoints.

### Solution
Updated `SecurityConfig.java` to permit API endpoints and static resources without authentication.

### Prevention
- Test API endpoints after security configuration changes
- Verify static resource serving works correctly
- Monitor for authentication-related errors in production

## Static Resource Serving Issues

### Problem Description
**Issue**: CSS and JavaScript files were being served as HTML content (redirected to login page).

### Root Cause
Static files were being redirected to login page instead of being served directly.

### Solution
Added specific matchers for root-level static files in security configuration.

### Prevention
- Test static resource loading after security changes
- Verify MIME types are correct for all static files
- Monitor for 404 errors on static resources

## Database Connection Issues

### Problem Description
**Issue**: Database connection failures in Cloud Foundry environment.

### Root Cause
VCAP_SERVICES configuration not properly mapped to Spring Boot properties.

### Solution
Updated `application-cloud.properties` to properly map VCAP service credentials.

### Prevention
- Test database connectivity in cloud environment
- Verify VCAP_SERVICES binding is correct
- Monitor database connection health checks

## RabbitMQ Connection Issues

### Problem Description
**Issue**: RabbitMQ connection failures for embedProc monitoring.

### Root Cause
Missing or incorrect RabbitMQ configuration in cloud environment.

### Solution
Added proper VCAP_SERVICES mapping for RabbitMQ credentials in cloud configuration.

### Prevention
- Test RabbitMQ connectivity in cloud environment
- Verify VCAP_SERVICES binding for RabbitMQ
- Monitor RabbitMQ connection health

## General Best Practices

### Configuration Management
- Always test configuration changes in target environment
- Use environment-specific property files
- Validate VCAP_SERVICES binding for cloud deployments
- Monitor configuration loading in application startup logs

### Error Handling
- Implement comprehensive error handling for all external service calls
- Add timeout configurations for all async operations
- Log detailed error information for debugging
- Provide user-friendly error messages

### Performance Monitoring
- Monitor response times for all API endpoints
- Track token usage and context sizes
- Monitor vector search performance
- Log performance metrics for optimization

### Security Considerations
- Regularly review security configuration
- Test authentication flows in production environment
- Monitor for unauthorized access attempts
- Keep dependencies updated for security patches 