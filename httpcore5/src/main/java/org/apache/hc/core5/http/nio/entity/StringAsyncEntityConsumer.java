/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.hc.core5.http.nio.entity;

import java.io.IOException;
import java.nio.CharBuffer;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.nio.CapacityChannel;
import org.apache.hc.core5.util.Args;
import org.apache.hc.core5.util.CharArrayBuffer;

public class StringAsyncEntityConsumer extends AbstractCharAsyncEntityConsumer<String> {

    private final int capacityIncrement;
    private final CharArrayBuffer content;
    private final AtomicReference<Exception> exception;

    private FutureCallback<String> resultCallback;

    public StringAsyncEntityConsumer(final int capacityIncrement) {
        Args.positive(capacityIncrement, "Capacity increment");
        this.capacityIncrement = capacityIncrement;
        this.content = new CharArrayBuffer(1024);
        this.exception = new AtomicReference<>(null);
    }

    public StringAsyncEntityConsumer() {
        this(Integer.MAX_VALUE);
    }

    @Override
    public void updateCapacity(final CapacityChannel capacityChannel) throws IOException {
        capacityChannel.update(capacityIncrement);
    }

    @Override
    protected void dataStart(
            final ContentType contentType, final FutureCallback<String> resultCallback) throws HttpException, IOException {
        this.resultCallback = resultCallback;
    }

    @Override
    protected void consumeData(final CharBuffer src) {
        Args.notNull(src, "CharBuffer");
        final int chunk = src.remaining();
        content.ensureCapacity(chunk);
        src.get(content.array(), content.length(), chunk);
        content.setLength(content.length() + chunk);
    }

    @Override
    protected void dataEnd() throws IOException {
        if (resultCallback != null) {
            resultCallback.completed(content.toString());
        }
    }

    @Override
    public void failed(final Exception cause) {
        if (exception.compareAndSet(null, cause)) {
            if (resultCallback != null) {
                resultCallback.failed(cause);
            }
            releaseResources();
        }
    }

    public Exception getException() {
        return exception.get();
    }

    @Override
    public String getContent() {
        return content.toString();
    }

    @Override
    public void releaseResources() {
    }

}
