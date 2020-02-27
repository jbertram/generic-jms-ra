/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2006, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.resource.adapter.jms;

import java.io.PrintWriter;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.TimeUnit;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.ExceptionListener;
import javax.jms.JMSContext;
import javax.jms.JMSException;
import javax.jms.QueueConnection;
import javax.jms.QueueConnectionFactory;
import javax.jms.ResourceAllocationException;
import javax.jms.Session;
import javax.jms.TopicConnection;
import javax.jms.TopicConnectionFactory;
import javax.jms.XAConnection;
import javax.jms.XAConnectionFactory;
import javax.jms.XAJMSContext;
import javax.jms.XAQueueConnection;
import javax.jms.XAQueueConnectionFactory;
import javax.jms.XAQueueSession;
import javax.jms.XASession;
import javax.jms.XATopicConnection;
import javax.jms.XATopicConnectionFactory;
import javax.jms.XATopicSession;
import javax.naming.Context;
import javax.naming.NamingException;
import javax.resource.ResourceException;
import javax.resource.spi.ConnectionEvent;
import javax.resource.spi.ConnectionEventListener;
import javax.resource.spi.ConnectionRequestInfo;
import javax.resource.spi.IllegalStateException;
import javax.resource.spi.LocalTransaction;
import javax.resource.spi.ManagedConnection;
import javax.resource.spi.ManagedConnectionMetaData;
import javax.resource.spi.SecurityException;
import javax.security.auth.Subject;
import javax.transaction.xa.XAResource;

import org.jboss.logging.Logger;
import org.jboss.resource.adapter.jms.inflow.JmsActivation;

/**
 * <p>
 * Managed Connection, manages one or more JMS sessions.
 * <p/>
 * <p>
 * Every ManagedConnection will have a physical JMSConnection under the hood.
 * This may leave out several session, as specifyed in 5.5.4 Multiple Connection
 * Handles. Thread safe semantics is provided
 * <p/>
 * <p>
 * Hm. If we are to follow the example in 6.11 this will not work. We would have
 * to use the SAME session. This means we will have to guard against concurrent
 * access. We use a stack, and only allowes the handle at the top of the stack
 * to do things.
 * <p/>
 * <p>
 * As to transactions we some fairly hairy alternatives to handle: XA - we get
 * an XA. We may now only do transaction through the XAResource, since a
 * XASession MUST throw exceptions in commit etc. But since XA support implies
 * LocatTransaction support, we will have to use the XAResource in the
 * LocalTransaction class. LocalTx - we get a normal session. The
 * LocalTransaction will then work against the normal session api.
 * <p/>
 * <p>
 * An invokation of JMS MAY BE DONE in none transacted context. What do we do
 * then? How much should we leave to the user???
 * <p/>
 * <p>
 * One possible solution is to use transactions any way, but under the hood. If
 * not LocalTransaction or XA has been aquired by the container, we have to do
 * the commit in send and publish. (CHECK is the container required to get a XA
 * every time it uses a managed connection? No its is not, only at creation!)
 * <p/>
 * <p>
 * Does this mean that a session one time may be used in a transacted env, and
 * another time in a not transacted.
 * <p/>
 * <p>
 * Maybe we could have this simple rule:
 * <p/>
 * <p>
 * If a user is going to use non trans:
 * <ul>
 * <li>mark that i ra deployment descr
 * <li>Use a JmsProviderAdapter with non XA factorys
 * <li>Mark session as non transacted (this defeats the purpose of specifying
 * <li>trans attrinbutes in deploy descr NOT GOOD
 * </ul>
 * <p/>
 * <p>
 * From the JMS tutorial: "When you create a session in an enterprise bean, the
 * container ignores the arguments you specify, because it manages all
 * transactional properties for enterprise beans."
 * <p/>
 * <p>
 * And further: "You do not specify a message acknowledgment mode when you
 * create a message-driven bean that uses container-managed transactions. The
 * container handles acknowledgment automatically."
 * <p/>
 * <p>
 * On Session or Connection:
 * <p>
 * From Tutorial: "A JMS API resource is a JMS API connection or a JMS API
 * session." But in the J2EE spec only connection is considered a resource.
 * <p/>
 * <p>
 * Not resolved: connectionErrorOccurred: it is verry hard to know from the
 * exceptions thrown if it is a connection error. Should we register an
 * ExceptionListener and mark al handles as errounous? And then let them send
 * the event and throw an exception?
 *
 * @author <a href="mailto:peter.antman@tim.se">Peter Antman</a>.
 * @author <a href="mailto:jason@planet57.com">Jason Dillon</a>
 * @author <a href="mailto:adrian@jboss.com">Adrian Brock</a>
 */
