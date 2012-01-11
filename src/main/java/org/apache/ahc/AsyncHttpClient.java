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
package org.apache.ahc;

import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLContext;

import org.apache.ahc.codec.HttpDecoder;
import org.apache.ahc.codec.HttpProtocolCodecFactory;
import org.apache.ahc.codec.HttpRequestMessage;
import org.apache.ahc.proxy.ProxyFilter;
import org.apache.ahc.ssl.TrustManagerFactoryImpl;
import org.apache.ahc.util.AsyncHttpClientException;
import org.apache.ahc.util.EventDispatcher;
import org.apache.ahc.util.MonitoringEvent;
import org.apache.ahc.util.MonitoringListener;
import org.apache.asyncweb.common.HttpMethod;
import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.future.DefaultConnectFuture;
import org.apache.mina.core.future.IoFutureListener;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.RuntimeIoException;
import org.apache.mina.core.service.SimpleIoProcessorPool;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.filter.executor.ExecutorFilter;
import org.apache.mina.filter.ssl.SslFilter;
import org.apache.mina.transport.socket.SocketConnector;
import org.apache.mina.transport.socket.SocketSessionConfig;
import org.apache.mina.transport.socket.nio.NioProcessor;
import org.apache.mina.transport.socket.nio.NioSocketConnector;


/**
 * Main class to use for sending asynchronous HTTP requests to servers.
 * Only one or a few of these objects should be used in an application since
 * it manages the threads and requests to multiple separate servers/sockets.
 */
public class AsyncHttpClient {

    /** The Constant DEFAULT_CONNECTION_TIMEOUT. */
    public static final int DEFAULT_CONNECTION_TIMEOUT = 30000;

    /** The Constant DEFAULT_SSL_PROTOCOL. */
    public static final String DEFAULT_SSL_PROTOCOL = "TLS";

    /** The Default Reuse Address. */
    private static final boolean DEFAULT_REUSE_ADDRESS = false;

    /** The Default Receive Buffer Size. */
    private static final int DEFAULT_RECEIVE_BUFFER_SIZE = 1024;

    /** The Default Send Buffer Size. */
    private static final int DEFAULT_SEND_BUFFER_SIZE = 1024;

    /** The Default Traffic Class */
    private static final int DEFAULT_TRAFFIC_CLASS = 0;

    /** The Default Keep Alive. */
    private static final boolean DEFAULT_KEEP_ALIVE = false;

    /** The Default OOB Inline. */
    private static final boolean DEFAULT_OOB_INLINE = false;

    /** The Default SO Linger. */
    private static final int DEFAULT_SO_LINGER = -1;

    /** The Default TCP No Delay. */
    private static final boolean DEFAULT_TCP_NO_DELAY = false;
    
    /** The default number of connection retries */
    private static final int DEFAULT_CONNECTION_RETRIES = 0; 

    /** The SSL protocol. */
    private String sslProtocol = DEFAULT_SSL_PROTOCOL;

    /** The connection timeout. */
    private int connectionTimeout = DEFAULT_CONNECTION_TIMEOUT;
    
    /** The default number of retries */
    private int connectionRetries = DEFAULT_CONNECTION_RETRIES; 

    /** The connector. */
    private final SocketConnector connector;

    /** The thread pool for I/O processing, and the events and callbacks if 
     * the optional event thread pool is not provided. */
    private Executor threadPool;
    
    /** The (optional) thread pool for the events and callbacks. */
    private volatile Executor eventThreadPool;
    
    /** The HttpIoHandler handler. */
    private final HttpIoHandler handler;

    /** The connection pool for session reuse */
    private ConnectionPool connectionPool; 

    /** The Reuse Address Socket Parameter. */
    private boolean reuseAddress = DEFAULT_REUSE_ADDRESS;

    /** The Receive Buffer Size Socket Parameter. */
    private int receiveBufferSize = DEFAULT_RECEIVE_BUFFER_SIZE;

