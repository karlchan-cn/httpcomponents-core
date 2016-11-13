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

package org.apache.hc.core5.testing.classic;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.io.HttpExpectationVerifier;
import org.apache.hc.core5.http.io.HttpRequestHandler;
import org.apache.hc.core5.http.io.entity.AbstractHttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.apache.hc.core5.http.protocol.DefaultHttpProcessor;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.http.protocol.RequestConnControl;
import org.apache.hc.core5.http.protocol.RequestContent;
import org.apache.hc.core5.http.protocol.RequestExpectContinue;
import org.apache.hc.core5.http.protocol.RequestTargetHost;
import org.apache.hc.core5.http.protocol.RequestUserAgent;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExternalResource;

public class TestClassicHttp {

    private ClassicTestServer server;

    @Rule
    public ExternalResource serverResource = new ExternalResource() {

        @Override
        protected void before() throws Throwable {
            server = new ClassicTestServer();
            server.setTimeout(5000);
        }

        @Override
        protected void after() {
            if (server != null) {
                try {
                    server.shutdown(3, TimeUnit.SECONDS);
                    server = null;
                } catch (Exception ignore) {
                }
            }
        }

    };

    private ClassicTestClient client;

    @Rule
    public ExternalResource clientResource = new ExternalResource() {

        @Override
        protected void before() throws Throwable {
            client = new ClassicTestClient();
            client.setTimeout(5000);
        }

        @Override
        protected void after() {
            if (client != null) {
                try {
                    client.shutdown();
                    client = null;
                } catch (Exception ignore) {
                }
            }
        }

    };

    /**
     * This test case executes a series of simple GET requests
     */
    @Test
    public void testSimpleBasicHttpRequests() throws Exception {

        final int reqNo = 20;

        final Random rnd = new Random();

        // Prepare some random data
        final List<byte[]> testData = new ArrayList<>(reqNo);
        for (int i = 0; i < reqNo; i++) {
            final int size = rnd.nextInt(5000);
            final byte[] data = new byte[size];
            rnd.nextBytes(data);
            testData.add(data);
        }

        // Initialize the server-side request handler
        this.server.registerHandler("*", new HttpRequestHandler() {

            @Override
            public void handle(
                    final ClassicHttpRequest request,
                    final ClassicHttpResponse response,
                    final HttpContext context) throws HttpException, IOException {

                String s = request.getPath();
                if (s.startsWith("/?")) {
                    s = s.substring(2);
                }
                final int index = Integer.parseInt(s);
                final byte[] data = testData.get(index);
                final ByteArrayEntity entity = new ByteArrayEntity(data);
                response.setEntity(entity);
            }

        });

        this.server.start();
        this.client.start();

        final HttpCoreContext context = HttpCoreContext.create();
        final HttpHost host = new HttpHost("localhost", this.server.getPort());

        for (int r = 0; r < reqNo; r++) {
            final BasicClassicHttpRequest get = new BasicClassicHttpRequest("GET", "/?" + r);
            final ClassicHttpResponse response = this.client.execute(host, get, context);
            final byte[] received = EntityUtils.toByteArray(response.getEntity());
            final byte[] expected = testData.get(r);

            Assert.assertEquals(expected.length, received.length);
            for (int i = 0; i < expected.length; i++) {
                Assert.assertEquals(expected[i], received[i]);
            }
            this.client.keepAlive(get, response, context);
        }
    }

    /**
     * This test case executes a series of simple POST requests with content length
     * delimited content.
     */
    @Test
    public void testSimpleHttpPostsWithContentLength() throws Exception {

        final int reqNo = 20;

        final Random rnd = new Random();

        // Prepare some random data
        final List<byte[]> testData = new ArrayList<>(reqNo);
        for (int i = 0; i < reqNo; i++) {
            final int size = rnd.nextInt(5000);
            final byte[] data = new byte[size];
            rnd.nextBytes(data);
            testData.add(data);
        }

        // Initialize the server-side request handler
        this.server.registerHandler("*", new HttpRequestHandler() {

            @Override
            public void handle(
                    final ClassicHttpRequest request,
                    final ClassicHttpResponse response,
                    final HttpContext context) throws HttpException, IOException {

                final HttpEntity incoming = request.getEntity();
                if (incoming != null) {
                    final byte[] data = EntityUtils.toByteArray(incoming);

                    final ByteArrayEntity outgoing = new ByteArrayEntity(data);
                    outgoing.setChunked(false);
                    response.setEntity(outgoing);
                }
            }

        });

        this.server.start();
        this.client.start();

        final HttpCoreContext context = HttpCoreContext.create();
        final HttpHost host = new HttpHost("localhost", this.server.getPort());

        for (int r = 0; r < reqNo; r++) {
            final BasicClassicHttpRequest post = new BasicClassicHttpRequest("POST", "/");
            final byte[] data = testData.get(r);
            final ByteArrayEntity outgoing = new ByteArrayEntity(data);
            post.setEntity(outgoing);

            final ClassicHttpResponse response = this.client.execute(host, post, context);
            final byte[] received = EntityUtils.toByteArray(response.getEntity());
            final byte[] expected = testData.get(r);

            Assert.assertEquals(expected.length, received.length);
            for (int i = 0; i < expected.length; i++) {
                Assert.assertEquals(expected[i], received[i]);
            }
            this.client.keepAlive(post, response, context);
        }
    }

