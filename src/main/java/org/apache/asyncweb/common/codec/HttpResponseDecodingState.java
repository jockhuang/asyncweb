/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.apache.asyncweb.common.codec;

import java.util.List;
import java.util.Map;

import org.apache.asyncweb.common.Cookie;
import org.apache.asyncweb.common.DefaultCookie;
import org.apache.asyncweb.common.DefaultHttpResponse;
import org.apache.asyncweb.common.HttpHeaderConstants;
import org.apache.asyncweb.common.HttpResponse;
import org.apache.asyncweb.common.HttpResponseStatus;
import org.apache.asyncweb.common.HttpVersion;
import org.apache.asyncweb.common.MutableCookie;
import org.apache.asyncweb.common.MutableHttpResponse;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.filter.codec.ProtocolDecoderException;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;
import org.apache.mina.filter.codec.statemachine.ConsumeToEndOfSessionDecodingState;
import org.apache.mina.filter.codec.statemachine.CrLfDecodingState;
import org.apache.mina.filter.codec.statemachine.DecodingState;
import org.apache.mina.filter.codec.statemachine.DecodingStateMachine;
import org.apache.mina.filter.codec.statemachine.FixedLengthDecodingState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses HTTP requests. Clients should register a <code>HttpRequestParserListener</code> in order to receive
 * notifications at important stages of request building.<br/>
 * 
 * <code>HttpRequestParser</code>s should not be built for each request as each parser constructs an underlying state
 * machine which is relatively costly to build.<br/>
 * Instead, parsers should be pooled.<br/>
 * 
 * Note, however, that a parser <i>must</i> be <code>prepare</code>d before each new parse.
 * 
 * @author The Apache MINA Project (dev@mina.apache.org)
 */
abstract class HttpResponseDecodingState extends DecodingStateMachine {

    private static final Logger LOG = LoggerFactory.getLogger(HttpResponseDecodingState.class);

    /**
     * The header which provides a requests transfer coding
     */
    private static final String TRANSFER_CODING = "transfer-encoding";

    /**
     * The chunked coding
     */
    private static final String CHUNKED = "chunked";

    /**
     * The header which provides a requests content length
     */
    private static final String CONTENT_LENGTH = "Content-Length";

    /**
     * Indicates the start of a coding extension
     */
    private static final char EXTENSION_CHAR = ';';

    public static final String COOKIE_COMMENT = "comment";

    public static final String COOKIE_DOMAIN = "domain";

    public static final String COOKIE_EXPIRES = "expires";

    public static final String COOKIE_MAX_AGE = "max-age";

    public static final String COOKIE_PATH = "path";

    public static final String COOKIE_SECURE = "secure";

    public static final String COOKIE_VERSION = "version";

    /**
     * The request we are building
     */
    private MutableHttpResponse response;

    @Override
    protected DecodingState init() throws Exception {
        response = new DefaultHttpResponse();
        return SKIP_EMPTY_LINES;
    }

    @Override
    protected void destroy() throws Exception {
    }

    private final DecodingState SKIP_EMPTY_LINES = new CrLfDecodingState() {

        @Override
        protected DecodingState finishDecode(boolean foundCRLF, ProtocolDecoderOutput out) throws Exception {
            if (foundCRLF) {
                return this;
            } else {
                return READ_RESPONSE_LINE;
            }
        }
    };

    private final DecodingState READ_RESPONSE_LINE = new HttpResponseLineDecodingState() {
        @Override
        protected DecodingState finishDecode(List<Object> childProducts, ProtocolDecoderOutput out) throws Exception {
            if (childProducts.size() < 3) {
                // Session is closed.
                return null;
            }
            response.setProtocolVersion((HttpVersion) childProducts.get(0));
            final HttpResponseStatus status = HttpResponseStatus.forId((Integer) childProducts.get(1));
            if (status.isFinalResponse()) {
                response.setStatus(status);
                String reasonPhrase = (String) childProducts.get(2);
                if (reasonPhrase.length() > 0) {
                    response.setStatusReasonPhrase(reasonPhrase);
                }
                return READ_HEADERS;
            } else {
                return SKIP_HEADERS;
            }
        }
    };

    private final DecodingState SKIP_HEADERS = new HttpHeaderDecodingState() {
        @Override
        protected DecodingState finishDecode(List<Object> childProducts, ProtocolDecoderOutput out) throws Exception {
            return READ_RESPONSE_LINE;
        }
    };