    /** The Send Buffer Size Socket Parameter. */
    private int sendBufferSize = DEFAULT_SEND_BUFFER_SIZE;

    /** The Traffic Class Socket Parameter. */
    private int trafficClass = DEFAULT_TRAFFIC_CLASS;

    /** The Keep Alive Socket Parameter. */
    private boolean keepAlive = DEFAULT_KEEP_ALIVE;

    /** The OOB Inline Socket Parameter. */
    private boolean oobInline = DEFAULT_OOB_INLINE;

    /** The Default SO Linger Socket Parameter. */
    private int soLinger = DEFAULT_SO_LINGER;

    /** The TCP No Delay Socket Parameter. */
    private boolean tcpNoDelay = DEFAULT_TCP_NO_DELAY;
    
    /** flag to make this as having been disposed of */
    private boolean destroyed = false; 
    
    /**
     * The timeout in seconds
     */
    private int timeout = 0;

    /** a dispatcher for dispatching monitoring events */
    private EventDispatcher eventDispatcher; 
    
    public static final String SSL_FILTER = "SSL";
    public static final String PROTOCOL_FILTER = "protocolFilter";
    public static final String PROXY_FILTER = "proxyFilter";
    public static final String EVENT_THREAD_POOL_FILTER = "eventThreadPoolFilter";

    /**
     * Checks if is reuse address.
     *
     * @return true, if is reuse address
     */
    public boolean isReuseAddress() {
        return reuseAddress;
    }

    /**
     * Sets the reuse address.
     *
     * @param reuseAddress the new reuse address
     */
    public void setReuseAddress(boolean reuseAddress) {
        this.reuseAddress = reuseAddress;
    }

    /**
     * Gets the receive buffer size.
     *
     * @return the receive buffer size
     */
    public int getReceiveBufferSize() {
        return receiveBufferSize;
    }

    /**
     * Sets the receive buffer size.
     *
     * @param receiveBufferSize the new receive buffer size
     */
    public void setReceiveBufferSize(int receiveBufferSize) {
        this.receiveBufferSize = receiveBufferSize;
    }

    /**
     * Gets the send buffer size.
     *
     * @return the send buffer size
     */
    public int getSendBufferSize() {
        return sendBufferSize;
    }

    /**
     * Sets the send buffer size.
     *
     * @param sendBufferSize the new send buffer size
     */
    public void setSendBufferSize(int sendBufferSize) {
        this.sendBufferSize = sendBufferSize;
    }

    /**
     * Gets the traffic class.
     *
     * @return the traffic class
     */
    public int getTrafficClass() {
        return trafficClass;
    }

    /**
     * Sets the traffic class.
     *
     * @param trafficClass the new traffic class
     */
    public void setTrafficClass(int trafficClass) {
        this.trafficClass = trafficClass;
    }

    /**
     * Checks if is keep alive.
     *
     * @return true, if is keep alive
     */
    public boolean isKeepAlive() {
        return keepAlive;
    }

    /**
     * Sets the keep alive.
     *
     * @param keepAlive the new keep alive
     */
    public void setKeepAlive(boolean keepAlive) {
        this.keepAlive = keepAlive;
    }

    /**
     * Checks if is oob inline.
     *
     * @return true, if is oob inline
     */
    public boolean isOobInline() {
        return oobInline;
    }

    /**
     * Sets the oob inline.
     *
     * @param oobInline the new oob inline
     */
    public void setOobInline(boolean oobInline) {
        this.oobInline = oobInline;
    }

    /**
     * Gets the so linger.
     *
     * @return the so linger
     */
    public int getSoLinger() {
        return soLinger;
    }

    /**
     * Sets the so linger.
     *
     * @param soLinger the new so linger
     */
    public void setSoLinger(int soLinger) {
        this.soLinger = soLinger;
    }

    /**
     * Checks if is tcp no delay.
     *
     * @return true, if is tcp no delay
     */
    public boolean isTcpNoDelay() {
        return tcpNoDelay;
    }

