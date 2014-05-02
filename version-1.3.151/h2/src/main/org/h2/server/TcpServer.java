/*
 * Copyright 2004-2011 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.h2.Driver;
import org.h2.constant.ErrorCode;
import org.h2.engine.Constants;
import org.h2.message.DbException;
import org.h2.message.TraceSystem;
import org.h2.util.JdbcUtils;
import org.h2.util.NetUtils;
import org.h2.util.New;
import org.h2.util.StringUtils;

/**
 * The TCP server implements the native H2 database server protocol.
 * It supports multiple client connections to multiple databases
 * (many to many). The same database may be opened by multiple clients.
 * Also supported is the mixed mode: opening databases in embedded mode,
 * and at the same time start a TCP server to allow clients to connect to
 * the same database over the network.
 */
public class TcpServer implements Service {

    private static final int SHUTDOWN_NORMAL = 0;
    private static final int SHUTDOWN_FORCE = 1;

    /**
     * The name of the in-memory management database used by the TCP server
     * to keep the active sessions.
     */
    private static final String MANAGEMENT_DB_PREFIX = "management_db_";

    private static final Map<Integer, TcpServer> SERVERS = Collections.synchronizedMap(new HashMap<Integer, TcpServer>());

    private int port;
    private boolean trace;
    private boolean ssl;
    private boolean stop;
    private ShutdownHandler shutdownHandler;
    private ServerSocket serverSocket;
    private Set<TcpServerThread> running = Collections.synchronizedSet(new HashSet<TcpServerThread>());
    private String baseDir;
    private boolean allowOthers;
    private boolean isDaemon;
    private boolean ifExists;
    private Connection managementDb;
    private PreparedStatement managementDbAdd;
    private PreparedStatement managementDbRemove;
    private String managementPassword = "";
    private Thread listenerThread;
    private int nextThreadId;
    private String key, keyDatabase;

    /**
     * Get the database name of the management database.
     * The management database contains a table with active sessions (SESSIONS).
     *
     * @param port the TCP server port
     * @return the database name (usually starting with mem:)
     */
    public static String getManagementDbName(int port) {
        return "mem:" + MANAGEMENT_DB_PREFIX + port;
    }

    private void initManagementDb() throws SQLException {
        Properties prop = new Properties();
        prop.setProperty("user", "");
        prop.setProperty("password", managementPassword);
        // avoid using the driver manager
        Connection conn = Driver.load().connect("jdbc:h2:" + getManagementDbName(port), prop);
        managementDb = conn;
        Statement stat = null;
        try {
            stat = conn.createStatement();
            stat.execute("CREATE ALIAS IF NOT EXISTS STOP_SERVER FOR \"" + TcpServer.class.getName() + ".stopServer\"");
            stat.execute("CREATE TABLE IF NOT EXISTS SESSIONS(ID INT PRIMARY KEY, URL VARCHAR, USER VARCHAR, CONNECTED TIMESTAMP)");
            managementDbAdd = conn.prepareStatement("INSERT INTO SESSIONS VALUES(?, ?, ?, NOW())");
            managementDbRemove = conn.prepareStatement("DELETE FROM SESSIONS WHERE ID=?");
        } finally {
            JdbcUtils.closeSilently(stat);
        }
        SERVERS.put(port, this);
    }

    /**
     * Shut down this server.
     */
    void shutdown() {
        if (shutdownHandler != null) {
            shutdownHandler.shutdown();
        }
    }

    public void setShutdownHandler(ShutdownHandler shutdownHandler) {
        this.shutdownHandler = shutdownHandler;
    }

    /**
     * Add a connection to the management database.
     *
     * @param id the connection id
     * @param url the database URL
     * @param user the user name
     */
    synchronized void addConnection(int id, String url, String user) {
        try {
            managementDbAdd.setInt(1, id);
            managementDbAdd.setString(2, url);
            managementDbAdd.setString(3, user);
            managementDbAdd.execute();
        } catch (SQLException e) {
            TraceSystem.traceThrowable(e);
        }
    }

    /**
     * Remove a connection from the management database.
     *
     * @param id the connection id
     */
    synchronized void removeConnection(int id) {
        try {
            managementDbRemove.setInt(1, id);
            managementDbRemove.execute();
        } catch (SQLException e) {
            TraceSystem.traceThrowable(e);
        }
    }