    /**
     * This test case executes a series of simple POST requests with chunk
     * coded content content.
     */
    @Test
    public void testSimpleHttpPostsChunked() throws Exception {

        final int reqNo = 20;

        final Random rnd = new Random();

        // Prepare some random data
        final List<byte[]> testData = new ArrayList<>(reqNo);
        for (int i = 0; i < reqNo; i++) {
            final int size = rnd.nextInt(20000);
            final byte[] data = new byte[size];
            rnd.nextBytes(data);
            testData.add(data);
        }

        // Initialize the server-side request handler
        this.server.registerHandler("*", new HttpRequestHandler() {

            @Override
            public void handle(
                    final ClassicHttpRequest request,
                    final ClassicHttpResponse response,
                    final HttpContext context) throws HttpException, IOException {

                final HttpEntity entity = request.getEntity();
                if (entity != null) {
                    final byte[] data = EntityUtils.toByteArray(entity);
                    final ByteArrayEntity outgoing = new ByteArrayEntity(data);
                    outgoing.setChunked(true);
                    response.setEntity(outgoing);
                }
            }

        });

        this.server.start();
        this.client.start();

        final HttpCoreContext context = HttpCoreContext.create();
        final HttpHost host = new HttpHost("localhost", this.server.getPort());

        for (int r = 0; r < reqNo; r++) {
            final BasicClassicHttpRequest post = new BasicClassicHttpRequest("POST", "/");
            final byte[] data = testData.get(r);
            final ByteArrayEntity outgoing = new ByteArrayEntity(data);
            outgoing.setChunked(true);
            post.setEntity(outgoing);

            final ClassicHttpResponse response = this.client.execute(host, post, context);
            final byte[] received = EntityUtils.toByteArray(response.getEntity());
            final byte[] expected = testData.get(r);

            Assert.assertEquals(expected.length, received.length);
            for (int i = 0; i < expected.length; i++) {
                Assert.assertEquals(expected[i], received[i]);
            }
            this.client.keepAlive(post, response, context);
        }
    }

    /**
     * This test case executes a series of simple HTTP/1.0 POST requests.
     */
    @Test
    public void testSimpleHttpPostsHTTP10() throws Exception {

        final int reqNo = 20;

        final Random rnd = new Random();

        // Prepare some random data
        final List<byte[]> testData = new ArrayList<>(reqNo);
        for (int i = 0; i < reqNo; i++) {
            final int size = rnd.nextInt(5000);
            final byte[] data = new byte[size];
            rnd.nextBytes(data);
            testData.add(data);
        }

        // Initialize the server-side request handler
        this.server.registerHandler("*", new HttpRequestHandler() {

            @Override
            public void handle(
                    final ClassicHttpRequest request,
                    final ClassicHttpResponse response,
                    final HttpContext context) throws HttpException, IOException {

                final HttpEntity incoming = request.getEntity();
                if (incoming != null) {
                    final byte[] data = EntityUtils.toByteArray(incoming);
                    final ByteArrayEntity outgoing = new ByteArrayEntity(data);
                    outgoing.setChunked(false);
                    response.setEntity(outgoing);
                }
                if (HttpVersion.HTTP_1_0.equals(request.getVersion())) {
                    response.addHeader("Version", "1.0");
                }
            }

        });

        this.server.start();
        this.client.start();

        final HttpCoreContext context = HttpCoreContext.create();
        final HttpHost host = new HttpHost("localhost", this.server.getPort());

        for (int r = 0; r < reqNo; r++) {
            // Set protocol level to HTTP/1.0
            final BasicClassicHttpRequest post = new BasicClassicHttpRequest("POST", "/");
            post.setVersion(HttpVersion.HTTP_1_0);
            final byte[] data = testData.get(r);
            final ByteArrayEntity outgoing = new ByteArrayEntity(data);
            post.setEntity(outgoing);

            final ClassicHttpResponse response = this.client.execute(host, post, context);
            Assert.assertEquals(HttpVersion.HTTP_1_1, response.getVersion());
            final Header h1 = response.getFirstHeader("Version");
            Assert.assertNotNull(h1);
            Assert.assertEquals("1.0", h1.getValue());
            final byte[] received = EntityUtils.toByteArray(response.getEntity());
            final byte[] expected = testData.get(r);

            Assert.assertEquals(expected.length, received.length);
            for (int i = 0; i < expected.length; i++) {
                Assert.assertEquals(expected[i], received[i]);
            }
            this.client.keepAlive(post, response, context);
        }
    }