    /**
     * Sets the tcp no delay.
     *
     * @param tcpNoDelay the new tcp no delay
     */
    public void setTcpNoDelay(boolean tcpNoDelay) {
        this.tcpNoDelay = tcpNoDelay;
    }
    
    /**
     * Sets the (optional) thread pool for the event processing and callback
     * invocation.  It is the caller's responsibility to properly dispose of
     * the thread pool.
     */
    public void setEventThreadPool(Executor threadPool) {
        eventThreadPool = threadPool;
    }
    
    /**
     * Returns the connection retry count set for this 
     * client.
     * 
     * @return The current connection retry count.
     */
    public int getConnectionRetries() {
        return connectionRetries; 
    }
    
    /**
     * Sets the number of retries that will be attempted
     * on connection failures.
     * 
     * @param retries The new retry count.
     */
    public void setConnectionRetries(int retries) {
        connectionRetries = retries; 
    }
    
    /**
     * Get the timeout time
     * @return  the timeout time in seconds, 0 means no timeout.
     */
    public int getTimeout() {
        return timeout;
    }

    /**
     * Sets the timeout time
     * @param timeout  the timeout time in seconds, 0 means no timeout.
     */
    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    /**
     * Instantiates a new AsyncHttpClient.  It will use a single threaded model and is good for
     * use in one-off connections.
     */
    public AsyncHttpClient() {
        this(DEFAULT_CONNECTION_TIMEOUT, null);
    }

    /**
     * Instantiates a new AsyncHttpClient.  This will take a thread pool (Executor) to use
     * for processing connections.
     *
     * @param executor  the executor
     */
    public AsyncHttpClient(Executor executor) {
        this(DEFAULT_CONNECTION_TIMEOUT, executor);
    }

    /**
     * Instantiates a new AsyncHttpClient.  Uses a single thread model by default and allows you to specify
     * a connection timeout.
     *
     * @param connectionTimeout the connection timeout in milliseconds.
     */
    public AsyncHttpClient(int connectionTimeout) {
        this(connectionTimeout, null);
    }

    /**
     * Instantiates a new AsyncHttpClient.  Allows you to specify a connection timeout and an Executor.
     *
     * @param connectionTimeout the connection timeout in milliseconds.
     * @param executor          the ExceutorService to use to process connections.
     */
    public AsyncHttpClient(int connectionTimeout, Executor executor) {
        this.connectionTimeout = connectionTimeout;

        threadPool = executor;

        handler = new HttpIoHandler();

        if (threadPool == null) {
            connector = new NioSocketConnector();
        }
        else {
            connector = new NioSocketConnector(threadPool, new SimpleIoProcessorPool(NioProcessor.class));
        }
        connector.setHandler(handler);
        applyConnectionTimeout();
    }

    
    /**
     * Set the connection pool that should be used for 
     * session reuse.
     * 
     * @param connectionPool  The new session connection pool.  If null, this will disable
     *               future connection pooling.
     */
    public void setConnectionPool(ConnectionPool connectionPool) {
        this.connectionPool = connectionPool; 
        // our I/O Handler instance needs to be fitted with the same 
        // connection pool
        handler.setConnectionPool(connectionPool); 
    }
    
    /**
     * Retrieve the connection pool used for storing 
     * connections for reuse. 
     * 
     * @return The current connection pool
     */
    public ConnectionPool getConnectionPool() {
        return connectionPool; 
    }
    
    /**
     * Sends a request.  The call is non-blocking, and returns a future object
     * with which the caller can synchronize on the completion of the request.
     * This does not use a completion queue as provided by the other version of
     * <code>sendRequest()</code> method.
     *
     * @param message the <code>HttpRequestMessage</code> to send to the remote server.
     * @return a future object that allows tracking of the progress.  If the
     * future object already exists in the request, then the same future is
     * returned.
     * @see HttpRequestMessage
     */
    public ResponseFuture sendRequest(HttpRequestMessage message) {
        return sendRequest(message, null);
    }
    
