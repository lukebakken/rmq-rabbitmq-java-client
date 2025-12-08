# PublisherConfirmationState Refactoring Plan

**Date:** 2025-12-09
**Status:** 🚧 Planning
**Related:** [Issue #1824](https://github.com/rabbitmq/rabbitmq-java-client/issues/1824)

## Motivation

`ChannelN.java` is a large, complex class (73,902 bytes) that handles multiple concerns.
Publisher confirmation tracking logic is scattered throughout the class, making it
difficult to understand, test, and maintain.

**Current state:**
- Fields: `publisherConfirmationTrackingEnabled`, `rateLimiter`, `confirmsFutures`, `confirmsPermits`, `unconfirmedSet`, `nextPublishSeqNo`, `onlyAcksReceived`
- Logic spread across: constructors, `basicPublishAsync()`, `handleAckNack()`, `callReturnListeners()`, `processShutdownSignal()`
- ~200+ lines of confirmation-related code mixed with other channel concerns

## Goal

Extract publisher confirmation tracking into a cohesive `PublisherConfirmationState` class that:
- Encapsulates all confirmation-related state and logic
- Provides clear API for `ChannelN` to interact with
- Improves testability (can unit test in isolation)
- Reduces complexity of `ChannelN`
- Maintains all existing functionality and tests

## Open Questions

### 1. Scope & Responsibilities
- ✅ Track futures and permits
- ✅ Manage sequence numbers
- ✅ Handle rate limiter interaction
- ❓ Include `confirmSelectActivated` flag?
- ❓ Include `onlyAcksReceived` flag (used by `waitForConfirms()`)?
- ❓ Include `unconfirmedSet` (also used by `waitForConfirms()`)?

### 2. Lifecycle & Ownership
- When to create: constructor or lazy initialization?
- Null when disabled or always present?
- Shutdown handling approach?

### 3. API Design
Proposed interface:
```java
class PublisherConfirmationState {
    PublishInfo startPublish(RateLimiter.Permit permit, BasicProperties props);
    void handleAck(long seqNo, boolean multiple);
    void handleNack(long seqNo, boolean multiple);
    void handleReturn(long seqNo, String exchange, String routingKey, int replyCode, String replyText);
    void shutdown(Exception cause);
}
```

### 4. Thread Safety
- Self-contained synchronization?
- Reuse existing locks?

### 5. Testing Strategy
- Unit tests for `PublisherConfirmationState`?
- Rely on existing integration tests?

### 6. Backward Compatibility
- `waitForConfirms()` methods depend on `unconfirmedSet` and `onlyAcksReceived`
- Keep in `ChannelN` or move to state class?

## Benefits

**Code organization:**
- Single responsibility for confirmation tracking
- Easier to understand and modify
- Clear separation of concerns

**Testability:**
- Can unit test confirmation logic in isolation
- Easier to test edge cases
- Reduced test setup complexity

**Maintainability:**
- Changes to confirmation tracking isolated to one class
- Reduced risk of breaking unrelated channel functionality
- Clearer code review scope

## Implementation Approach

1. Create `PublisherConfirmationState` class
2. Move fields and logic incrementally
3. Update `ChannelN` to delegate to state class
4. Verify all tests still pass
5. Add unit tests for state class (optional)

## Timeline

- **2025-12-09:** Planning and design
- **Next:** Implementation
- **Target:** Complete refactoring while maintaining all tests passing

## References

- [ChannelN.java](../src/main/java/com/rabbitmq/client/impl/ChannelN.java)
- [Publisher Confirms Documentation](./publisher-confirms-async.md)
- [Throttling Rate Limiter](./throttling-rate-limiter.md)