    /**
     * This test case executes a series of simple POST requests using
     * the 'expect: continue' handshake.
     */
    @Test
    public void testHttpPostsWithExpectContinue() throws Exception {

        final int reqNo = 20;

        final Random rnd = new Random();

        // Prepare some random data
        final List<byte[]> testData = new ArrayList<>(reqNo);
        for (int i = 0; i < reqNo; i++) {
            final int size = rnd.nextInt(5000);
            final byte[] data = new byte[size];
            rnd.nextBytes(data);
            testData.add(data);
        }

        // Initialize the server-side request handler
        this.server.registerHandler("*", new HttpRequestHandler() {

            @Override
            public void handle(
                    final ClassicHttpRequest request,
                    final ClassicHttpResponse response,
                    final HttpContext context) throws HttpException, IOException {

                final HttpEntity incoming = request.getEntity();
                if (incoming != null) {
                    final byte[] data = EntityUtils.toByteArray(incoming);

                    final ByteArrayEntity outgoing = new ByteArrayEntity(data);
                    outgoing.setChunked(true);
                    response.setEntity(outgoing);
                }
            }

        });

        this.server.start();
        this.client.start();

        final HttpCoreContext context = HttpCoreContext.create();
        final HttpHost host = new HttpHost("localhost", this.server.getPort());

        for (int r = 0; r < reqNo; r++) {
            final BasicClassicHttpRequest post = new BasicClassicHttpRequest("POST", "/");
            final byte[] data = testData.get(r);
            final ByteArrayEntity outgoing = new ByteArrayEntity(data);
            outgoing.setChunked(true);
            post.setEntity(outgoing);

            final ClassicHttpResponse response = this.client.execute(host, post, context);
            final byte[] received = EntityUtils.toByteArray(response.getEntity());
            final byte[] expected = testData.get(r);

            Assert.assertEquals(expected.length, received.length);
            for (int i = 0; i < expected.length; i++) {
                Assert.assertEquals(expected[i], received[i]);
            }
            this.client.keepAlive(post, response, context);
        }
    }