    /**
     * Sends a request.  The call is non-blocking, and returns a future object
     * with which the caller can synchronize on the completion of the request.
     * Once the call is complete, the future will also be placed in the queue 
     * that is provided in the arguments.
     * 
     * @param message the <code>HttpRequestMessage</code> to send to the remote
     * server.
     * @param queue the completion queue on which the future will be added once
     * it is complete.  May be null.  If the future object already exists in the
     * request (i.e. <tt>sendRequest()</tt> was called repeatedly on the same
     * request), then the queue is ignored, and the queue that's associated with
     * the existing future object will be used.
     * @return a future object that allows tracking of the progress.  If the
     * future object already exists in the request, then the same future is
     * returned.
     */
    public ResponseFuture sendRequest(HttpRequestMessage message, 
            BlockingQueue<ResponseFuture> queue) {
        if (destroyed) {
            throw new IllegalStateException("AsyncHttpClient has been destroyed and cannot be reused.");
        }
        
        // set the request start time
        message.setRequestStartTime();
        // notify any interesting parties that this is starting 
        notifyMonitoringListeners(MonitoringEvent.REQUEST_STARTED, message); 
        
        // we need to provide a new result future and associate it with the
        // request unless it already exists (i.e. sendRequest() is called
        // multiple times for the request)
        if (message.getResponseFuture() == null) {
            message.setResponseFuture(new ResponseFuture(message, queue));
        }
        
        // *IF* connection reuse is enabled, we should see if we have a pooled 
        // connection first; if not, always open a new one
        ConnectFuture future = null;
        if (!message.isProxyEnabled()) {
        	ConnectionPool connectionPool = getConnectionPool(); 
        	if (connectionPool != null) {
        		future = getPooledConnection(message);
        	} else {
        		// add the Connection close header explicitly
        		message.setHeader(HttpDecoder.CONNECTION, HttpDecoder.CLOSE);
        	}
        }
        
        // if no pooled connection is found or keep-alive is disabled, force a
        // new connection
        if (future == null) {
            // set the connect start time
            message.setConnectStartTime();
            // NB:  We broadcast this here rather than in open connection to avoid 
            // having a connection retry result in both a CONNECTION_ATTEMPTED and 
            // CONNECTION_RETRIED event getting dispatched. 
            notifyMonitoringListeners(MonitoringEvent.CONNECTION_ATTEMPTED, message); 
            future = openConnection(message);
        }
        ResponseFuture response = message.getResponseFuture();
        FutureListener listener = 
                message.isProxyEnabled() ? 
                        new ProxyFutureListener(message, response) :
                        new FutureListener(message, response);
        future.addListener(listener);
        return response;
    }
    
    /**
     * Retry a connection after a failure.  This will 
     * create a new connection and try again.
     * 
     * @param message  The message request we're sending.
     * @param response The response future for the message.
     * @param retries  The number of retries to make for the next connection
     *                 attempt.  This should be one less than the count
     *                 used for the previous attempt.
     */
    private void retryConnection(HttpRequestMessage message, ResponseFuture response, FutureListener listener) {
        // set the connect start time again
        message.setConnectStartTime();
        notifyMonitoringListeners(MonitoringEvent.CONNECTION_RETRIED, message); 
        ConnectFuture future = openConnection(message);
        future.addListener(listener);
    }
    
    /**
     * Open the appropriate connection for this message.
     * This will either open a direct connection or connect 
     * to the configured proxy server.
     * 
     * @param message The message getting sent.  This defines the target
     *                location and also holds the proxy configuration.
     * 
     * @return A ConnectFuture instance for managing the connection.
     */
    private ConnectFuture openConnection(HttpRequestMessage message) {
        InetSocketAddress remote = 
                message.isProxyEnabled() ?
                        message.getProxyConfiguration().getProxyAddress(message.getUrl()) :
                        new InetSocketAddress(message.getHost(), message.getPort());
        return connector.connect(remote);
    }
    