    private synchronized void stopManagementDb() {
        if (managementDb != null) {
            try {
                managementDb.close();
            } catch (SQLException e) {
                TraceSystem.traceThrowable(e);
            }
            managementDb = null;
        }
    }

    public void init(String... args) {
        port = Constants.DEFAULT_TCP_PORT;
        for (int i = 0; args != null && i < args.length; i++) {
            String a = args[i];
            if ("-trace".equals(a)) {
                trace = true;
            } else if ("-tcpSSL".equals(a)) {
                ssl = true;
            } else if ("-tcpPort".equals(a)) {
                port = Integer.decode(args[++i]);
            } else if ("-tcpPassword".equals(a)) {
                managementPassword = args[++i];
            } else if ("-baseDir".equals(a)) {
                baseDir = args[++i];
            } else if ("-key".equals(a)) {
                key = args[++i];
                keyDatabase = args[++i];
            } else if ("-tcpAllowOthers".equals(a)) {
                allowOthers = true;
            } else if ("-tcpDaemon".equals(a)) {
                isDaemon = true;
            } else if ("-ifExists".equals(a)) {
                ifExists = true;
            }
        }
        org.h2.Driver.load();
    }

    public String getURL() {
        return (ssl ? "ssl" : "tcp") + "://" + NetUtils.getLocalAddress() + ":" + port;
    }

    public int getPort() {
        return port;
    }

    /**
     * Check if this socket may connect to this server. Remote connections are
     * not allowed if the flag allowOthers is set.
     *
     * @param socket the socket
     * @return true if this client may connect
     */
    boolean allow(Socket socket) {
        if (allowOthers) {
            return true;
        }
        try {
            return NetUtils.isLocalAddress(socket);
        } catch (UnknownHostException e) {
            traceError(e);
            return false;
        }
    }

    public synchronized void start() throws SQLException {
        stop = false;
        serverSocket = NetUtils.createServerSocket(port, ssl);
        port = serverSocket.getLocalPort();
        initManagementDb();
    }

    public void listen() {
        listenerThread = Thread.currentThread();
        String threadName = listenerThread.getName();
        try {
            while (!stop) {
                Socket s = serverSocket.accept();
                TcpServerThread c = new TcpServerThread(s, this, nextThreadId++);
                running.add(c);
                Thread thread = new Thread(c);
                thread.setDaemon(isDaemon);
                thread.setName(threadName + " thread");
                c.setThread(thread);
                thread.start();
            }
            serverSocket = NetUtils.closeSilently(serverSocket);
        } catch (Exception e) {
            if (!stop) {
                TraceSystem.traceThrowable(e);
            }
        }
        stopManagementDb();
    }

    public synchronized boolean isRunning(boolean traceError) {
        if (serverSocket == null) {
            return false;
        }
        try {
            Socket s = NetUtils.createLoopbackSocket(port, ssl);
            s.close();
            return true;
        } catch (Exception e) {
            if (traceError) {
                traceError(e);
            }
            return false;
        }
    }

