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
 * Exception thrown when an internal error is detected in the client library.
 * <p>
 * This exception indicates a bug in the RabbitMQ Java client implementation.
 * If you encounter this exception, please report it with the full stack trace at:
 * <a href="https://github.com/rabbitmq/rabbitmq-java-client/issues">https://github.com/rabbitmq/rabbitmq-java-client/issues</a>
 */
public class InternalException extends IllegalStateException {
    private static final String BUG_REPORT_MESSAGE =
        "BUG FOUND - please report this exception (with stacktrace) at: https://github.com/rabbitmq/rabbitmq-java-client/issues";

    public InternalException() {
        super(BUG_REPORT_MESSAGE);
    }

    public InternalException(String details) {
        super(BUG_REPORT_MESSAGE + " - " + details);
    }

    public InternalException(Throwable cause) {
        super(BUG_REPORT_MESSAGE, cause);
    }

    public InternalException(String details, Throwable cause) {
        super(BUG_REPORT_MESSAGE + " - " + details, cause);
    }
}
