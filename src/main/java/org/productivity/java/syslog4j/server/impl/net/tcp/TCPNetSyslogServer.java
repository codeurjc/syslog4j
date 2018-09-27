package org.productivity.java.syslog4j.server.impl.net.tcp;

import static java.lang.invoke.MethodHandles.lookup;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Iterator;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ServerSocketFactory;

import org.productivity.java.syslog4j.SyslogConstants;
import org.productivity.java.syslog4j.SyslogRuntimeException;
import org.productivity.java.syslog4j.server.SyslogServerEventIF;
import org.productivity.java.syslog4j.server.SyslogServerIF;
import org.productivity.java.syslog4j.server.impl.AbstractSyslogServer;
import org.productivity.java.syslog4j.util.SyslogUtility;
import org.slf4j.Logger;

/**
 * TCPNetSyslogServer provides a simple threaded TCP/IP server implementation.
 * 
 * <p>
 * Syslog4j is licensed under the Lesser GNU Public License v2.1. A copy of the
 * LGPL license is available in the META-INF folder in all distributions of
 * Syslog4j and in the base directory of the "doc" ZIP.
 * </p>
 * 
 * @author &lt;syslog4j@productivity.org&gt;
 * @version $Id: TCPNetSyslogServer.java,v 1.23 2010/11/28 22:07:57 cvs Exp $
 */
public class TCPNetSyslogServer extends AbstractSyslogServer {

    public static class TCPNetSyslogSocketHandler implements Runnable {
        public final Logger logger = getLogger(lookup().lookupClass());
        protected SyslogServerIF server = null;
        protected Socket socket = null;
        protected Sessions sessions = null;

        public TCPNetSyslogSocketHandler(Sessions sessions,
                SyslogServerIF server, Socket socket) {
            this.sessions = sessions;
            this.server = server;
            this.socket = socket;

            synchronized (this.sessions) {
                this.sessions.addSocket(socket);
            }
        }

        public void run() {
            boolean timeout = false;

            try {
                Scanner scanner = new Scanner(this.socket.getInputStream());

                // Syslog rfc 5424 pattern
                // <30>1 2018-09-27T08:47:12.822535+02:00 HOSTNAME APP_NAME
                // PROCID MSGID - MESSAGE
                String regex = "([<]\\d*[>].*[\\s]\\d\\d\\d\\d-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d.\\d*[+]\\d\\d:\\d\\d[\\s].*[\\s].*[\\s]\\d*[\\s].*[\\s]-[\\s].*)";
                Pattern pattern = Pattern.compile(regex);
                String line = scanner.nextLine();
                if (line != null) {
                    AbstractSyslogServer.handleSessionOpen(this.sessions,
                            this.server, this.socket);
                }

                String currentCompleteLine = line;

                while (line != null && line.length() != 0) {
                    // Load next
                    String nextLine = null;
                    if (scanner.hasNextLine()) {
                        nextLine = scanner.nextLine();

                        Matcher matcher = pattern.matcher(nextLine);
                        boolean areMatches = matcher.matches();
                        // If are not matches, is the same trace
                        while (!areMatches) {
                            currentCompleteLine += "\\r" + nextLine;
                            if (!scanner.hasNextLine()) {
                                break;
                            }
                            nextLine = scanner.nextLine();

                            matcher = pattern.matcher(nextLine);
                            areMatches = matcher.matches();
                        }
                    }

                    SyslogServerEventIF event = createEvent(
                            this.server.getConfig(), currentCompleteLine,
                            this.socket.getInetAddress());

                    AbstractSyslogServer.handleEvent(this.sessions, this.server,
                            this.socket, event);

                    line = nextLine;
                    currentCompleteLine = line;
                }

                scanner.close();

            } catch (SocketTimeoutException ste) {
                timeout = true;

            } catch (SocketException se) {
                AbstractSyslogServer.handleException(this.sessions, this.server,
                        this.socket.getRemoteSocketAddress(), se);

                if ("Socket closed".equals(se.getMessage())) {
                    //

                } else {
                    //
                }

            } catch (IOException ioe) {
                AbstractSyslogServer.handleException(this.sessions, this.server,
                        this.socket.getRemoteSocketAddress(), ioe);
            }

            try {
                AbstractSyslogServer.handleSessionClosed(this.sessions,
                        this.server, this.socket, timeout);

                this.sessions.removeSocket(this.socket);

                this.socket.close();

            } catch (IOException ioe) {
                AbstractSyslogServer.handleException(this.sessions, this.server,
                        this.socket.getRemoteSocketAddress(), ioe);
            }
        }
    }

    protected ServerSocket serverSocket = null;

    protected final Sessions sessions = new Sessions();

    protected TCPNetSyslogServerConfigIF tcpNetSyslogServerConfig = null;

