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
    
    private final static Logger logger = LoggerFactory.getLogger(ZooClient.class);
    
    public static void main(String[] args) throws IOException {
        selector = Selector.open();
        InetSocketAddress address = new InetSocketAddress("localhost", 7878);
        SocketChannel channel = SocketChannel.open();
        channel.configureBlocking(false);
        channel.connect(address);//connect not open
        
        socketKey = channel.register(selector, SelectionKey.OP_CONNECT);
        
        while(true){
            selector.select();
            Set<SelectionKey> keys = selector.selectedKeys();
            for (SelectionKey key : keys) {
                if((key.readyOps() & SelectionKey.OP_CONNECT) > 0){
                    socketKey.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                    logger.debug("connectted now, ready to read or write.");
                    if (channel.isConnectionPending()) {
                        channel.finishConnect();
                    }
                    String content = "hello server";
                    channel.write(ByteBuffer.wrap(content.getBytes()));
                }else if ((key.readyOps() & SelectionKey.OP_READ) > 0){
                    logger.debug("reading from server");
                    SocketChannel ch = (SocketChannel) key.channel();
                    int len;
                    StringBuilder sb = new StringBuilder();
                    while((len = ch.read(buffer)) > 0){
                        buffer.flip();//limit=position, position=0,为读做准备
                        byte[] tmp = new byte[len];
                        buffer.get(tmp);//position++ <= limit
                        sb.append(new String(tmp));
                        buffer.clear();//position置为0，并不清除buffer内容
                    }
                    logger.debug(sb.toString());
                }
            }
            keys.clear();
        }
    }

    public final void close(){
        if (socketKey != null) {
            SocketChannel sock = (SocketChannel) socketKey.channel();
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
            selector.wakeup();
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
