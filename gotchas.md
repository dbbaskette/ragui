# RAG UI Gotchas and Edge Cases

## System Prompt Simplification

### Removed Complex Filtering
- **Issue**: Complex reasoning pattern filtering was causing inconsistent behavior
- **Solution**: Simplified to clean, direct system prompts for each mode
- **Impact**: More predictable responses, easier maintenance
- **Note**: No more post-processing filtering - relies on clear prompts

### System Prompt Changes
- **Pure LLM**: Simple, direct prompt without reasoning instructions
- **RAG + LLM Fallback**: Clear instruction to use context and knowledge
- **RAG Only**: Explicit instruction to use only context
- **Result**: Cleaner responses without internal reasoning

## Token Management

### LLM Truncation Prevention
- **Issue**: LLM responses getting truncated due to token limits
- **Solution**: Token-aware context management with character-based estimation
- **Configuration**: 
  - `ragui.token.max-total-tokens=16000`
  - `ragui.token.max-context-tokens=6000`
  - `ragui.token.max-response-tokens=6000`
- **Note**: Uses 1 token ≈ 4 characters approximation
- **Recent Fix**: Increased token limits to prevent truncation in Pure LLM and RAG + LLM Fallback modes

### Context Size Balancing
- **Issue**: Too much context leaves no room for response
- **Solution**: Dynamic context truncation based on token limits
- **Configuration**: `ragui.context.max-chars=4000`
- **Impact**: Prevents "Response too long" errors

## Vector Search Issues

### Similarity Threshold Tuning
- **Issue**: Too low threshold returns irrelevant documents
- **Current Setting**: `ragui.vector.similarity-threshold=0.6`
- **Impact**: Better quality matches, fewer false positives
- **Monitoring**: Check distance values in debug logs

### Document Retrieval Failures
- **Issue**: No documents found for valid queries
- **Causes**: 
  - Query too specific
  - Low similarity threshold
  - Missing embeddings
- **Debug**: Enable vector search debug logging
- **Solution**: Query expansion or threshold adjustment

## Query Processing

### Query Cleaning Failures
- **Issue**: LLM query cleaning sometimes fails
- **Fallback**: `ragui.debug.skip-query-cleaning=true`
- **Impact**: Uses original query directly
- **Monitoring**: Check query cleaning logs

### Query Expansion Issues
- **Issue**: Expanded queries may not improve retrieval
- **Control**: `QueryExpansionController.isEnabled()`
- **Debug**: Monitor expansion results
- **Solution**: Disable if not helping

## Streaming Issues

### Simulated Streaming Behavior
- **Issue**: Not real streaming, but simulated for consistency
- **Implementation**: `simulateStreamingResponse()` with word-level chunks
- **Delay**: 50ms between chunks for realistic typing effect
- **Note**: All modes use simulated streaming for UX consistency

### Progress Tracking
- **Issue**: Progress percentages may not reflect actual work
- **Implementation**: Fixed percentages for different stages
- **Stages**: Query cleaning (15-18%), Vector search (20-40%), LLM (70-90%)
- **Note**: Estimates, not actual progress

## Timeout Issues

### LLM Timeout
- **Timeout**: 180 seconds for all async operations
- **Issue**: Long responses may timeout
- **Monitoring**: Check timeout logs
- **Solution**: Increase timeout or optimize prompts

### Vector Search Timeout
- **Issue**: Large vector databases may timeout
- **Monitoring**: Vector search debug logs
- **Solution**: Optimize similarity threshold or reduce top-k

## Error Handling

### Graceful Degradation
- **Issue**: Complete failures when components fail
- **Solution**: Comprehensive try-catch with user-friendly messages
- **Examples**: 
  - "Request timed out while processing"
  - "Error occurred while processing with RAG context"
- **Note**: Always provides some response to user

### Logging Consistency
- **Issue**: Inconsistent log levels and formats
- **Solution**: Standardized logging with timestamps
- **Format**: `[timestamp] Component (Mode) call started/finished`
- **Debug**: Enable debug logging for troubleshooting

## Configuration Issues

### Environment-Specific Settings
- **Issue**: Different behavior in local vs cloud environments
- **Solution**: Environment-specific property files
- **Local**: `application.properties`
- **Cloud**: `application-cloud.properties`
- **Note**: Check which properties file is being used

### Service Binding
- **Issue**: Cloud Foundry service bindings not working
- **Symptoms**: Database connection failures, AI service errors
- **Debug**: Check VCAP_SERVICES environment variable
- **Solution**: Verify service bindings in manifest.yml

## Performance Issues

### Memory Usage
- **Issue**: Large context processing may use significant memory
- **Solution**: Token-aware context limits
- **Monitoring**: Check memory usage in Cloud Foundry
- **Optimization**: Reduce context size if needed

### Response Time
- **Issue**: Slow responses due to multiple async operations
- **Components**: Query cleaning, vector search, LLM processing
- **Optimization**: Parallel processing where possible
- **Monitoring**: Response time logging

## Debugging Tips

### Enable Debug Logging
```properties
logging.level.com.baskettecase.ragui=DEBUG
```

### Vector Search Debug
- Check distance values in logs
- Monitor document retrieval counts
- Verify similarity threshold impact

### Token Estimation Debug
- Monitor token estimates in logs
- Check context truncation decisions
- Verify token limit compliance

### Query Processing Debug
- Monitor query cleaning results
- Check query expansion effectiveness
- Verify length constraint extraction

## Common Issues and Solutions

### "No relevant information found"
- **Cause**: No documents above similarity threshold
- **Debug**: Check vector search logs
- **Solution**: Lower similarity threshold or improve query

### "Request timed out"
- **Cause**: LLM or vector search taking too long
- **Debug**: Check timeout logs
- **Solution**: Increase timeout or optimize queries

### "Error during processing"
- **Cause**: Various component failures
- **Debug**: Check exception logs
- **Solution**: Verify service connectivity and configuration

### Inconsistent Responses
- **Cause**: Different response modes or token limits
- **Debug**: Check response mode and token usage
- **Solution**: Ensure consistent configuration across environments

## Best Practices

### Configuration Management
- Use environment-specific property files
- Document all configuration changes
- Test configuration changes thoroughly

### Monitoring
- Enable appropriate log levels
- Monitor response times and error rates
- Track vector search performance

### Error Handling
- Always provide user-friendly error messages
- Log detailed error information for debugging
- Implement graceful degradation

### Performance
- Monitor token usage and context sizes
- Optimize similarity thresholds
- Use appropriate timeouts for operations 