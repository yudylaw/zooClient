package com.yudylaw.demo.nio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashSet;
import java.util.Set;

/**
 * @author liuyu3@xiaomi.com
 * @since 2014年12月16日
 */

public class ServerThread extends Thread {

    private static Selector selector = null;
    private static ServerSocketChannel serverSocketChannel = null;
    private final static int TIMEOUT = 1000;
    private static int MAX_CONN;
    private static volatile int conns = 0;
    private final HashSet<NIOServerCnxn> cnxns = new HashSet<NIOServerCnxn>();
    
    private final static Logger logger = LoggerFactory.getLogger(ServerThread.class);
    
    static {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                logger.error("Thread " + t + " died", e);
            }
        });
        /**
         * this is to avoid the jvm bug:
         * NullPointerException in Selector.open()
         * http://bugs.sun.com/view_bug.do?bug_id=6427854
         */
        try {
            Selector.open().close();
        } catch(IOException ie) {
            logger.error("Selector failed to open", ie);
        }
    }
    
    public ServerThread(SocketAddress addr, int maxConn) throws IOException{
        super("ServerThread:" + addr);
        setDaemon(true);
        MAX_CONN = maxConn;
        selector = Selector.open();
        serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.bind(addr);
        serverSocketChannel.socket().setReuseAddress(true);
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
    }
    
    public void run() {
        while(!serverSocketChannel.socket().isClosed()){
            try{
                selector.select(TIMEOUT);
                Set<SelectionKey> keys = selector.selectedKeys();
                for (SelectionKey key : keys) {
                    if((key.readyOps() & SelectionKey.OP_ACCEPT) > 0){
                        ServerSocketChannel server = (ServerSocketChannel) key.channel();
                        SocketChannel sc = server.accept();
                        InetAddress ia = sc.socket().getInetAddress();
                        synchronized (ServerThread.class) {
                            conns++;
                        }
                        if(conns > MAX_CONN){
                            logger.warn("Too many connections from " + ia + " - max is " + MAX_CONN );
                            sc.close();
                            break;
                        }
                        logger.info("Accepted socket connection from " + sc.socket().getRemoteSocketAddress());
                        sc.configureBlocking(false);
                        SelectionKey sk = sc.register(selector, SelectionKey.OP_READ);
                        //attach
                        NIOServerCnxn nioCnxn = new NIOServerCnxn(sc, sk);
                        sk.attach(nioCnxn);
                        cnxns.add(nioCnxn);
                    } else if ((key.readyOps() & (SelectionKey.OP_READ | SelectionKey.OP_WRITE)) != 0){
                        NIOServerCnxn nioCnxn = (NIOServerCnxn) key.attachment();
                        nioCnxn.doIO(key);
                    } else {
                        logger.debug("Unexpected ops in keys " + key.readyOps());
                    }
                }
                keys.clear();
            }catch (RuntimeException e) {
                logger.warn("Ignoring unexpected runtime exception", e);
            } catch (Exception e) {
                logger.warn("Ignoring exception", e);
            }
        }
    }

    synchronized public void clear() {
        selector.wakeup();
        HashSet<NIOServerCnxn> cnxns;
        synchronized (this.cnxns) {
            cnxns = (HashSet<NIOServerCnxn>)this.cnxns.clone();
        }
        // got to clear all the connections that we have in the selector
        for (NIOServerCnxn cnxn: cnxns) {
            try {
                // don't hold this.cnxns lock as deadlock may occur
                cnxn.close();
            } catch (Exception e) {
                logger.warn("Ignoring exception closing cnxn", e);
            }
        }
    }
    
    public void shutdown() {
        try {
            logger.info("server shutdown now");
            ServerSocket sock = serverSocketChannel.socket();
            if(sock !=null && !sock.isClosed()){
                sock.close();
            }
            clear();
            this.interrupt();
            //TODO 作用
            this.join();
        } catch (InterruptedException e) {
            logger.warn("Ignoring interrupted exception during shutdown", e);
        } catch (Exception e) {
            logger.warn("Ignoring unexpected exception during shutdown", e);
        }
        try {
            if(selector != null && selector.isOpen()){
                selector.close();
            }
        } catch (IOException e) {
            logger.warn("Selector closing", e);
        }
    }
}
