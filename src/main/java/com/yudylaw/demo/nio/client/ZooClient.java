package com.yudylaw.demo.nio.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * @author liuyu3@xiaomi.com
 * @since 2014年12月16日
 */

public class ZooClient {
    private final static Logger logger = LoggerFactory.getLogger(ZooClient.class);
    private static ClientCnxn clientCnxn;
    
    public static void main(String[] args){
        InetSocketAddress addr = new InetSocketAddress("localhost", 7878);
        try {
            clientCnxn = new ClientCnxn(addr);
            clientCnxn.start();
        } catch (Exception e) {
            logger.error("error to start client thread", e);
        }
    }
    
    public static void addShutdownHook(){
        Runtime.getRuntime().addShutdownHook(new Thread() {
            
            public void run() {
                clientCnxn.close();
            }
        });
    }
    
}
