# Automatic Publisher Confirmation Tracking

## Overview

This feature provides automatic tracking and awaiting of publisher confirmations in the Java AMQP client, similar to the .NET client implementation. When enabled, the library automatically manages publisher confirms and provides an async API that completes when messages are confirmed by the broker.

## Motivation

Traditional publisher confirms in the Java client require manual tracking:

```java
channel.confirmSelect();
channel.basicPublish(exchange, routingKey, props, body);
channel.waitForConfirms(); // Blocks until all messages confirmed
```

This approach has limitations:
- Manual sequence number tracking required for per-message handling
- Difficult to correlate Basic.Return with specific messages
- No built-in async/await pattern
- Complex error handling for nacks and returns

The new automatic tracking feature addresses these issues by:
- Automatically tracking each message's confirmation status
- Providing async API with `CompletableFuture`
- Handling Basic.Return as nack with full error details
- Managing sequence numbers transparently

## Basic Usage

### Enabling Publisher Confirmation Tracking

Create a channel with `ChannelOptions`:

```java
ChannelOptions options = new ChannelOptions(
    true,  // publisherConfirmationsEnabled
    true,  // publisherConfirmationTrackingEnabled
    100    // maxOutstandingConfirms (0 = unlimited)
);

Channel channel = connection.createChannel(options);
```

### Publishing with Automatic Confirmation

Use `basicPublishAsync()` instead of `basicPublish()`:

```java
CompletableFuture<Void> future = channel.basicPublishAsync(
    "",           // exchange
    queueName,    // routingKey
    MessageProperties.PERSISTENT_TEXT_PLAIN,
    "Hello".getBytes()
);

// Wait for confirmation
future.join();
```

### Handling Confirmations and Errors

```java
CompletableFuture<Void> future = channel.basicPublishAsync(
    exchange, routingKey, props, body
);

future.thenRun(() -> {
    System.out.println("Message confirmed by broker");
}).exceptionally(ex -> {
    if (ex.getCause() instanceof PublishException) {
        PublishException pe = (PublishException) ex.getCause();
        if (pe.isReturn()) {
            System.err.println("Message returned: " + pe.getReplyText());
        } else {
            System.err.println("Message nack'd");
        }
    } else if (ex.getCause() instanceof AlreadyClosedException) {
        System.err.println("Channel closed before confirmation");
    }
    return null;
});
```

## Configuration Options

### ChannelOptions Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `publisherConfirmationsEnabled` | boolean | Enable publisher confirms on the channel (calls `confirmSelect()`) |
| `publisherConfirmationTrackingEnabled` | boolean | Enable automatic tracking and awaiting of confirmations |
| `maxOutstandingConfirms` | int | Maximum number of unconfirmed messages allowed (0 = unlimited) |

### Configuration Combinations

| Confirms Enabled | Tracking Enabled | Behavior |
|------------------|------------------|----------|
| `false` | `false` | Standard channel, no confirms. `basicPublishAsync()` completes immediately after send. |
| `true` | `false` | Confirms enabled, manual tracking via `waitForConfirms()`. `basicPublishAsync()` completes immediately after send. |
| `true` | `true` | Automatic tracking with async API. `basicPublishAsync()` completes when broker confirms. |
| `false` | `true` | Invalid - throws `IllegalArgumentException` in constructor |

**Note:** `basicPublishAsync()` can be called on any channel configuration. When tracking is disabled, the returned `CompletableFuture` completes immediately after the message is sent, providing a consistent async API regardless of configuration.

## API Reference

### Channel Interface

#### basicPublishAsync(String, String, BasicProperties, byte[])

```java
CompletableFuture<Void> basicPublishAsync(
    String exchange,
    String routingKey,
    BasicProperties props,
    byte[] body
)
```

Publishes a message and returns a future that completes when confirmed.

**Returns:** `CompletableFuture<Void>` that:
- Completes successfully when broker sends Basic.Ack
- Completes exceptionally with `PublishException` when:
  - Broker sends Basic.Nack
  - Broker returns message (Basic.Return)
- Completes exceptionally with `AlreadyClosedException` if channel closes
- Completes immediately if tracking is disabled

#### basicPublishAsync(String, String, boolean, BasicProperties, byte[])

