package com.yudylaw.demo.nio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Set;

/**
 * @author liuyu3@xiaomi.com
 * @since 2014年12月16日
 */

public class ZooServer {

    private static Selector selector = null;
    private static ServerSocketChannel channel = null;
    private final static int TIMEOUT = 1000;
    
    private final static Logger logger = LoggerFactory.getLogger(ZooServer.class);
    
    public static void main(String[] args) throws IOException {
        
        selector = Selector.open();
        channel = ServerSocketChannel.open();
        channel.bind(new InetSocketAddress("localhost", 7878));
        channel.socket().setReuseAddress(true);
        channel.configureBlocking(false);
        channel.register(selector, SelectionKey.OP_ACCEPT);
        
        while(!channel.socket().isClosed()){
            try{
                selector.select(TIMEOUT);
                Set<SelectionKey> keys = selector.selectedKeys();
                for (SelectionKey key : keys) {
                    if((key.readyOps() & SelectionKey.OP_ACCEPT) > 0){
                        ServerSocketChannel server = (ServerSocketChannel) key.channel();
                        SocketChannel sc = server.accept();
                        logger.info("Accepted socket connection from " + sc.socket().getRemoteSocketAddress());
                        sc.configureBlocking(false);
                        SelectionKey sk = sc.register(selector, SelectionKey.OP_READ);
                        //attach
                        NIOServerCnxn nioCnxn = new NIOServerCnxn(sc, sk);
                        sk.attach(nioCnxn);
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
        
        logger.debug("server is going to shutdown");
    }

}
