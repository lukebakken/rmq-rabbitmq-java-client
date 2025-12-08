# Context Parameter for Message Correlation

**Date:** 2025-12-09
**Status:** ✅ Complete

## Problem

When publishing messages asynchronously, applications need to track which message was confirmed or failed. Without built-in correlation, applications must maintain separate data structures:

```java
// ❌ Without context - requires separate tracking
Map<String, CompletableFuture<Void>> tracking = new ConcurrentHashMap<>();

String msgId = generateMessageId();
CompletableFuture<Void> future = channel.basicPublishAsync(...);
tracking.put(msgId, future);

future.whenComplete((v, ex) -> {
    tracking.remove(msgId);  // Manual cleanup
    if (ex != null) {
        handleFailure(msgId);  // Must capture msgId
    }
});
```

**Issues:**
- Separate data structure needed
- Manual correlation and cleanup
- Closure captures can be error-prone
- Race conditions if not careful

## Solution

Add a generic context parameter to `basicPublishAsync()` that flows through the `CompletableFuture`:

```java
<T> CompletableFuture<T> basicPublishAsync(String exchange, String routingKey,
                                           BasicProperties props, byte[] body, T context);
```

## Usage

### Success Case

```java
String messageId = "order-12345";
channel.basicPublishAsync("", queue, null, body, messageId)
    .thenAccept(ctx -> {
        System.out.println("Confirmed: " + ctx);
        // ctx is "order-12345"
    });
```

### Error Case

```java
String messageId = "order-12345";
channel.basicPublishAsync("", queue, true, null, body, messageId)
    .exceptionally(ex -> {
        if (ex.getCause() instanceof PublishException) {
            PublishException pe = (PublishException) ex.getCause();
            String msgId = (String) pe.getContext();
            System.err.println("Message " + msgId + " failed");
        }
        return null;
    });
```

### Batch Publishing Pattern

```java
Map<String, CompletableFuture<String>> outstandingPublishes = new ConcurrentHashMap<>();

for (Message msg : messages) {
    String msgId = msg.getId();
    CompletableFuture<String> future = channel.basicPublishAsync(
        "", queue, null, msg.getBody(), msgId
    );
    
    future.whenComplete((ctx, ex) -> {
        outstandingPublishes.remove(ctx);
        if (ex != null) {
            handleFailure(ctx, ex);
        } else {
            handleSuccess(ctx);
        }
    });
    
    outstandingPublishes.put(msgId, future);
}

// Wait for all
CompletableFuture.allOf(outstandingPublishes.values().toArray(new CompletableFuture[0])).join();
```

## Implementation Details

### API Changes

**Channel interface:**
```java
<T> CompletableFuture<T> basicPublishAsync(..., T context);
```

**PublishException:**
```java
public class PublishException extends IOException {
    private final Object context;
    
    public Object getContext() { return context; }
}
```

**ChannelN:**
```java
private static class ConfirmationEntry<T> {
    final CompletableFuture<T> future;
    final RateLimiter.Permit permit;
    final T context;
}
```

### Type Safety

The context type is preserved through the `CompletableFuture<T>`:
- Compile-time type checking
- No casting needed in success case
- Casting needed in exception case (Java limitation)

### Null Context

Passing `null` is valid when correlation is not needed:
```java
channel.basicPublishAsync("", queue, null, body, null)
    .thenRun(() -> System.out.println("Confirmed"));
```

## Benefits

✅ **No separate tracking needed** - Context flows through the future
✅ **Type-safe** - Compiler enforces context type
✅ **Composable** - Works with all `CompletableFuture` operations
✅ **Available in errors** - Context accessible in `PublishException`
✅ **Flexible** - Can be correlation ID, message object, or any type

## Testing

**Tests added:**
- `testBasicPublishAsync` - Verifies 100 messages with `Integer` context
- `testMaxOutstandingConfirms` - Verifies context with throttling
- `testBasicPublishAsyncWithThrottling` - Verifies 50 messages with `String` context
- `testBasicPublishAsyncWithContext` - Demonstrates correlation ID pattern
- `testBasicPublishAsyncWithContextInException` - Verifies context in `PublishException`

**Result:** 25/25 Confirm tests passing

## Comparison with Other Approaches

| Approach | Pros | Cons |
|----------|------|------|
| **Context parameter** | Type-safe, composable, no separate tracking | Requires passing parameter |
| Separate Map | Familiar pattern | Manual correlation, race conditions |
| Message properties | No API change | Limited to String, pollutes headers |
| Wrapper class | Encapsulation | Extra object allocation, less composable |

## References

- [Channel.java](../src/main/java/com/rabbitmq/client/Channel.java) - API definition
- [PublishException.java](../src/main/java/com/rabbitmq/client/PublishException.java) - Exception with context
- [Confirm.java](../src/test/java/com/rabbitmq/client/test/functional/Confirm.java) - Integration tests