public class JmsManagedConnection implements ManagedConnection, ExceptionListener {

    private static final Logger log = Logger.getLogger(JmsManagedConnection.class);

    private JmsManagedConnectionFactory mcf;
    private JmsConnectionRequestInfo info;
    private String user;
    private String pwd;
    private volatile boolean isSetUp;
    private volatile boolean isDestroyed;

    private ReentrantLock lock = new ReentrantLock(true);

    // Physical JMS connection stuff
    private Connection con = null;
    private Session session = null;
    private XASession xaSession = null;
    private XAResource xaResource = null;
    private boolean xaTransacted = false;
    private JMSContext context = null;
    private XAJMSContext xaContext = null;

    /**
     * Holds all current JmsSession handles.
     */
    private Set<JmsSession> handles = Collections.synchronizedSet(new HashSet<>());

    /**
     * The event listeners
     */
    private Vector<ConnectionEventListener> listeners = new Vector<>();

    /**
     * Create a <tt>JmsManagedConnection</tt>.
     *
     * @param mcf
     * @param info
     * @param user
     * @param pwd
     * @throws ResourceException
     */
    public JmsManagedConnection(final JmsManagedConnectionFactory mcf,
                                final ConnectionRequestInfo info,
                                final String user,
                                final String pwd)
       throws ResourceException {
        this.mcf = mcf;

        // seem like its asking for trouble here
        this.info = (JmsConnectionRequestInfo) info;
        this.user = user;
        this.pwd = pwd;

        try {
            setup();
        } catch (Throwable t) {
            try {
                destroy();
            } catch (Throwable ignored) {
            }
            if (t instanceof ResourceException) {
                throw (ResourceException) t;
            } else {
                throw new ResourceException(t);
            }
        }
    }

    //---- ManagedConnection API ----
    /**
     * Get the physical connection handler. This bummer will be called in two
     * situations:
     * <ol>
     * <li>When a new mc has bean created and a connection is needed
     * <li>When an mc has been fetched from the pool (returned in match*)
     * </ol>
     * It may also be called multiple time without a cleanup, to support
     * connection sharing.
     *
     * @param subject
     * @param info
     * @return A new connection object.
     * @throws ResourceException
     */
    @Override
    public Object getConnection(final Subject subject, final ConnectionRequestInfo info) throws ResourceException {
        // Check user first
        JmsCred cred = JmsCred.getJmsCred(mcf, subject, info);

        // Null users are allowed!
        if (user != null && !user.equals(cred.name)) {
            throw new SecurityException("Password credentials not the same, reauthentication not allowed");
        }
        if (cred.name != null && user == null) {
            throw new SecurityException("Password credentials not the same, reauthentication not allowed");
        }

        user = cred.name; // Basically meaningless

        if (isDestroyed) {
            throw new IllegalStateException("ManagedConnection already destroyed");
        }

        // Create a handle
        JmsSession handle = new JmsSession(this, (JmsConnectionRequestInfo) info);
        handles.add(handle);
        return handle;
    }

    /**
     * Destroy all handles.
     *
     * @throws ResourceException Failed to close one or more handles.
     */
    private void destroyHandles() throws ResourceException {
        try {
            if (con != null) {
                con.stop();
            }
        } catch (Throwable t) {
            log.trace("Ignored error stopping connection", t);
        }

        Iterator<JmsSession> iter = handles.iterator();
        while (iter.hasNext()) {
            iter.next().destroy();
        }

        // clear the handles map
        handles.clear();
    }