    /**
     * This test case executes a series of simple POST requests that do not
     * meet the target server expectations.
     */
    @Test
    public void testHttpPostsWithExpectationVerification() throws Exception {

        final int reqNo = 20;

        // Initialize the server-side request handler
        this.server.registerHandler("*", new HttpRequestHandler() {

            @Override
            public void handle(
                    final ClassicHttpRequest request,
                    final ClassicHttpResponse response,
                    final HttpContext context) throws HttpException, IOException {

                final StringEntity outgoing = new StringEntity("No content");
                response.setEntity(outgoing);
            }

        });

        this.server.setExpectationVerifier(new HttpExpectationVerifier() {

            @Override
            public void verify(
                    final ClassicHttpRequest request,
                    final ClassicHttpResponse response,
                    final HttpContext context) throws HttpException {
                final Header someheader = request.getFirstHeader("Secret");
                if (someheader != null) {
                    final int secretNumber;
                    try {
                        secretNumber = Integer.parseInt(someheader.getValue());
                    } catch (final NumberFormatException ex) {
                        response.setCode(HttpStatus.SC_BAD_REQUEST);
                        return;
                    }
                    if (secretNumber >= 2) {
                        response.setCode(HttpStatus.SC_EXPECTATION_FAILED);
                        response.setEntity(
                                new StringEntity("Wrong secret number", ContentType.TEXT_PLAIN));
                    }
                }
            }

        });

        this.server.start();
        this.client.start();

        final HttpCoreContext context = HttpCoreContext.create();
        final HttpHost host = new HttpHost("localhost", this.server.getPort());

        for (int r = 0; r < reqNo; r++) {
            final BasicClassicHttpRequest post = new BasicClassicHttpRequest("POST", "/");
            post.addHeader("Secret", Integer.toString(r));

            final byte[] b = new byte[2048];
            for (int i = 0; i < b.length; i++) {
                b[i] = (byte) ('a' + r);
            }
            final ByteArrayEntity requestEntity = new ByteArrayEntity(b, ContentType.TEXT_PLAIN);
            requestEntity.setChunked(false);
            post.setEntity(requestEntity);

            final ClassicHttpResponse response = this.client.execute(host, post, context);

            final HttpEntity responseEntity = response.getEntity();
            Assert.assertNotNull(responseEntity);
            EntityUtils.consume(responseEntity);

            if (r >= 2) {
                Assert.assertEquals(HttpStatus.SC_EXPECTATION_FAILED, response.getCode());
            } else {
                Assert.assertEquals(HttpStatus.SC_OK, response.getCode());
            }
            this.client.keepAlive(post, response, context);
        }
    }

    static class RepeatingEntity extends AbstractHttpEntity {

        private final byte[] raw;
        private final int n;

        public RepeatingEntity(final String content, final Charset charset, final int n) {
            super();
            final Charset cs = charset != null ? charset : Charset.forName("US-ASCII");
            this.raw = content.getBytes(cs);
            this.n = n;
        }

        @Override
        public InputStream getContent() throws IOException, IllegalStateException {
            throw new IllegalStateException("This method is not implemented");
        }

        @Override
        public long getContentLength() {
            return (this.raw.length + 2) * this.n;
        }

        @Override
        public boolean isRepeatable() {
            return true;
        }

        @Override
        public boolean isStreaming() {
            return false;
        }

        @Override
        public void writeTo(final OutputStream outstream) throws IOException {
            for (int i = 0; i < this.n; i++) {
                outstream.write(this.raw);
                outstream.write('\r');
                outstream.write('\n');
            }
            outstream.flush();
        }

        @Override
        public void close() throws IOException {
        }

    }

