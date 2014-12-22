package com.yudylaw.demo.nio.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * @author liuyu3@xiaomi.com
 * @since 2014年12月19日
 */

public class ZooServer {
    
    private final static Logger logger = LoggerFactory.getLogger(ZooServer.class);
    
    private static ServerThread server;
    
    public static void main(String[] args) {
        addShutdownHook();
        SocketAddress addr = new InetSocketAddress("localhost", 7878);
        try {
            server = new ServerThread(addr, 2);
            server.start();
            server.join();
        } catch (Exception e) {
            logger.error("start server thread fail", e);
        }
    }
    
    public static void addShutdownHook(){
        Runtime.getRuntime().addShutdownHook(new Thread() {
            
            public void run() {
                server.shutdown();
            }
        });
    }
}