    public void stop() {
        // TODO server: share code between web and tcp servers
        // need to remove the server first, otherwise the connection is broken
        // while the server is still registered in this map
        SERVERS.remove(port);
        if (!stop) {
            stopManagementDb();
            stop = true;
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    TraceSystem.traceThrowable(e);
                } catch (NullPointerException e) {
                    // ignore
                }
                serverSocket = null;
            }
            if (listenerThread != null) {
                try {
                    listenerThread.join(1000);
                } catch (InterruptedException e) {
                    TraceSystem.traceThrowable(e);
                }
            }
        }
        // TODO server: using a boolean 'now' argument? a timeout?
        for (TcpServerThread c : New.arrayList(running)) {
            if (c != null) {
                c.close();
                try {
                    c.getThread().join(100);
                } catch (Exception e) {
                    TraceSystem.traceThrowable(e);
                }
            }
        }
    }

    /**
     * Stop a running server. This method is called via reflection from the
     * STOP_SERVER function.
     *
     * @param port the port where the server runs, or 0 for all running servers
     * @param password the password (or null)
     * @param shutdownMode the shutdown mode, SHUTDOWN_NORMAL or SHUTDOWN_FORCE.
     */
    public static void stopServer(int port, String password, int shutdownMode) {
        if (port == 0) {
            for (int p : SERVERS.keySet().toArray(new Integer[0])) {
                if (p != 0) {
                    stopServer(p, password, shutdownMode);
                }
            }
            return;
        }
        TcpServer server = SERVERS.get(port);
        if (server == null) {
            return;
        }
        if (!server.managementPassword.equals(password)) {
            return;
        }
        if (shutdownMode == SHUTDOWN_NORMAL) {
            server.stopManagementDb();
            server.stop = true;
            try {
                Socket s = NetUtils.createLoopbackSocket(port, false);
                s.close();
            } catch (Exception e) {
                // try to connect - so that accept returns
            }
        } else if (shutdownMode == SHUTDOWN_FORCE) {
            server.stop();
        }
        server.shutdown();
    }

    /**
     * Remove a thread from the list.
     *
     * @param t the thread to remove
     */
    void remove(TcpServerThread t) {
        running.remove(t);
    }

    /**
     * Get the configured base directory.
     *
     * @return the base directory
     */
    String getBaseDir() {
        return baseDir;
    }

    /**
     * Print a message if the trace flag is enabled.
     *
     * @param s the message
     */
    void trace(String s) {
        if (trace) {
            System.out.println(s);
        }
    }
    /**
     * Print a stack trace if the trace flag is enabled.
     *
     * @param e the exception
     */
    void traceError(Throwable e) {
        if (trace) {
            e.printStackTrace();
        }
    }

    public boolean getAllowOthers() {
        return allowOthers;
    }

    public String getType() {
        return "TCP";
    }

    public String getName() {
        return "H2 TCP Server";
    }

    boolean getIfExists() {
        return ifExists;
    }

    /**
     * Stop the TCP server with the given URL.
     *
     * @param url the database URL
     * @param password the password
     * @param force if the server should be stopped immediately
     * @param all whether all TCP servers that are running in the JVM should be
     *            stopped
     */
    public static synchronized void shutdown(String url, String password, boolean force, boolean all) throws SQLException {
        try {
            int port = Constants.DEFAULT_TCP_PORT;
            int idx = url.lastIndexOf(':');
            if (idx >= 0) {
                String p = url.substring(idx + 1);
                if (StringUtils.isNumber(p)) {
                    port = Integer.decode(p);
                }
            }
            String db = getManagementDbName(port);
            try {
                org.h2.Driver.load();
            } catch (Throwable e) {
                throw DbException.convert(e);
            }
            for (int i = 0; i < 2; i++) {
                Connection conn = null;
                PreparedStatement prep = null;
                try {
                    conn = DriverManager.getConnection("jdbc:h2:" + url + "/" + db, "", password);
                    prep = conn.prepareStatement("CALL STOP_SERVER(?, ?, ?)");
                    prep.setInt(1, all ? 0 : port);
                    prep.setString(2, password);
                    prep.setInt(3, force ? SHUTDOWN_FORCE : SHUTDOWN_NORMAL);
                    try {
                        prep.execute();
                    } catch (SQLException e) {
                        if (force) {
                            // ignore
                        } else {
                            if (e.getErrorCode() != ErrorCode.CONNECTION_BROKEN_1) {
                                throw e;
                            }
                        }
                    }
                    break;
                } catch (SQLException e) {
                    if (i == 1) {
                        throw e;
                    }
                } finally {
                    JdbcUtils.closeSilently(prep);
                    JdbcUtils.closeSilently(conn);
                }
            }
        } catch (Exception e) {
            throw DbException.toSQLException(e);
        }
    }

    /**
     * Cancel a running statement.
     *
     * @param sessionId the session id
     * @param statementId the statement id
     */
    void cancelStatement(String sessionId, int statementId) throws SQLException {
        for (TcpServerThread c : New.arrayList(running)) {
            if (c != null) {
                c.cancelStatement(sessionId, statementId);
            }
        }
    }

    /**
     * If no key is set, return the original database name. If a key is set,
     * check if the key matches. If yes, return the correct database name. If
     * not, throw an exception.
     *
     * @param db the key to test (or database name if no key is used)
     * @return the database name
     * @throws SQLException if a key is set but doesn't match
     */
    public String checkKeyAndGetDatabaseName(String db) throws SQLException {
        if (key == null) {
            return db;
        }
        if (key.equals(db)) {
            return keyDatabase;
        }
        throw DbException.get(ErrorCode.WRONG_USER_OR_PASSWORD);
    }

    public boolean isDaemon() {
        return isDaemon;
    }

}