    /**
     * Destroy the physical connection.
     *
     * @throws ResourceException Could not property close the session and
     * connection.
     */
    @Override
    public final void destroy() throws ResourceException {
        if (isDestroyed || con == null) {
            return;
        }
        synchronized (this) {
            if (isDestroyed || con == null) {
                return;
            }

            try {
                con.setExceptionListener(null);
            } catch (JMSException e) {
                log.debug("Error unsetting the exception listener " + this, e);
            }

            destroyHandles();

            try {
                // Close session and connection
                try {
                    if (session != null) {
                        session.close();
                    }
                } catch (JMSException e) {
                    log.debug("Error closing session " + this, e);
                }
                try {
                    if (xaTransacted && xaSession != null) {
                        xaSession.close();
                    }
                } catch (JMSException e) {
                    log.debug("Error closing xaSession " + this, e);
                }
                con.close();
            } catch (Throwable e) {
                throw new ResourceException("Could not properly close the session and connection", e);
            } finally {
                isDestroyed = true;
            }
        }
    }

    /**
     * Cleans up, from the spec - The cleanup of ManagedConnection instance
     * resets its client specific state. Does that mean that authentication
     * should be redone.
     *
     * FIXME
     *
     * @throws javax.resource.ResourceException
     */
    @Override
    public void cleanup() throws ResourceException {
        if (isDestroyed) {
            throw new IllegalStateException("ManagedConnection already destroyed");
        }

        // destory handles
        destroyHandles();

        boolean isActive = false;

        if (lock.hasQueuedThreads()) {
            Collection<Thread> threads = lock.getQueuedThreads();
            for (Thread thread : threads) {
                Throwable t = new Throwable("Thread waiting for lock during cleanup");
                t.setStackTrace(thread.getStackTrace());

                log.warn(t.getMessage(), t);
            }

            isActive = true;
        }

        if (lock.isLocked()) {
            Throwable t = new Throwable("Lock owned during cleanup");
            t.setStackTrace(lock.getOwner().getStackTrace());

            log.warn(t.getMessage(), t);

            isActive = true;
        }

        if (isActive) {
            // There are active lock - make sure that the JCA container kills
            // this handle by throwing an exception

            throw new ResourceException("Still active locks for " + this);
        }
    }

    /**
     * Move a handler from one mc to this one.
     *
     * @param obj An object of type JmsSession.
     * @throws ResourceException Failed to associate connection.
     * @throws IllegalStateException ManagedConnection in an illegal state.
     */
    @Override
    public void associateConnection(final Object obj) throws ResourceException {
        //
        // Should we check auth, ie user and pwd? FIXME
        //

        if (!isDestroyed && obj instanceof JmsSession) {
            JmsSession h = (JmsSession) obj;
            h.setManagedConnection(this);
            handles.add(h);
        } else {
            throw new IllegalStateException("ManagedConnection in an illegal state");
        }
    }

    protected void lock() {
        lock.lock();
    }

    protected void tryLock() throws JMSException {
        int tryLock = mcf.getUseTryLock();
        if (tryLock <= 0) {
            lock();
            return;
        }
        try {
            if (lock.tryLock(tryLock, TimeUnit.SECONDS) == false) {
                throw new ResourceAllocationException("Unable to obtain lock in " + tryLock + " seconds: " + this);
            }
        } catch (InterruptedException e) {
            throw new ResourceAllocationException("Interrupted attempting lock: " + this);
        }
    }

    protected void unlock() {
        if (lock.isLocked()) {
            lock.unlock();
        } else {
            log.warn("Owner is null");

            Throwable t = new Throwable("Thread trying to unlock");
            t.setStackTrace(Thread.currentThread().getStackTrace());

            log.warn(t.getMessage(), t);
        }
    }

    /**
     * Add a connection event listener.
     *
     * @param l The connection event listener to be added.
     */
    @Override
    public void addConnectionEventListener(final ConnectionEventListener l) {
        listeners.addElement(l);

        if (log.isTraceEnabled()) {
            log.trace("ConnectionEvent listener added: " + l);
        }
    }

    /**
     * Remove a connection event listener.
     *
     * @param l The connection event listener to be removed.
     */
    @Override
    public void removeConnectionEventListener(final ConnectionEventListener l) {
        listeners.removeElement(l);
    }