    /**
     * Attempt to get a connection from the connection pool.
     * 
     * @param message The message we're sending.
     * 
     * @return A pooled connection.  This returns null if there's
     *         no available connection for the target location.
     */
    private ConnectFuture getPooledConnection(HttpRequestMessage message) {
        IoSession pooledSession = connectionPool.getActiveSession(message);
        if (pooledSession == null) {
            return null;
        }

        // clear the connect start time to prevent misreporting
        message.clearConnectStartTime();
        notifyMonitoringListeners(MonitoringEvent.CONNECTION_REUSED, message); 
        // create a containing future object and set the session right away
        ConnectFuture future = new DefaultConnectFuture();
        future.setSession(pooledSession);
        return future;
    }

    /**
     * Gets the connection timeout.
     *
     * @return the connection timeout in milliseconds
     */
    public int getConnectionTimeout() {
        return connectionTimeout;
    }

    /**
     * Sets the connection timeout.
     *
     * @param connectionTimeout the new connection timeout in milliseconds
     */
    public void setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
        // apply the new connect timeout value to the config
        applyConnectionTimeout();
    }

    /** Apply connection timeout. */
    private void applyConnectionTimeout() {
        connector.setConnectTimeout(connectionTimeout / 1000);
    }

    /**
     * Shuts down the AsyncHttpClient object and releases references to associated thread pools.  
     * Should always be called when the application is  
     * done using the object or a hang can occur.
     */
    public void destroyAll() {
        if (connector != null) {
            connector.dispose();
            //connector.setWorkerTimeout(0);
        }
        // release the thread pool references 
        threadPool = null; 
        eventThreadPool = null; 
        // and mark this as no longer usable. 
        destroyed = true; 
    }

    /**
     * Add a statistics listener to this client object.
     * 
     * @param listener The listener to add.
     */
    public void addMonitoringListener(MonitoringListener listener) {
        synchronized (this) {
            // we've deferred creation until we have someone listening 
            if (eventDispatcher == null) {
                eventDispatcher = new EventDispatcher();
            }
        }
        eventDispatcher.addListener(listener);
    }

    /**
     * Remove a listener from the client. 
     * 
     * @param listener The listener to remove.
     */
    public void removeMonitoringListener(MonitoringListener listener) {
        if (eventDispatcher != null) {
            eventDispatcher.removeListener(listener);
        }
    }

    /**
     * Send a notification event to any monitoring listeners.
     * 
     * @param type    The type of event.
     * @param request The HttpRequestMessage that triggerd the event.
     */
    public void notifyMonitoringListeners(int type, HttpRequestMessage request) {
        // if there's no event dispatcher, no point in dispatching this
        if (eventDispatcher == null) {
            return;
        }
        
        MonitoringEvent event = new MonitoringEvent(type, request);         
        eventDispatcher.dispatchEvent(event); 
    }

    /**
     * The listener interface for receiving connection events. Its main purpose is to notify the
     * callback of any exceptions that may have occurred, or to handle the session and inject
     * the proper SSL filter if the connection is to be used for <code>https</code>.  If a good
     * connection occurs, it is also responsible for sending the request.
     */
    class FutureListener implements IoFutureListener<ConnectFuture> {
        /** The request. */
        final HttpRequestMessage request;
        /** The response future. */
        final ResponseFuture response;
        
        /** The count of additional retries for the connection */
        volatile int retries = getConnectionRetries(); 

        /**
         * Instantiates a new future listener for a connection.
         *
         * @param request the <code>HttpRequestMessage</code> request that is to be sent.
         * @param response the response future object.
         */
        public FutureListener(HttpRequestMessage request, ResponseFuture response) {
            this.request = request;
            this.response = response;
        }

        /**
         * Event notification that the conection has completed, either by a successful connection or
         * by an error.
         *
         * @param future the {@link org.apache.mina.core.ConnectFuture}.
         */
        public void operationComplete(ConnectFuture future) {
            if (future.isConnected()) {
                notifyMonitoringListeners(MonitoringEvent.CONNECTION_SUCCESSFUL, request); 
                IoSession sess = future.getSession();

                sess.getConfig().setReaderIdleTime(timeout);
                // see if we need to add the SSL filter
                addSSLFilter(sess);
                // add the protocol filter (if it's not there already like in a
                // reused session)
                addProtocolCodecFilter(sess);
                // (optional) add the executor filter for the event thread pool 
                // (if it's not there already like in a reused session)
                addEventThreadPoolFilter(sess);
                // now that we're connection, configure the session appropriately. 
                configureSession(sess);
                // and finally start the request process rolling. 
                sess.write(request);
            } 
            else {
                if (retries-- > 0) {
                    // go retry this connection 
                    retryConnection(request, response, this); 
                }
                else {
                    try {
                        notifyMonitoringListeners(MonitoringEvent.CONNECTION_FAILED, request); 
                        future.getSession();
                        response.setException(new AsyncHttpClientException("Connection failed."));
                    } catch (RuntimeIoException e) {
                        //Set the future exception
                        response.setException(e);
                    }
                }
            }
        }

        /**
         * Configure the IoSession with the client connection 
         * parameters.
         * 
         * @param sess   The session to which the configuration values are to
         *               be applied.
         */
        protected void configureSession(IoSession sess) {
                sess.setAttribute(HttpIoHandler.CURRENT_REQUEST, request);

                sess.setAttachment(AsyncHttpClient.this);

                //Set the socket parameters on successfully obtaining the session
                SocketSessionConfig config = (SocketSessionConfig) sess.getConfig();
                config.setKeepAlive(keepAlive);
                config.setOobInline(oobInline);
                config.setReceiveBufferSize(receiveBufferSize);
                config.setReuseAddress(reuseAddress);
                config.setSendBufferSize(sendBufferSize);
                config.setSoLinger(soLinger);
                config.setTcpNoDelay(tcpNoDelay);
                config.setTrafficClass(trafficClass);
        }

        /**
         * Add the ExecutorFilter to the session filter chain.
         * The ExecutorFilter allows session callbacks to be 
         * dispatched using a different thread pool than the one 
         * used for the I/O threads.
         * 
         * @param sess   The session to configure.
         */
        protected void addEventThreadPoolFilter(IoSession sess) {
            if (eventThreadPool != null && 
                    !sess.getFilterChain().contains(EVENT_THREAD_POOL_FILTER)) {
                sess.getFilterChain().addLast(EVENT_THREAD_POOL_FILTER, 
                        new ExecutorFilter(eventThreadPool));
            }
        }

        /**
         * Add the HttpProtocol filter to the session processing 
         * chain.  The protocol filter handles the returned 
         * response information.
         * 
         * @param sess   The target session.
         */
        protected void addProtocolCodecFilter(IoSession sess) {
            if (!sess.getFilterChain().contains(PROTOCOL_FILTER)) {
                sess.getFilterChain().addLast(PROTOCOL_FILTER, new ProtocolCodecFilter(
                        new HttpProtocolCodecFactory()));
            }
        }

        /**
         * Add an SSL filter to the io session when the 
         * connection type is "https".
         * 
         * @param sess   The session to configure.
         */
        private void addSSLFilter(IoSession sess) {
            String scheme = request.getUrl().getProtocol();
            
            //Add the https filter
            if (scheme.toLowerCase().equals("https")) {
                // add the SSL filter if it's not there already like in a reused
                // session
                if (!sess.getFilterChain().contains(SSL_FILTER)) {
                    try {
                        SslFilter sslFilter = createSSLFilter();
                        sess.getFilterChain().addLast(SSL_FILTER, sslFilter);
                    } catch (GeneralSecurityException e) {
                        try {
                            sess.getHandler().exceptionCaught(sess, e);
                        } catch (Exception e1) {
                            //Do nothing...we just reported it
                        }
                    }
                }
            }
        }

        /**
         * Create an SslFilter instance for this client.  The 
         * filter will be configured using any SSL context defined 
         * for the request, or a default context if one has not
         * been explicitly configured. 
         * 
         * @return An appropriately configured SSLFilter for this connection.
         * @exception GeneralSecurityException
         */
        protected SslFilter createSSLFilter() throws GeneralSecurityException {
            SSLContext context = request.getSSLContext();
            if (context == null) {
                // if the caller did not provide an SSL context
                // create a default SSL context
                context = createDefaultSSLContext();
            }
            SslFilter sslFilter = new SslFilter(context);
            sslFilter.setUseClientMode(true);
            return sslFilter;
        }

        /**
         * Creates a default SSL context in case it was not provided by the
         * caller.
         *
         * @return the SSL context
         * @throws GeneralSecurityException the general security exception
         */
        private SSLContext createDefaultSSLContext() throws GeneralSecurityException {
            SSLContext context = SSLContext.getInstance(sslProtocol);
            context.init(null, TrustManagerFactoryImpl.X509_MANAGERS, null);
            return context;
        }
    }
    
    /**
     * A FutureListener for managing connections used with 
     * proxied connections.  This Future manages establishing 
     * the appropriate connection type with the proxy before 
     * handling the actual client request. 
     */
    class ProxyFutureListener extends FutureListener {
        public ProxyFutureListener(HttpRequestMessage request, 
                                   ResponseFuture response) {
            super(request, response);
        }

        @Override
        /**
         * Handle operation completion events.  This is primarly 
         * to handle the tunneling protocol required by 
         * https requests through a proxy server.  
         * 
         * @param future The Future object associated with the operation.
         */
        public void operationComplete(ConnectFuture future) {
            if (future.isConnected()) {
                IoSession session = future.getSession();
                // add the protocol filter (if it's not there already like in a
                // reused session)
                addProtocolCodecFilter(session);
                addProxyFilter(session);
                // (optional) add the executor filter for the event thread pool 
                // (if it's not there already like in a reused session)
                addEventThreadPoolFilter(session);
                
                configureSession(session);
                
                // write the connect request if the protocol is https
                String protocol = request.getUrl().getProtocol();
                if (protocol.toLowerCase().equals("https")) {
                    session.write(createConnectRequest());
                } else {
                    session.write(request);
                }
            } else {
                super.operationComplete(future);
            }
        }
        
        /**
         * Compose the connection request used for SSL proxy 
         * tunneling connections.  This CONNECT request tells
         * the proxy server to establish a connection with 
         * the remote target and tunnel it through to the 
         * client.  Once the connection has been established, 
         * an SLL connection will be layered over the top 
         * of the connection, creating a secure channel between 
         * the client and the server. 
         * 
         * @return The request message to send back to the proxy for 
         *         establishing the tunneled connection.
         */
        private HttpRequestMessage createConnectRequest() {
            try {
                HttpRequestMessage req = new HttpRequestMessage(new URL("http", request.getHost(), request.getPort(), ""), null);
                req.setRequestMethod(HttpMethod.CONNECT);
                return req;
            } catch (MalformedURLException e) {
                // ignored, shouldn't happen
            } catch (ProtocolException e) {
                // ignored, shouldn't happen
            }
            // this can't happen
            return null;
        }

        /**
         * Add a proxy filter to the session filter chain.
         * The proxy filter will be either a plain filter or a 
         * tunneling SSL filter. 
         * 
         * @param session
         */
        private void addProxyFilter(IoSession session) {
            if (!session.getFilterChain().contains(PROXY_FILTER)) {
                String scheme = request.getUrl().getProtocol();
                ProxyFilter proxyFilter = null;
                if (scheme.toLowerCase().equals("https")) {
                    try {
                        proxyFilter = new ProxyFilter(createSSLFilter());
                    } catch (GeneralSecurityException e) {
                        // this normally cannot happen
                    }
                } else {
                    proxyFilter = new ProxyFilter();
                }
                session.getFilterChain().addLast(PROXY_FILTER, proxyFilter);
            }
        }
    }
}
