package com.yudylaw.demo.nio.server;

import com.yudylaw.demo.nio.proto.Zoo.IQType;
import com.yudylaw.demo.nio.proto.Zoo.Packet;
import com.yudylaw.demo.nio.proto.Zoo.Request;
import com.yudylaw.demo.nio.proto.Zoo.Response;
import com.yudylaw.demo.nio.proto.Zoo.ZooError;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
    private final static int TIMEOUT = 5000;
    private static int MAX_CONN;
    private final HashSet<NIOServerCnxn> cnxns = new HashSet<NIOServerCnxn>();
    private ZooDatabase zooDb;
    
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
        //TODO 守护线程的作用
        setDaemon(true);
        zooDb = new ZooDatabase();
        MAX_CONN = maxConn;
        selector = Selector.open();
        serverSocketChannel = ServerSocketChannel.open();
        //只有TCP状态位于TIME_WAIT，才可以重用该端口，仅Linux下可用
        serverSocketChannel.socket().setReuseAddress(true);
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.bind(addr);
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
    }
    
    public void run() {
        while(!serverSocketChannel.socket().isClosed()){
            try{
                selector.select(TIMEOUT);
                Set<SelectionKey> keys;
                //TODO　单线程，不需要同步
                synchronized (this) {
                    keys = selector.selectedKeys();
                }
                for (SelectionKey key : keys) {
                    if((key.readyOps() & SelectionKey.OP_ACCEPT) > 0){
                        ServerSocketChannel server = (ServerSocketChannel) key.channel();
                        SocketChannel sc = server.accept();
                        int conns = getCnxnCount();
                        if(conns >= MAX_CONN){
                            logger.warn("Too many connections - max is " + MAX_CONN );
                            sc.close();
                            break;
                        }
                        logger.info("Accepted socket connection from " + sc.socket().getRemoteSocketAddress());
                        sc.configureBlocking(false);
                        SelectionKey sk = sc.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                        //attach
                        NIOServerCnxn cnxn = new NIOServerCnxn(this, sc, sk);
                        sk.attach(cnxn);
                        addCnxn(cnxn);
                    } else if ((key.readyOps() & (SelectionKey.OP_READ | SelectionKey.OP_WRITE)) > 0){
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
    
    public void removeCnxn(NIOServerCnxn cnxn){
        synchronized (cnxns) {
            cnxns.remove(cnxn);
        }
    }
    
    public void addCnxn(NIOServerCnxn cnxn){
        synchronized (cnxns) {
            cnxns.add(cnxn);
        }
    }
    
    public int getCnxnCount(){
        synchronized (cnxns) {
            return cnxns.size();
        }
    }
    
    public void setWatcher(String path, Watcher watcher){
        zooDb.addWatch(path, watcher);
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

    public void handle(NIOServerCnxn cnxn, Request req) {
        switch(req.getType()){
            case CREATE:
                try {
                    //TODO 缺少会话标识
                    String path = zooDb.create(req.getPath(), req.getData().toByteArray());
                    Response resp = Response.newBuilder().setPath(path).setType(req.getType()).build();
                    Packet packet = Packet.newBuilder().setContent(resp.toByteString()).setType(IQType.RESPONSE).build();
                    cnxn.addPacket(packet);
                } catch (Exception e) {
                    ZooError error = ZooError.newBuilder().setError(e.getMessage()).build();
                    Packet packet = Packet.newBuilder().setContent(error.toByteString()).setType(IQType.ERROR).build();
                    cnxn.addPacket(packet);
                    logger.error("error to create node", e);
                }
                break;
            case GET:
                break;
            case UPDATE:
                break;
            case DELETE:
                break;
            default:
                break;
        }
    }
}