    /**
     * Get the XAResource for the connection.
     *
     * @return The XAResource for the connection.
     * @throws ResourceException XA transaction not supported
     */
    @Override
    public XAResource getXAResource() throws ResourceException {
        //
        // Spec says a mc must always return the same XA resource,
        // so we cache it.
        //
        if (!xaTransacted) {
            return null;
        }

        if (xaResource == null) {
            xaResource = xaSession.getXAResource();
        }

        if (log.isTraceEnabled()) {
            log.trace("XAResource=" + xaResource);
        }

        xaResource = new JmsXAResource(this, xaResource);
        return xaResource;
    }

    /**
     * Get the location transaction for the connection.
     *
     * @return The local transaction for the connection.
     * @throws ResourceException
     */
    @Override
    public LocalTransaction getLocalTransaction() throws ResourceException {
        LocalTransaction tx = new JmsLocalTransaction(this);
        if (log.isTraceEnabled()) {
            log.trace("LocalTransaction=" + tx);
        }
        return tx;
    }

    /**
     * Get the meta data for the connection.
     *
     * @return The meta data for the connection.
     * @throws ResourceException
     * @throws IllegalStateException ManagedConnection already destroyed.
     */
    @Override
    public ManagedConnectionMetaData getMetaData() throws ResourceException {
        if (isDestroyed) {
            throw new IllegalStateException("ManagedConnection already destroyd");
        }

        return new JmsMetaData(this);
    }

    /**
     * Set the log writer for this connection.
     *
     * @param out The log writer for this connection.
     * @throws ResourceException
     */
    @Override
    public void setLogWriter(final PrintWriter out) throws ResourceException {
        //
        // jason: screw the logWriter stuff for now it sucks ass
        //
    }

    /**
     * Get the log writer for this connection.
     *
     * @return Always null
     * @throws javax.resource.ResourceException
     */
    @Override
    public PrintWriter getLogWriter() throws ResourceException {
        //
        // jason: screw the logWriter stuff for now it sucks ass
        //

        return null;
    }

    // --- Exception listener implementation
    @Override
    public void onException(JMSException exception) {
        if (isDestroyed) {
            if (log.isTraceEnabled()) {
                log.trace("Ignoring error on already destroyed connection " + this, exception);
            }
            return;
        }

        log.warn("Handling jms exception failure: " + this, exception);

        // We need to unlock() before sending the connection error to the
        // event listeners. Otherwise the lock won't be in sync once
        // cleanup() is called
        if (lock.isLocked() && Thread.currentThread().equals(lock.getOwner())) {
            unlock();
        }

        try {
            con.setExceptionListener(null);
        } catch (JMSException e) {
            log.debug("Unable to unset exception listener", e);
        }

        ConnectionEvent event = new ConnectionEvent(this, ConnectionEvent.CONNECTION_ERROR_OCCURRED, exception);
        sendEvent(event);
    }

    // --- Api to JmsSession
    /**
     * Get the session for this connection.
     *
     * @return Either a topic or queue connection.
     */
    protected Session getSession() {
        return session;
    }

    /**
     * Get the JMSContext for this connection.
     * @return the JMSContext for this connection.
     */
    protected JMSContext getJMSContext() {
        return context;
    }

    /**
     * Send an event.
     *
     * @param event The event to send.
     */
    protected void sendEvent(final ConnectionEvent event) {
        int type = event.getId();

        if (log.isTraceEnabled()) {
            log.trace("Sending connection event: " + type);
        }

        // convert to an array to avoid concurrent modification exceptions
        ConnectionEventListener[] list
           = listeners.toArray(new ConnectionEventListener[listeners.size()]);

        for (int i = 0; i < list.length; i++) {
            switch (type) {
                case ConnectionEvent.CONNECTION_CLOSED:
                    list[i].connectionClosed(event);
                    break;

                case ConnectionEvent.LOCAL_TRANSACTION_STARTED:
                    list[i].localTransactionStarted(event);
                    break;

                case ConnectionEvent.LOCAL_TRANSACTION_COMMITTED:
                    list[i].localTransactionCommitted(event);
                    break;

                case ConnectionEvent.LOCAL_TRANSACTION_ROLLEDBACK:
                    list[i].localTransactionRolledback(event);
                    break;

                case ConnectionEvent.CONNECTION_ERROR_OCCURRED:
                    list[i].connectionErrorOccurred(event);
                    break;

                default:
                    throw new IllegalArgumentException("Illegal eventType: " + type);
            }
        }
    }

