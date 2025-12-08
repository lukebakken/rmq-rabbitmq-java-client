// Copyright (c) 2007-2025 Broadcom. All Rights Reserved. The term "Broadcom" refers to Broadcom Inc. and/or its subsidiaries.
//
// This software, the RabbitMQ Java client library, is triple-licensed under the
// Mozilla Public License 2.0 ("MPL"), the GNU General Public License version 2
// ("GPL") and the Apache License version 2 ("ASL"). For the MPL, please see
// LICENSE-MPL-RabbitMQ. For the GPL, please see LICENSE-GPL2.  For the ASL,
// please see LICENSE-APACHE2.
//
// This software is distributed on an "AS IS" basis, WITHOUT WARRANTY OF ANY KIND,
// either express or implied. See the LICENSE file for specific language governing
// rights and limitations of this software.
//
// If you have any questions regarding licensing, please contact us at
// info@rabbitmq.com.

package com.rabbitmq.client;

/**
 * Options for creating a channel with publisher confirmation tracking.
 * <p>
 * Use the {@link Builder} to create instances with desired configuration.
 * <p>
 * <b>Example usage:</b>
 * <pre>{@code
 * // Enable publisher confirms with throttling (100 permits, 50% threshold)
 * ThrottlingRateLimiter limiter = new ThrottlingRateLimiter(100, 50);
 * ChannelOptions options = ChannelOptions.builder()
 *     .publisherConfirmations(true)
 *     .publisherConfirmationTracking(true)
 *     .rateLimiter(limiter)
 *     .build();
 * Channel channel = connection.createChannel(options);
 *
 * // Publish with context (such as a correlation ID)
 * String messageId = "msg-123";
 * CompletableFuture<String> future = channel.basicPublishAsync(
 *     "", queueName, null, "Hello".getBytes(), messageId
 * );
 * future.thenAccept(ctx -> System.out.println("Confirmed: " + ctx));
 * }</pre>
 *
 * @see Channel#basicPublishAsync(String, String, com.rabbitmq.client.AMQP.BasicProperties, byte[])
 * @see Connection#createChannel(ChannelOptions)
 * @see PublishException
 * @see ThrottlingRateLimiter
 */
public class ChannelOptions
{
    /**
     * Header name for tracking publish sequence numbers in message properties.
     * <p>
     * When publisher confirmation tracking is enabled, this header is automatically
     * added to each published message with the message's sequence number as the value.
     * This allows the library to correlate Basic.Return responses with the correct message.
     */
    public static final String PUBLISH_SEQUENCE_NUMBER_HEADER = "x-seq-no";

    private final boolean publisherConfirmationsEnabled;
    private final boolean publisherConfirmationTrackingEnabled;
    private final RateLimiter rateLimiter;

    private ChannelOptions(Builder builder)
    {
        this.publisherConfirmationsEnabled = builder.publisherConfirmationsEnabled;
        this.publisherConfirmationTrackingEnabled = builder.publisherConfirmationTrackingEnabled;
        this.rateLimiter = builder.rateLimiter;
    }

    /**
     * Creates a new builder for ChannelOptions.
     *
     * @return a new builder instance
     */
    public static Builder builder()
    {
        return new Builder();
    }

    /**
     * @return true if publisher confirmations are enabled on this channel
     */
    public boolean isPublisherConfirmationsEnabled()
    {
        return publisherConfirmationsEnabled;
    }

    /**
     * @return true if automatic publisher confirmation tracking is enabled
     */
    public boolean isPublisherConfirmationTrackingEnabled()
    {
        return publisherConfirmationTrackingEnabled;
    }

    /**
     * @return the rate limiter for controlling outstanding confirmations, or null if not configured
     */
    public RateLimiter getRateLimiter()
    {
        return rateLimiter;
    }

    /**
     * Builder for creating ChannelOptions instances.
     */
    public static class Builder
    {
        private boolean publisherConfirmationsEnabled = false;
        private boolean publisherConfirmationTrackingEnabled = false;
        private RateLimiter rateLimiter = null;

        private Builder() {}

        /**
         * Enable or disable publisher confirmations on the channel.
         * When enabled, the library automatically calls {@link Channel#confirmSelect()}.
         *
         * @param enabled true to enable publisher confirmations
         * @return this builder
         */
        public Builder publisherConfirmations(boolean enabled)
        {
            this.publisherConfirmationsEnabled = enabled;
            return this;
        }

        /**
         * Enable or disable automatic publisher confirmation tracking.
         * When enabled, {@link Channel#basicPublishAsync} methods return futures that
         * complete when the broker confirms the message.
         * <p>
         * <b>Note:</b> Requires {@link #publisherConfirmations(boolean)} to be enabled.
         *
         * @param enabled true to enable automatic tracking
         * @return this builder
         */
        public Builder publisherConfirmationTracking(boolean enabled)
        {
            this.publisherConfirmationTrackingEnabled = enabled;
            return this;
        }

        /**
         * Set the rate limiter for controlling outstanding confirmations.
         * <p>
         * Use {@link ThrottlingRateLimiter} for progressive throttling behavior,
         * or implement {@link RateLimiter} for custom strategies.
         * Set to null for unlimited outstanding confirmations (default).
         *
         * @param rateLimiter the rate limiter, or null for unlimited
         * @return this builder
         */
        public Builder rateLimiter(RateLimiter rateLimiter)
        {
            this.rateLimiter = rateLimiter;
            return this;
        }

        /**
         * Builds the ChannelOptions instance.
         *
         * @return a new ChannelOptions instance
         * @throws IllegalArgumentException if tracking is enabled but confirmations are not enabled
         */
        public ChannelOptions build()
        {
            if (publisherConfirmationTrackingEnabled && !publisherConfirmationsEnabled) {
                throw new IllegalArgumentException(
                    "publisherConfirmations must be enabled when publisherConfirmationTracking is enabled");
            }
            return new ChannelOptions(this);
        }
    }
}
