package com.yudylaw.demo.nio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Set;

/**
 * @author liuyu3@xiaomi.com
 * @since 2014年12月16日
 */

public class ZooClient {

    private static Selector selector = null;
    private static SelectionKey socketKey = null;
    private static ByteBuffer buffer = ByteBuffer.allocate(1 * 1024);
    private final static int TIMEOUT = 1000;
    private static SocketChannel channel = null;
    
    private final static Logger logger = LoggerFactory.getLogger(ZooClient.class);
    
    public static void main(String[] args) throws IOException {
        selector = Selector.open();
        InetSocketAddress address = new InetSocketAddress("localhost", 7878);
        channel = SocketChannel.open();
        channel.configureBlocking(false);
        channel.socket().setSoLinger(false, -1);
        channel.socket().setTcpNoDelay(true);
        channel.connect(address);//connect not open
        
        socketKey = channel.register(selector, SelectionKey.OP_CONNECT);
        
        while(!channel.socket().isClosed()){
            selector.select(TIMEOUT);
            Set<SelectionKey> keys = selector.selectedKeys();
            for (SelectionKey key : keys) {
                if((key.readyOps() & SelectionKey.OP_CONNECT) > 0){
                    if (channel.isConnectionPending()) {
                        channel.finishConnect();
                    }
                    socketKey.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                    logger.debug("connectted now");
                }else if ((key.readyOps() & SelectionKey.OP_READ) > 0){
                    logger.debug("reading from server");
                    SocketChannel sock = (SocketChannel) key.channel();
                    int c = sock.read(buffer);
                    if(c < 0){
                        //TODO 服务端断开
                        logger.debug("loss connection to server : " + sock.getRemoteAddress());
                        key.cancel();
                        close();
                        return;
                    }
                    StringBuilder sb = new StringBuilder();
                    buffer.flip();//limit=position, position=0,为读做准备
                    byte[] tmp = new byte[c];
                    buffer.get(tmp);//position++ <= limit
                    sb.append(new String(tmp));
                    buffer.clear();//position置为0，并不清除buffer内容
                    logger.debug(sb.toString());
                } else if ((key.readyOps() & SelectionKey.OP_WRITE) > 0){
                    String content = "hello server";
                    logger.info("client write : " + content);
                    channel.write(ByteBuffer.wrap(content.getBytes()));
                    //写完取消,否则会循环写
                    key.interestOps(key.interestOps() & (~SelectionKey.OP_WRITE));
                }
            }
            keys.clear();
        }
    }

    private static void enableWrite() {
        int i = socketKey.interestOps();
        if ((i & SelectionKey.OP_WRITE) == 0) {
            socketKey.interestOps(i | SelectionKey.OP_WRITE);
        }
    }

    private static void disableWrite() {
        int i = socketKey.interestOps();
        if ((i & SelectionKey.OP_WRITE) != 0) {
            socketKey.interestOps(i & (~SelectionKey.OP_WRITE));
        }
    }
    
    public static void close(){
        if (socketKey != null) {
            SocketChannel sock = (SocketChannel) socketKey.channel();
            logger.info("Closed socket connection " + sock.socket().getRemoteSocketAddress());
            socketKey.cancel();
            try {
                sock.socket().shutdownInput();
            } catch (IOException e) {
                logger.debug("Ignoring exception during shutdown input", e);
            }
            try {
                sock.socket().shutdownOutput();
            } catch (IOException e) {
                logger.debug("Ignoring exception during shutdown output", e);
            }
            try {
                sock.socket().close();
            } catch (IOException e) {
                logger.debug("Ignoring exception during socket close", e);
            }
            try {
                sock.close();
            } catch (IOException e) {
                logger.debug("Ignoring exception during channel close", e);
            }
        }
        if(selector != null && selector.isOpen()){
            try {
                selector.close();
            } catch (IOException e) {
                logger.debug("close selector", e);
            }
        }
    }
    
    public void addShutdownHook(){
        Runtime.getRuntime().addShutdownHook(new Thread() {
            
            public void run() {
                close();
            }
        });
    }
    
}