    /**
     * Remove a handle from the handle map.
     *
     * @param handle The handle to remove.
     */
    protected void removeHandle(final JmsSession handle) {
        handles.remove(handle);
    }

    // --- Used by MCF
    /**
     * Get the request info for this connection.
     *
     * @return The request info for this connection.
     */
    protected ConnectionRequestInfo getInfo() {
        return info;
    }

    /**
     * Get the connection factory for this connection.
     *
     * @return The connection factory for this connection.
     */
    protected JmsManagedConnectionFactory getManagedConnectionFactory() {
        return mcf;
    }

    void start() throws JMSException {
        con.start();
    }

    void stop() throws JMSException {
        con.stop();
    }

    // --- Used by MetaData
    /**
     * Get the user name for this connection.
     *
     * @return The user name for this connection.
     */
    protected String getUserName() {
        return user;
    }

    /**
     * Setup the connection.
     *
     * @throws ResourceException
     */
    private void setup() throws ResourceException {
        synchronized (this) {
            if (isSetUp) {
                return;
            }

            boolean trace = log.isTraceEnabled();
            ClassLoader oldTCCL = SecurityActions.getThreadContextClassLoader();
            try {
                SecurityActions.setThreadContextClassLoader(JmsManagedConnection.class.getClassLoader());

                Context context = JmsActivation.convertStringToContext(mcf.getJndiParameters());
                Object factory;
                boolean transacted = info.isTransacted();
                int ack = transacted ? Session.SESSION_TRANSACTED : info.getAcknowledgeMode();

                String connectionFactory = mcf.getConnectionFactory();
                if (connectionFactory == null) {
                    throw new IllegalStateException("No configured 'connectionFactory'.");
                }
                factory = context.lookup(connectionFactory);
                con = createConnection(factory, user, pwd, transacted, ack);
                if (info.getClientID() != null && !info.getClientID().equals(con.getClientID())) {
                    con.setClientID(info.getClientID());
                }

                if (con instanceof XAConnection && transacted) {
                    switch (mcf.getProperties().getType()) {
                        case JmsConnectionFactory.QUEUE:
                            xaSession = ((XAQueueConnection) con).createXAQueueSession();
                            session = ((XAQueueSession) xaSession).getQueueSession();
                            break;
                        case JmsConnectionFactory.TOPIC:
                            xaSession = ((XATopicConnection) con).createXATopicSession();
                            session = ((XATopicSession) xaSession).getTopicSession();
                            break;
                        default:
                            xaSession = ((XAConnection) con).createXASession();
                            session = xaSession.getSession();
                            break;
                    }
                    xaTransacted = true;
                } else {
                    switch (mcf.getProperties().getType()) {
                        case JmsConnectionFactory.QUEUE:
                            session = ((QueueConnection) con).createQueueSession(transacted, ack);
                            break;
                        case JmsConnectionFactory.TOPIC:
                            session = ((TopicConnection) con).createTopicSession(transacted, ack);
                            break;
                        default:
                            session = con.createSession(transacted, ack);
                            break;
                    }
                    if (trace) {
                        log.trace("Using a non-XA Connection.  "
                                     + "It will not be able to participate in a Global UOW");
                    }
                }
                con.setExceptionListener(this);
                if (trace) {
                    log.trace("created connection: " + con);
                }

                log.debug("xaSession=" + xaSession + ", Session=" + session);
                log.debug("transacted=" + transacted + ", ack=" + ack);
                isSetUp = true;
            } catch (NamingException | JMSException e) {
                throw new ResourceException("Unable to setup connection", e);
            } finally {
                SecurityActions.setThreadContextClassLoader(oldTCCL);
            }
        }
    }