    @Test
    public void testHttpContent() throws Exception {

        final String[] patterns = {

            "0123456789ABCDEF",
            "yadayada-blahblah-this-and-that-yadayada-blahblah-this-and-that-" +
            "yadayada-blahblah-this-and-that-yadayada-blahblah-this-and-that-" +
            "yadayada-blahblah-this-and-that-yadayada-blahblah-this-and-that-" +
            "yadayada-blahblah-this-and-that-yadayada-blahblah-this-and-that-" +
            "yadayada-blahblah-this-and-that-yadayada-blahblah-this-and-that-" +
            "yadayada-blahblah-this-and-that-yadayada-blahblah-this-and-that-" +
            "yadayada-blahblah-this-and-that-yadayada-blahblah-this-and-that-" +
            "yadayada-blahblah-this-and-that-yadayada-blahblah-this-and-that-" +
            "yadayada-blahblah-this-and-that-yadayada-blahblah-this-and-that-" +
            "yadayada-blahblah-this-and-that-yadayada-blahblah-this-and-that-" +
            "yadayada-blahblah-this-and-that-yadayada-blahblah-this-and-that-" +
            "yadayada-blahblah-this-and-that-yadayada-blahblah-this-and-that-" +
            "yadayada-blahblah-this-and-that-yadayada-blahblah-this-and-that-" +
            "yadayada-blahblah-this-and-that-yadayada-blahblah-this-and-that-" +
            "yadayada-blahblah-this-and-that-yadayada-blahblah-this-and-that"
        };

        // Initialize the server-side request handler
        this.server.registerHandler("*", new HttpRequestHandler() {

            @Override
            public void handle(
                    final ClassicHttpRequest request,
                    final ClassicHttpResponse response,
                    final HttpContext context) throws HttpException, IOException {

                int n = 1;
                String s = request.getPath();
                if (s.startsWith("/?n=")) {
                    s = s.substring(4);
                    try {
                        n = Integer.parseInt(s);
                        if (n <= 0) {
                            throw new HttpException("Invalid request: " +
                                    "number of repetitions cannot be negative or zero");
                        }
                    } catch (final NumberFormatException ex) {
                        throw new HttpException("Invalid request: " +
                                "number of repetitions is invalid");
                    }
                }

                final HttpEntity incoming = request.getEntity();
                if (incoming != null) {
                    final String line = EntityUtils.toString(incoming);
                    final ContentType contentType = EntityUtils.getContentTypeOrDefault(incoming);
                    Charset charset = contentType.getCharset();
                    if (charset == null) {
                        charset = StandardCharsets.ISO_8859_1;
                    }
                    final RepeatingEntity outgoing = new RepeatingEntity(line, charset, n);
                    outgoing.setChunked(n % 2 == 0);
                    response.setEntity(outgoing);
                }
            }

        });

        this.server.start();
        this.client.start();

        final HttpCoreContext context = HttpCoreContext.create();
        final HttpHost host = new HttpHost("localhost", this.server.getPort());

        for (final String pattern : patterns) {
            for (int n = 1000; n < 1020; n++) {
                final BasicClassicHttpRequest post = new BasicClassicHttpRequest(
                        "POST", "/?n=" + n);
                final StringEntity outgoing = new StringEntity(pattern);
                outgoing.setChunked(n % 2 == 0);
                post.setEntity(outgoing);

                final ClassicHttpResponse response = this.client.execute(host, post, context);
                final HttpEntity incoming = response.getEntity();
                Assert.assertNotNull(incoming);
                final InputStream instream = incoming.getContent();
                final ContentType contentType = EntityUtils.getContentTypeOrDefault(incoming);
                Charset charset = contentType.getCharset();
                if (charset == null) {
                    charset = StandardCharsets.ISO_8859_1;
                }
                Assert.assertNotNull(instream);
                final BufferedReader reader = new BufferedReader(new InputStreamReader(instream, charset));

                String line;
                int count = 0;
                while ((line = reader.readLine()) != null) {
                    Assert.assertEquals(pattern, line);
                    count++;
                }
                Assert.assertEquals(n, count);
                this.client.keepAlive(post, response, context);
            }
        }
    }

    @Test
    public void testHttpPostNoEntity() throws Exception {
        this.server.registerHandler("*", new HttpRequestHandler() {

            @Override
            public void handle(
                    final ClassicHttpRequest request,
                    final ClassicHttpResponse response,
                    final HttpContext context) throws HttpException, IOException {

                final HttpEntity incoming = request.getEntity();
                if (incoming != null) {
                    final byte[] data = EntityUtils.toByteArray(incoming);
                    final ByteArrayEntity outgoing = new ByteArrayEntity(data);
                    response.setEntity(outgoing);
                }
            }

        });

        this.server.start();
        this.client.start();

        final HttpCoreContext context = HttpCoreContext.create();
        final HttpHost host = new HttpHost("localhost", this.server.getPort());

        final BasicClassicHttpRequest post = new BasicClassicHttpRequest("POST", "/");
        post.setEntity(null);

        final ClassicHttpResponse response = this.client.execute(host, post, context);
        Assert.assertEquals(HttpStatus.SC_OK, response.getCode());
        final byte[] received = EntityUtils.toByteArray(response.getEntity());
        Assert.assertEquals(0, received.length);
        this.client.keepAlive(post, response, context);
    }

    @Test
    public void testHttpPostNoContentLength() throws Exception {
        this.server.registerHandler("*", new HttpRequestHandler() {

            @Override
            public void handle(
                    final ClassicHttpRequest request,
                    final ClassicHttpResponse response,
                    final HttpContext context) throws HttpException, IOException {

                final HttpEntity incoming = request.getEntity();
                if (incoming != null) {
                    final byte[] data = EntityUtils.toByteArray(incoming);
                    final ByteArrayEntity outgoing = new ByteArrayEntity(data);
                    response.setEntity(outgoing);
                }
            }

        });

        this.server.start();
        this.client.setHttpProcessor(new DefaultHttpProcessor(
                new RequestTargetHost(),
                new RequestConnControl(),
                new RequestUserAgent(),
                new RequestExpectContinue()));
        this.client.start();

        final HttpCoreContext context = HttpCoreContext.create();
        final HttpHost host = new HttpHost("localhost", this.server.getPort());

        final BasicClassicHttpRequest post = new BasicClassicHttpRequest("POST", "/");
        post.setEntity(null);

        final ClassicHttpResponse response = this.client.execute(host, post, context);
        Assert.assertEquals(HttpStatus.SC_OK, response.getCode());
        final byte[] received = EntityUtils.toByteArray(response.getEntity());
        Assert.assertEquals(0, received.length);
        this.client.keepAlive(post, response, context);
    }