    private final DecodingState READ_HEADERS = new HttpHeaderDecodingState() {
        @Override
        @SuppressWarnings("unchecked")
        protected DecodingState finishDecode(List<Object> childProducts, ProtocolDecoderOutput out) throws Exception {
            Map<String, List<String>> headers = (Map<String, List<String>>) childProducts.get(0);

            // Parse cookies
            List<String> cookies = headers.get(HttpHeaderConstants.KEY_SET_COOKIE);
            if (cookies != null && !cookies.isEmpty()) {
                for (String cookie : cookies) {
                    response.addCookie(parseCookie(cookie));
                }
            }
            response.setHeaders(headers);

            if (LOG.isDebugEnabled()) {
                LOG.debug("Decoded header: " + response.getHeaders());
            }

            // Select appropriate body decoding state.
            boolean isChunked = false;
            if (response.getProtocolVersion() == HttpVersion.HTTP_1_1) {
                LOG.debug("Request is HTTP 1/1. Checking for transfer coding");
                isChunked = isChunked(response);
            } else {
                LOG.debug("Request is not HTTP 1/1. Using content length");
            }
            DecodingState nextState;
            if (isChunked) {
                LOG.debug("Using chunked decoder for request");
                nextState = new ChunkedBodyDecodingState() {
                    @Override
                    protected DecodingState finishDecode(List<Object> childProducts, ProtocolDecoderOutput out)
                            throws Exception {
                        if (childProducts.size() != 1) {
                            int chunkSize = 0;
                            for (Object product : childProducts) {
                                IoBuffer chunk = (IoBuffer) product;
                                chunkSize += chunk.remaining();
                            }

                            IoBuffer body = IoBuffer.allocate(chunkSize);
                            for (Object product : childProducts) {
                                IoBuffer chunk = (IoBuffer) product;
                                body.put(chunk);
                            }
                            body.flip();
                            response.setContent(body);
                        } else {
                            response.setContent((IoBuffer) childProducts.get(0));
                        }

                        out.write(response);
                        return null;
                    }
                };
            } else {
                int length = getContentLength(response);
                if (length > 0) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Using fixed length decoder for request with " + "length " + length);
                    }

                    // TODO max length limitation.
                    nextState = new FixedLengthDecodingState(length) {
                        @Override
                        protected DecodingState finishDecode(IoBuffer readData, ProtocolDecoderOutput out)
                                throws Exception {
                            response.setContent(readData);
                            out.write(response);
                            return null;
                        }
                    };
                } else if (length < 0) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Using consume-to-disconnection decoder for " + "request with unspecified length.");
                    }
                    // FIXME hard-coded max length.
                    nextState = new ConsumeToEndOfSessionDecodingState(1048576) {
                        @Override
                        protected DecodingState finishDecode(IoBuffer readData, ProtocolDecoderOutput out)
                                throws Exception {
                            response.setContent(readData);
                            out.write(response);
                            return null;
                        }
                    };
                } else {
                    LOG.debug("No entity body for this request");
                    out.write(response);
                    nextState = null;
                }
            }
            return nextState;
        }

        private Cookie parseCookie(String cookieHeader) throws DateParseException {

            MutableCookie cookie = null;

            String pairs[] = cookieHeader.split(";");
            for (int i = 0; i < pairs.length; i++) {
                String nameValue[] = pairs[i].trim().split("=");
                String name = nameValue[0].trim();
                String value = (nameValue.length == 2) ? nameValue[1].trim() : null;

                // First pair is the cookie name/value
                if (i == 0) {
                    cookie = new DefaultCookie(name, value);
                } else if (name.equalsIgnoreCase(COOKIE_COMMENT)) {
                    cookie.setComment(value);
                } else if (name.equalsIgnoreCase(COOKIE_PATH)) {
                    cookie.setPath(value);
                } else if (name.equalsIgnoreCase(COOKIE_SECURE)) {
                    cookie.setSecure(true);
                } else if (name.equalsIgnoreCase(COOKIE_VERSION)) {
                    cookie.setVersion(Integer.parseInt(value));
                } else if (name.equalsIgnoreCase(COOKIE_MAX_AGE)) {
                    int age = Integer.parseInt(value);
                    cookie.setMaxAge(age);
                } else if (name.equalsIgnoreCase(COOKIE_EXPIRES)) {
                    long createdDate = System.currentTimeMillis();
                    int age = (int) (DateUtil.parseDate(value).getTime() - createdDate) / 1000;
                    cookie.setCreatedDate(createdDate);
                    cookie.setMaxAge(age);
                } else if (name.equalsIgnoreCase(COOKIE_DOMAIN)) {
                    cookie.setDomain(value);
                }
            }

            return cookie;
        }

        /**
         * Obtains the content length from the specified request
         * 
         * @param response The request
         * @return The content length, or -1 if not specified
         * @throws HttpDecoderException If an invalid content length is specified
         */
        private int getContentLength(HttpResponse response) throws ProtocolDecoderException {
            int length = -1;
            String lengthValue = response.getHeader(CONTENT_LENGTH);
            if (lengthValue != null) {
                try {
                    length = Integer.parseInt(lengthValue);
                } catch (NumberFormatException e) {
                    HttpCodecUtils.throwDecoderException("Invalid content length: " + length,
                            HttpResponseStatus.BAD_REQUEST);
                }
            }
            return length;
        }

        /**
         * Determines whether a specified request employs a chunked transfer coding
         * 
         * @param response The request
         * @return <code>true</code> iff the request employs a chunked transfer coding
         * @throws HttpDecoderException If the request employs an unsupported coding
         */
        private boolean isChunked(HttpResponse response) throws ProtocolDecoderException {
            boolean isChunked = false;
            String coding = response.getHeader(TRANSFER_CODING);
            if (coding != null) {
                int extensionIndex = coding.indexOf(EXTENSION_CHAR);
                if (extensionIndex != -1) {
                    coding = coding.substring(0, extensionIndex);
                }
                if (CHUNKED.equalsIgnoreCase(coding)) {
                    isChunked = true;
                } else {
                    // As we only support chunked encoding, any other encoding
                    // is unsupported
                    HttpCodecUtils.throwDecoderException("Unknown transfer coding " + coding,
                            HttpResponseStatus.NOT_IMPLEMENTED);
                }
            }
            return isChunked;
        }
    };
}
