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
package org.apache.asyncweb.server.context;

import java.net.InetSocketAddress;
import java.util.ArrayList;

import org.apache.asyncweb.common.DefaultHttpResponse;
import org.apache.asyncweb.common.HttpHeaderConstants;
import org.apache.asyncweb.common.HttpRequest;
import org.apache.asyncweb.common.HttpResponse;
import org.apache.asyncweb.common.HttpResponseStatus;
import org.apache.asyncweb.common.MutableHttpResponse;
import org.apache.asyncweb.server.HttpClientListener;
import org.apache.asyncweb.server.HttpServiceContext;
import org.apache.asyncweb.server.HttpSession;
import org.apache.asyncweb.server.ServiceContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A default implementation of {@link HttpServiceContext}.
 * 
 * @author The Apache MINA Project (dev@mina.apache.org)
 */
public abstract class AbstractHttpServiceContext implements HttpServiceContext {
    private final Logger log = LoggerFactory.getLogger(AbstractHttpServiceContext.class);

    private final InetSocketAddress remoteAddress;

    private final InetSocketAddress localAddress;

    private final HttpRequest request;

    private HttpResponse committedResponse;

    private HttpSession session;

    private boolean createdSession;

    private final ServiceContainer container;

    private final ArrayList<HttpClientListener> listeners = new ArrayList<HttpClientListener>(2);

    public AbstractHttpServiceContext(InetSocketAddress localAddress, InetSocketAddress remoteAddress,
            HttpRequest request, ServiceContainer container) {
        if (remoteAddress == null) {
            throw new NullPointerException("remoteAddress");
        }

        if (localAddress == null) {
            throw new NullPointerException("localAddress");
        }

        if (request == null) {
            throw new NullPointerException("request");
        }

        if (container == null) {
            throw new NullPointerException("container");
        }

        this.remoteAddress = remoteAddress;
        this.localAddress = localAddress;
        this.request = request;
        this.container = container;
        this.session = container.getSessionAccessor().getSession(this, false);
    }

    public synchronized boolean isResponseCommitted() {
        return committedResponse != null;
    }

    /**
     * Commits a <code>HttpResponse</code> to this <code>Request</code>.
     * 
     * @param response The response to commit
     * @return <code>true</code> iff the response was committed
     */
    public boolean commitResponse(HttpResponse response) {
        synchronized (this) {
            if (isResponseCommitted()) {
                log.info("Request already comitted to a response. Disposing response");
                return false;
            }

            committedResponse = response;
        }

        // Add the session identifier if the session was newly created.
        if (createdSession) {
            container.getSessionAccessor().addSessionIdentifier(this, (MutableHttpResponse) response);
        }

        // Only parsed requests can be formatted.
        if (getRequest().getMethod() != null) {
            container.getErrorResponseFormatter().formatResponse(getRequest(), (MutableHttpResponse) response);
        }

        if (container.isSendServerHeader()) {
            ((MutableHttpResponse) response).setHeader(HttpHeaderConstants.KEY_SERVER, "AsyncWeb");
        }

        // Normalize the response.
        ((MutableHttpResponse) response).normalize(getRequest());

        // Override connection header if needed.
        if (!container.getKeepAliveStrategy().keepAlive(this, response)) {
            ((MutableHttpResponse) response).setHeader(HttpHeaderConstants.KEY_CONNECTION,
                    HttpHeaderConstants.VALUE_CLOSE);
        }

        boolean requiresClosure = !HttpHeaderConstants.VALUE_KEEP_ALIVE.equalsIgnoreCase(response
                .getHeader(HttpHeaderConstants.KEY_CONNECTION));

        if (requiresClosure && log.isDebugEnabled()) {
            log.debug("Response status: " + response.getStatus());
            log.debug("Keep-alive strategy requires closure of " + getRemoteAddress());
        }

        if (log.isDebugEnabled()) {
            log.debug("Committing a response:");
            log.debug("Status: " + response.getStatus() + ' ' + response.getStatusReasonPhrase());
            log.debug("Headers: " + response.getHeaders());
        }

        doWrite(requiresClosure);
        return true;
    }

    public boolean commitResponse(HttpResponseStatus status) {
        MutableHttpResponse response = new DefaultHttpResponse();
        response.setStatus(status);
        return commitResponse(response);
    }

    public synchronized HttpResponse getCommittedResponse() {
        return committedResponse;
    }

    public InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    public InetSocketAddress getLocalAddress() {
        return localAddress;
    }

    public HttpRequest getRequest() {
        return request;
    }

    public HttpSession getSession() {
        return getSession(true);
    }

    public synchronized HttpSession getSession(boolean create) {
        if (session != null && !session.isValid()) {
            session = null;
        }

        if (session == null) {
            session = container.getSessionAccessor().getSession(this, create);
            if (create) {
                createdSession = true;
            }
        }

        return session;
    }

    protected void fireClientDisconnected() {
        ArrayList<HttpClientListener> cloned;
        synchronized (listeners) {
            // noinspection unchecked
            cloned = (ArrayList<HttpClientListener>) listeners.clone();
        }

        for (HttpClientListener listener : cloned) {
            listener.clientDisconnected(this);
        }
    }

    protected void fireClientIdle(long idleTime, int idleCount) {
        ArrayList<HttpClientListener> cloned;
        synchronized (listeners) {
            // noinspection unchecked
            cloned = (ArrayList<HttpClientListener>) listeners.clone();
        }

        for (HttpClientListener listener : cloned) {
            listener.clientIdle(this, idleTime, idleCount);
        }
    }

    public boolean addClientListener(HttpClientListener listener) {
        return listeners.add(listener);
    }

    public boolean removeClientListener(HttpClientListener listener) {
        return listeners != null && listeners.remove(listener);
    }

    protected abstract void doWrite(boolean requiresClosure);
}