    /**
     * Create a connection from the given factory.An XA connection will be
     * created if possible.
     *
     * @param factory An object that implements ConnectionFactory,
     * XAQConnectionFactory
     * @param username The username to use or null for no user.
     * @param password The password for the given username or null if no
     * username was specified.
     * @param transacted
     * @param ack
     * @return A connection.
     * @throws JMSException Failed to create connection.
     * @throws IllegalArgumentException Factory is null or invalid.
     */
    public Connection createConnection(final Object factory, final String username, final String password, boolean transacted, int ack)
       throws JMSException {
        if (factory == null) {
            throw new IllegalArgumentException("factory is null");
        }

        log.debug("using connection factory: " + factory);
        log.debug("using username/password: " + String.valueOf(username) + "/-- not shown --");

        Connection connection = null;

        if (factory instanceof XAConnectionFactory) {
            XAConnectionFactory xaConnFactory = (XAConnectionFactory) factory;

            if (username != null) {
                switch (mcf.getProperties().getType()) {
                    case JmsConnectionFactory.QUEUE: {
                        Connection realConnection = ((XAQueueConnectionFactory) xaConnFactory).createXAQueueConnection(username, password);
                        context = null;
                        connection = new JmsConnectionSession(realConnection, createSession(realConnection, transacted, ack));
                        break;
                    }
                    case JmsConnectionFactory.TOPIC: {
                        Connection realConnection = ((XATopicConnectionFactory) xaConnFactory).createXATopicConnection(username, password);
                        context = null;
                        connection = new JmsConnectionSession(realConnection, createSession(realConnection, transacted, ack));
                        break;
                    }
                    case JmsConnectionFactory.AGNOSTIC: {
                        Connection realConnection = xaConnFactory.createXAConnection(username, password);
                        context = null;
                        connection = new JmsConnectionSession(realConnection, createSession(realConnection, transacted, ack));
                        break;
                    }
                    case JmsConnectionFactory.JMS_CONTEXT:
                        try {
                            xaContext = xaConnFactory.createXAContext(username, password);
                            context = xaContext.getContext();
                            connection = new JmsConnectionContext(context);
                        } catch (Exception e) {
                            log.fatal("The JMS provider does not support the JMS 2.0 XAJMSContext interface: "
                                         + e.getMessage(), e);
                            throw new JMSException("The JMS provider does not support the JMS 2.0 XAJMSContext interface");
                        }
                        break;
                }
            } else {
                switch (mcf.getProperties().getType()) {
                    case JmsConnectionFactory.QUEUE: {
                        Connection realConnection = ((XAQueueConnectionFactory) xaConnFactory).createXAQueueConnection();
                        context = null;
                        connection = new JmsConnectionSession(realConnection, createSession(realConnection, transacted, ack));
                        break;
                    }
                    case JmsConnectionFactory.TOPIC: {
                        Connection realConnection = ((XATopicConnectionFactory) xaConnFactory).createXATopicConnection();
                        context = null;
                        connection = new JmsConnectionSession(realConnection, createSession(realConnection, transacted, ack));
                        break;
                    }
                    case JmsConnectionFactory.AGNOSTIC: {
                        Connection realConnection = xaConnFactory.createXAConnection();
                        context = null;
                        connection = new JmsConnectionSession(realConnection, createSession(realConnection, transacted, ack));
                        break;
                    }
                    case JmsConnectionFactory.JMS_CONTEXT:
                        try {
                            xaContext = xaConnFactory.createXAContext();
                            context = xaContext.getContext();
                            connection = new JmsConnectionContext(context);
                        } catch (Exception e) {
                            log.fatal("The JMS provider does not support the JMS 2.0 XAJMSContext interface: "
                                         + e.getMessage(), e);
                            throw new JMSException("The JMS provider does not support the JMS 2.0 XAJMSContext interface");
                        }
                        break;
                }
            }
            log.debug("created XAConnection: " + connection);
        } else if (factory instanceof ConnectionFactory) {
            ConnectionFactory nonXAConnFactory = (ConnectionFactory) factory;
            if (username != null) {
                switch (mcf.getProperties().getType()) {
                    case JmsConnectionFactory.QUEUE: {
                        Connection realConnection = ((QueueConnectionFactory) nonXAConnFactory).createQueueConnection(username, password);
                        context = null;
                        connection = new JmsConnectionSession(realConnection, createSession(realConnection, transacted, ack));
                        break;
                    }
                    case JmsConnectionFactory.TOPIC: {
                        Connection realConnection = ((TopicConnectionFactory) nonXAConnFactory).createTopicConnection(username, password);
                        context = null;
                        connection = new JmsConnectionSession(realConnection, createSession(realConnection, transacted, ack));
                        break;
                    }
                    case JmsConnectionFactory.AGNOSTIC: {
                        Connection realConnection = nonXAConnFactory.createConnection(username, password);
                        context = null;
                        connection = new JmsConnectionSession(realConnection, createSession(realConnection, transacted, ack));
                        break;
                    }
                    case JmsConnectionFactory.JMS_CONTEXT:
                        try {
                            context = nonXAConnFactory.createContext(username, password);
                            connection = new JmsConnectionContext(context);
                        } catch (Exception e) {
                            log.fatal(
                               "The JMS provider does not support the JMS 2.0 JMSContext interface: " + e.getMessage(),
                               e);
                            throw new JMSException("The JMS provider does not support the JMS 2.0 JMSContext interface");
                        }
                        break;
                }
            } else {
                switch (mcf.getProperties().getType()) {
                    case JmsConnectionFactory.QUEUE: {
                        Connection realConnection = ((QueueConnectionFactory) nonXAConnFactory).createQueueConnection();
                        context = null;
                        connection = new JmsConnectionSession(realConnection, createSession(realConnection, transacted, ack));
                        break;
                    }
                    case JmsConnectionFactory.TOPIC: {
                        Connection realConnection = ((TopicConnectionFactory) nonXAConnFactory).createTopicConnection();
                        context = null;
                        connection = new JmsConnectionSession(realConnection, createSession(realConnection, transacted, ack));
                        break;
                    }
                    case JmsConnectionFactory.AGNOSTIC: {
                        Connection realConnection = nonXAConnFactory.createConnection();
                        context = null;
                        connection = new JmsConnectionSession(realConnection, createSession(realConnection, transacted, ack));
                        break;
                    }
                    case JmsConnectionFactory.JMS_CONTEXT:
                        try {
                            context = nonXAConnFactory.createContext();
                            connection = new JmsConnectionContext(context);
                        } catch (Exception e) {
                            log.fatal(
                               "The JMS provider does not support the JMS 2.0 JMSContext interface: " + e.getMessage(),
                               e);
                            throw new JMSException("The JMS provider does not support the JMS 2.0 JMSContext interface");
                        }
                        break;
                }
            }

            log.debug("created " + mcf.getProperties().getSessionDefaultType() + " connection: " + connection);
        } else {
            throw new IllegalArgumentException("factory is invalid: " + factory);
        }
        return connection;
    }

