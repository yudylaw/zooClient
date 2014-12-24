package com.yudylaw.demo.nio.client;

import com.yudylaw.demo.nio.server.Watcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * @author liuyu3@xiaomi.com
 * @since 2014年12月16日
 */

public class ZooClient {
    private final static Logger logger = LoggerFactory.getLogger(ZooClient.class);
    private ClientCnxn clientCnxn;
    
    public ZooClient(String host, int port, Watcher watcher){
        InetSocketAddress addr = new InetSocketAddress(host, port);
        try {
            addShutdownHook();
            clientCnxn = new ClientCnxn(addr, this, watcher);
            clientCnxn.start();
        } catch (Exception e) {
            logger.error("error to start client thread", e);
        }
    }
    
    public void addShutdownHook(){
        Runtime.getRuntime().addShutdownHook(new Thread() {
            
            public void run() {
                clientCnxn.close();
            }
        });
    }
    
}