    @Test
    public void testHttpPostIdentity() throws Exception {
        this.server.registerHandler("*", new HttpRequestHandler() {

            @Override
            public void handle(
                    final ClassicHttpRequest request,
                    final ClassicHttpResponse response,
                    final HttpContext context) throws HttpException, IOException {

                final HttpEntity incoming = request.getEntity();
                if (incoming != null) {
                    final byte[] data = EntityUtils.toByteArray(incoming);
                    final ByteArrayEntity outgoing = new ByteArrayEntity(data);
                    response.setEntity(outgoing);
                }
            }

        });

        this.server.start();
        this.client.setHttpProcessor(new DefaultHttpProcessor(
                new HttpRequestInterceptor() {

                    @Override
                    public void process(
                            final HttpRequest request,
                            final EntityDetails entity,
                            final HttpContext context) throws HttpException, IOException {
                        request.addHeader(HttpHeaders.TRANSFER_ENCODING, "identity");
                    }

                },
                new RequestTargetHost(),
                new RequestConnControl(),
                new RequestUserAgent(),
                new RequestExpectContinue()));
        this.client.start();

        final HttpCoreContext context = HttpCoreContext.create();
        final HttpHost host = new HttpHost("localhost", this.server.getPort());

        final BasicClassicHttpRequest post = new BasicClassicHttpRequest("POST", "/");
        post.setEntity(null);

        final ClassicHttpResponse response = this.client.execute(host, post, context);
        Assert.assertEquals(HttpStatus.SC_NOT_IMPLEMENTED, response.getCode());
        this.client.keepAlive(post, response, context);
    }

    @Test
    public void testNoContentResponse() throws Exception {

        final int reqNo = 20;

        // Initialize the server-side request handler
        this.server.registerHandler("*", new HttpRequestHandler() {

            @Override
            public void handle(
                    final ClassicHttpRequest request,
                    final ClassicHttpResponse response,
                    final HttpContext context) throws HttpException, IOException {
                response.setCode(HttpStatus.SC_NO_CONTENT);
            }

        });

        this.server.start();
        this.client.start();

        final HttpCoreContext context = HttpCoreContext.create();
        final HttpHost host = new HttpHost("localhost", this.server.getPort());

        for (int r = 0; r < reqNo; r++) {
            final BasicClassicHttpRequest get = new BasicClassicHttpRequest("GET", "/?" + r);
            final ClassicHttpResponse response = this.client.execute(host, get, context);
            Assert.assertNull(response.getEntity());
            if (!this.client.keepAlive(get, response, context)) {
                Assert.fail("Connection expected to be re-usable");
            }
        }
    }

    @Test
    public void testAbsentHostHeader() throws Exception {

        // Initialize the server-side request handler
        this.server.registerHandler("*", new HttpRequestHandler() {

            @Override
            public void handle(
                    final ClassicHttpRequest request,
                    final ClassicHttpResponse response,
                    final HttpContext context) throws HttpException, IOException {
                response.setEntity(new StringEntity("All is well", StandardCharsets.US_ASCII));
            }

        });

        this.server.start();
        this.client.setHttpProcessor(new DefaultHttpProcessor(new RequestContent(), new RequestConnControl()));
        this.client.start();

        final HttpCoreContext context = HttpCoreContext.create();
        final HttpHost host = new HttpHost("localhost", this.server.getPort());

        final BasicClassicHttpRequest get1 = new BasicClassicHttpRequest("GET", "/");
        get1.setVersion(HttpVersion.HTTP_1_0);
        final ClassicHttpResponse response1 = this.client.execute(host, get1, context);
        Assert.assertEquals(200, response1.getCode());
        EntityUtils.consume(response1.getEntity());
        this.client.keepAlive(get1, response1, context);

        final BasicClassicHttpRequest get2 = new BasicClassicHttpRequest("GET", "/");
        final ClassicHttpResponse response2 = this.client.execute(host, get2, context);
        Assert.assertEquals(400, response2.getCode());
        EntityUtils.consume(response2.getEntity());
        this.client.keepAlive(get2, response2, context);
    }

}