```java
CompletableFuture<Void> basicPublishAsync(
    String exchange,
    String routingKey,
    boolean mandatory,
    BasicProperties props,
    byte[] body
)
```

Same as above but with `mandatory` flag support.

### Connection Interface

#### createChannel(ChannelOptions)

```java
Channel createChannel(ChannelOptions options) throws IOException
```

Creates a channel with the specified options.

**Parameters:**
- `options` - Channel configuration including publisher confirmation settings

**Returns:** New channel instance or null if no channels available

## Implementation Details

### Sequence Number Tracking

When tracking is enabled, the library:
1. Increments an internal sequence counter for each publish
2. Adds the sequence number to message headers as `x-java-pub-seq-no`
3. Stores a `CompletableFuture` mapped to the sequence number
4. Completes the future when Basic.Ack/Nack received

### Basic.Return Handling

When a message is returned by the broker:
1. Extract sequence number from `x-java-pub-seq-no` header
2. Look up the corresponding `CompletableFuture`
3. Complete it exceptionally with `PublishException` containing:
   - Sequence number
   - Exchange and routing key
   - Reply code and text
   - `isReturn() = true` flag

### Outstanding Confirms Limit

When `maxOutstandingConfirms > 0`:
- `basicPublishAsync()` blocks if limit reached
- Unblocks when confirmations received
- Prevents unbounded memory growth

#### Detailed Blocking Behavior

When the limit is reached, the following sequence occurs:

**1. Publisher Thread Blocks (Entry Check)**
```java
if (publisherConfirmationTrackingEnabled && maxOutstandingConfirms > 0) {
    synchronized (confirmLimitLock) {
        while (confirmsFutures.size() >= maxOutstandingConfirms) {
            confirmLimitLock.wait();  // Thread blocks here
        }
    }
}
```

The calling thread:
- Acquires `confirmLimitLock`
- Checks if `confirmsFutures.size() >= maxOutstandingConfirms`
- If true: calls `wait()` which **blocks the thread** and releases the lock
- Remains blocked until `notifyAll()` is called by the confirmation handler

**2. Confirmation Handler Unblocks (in handleAckNack)**
```java
if (publisherConfirmationTrackingEnabled && maxOutstandingConfirms > 0) {
    synchronized (confirmLimitLock) {
        confirmLimitLock.notifyAll();  // Wakes up blocked threads
    }
}
```

The reader thread (processing broker confirmations):
- Removes completed futures from `confirmsFutures` (reducing size)
- Acquires `confirmLimitLock`
- Calls `notifyAll()` to wake all waiting publisher threads
- Releases the lock

**3. Publisher Thread Resumes**

The blocked publisher thread:
- Wakes up from `wait()`
- Re-checks condition: `while (confirmsFutures.size() >= maxOutstandingConfirms)`
- If size is now below limit: exits loop and continues publishing
- If still at limit: goes back to `wait()` (handles spurious wakeups)

#### Key Characteristics

- **Synchronous blocking** - The calling thread blocks, not just the future
- **No FIFO guarantee** - Multiple blocked threads may wake in any order
- **Interruptible** - If thread is interrupted while waiting, the future completes exceptionally with `InterruptedException` and the method returns immediately
- **No timeout** - Thread waits indefinitely until space becomes available
- **Backpressure mechanism** - Prevents memory exhaustion by limiting in-flight messages

#### Important Notes

This is a **blocking backpressure** mechanism, not async backpressure:
- The thread itself blocks (synchronous wait)
- Simpler to implement and reason about
- Less efficient than async alternatives (thread remains allocated while blocked)
- Suitable for most use cases where publish rate naturally matches confirmation rate

Future enhancements could add async backpressure using reactive patterns or custom rate limiters.

### Thread Safety

- `basicPublishAsync()` is thread-safe
- Uses synchronized blocks for critical sections
- `CompletableFuture` callbacks execute asynchronously

### Channel Shutdown

When channel closes:
- All pending futures complete exceptionally with `AlreadyClosedException`
- Futures are cleared to prevent memory leaks

## Error Handling

### PublishException

