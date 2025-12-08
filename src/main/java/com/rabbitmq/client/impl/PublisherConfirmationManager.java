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
package com.rabbitmq.client.impl;

import com.rabbitmq.client.AMQP.Basic;
import com.rabbitmq.client.AMQP.BasicProperties;
import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.ChannelOptions;
import com.rabbitmq.client.PublishException;
import com.rabbitmq.client.RateLimiter;
import com.rabbitmq.client.ShutdownSignalException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class PublisherConfirmationManager {
    private final boolean isPublisherConfirmationTrackingEnabled;
    private final RateLimiter rateLimiter;
    private final Map<Long, PublisherConfirmationEntry<?>> confirmations;

    private boolean isCompleted = false;

    public PublisherConfirmationManager(ChannelOptions options) {
        if (options == null) {
            this.isPublisherConfirmationTrackingEnabled = false;
            this.rateLimiter = null;
            this.confirmations = null;
        } else {
            this.isPublisherConfirmationTrackingEnabled = options.isPublisherConfirmationTrackingEnabled();
            this.rateLimiter = options.getRateLimiter();
            if (this.isPublisherConfirmationTrackingEnabled) {
                int initialCapacity = (rateLimiter != null) ? rateLimiter.getMaxConcurrency() : 16;
                this.confirmations = new ConcurrentHashMap<>(initialCapacity > 0 ? initialCapacity : 16);
            } else {
                this.confirmations = null;
            }
        }
    }

    public boolean isPublisherConfirmationTrackingEnabled() {
        return this.isPublisherConfirmationTrackingEnabled;
    }

    public <T> PublisherConfirmationState<T> start() {
        CompletableFuture<T> future = new CompletableFuture<>();
        RateLimiter.Permit permit = null;

        // Acquire rate limiter permit if configured
        if (isPublisherConfirmationTrackingEnabled && rateLimiter != null) {
            try {
                permit = rateLimiter.acquire();
            } catch (InterruptedException e) {
                future.completeExceptionally(e);
            }
        }
        
        return new PublisherConfirmationState<>(permit, future);
    }

    public <T> void maybePutEntry(long seqNo, PublisherConfirmationState<T> confirmationState, T context) {
        if (isPublisherConfirmationTrackingEnabled) {
            confirmations.put(seqNo, new PublisherConfirmationEntry<>(confirmationState, context));
        }
    }

    public <T> void maybeCompleteImmediately(PublisherConfirmationState<T> confirmationState, T context) {
        /*
         * IMPORTANT: if we are NOT tracking confirmations, we can immediately complete the future
         */
        if (false == isPublisherConfirmationTrackingEnabled) {
            confirmationState.complete(context);
        }
    }

    public <T> void completeExceptionally(long seqNo, Exception e) {
        if (isPublisherConfirmationTrackingEnabled) {
            PublisherConfirmationEntry<?> entry = confirmations.remove(seqNo);
            entry.releasePermit();
            entry.completeExceptionally(e);
        }
    }

    public BasicProperties maybeBuildProps(BasicProperties argProps, long seqNo) {
        BasicProperties rv = argProps;

        if (isPublisherConfirmationTrackingEnabled) {
            Map<String, Object> headers = argProps.getHeaders();
            if (headers == null) {
                headers = new HashMap<>();
            } else {
                headers = new HashMap<>(headers);
            }
            headers.put(ChannelOptions.PUBLISH_SEQUENCE_NUMBER_HEADER, seqNo);
            rv = argProps.builder().headers(headers).build();
        }

        return rv;
    }

    public void handleAckNack(long seqNo, boolean multiple, boolean nack) {
        if (isPublisherConfirmationTrackingEnabled) {
            if (multiple) {
                for (Long seq : new ArrayList<>(confirmations.keySet())) {
                    if (seq <= seqNo) {
                        PublisherConfirmationEntry<?> entry = confirmations.remove(seq);
                        // Entry may be null if already processed by Basic.Return
                        if (entry != null) {
                            if (nack) {
                                entry.completeExceptionally(seq);
                            } else {
                                entry.complete();
                            }
                            entry.releasePermit();
                        }
                    }
                }
            } else {
                PublisherConfirmationEntry<?> entry = confirmations.remove(seqNo);
                // Entry may be null if already processed by Basic.Return
                if (entry != null) {
                    if (nack) {
                        entry.completeExceptionally(seqNo);
                    } else {
                        entry.complete();
                    }
                    entry.releasePermit();
                }
            }
        }
    }

    public void finishProcessShutdownSignal(ShutdownSignalException shutdownSignalException) {
        // Fail all pending publisher confirmation futures
        if (isPublisherConfirmationTrackingEnabled && !confirmations.isEmpty()) {
            AlreadyClosedException ex = new AlreadyClosedException(shutdownSignalException);
            for (PublisherConfirmationEntry<?> entry : confirmations.values()) {
                entry.completeExceptionally(ex);
                entry.releasePermit();
            }
            confirmations.clear();
        }
    }

    public long handleReturn(BasicProperties props, Basic.Return basicReturn) {
        long seqNo = 0;

        if (isPublisherConfirmationTrackingEnabled) {
            Object seqNumObj = props.getHeaders().get(ChannelOptions.PUBLISH_SEQUENCE_NUMBER_HEADER);
            seqNo = extractSequenceNumber(seqNumObj);

            PublisherConfirmationEntry<?> entry = confirmations.remove(seqNo);
            entry.completeExceptionally(new PublishException(seqNo, true,
                    basicReturn.getExchange(), basicReturn.getRoutingKey(),
                    (int)basicReturn.getReplyCode(), basicReturn.getReplyText(), entry.context));
            entry.releasePermit();
        }

        return seqNo;
    }

    /**
     * Extract sequence number from message header.
     * NOTE: Since this library always writes the sequence number as a Long,
     * the header value should always be a Long when read back from the broker.
     * The additional type checks (Integer, String, byte[]) are defensive programming
     * and should never be needed in practice.
     */
    private long extractSequenceNumber(Object seqNumObj) {
        if (seqNumObj instanceof Long) {
            return (Long) seqNumObj;
        } else if (seqNumObj instanceof Integer) {
            return ((Integer) seqNumObj).longValue();
        } else if (seqNumObj instanceof String) {
            return Long.parseLong((String) seqNumObj);
        } else if (seqNumObj instanceof byte[]) {
            return Long.parseLong(new String((byte[]) seqNumObj));
        }
        return 0;
    }

    public static class PublisherConfirmationState<T> {
        final RateLimiter.Permit permit;
        final CompletableFuture<T> future;

        public PublisherConfirmationState(RateLimiter.Permit permit, CompletableFuture<T> future) {
            this.permit = permit;

            if (future == null) {
                throw new IllegalArgumentException("future must be non-null");
            }
            this.future = future;
        }

        public boolean isDone() {
            return future.isDone();
        }

        public void complete(T context) {
            this.future.complete(context);
        }

        public CompletableFuture<T> getFuture() {
            return this.future;
        }

        public RateLimiter.Permit getPermit() {
            return this.permit;
        }

        public void releasePermit() {
            if (this.permit != null) {
                this.permit.release();
            }
        }
    }

    private static class PublisherConfirmationEntry<T> {
        final PublisherConfirmationState<T> state;
        final T context;

        PublisherConfirmationEntry(PublisherConfirmationState<T> state, T context) {
            if (state == null) {
                throw new IllegalArgumentException("state must be non-null");
            }
            this.state = state;
            this.context = context;
        }

        public void completeExceptionally(Exception e) {
            state.getFuture().completeExceptionally(e);
        }

        public void completeExceptionally(long seq) {
            // TODO LRB add the rest of the information to PublishException
            PublishException ex = new PublishException(seq, false, null, null, null, null, this.context);
            state.getFuture().completeExceptionally(ex);
        }

        public void complete() {
            CompletableFuture<T> future = state.getFuture();
            future.complete(context);
        }
        
        public void releasePermit() {
            state.releasePermit();
        }
    }
}