    private boolean hasMethod(Object object, String method) {
        try {
            object.getClass().getDeclaredMethod(method);
        } catch (NoSuchMethodException | java.lang.SecurityException ex) {
            return false;
        }
        return true;
    }

    private Session createSession(Connection connection, boolean xaTransacted, int ack) throws JMSException {
        Session internalSession;
        if (hasMethod(connection, "createSession")) {
            internalSession = connection.createSession();
            log.debug("Session " + internalSession + " created with createSession()");
        } else {
            internalSession = connection.createSession(xaTransacted, ack);
            log.debug("Session " + internalSession + " created with createSession(" + xaTransacted + ", " + ack + ")");
        }
        return internalSession;
    }

    @Override
    public String toString() {
        return "JmsManagedConnection{"
           + "mcf=" + mcf
           + ", info=" + info
           + ", user=" + user
           + ", pwd=" + pwd
           + ", isSetUp=" + isSetUp
           + ", isDestroyed=" + isDestroyed
           + ", lock=" + lock
           + ", con=" + con
           + ", session=" + (session != null ? (session.getClass() + "@" + session.hashCode()) : "null")
           + ", xaSession=" + (xaSession != null ? (xaSession.getClass() + "@" + xaSession.hashCode()) : "null")
           + ", xaResource=" + xaResource
           + ", xaTransacted=" + xaTransacted
           + ", context=" + context
           + ", xaContext=" + xaContext
           + '}';
    }
}