```java
public class PublishException extends IOException {
    long getSequenceNumber()    // Message sequence number
    boolean isReturn()          // true if Basic.Return, false if Basic.Nack
    String getExchange()        // Exchange name (may be null)
    String getRoutingKey()      // Routing key (may be null)
    Integer getReplyCode()      // Reply code (may be null)
    String getReplyText()       // Reply text (may be null)
}
```

### Exception Scenarios

| Scenario | Exception | Cause |
|----------|-----------|-------|
| Broker nacks message | `PublishException` | `isReturn() = false` |
| Broker returns message | `PublishException` | `isReturn() = true` |
| Channel closes | `AlreadyClosedException` | Channel shutdown |
| Interrupted while waiting | `InterruptedException` | Thread interrupted |

## Migration Guide

### From Manual Tracking

**Before:**
```java
Channel channel = connection.createChannel();
channel.confirmSelect();

SortedSet<Long> unconfirmed = Collections.synchronizedSortedSet(new TreeSet<>());
channel.addConfirmListener(new ConfirmListener() {
    public void handleAck(long deliveryTag, boolean multiple) {
        if (multiple) {
            unconfirmed.headSet(deliveryTag + 1).clear();
        } else {
            unconfirmed.remove(deliveryTag);
        }
    }
    public void handleNack(long deliveryTag, boolean multiple) {
        // Handle nack
    }
});

long seqNo = channel.getNextPublishSeqNo();
unconfirmed.add(seqNo);
channel.basicPublish(exchange, routingKey, props, body);
// Wait for confirmation...
```

**After:**
```java
ChannelOptions options = new ChannelOptions(true, true, 100);
Channel channel = connection.createChannel(options);

channel.basicPublishAsync(exchange, routingKey, props, body)
    .thenRun(() -> System.out.println("Confirmed"))
    .exceptionally(ex -> {
        System.err.println("Failed: " + ex.getMessage());
        return null;
    });
```

### From waitForConfirms()

**Before:**
```java
channel.confirmSelect();
for (int i = 0; i < 100; i++) {
    channel.basicPublish(exchange, routingKey, props, body);
}
channel.waitForConfirms();
```

**After:**
```java
ChannelOptions options = new ChannelOptions(true, true, 0);
Channel channel = connection.createChannel(options);

List<CompletableFuture<Void>> futures = new ArrayList<>();
for (int i = 0; i < 100; i++) {
    futures.add(channel.basicPublishAsync(exchange, routingKey, props, body));
}
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
```

## Advanced Patterns

### Batch Publishing with Error Handling

```java
List<CompletableFuture<Void>> futures = new ArrayList<>();

for (Message msg : messages) {
    CompletableFuture<Void> future = channel.basicPublishAsync(
        msg.exchange, msg.routingKey, msg.props, msg.body
    );

    future.exceptionally(ex -> {
        if (ex.getCause() instanceof PublishException) {
            PublishException pe = (PublishException) ex.getCause();
            // Retry logic, dead letter, etc.
            handleFailedMessage(msg, pe);
        }
        return null;
    });

    futures.add(future);
}

// Wait for all
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
```

### Rate-Limited Publishing

```java
ChannelOptions options = new ChannelOptions(true, true, 50);
Channel channel = connection.createChannel(options);

// Automatically blocks when 50 outstanding confirms reached
for (int i = 0; i < 1000; i++) {
    channel.basicPublishAsync(exchange, routingKey, props, body);
    // Blocks here if limit reached, resumes when confirmations arrive
}
```

### Mandatory Publishing with Return Handling

```java
CompletableFuture<Void> future = channel.basicPublishAsync(
    exchange, routingKey,
    true,  // mandatory
    props, body
);

future.exceptionally(ex -> {
    if (ex.getCause() instanceof PublishException) {
        PublishException pe = (PublishException) ex.getCause();
        if (pe.isReturn()) {
            System.err.println("Message returned - no route found");
            System.err.println("Exchange: " + pe.getExchange());
            System.err.println("Routing key: " + pe.getRoutingKey());
            System.err.println("Reply: " + pe.getReplyText());
        }
    }
    return null;
});
```

## Compatibility

### Backward Compatibility

- Existing code continues to work unchanged
- `basicPublish()` methods unchanged
- `waitForConfirms()` methods unchanged
- New API is opt-in via `ChannelOptions`

### Version Requirements