    public void initialize() throws SyslogRuntimeException {
        this.tcpNetSyslogServerConfig = null;

        try {
            this.tcpNetSyslogServerConfig = (TCPNetSyslogServerConfigIF) this.syslogServerConfig;

        } catch (ClassCastException cce) {
            throw new SyslogRuntimeException(
                    "config must be of type TCPNetSyslogServerConfig");
        }

        if (this.syslogServerConfig == null) {
            throw new SyslogRuntimeException("config cannot be null");
        }

        if (this.tcpNetSyslogServerConfig.getBacklog() < 1) {
            this.tcpNetSyslogServerConfig
                    .setBacklog(SyslogConstants.SERVER_SOCKET_BACKLOG_DEFAULT);
        }
    }

    public Sessions getSessions() {
        return this.sessions;
    }

    public synchronized void shutdown() {
        super.shutdown();

        try {
            if (this.serverSocket != null) {
                if (this.syslogServerConfig.getShutdownWait() > 0) {
                    SyslogUtility
                            .sleep(this.syslogServerConfig.getShutdownWait());
                }

                this.serverSocket.close();
            }

            synchronized (this.sessions) {
                Iterator i = this.sessions.getSockets();

                if (i != null) {
                    while (i.hasNext()) {
                        Socket s = (Socket) i.next();

                        s.close();
                    }
                }
            }

        } catch (IOException ioe) {
            //
        }

        if (this.thread != null) {
            this.thread.interrupt();
            this.thread = null;
        }
    }

    protected ServerSocketFactory getServerSocketFactory() throws IOException {
        ServerSocketFactory serverSocketFactory = ServerSocketFactory
                .getDefault();

        return serverSocketFactory;
    }

    protected ServerSocket createServerSocket() throws IOException {
        ServerSocket newServerSocket = null;

        ServerSocketFactory factory = getServerSocketFactory();

        if (this.syslogServerConfig.getHost() != null) {
            InetAddress inetAddress = InetAddress
                    .getByName(this.syslogServerConfig.getHost());

            newServerSocket = factory.createServerSocket(
                    this.syslogServerConfig.getPort(),
                    this.tcpNetSyslogServerConfig.getBacklog(), inetAddress);

        } else {
            if (this.tcpNetSyslogServerConfig.getBacklog() < 1) {
                newServerSocket = factory
                        .createServerSocket(this.syslogServerConfig.getPort());

            } else {
                newServerSocket = factory.createServerSocket(
                        this.syslogServerConfig.getPort(),
                        this.tcpNetSyslogServerConfig.getBacklog());
            }
        }

        return newServerSocket;
    }

    public void run() {
        try {
            this.serverSocket = createServerSocket();
            this.shutdown = false;

        } catch (SocketException se) {
            throw new SyslogRuntimeException(se);

        } catch (IOException ioe) {
            throw new SyslogRuntimeException(ioe);
        }

        handleInitialize(this);

        while (!this.shutdown) {
            try {
                Socket socket = this.serverSocket.accept();

                if (this.tcpNetSyslogServerConfig.getTimeout() > 0) {
                    socket.setSoTimeout(
                            this.tcpNetSyslogServerConfig.getTimeout());
                }

                if (this.tcpNetSyslogServerConfig.getMaxActiveSockets() > 0
                        && this.sessions.size() >= this.tcpNetSyslogServerConfig
                                .getMaxActiveSockets()) {
                    if (this.tcpNetSyslogServerConfig
                            .getMaxActiveSocketsBehavior() == TCPNetSyslogServerConfigIF.MAX_ACTIVE_SOCKETS_BEHAVIOR_REJECT) {
                        try {
                            socket.close();

                        } catch (Exception e) {
                            //
                        }

                        socket = null;

                    } else if (this.tcpNetSyslogServerConfig
                            .getMaxActiveSocketsBehavior() == TCPNetSyslogServerConfigIF.MAX_ACTIVE_SOCKETS_BEHAVIOR_BLOCK) {
                        while (!this.shutdown
                                && this.sessions
                                        .size() >= this.tcpNetSyslogServerConfig
                                                .getMaxActiveSockets()
                                && socket.isConnected() && !socket.isClosed()) {
                            SyslogUtility.sleep(
                                    SyslogConstants.THREAD_LOOP_INTERVAL_DEFAULT);
                        }
                    }
                }

                if (socket != null) {
                    TCPNetSyslogSocketHandler handler = new TCPNetSyslogSocketHandler(
                            this.sessions, this, socket);

                    Thread t = new Thread(handler);

                    t.start();
                }

            } catch (SocketException se) {
                if ("Socket closed".equals(se.getMessage())) {
                    this.shutdown = true;

                } else {
                    //
                }

            } catch (IOException ioe) {
                //
            }
        }

        handleDestroy(this);
    }
}