- Java 8+ (uses `CompletableFuture`)
- RabbitMQ server 3.6.0+ (publisher confirms support)

## Limitations

### Current Implementation

1. **No rate limiting strategy** - Only hard limit on outstanding confirms
2. **Blocking on limit** - Thread blocks when limit reached (future: async backpressure)

## Testing

### Unit Tests (TODO)

- [ ] Test confirmation tracking with acks
- [ ] Test confirmation tracking with nacks
- [ ] Test Basic.Return handling
- [ ] Test outstanding confirms limit
- [ ] Test channel shutdown with pending confirms
- [ ] Test sequence number extraction from various types

### Integration Tests (TODO)

- [ ] Test with real RabbitMQ broker
- [ ] Test mandatory flag with unroutable messages
- [ ] Test high-throughput scenarios
- [ ] Test recovery scenarios

## Future Enhancements

1. **Rate Limiting Strategies**
   - Implement throttling rate limiter (like .NET's `ThrottlingRateLimiter`)
   - Support custom rate limiting strategies
   - Async backpressure instead of blocking

2. **Metrics and Observability**
   - Track confirmation latency
   - Monitor outstanding confirms count
   - Expose via `MetricsCollector`

## References

- [RabbitMQ Publisher Confirms](https://www.rabbitmq.com/confirms.html)
- [.NET Client Implementation](https://github.com/rabbitmq/rabbitmq-dotnet-client)
- [Java Client User Guide](https://www.rabbitmq.com/api-guide.html)

## Implementation Notes

### Design Decisions

**Why separate `basicPublishAsync()` methods?**
- Maintains backward compatibility
- Clear opt-in to async behavior
- Allows mixing sync and async in same channel

**Why block on `maxOutstandingConfirms`?**
- Simple implementation for initial version
- Prevents unbounded memory growth
- Future versions can add async backpressure

### Sequence Number Header

The library adds `x-java-pub-seq-no` header to each message when tracking is enabled. This allows:
- Correlation of Basic.Return with specific message within the same channel
- Debugging and troubleshooting

### Synchronization Strategy

The implementation uses multiple synchronization mechanisms:

1. **`confirmLimitLock`** - Controls outstanding confirms limit
   - Threads wait here when limit reached
   - Notified when confirmations received

2. **`confirmsFutures` map** - Synchronized map for futures
   - Thread-safe access to pending confirmations
   - Cleared on shutdown

3. **`unconfirmedSet`** - Existing synchronization for `waitForConfirms()`
   - Maintains compatibility with existing API
   - Used for both manual and automatic tracking

### Error Propagation

Exceptions are propagated through `CompletableFuture`:

```
basicPublishAsync()
    ↓
IOException during publish → future.completeExceptionally(IOException)
    ↓
Basic.Nack received → future.completeExceptionally(PublishException)
    ↓
Basic.Return received → future.completeExceptionally(PublishException)
    ↓
Channel closes → future.completeExceptionally(AlreadyClosedException)
    ↓
Basic.Ack received → future.complete(null)
```

## Code Examples

### Example 1: Simple Publish with Confirmation

```java
ConnectionFactory factory = new ConnectionFactory();
factory.setHost("localhost");

try (Connection connection = factory.newConnection()) {
    ChannelOptions options = new ChannelOptions(true, true, 0);

    try (Channel channel = connection.createChannel(options)) {
        String queue = channel.queueDeclare().getQueue();

        CompletableFuture<Void> future = channel.basicPublishAsync(
            "", queue, null, "Hello World".getBytes()
        );

        future.join(); // Wait for confirmation
        System.out.println("Message confirmed!");
    }
}
```

### Example 2: Batch Publishing with Progress Tracking

```java
ChannelOptions options = new ChannelOptions(true, true, 100);
Channel channel = connection.createChannel(options);

int total = 1000;
AtomicInteger confirmed = new AtomicInteger(0);
AtomicInteger failed = new AtomicInteger(0);

List<CompletableFuture<Void>> futures = new ArrayList<>();

for (int i = 0; i < total; i++) {
    String message = "Message " + i;
    CompletableFuture<Void> future = channel.basicPublishAsync(
        "", queueName, null, message.getBytes()
    );

    future.thenRun(() -> {
        int count = confirmed.incrementAndGet();
        if (count % 100 == 0) {
            System.out.println("Confirmed: " + count);
        }
    }).exceptionally(ex -> {
        failed.incrementAndGet();
        System.err.println("Failed: " + message);
        return null;
    });

    futures.add(future);
}

CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
System.out.println("Total confirmed: " + confirmed.get());
System.out.println("Total failed: " + failed.get());
```

### Example 3: Mandatory Publishing with Retry

```java
ChannelOptions options = new ChannelOptions(true, true, 50);
Channel channel = connection.createChannel(options);

CompletableFuture<Void> publishWithRetry(String exchange, String routingKey,
                                          byte[] body, int maxRetries) {
    return channel.basicPublishAsync(exchange, routingKey, true, null, body)
        .exceptionally(ex -> {
            if (ex.getCause() instanceof PublishException) {
                PublishException pe = (PublishException) ex.getCause();
                if (pe.isReturn() && maxRetries > 0) {
                    System.out.println("Retrying message...");
                    return publishWithRetry(exchange, routingKey, body, maxRetries - 1).join();
                }
            }
            throw new RuntimeException(ex);
        });
}

publishWithRetry("my-exchange", "my-key", "Hello".getBytes(), 3).join();
```

### Example 4: Mixing Sync and Async

```java
ChannelOptions options = new ChannelOptions(true, true, 100);
Channel channel = connection.createChannel(options);

// Async publish
channel.basicPublishAsync("", queue, null, "async".getBytes());

// Sync publish (still works, uses same confirmation mechanism)
channel.basicPublish("", queue, null, "sync".getBytes());

// Wait for all confirms
channel.waitForConfirms();
```

### Example 5: Graceful Shutdown

```java
ChannelOptions options = new ChannelOptions(true, true, 100);
Channel channel = connection.createChannel(options);

List<CompletableFuture<Void>> pending = new ArrayList<>();

// Publish messages
for (int i = 0; i < 100; i++) {
    pending.add(channel.basicPublishAsync("", queue, null, ("msg" + i).getBytes()));
}

// Graceful shutdown: wait for all confirmations
try {
    CompletableFuture.allOf(pending.toArray(new CompletableFuture[0]))
        .get(30, TimeUnit.SECONDS);
    System.out.println("All messages confirmed");
} catch (TimeoutException e) {
    System.err.println("Timeout waiting for confirmations");
} finally {
    channel.close();
}
```

## Troubleshooting

### Issue: Future never completes

**Possible causes:**
- Publisher confirms not enabled on channel
- Broker not sending confirmations
- Channel closed before confirmation

**Solution:**
```java
// Add timeout
future.get(10, TimeUnit.SECONDS);

// Or use orTimeout (Java 9+)
future.orTimeout(10, TimeUnit.SECONDS);
```

### Issue: Cannot correlate Basic.Return

**Cause:** Sequence number header missing or wrong type

**Solution:**
- Ensure tracking is enabled (adds header automatically)
- Check broker doesn't strip custom headers
- Verify header name matches `ChannelOptions.PUBLISH_SEQUENCE_NUMBER_HEADER`

## FAQ

**Q: Can I use this with existing `ConfirmListener`?**

A: Yes, both mechanisms work together. The async API and listeners both receive confirmations.

**Q: What happens if I don't wait for the future?**

A: The message is still published and confirmed, but you won't know the result. The future will be garbage collected eventually.

**Q: Can I disable tracking after channel creation?**

A: No, options are set at channel creation and cannot be changed.

**Q: Does this work with transactions?**

A: No, publisher confirms and transactions are mutually exclusive in AMQP.

**Q: What's the performance overhead?**

A: Minimal - mainly the `CompletableFuture` object and map entry per message. Use `maxOutstandingConfirms` to limit memory.

**Q: Can I use this with automatic connection recovery?**

A: Yes, it works with `AutorecoveringConnection` and `AutorecoveringChannel`.

## See Also

- [Publisher Confirms Tutorial](https://www.rabbitmq.com/tutorials/tutorial-seven-java.html)
- [Reliability Guide](https://www.rabbitmq.com/reliability.html)
- [Java Client API Guide](https://www.rabbitmq.com/api-guide.html)
