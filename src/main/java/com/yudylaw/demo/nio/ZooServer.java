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
    private final static Logger logger = LoggerFactory.getLogger(ZooServer.class);
    private static ByteBuffer buffer = ByteBuffer.allocate(1 * 1024);
    
    public static void main(String[] args) throws IOException {
        
        selector = Selector.open();
        
        ServerSocketChannel channel = ServerSocketChannel.open();
        channel.bind(new InetSocketAddress("localhost", 7878));
        channel.configureBlocking(false);
        channel.register(selector, SelectionKey.OP_ACCEPT);//READ
        
        while(true){
            selector.select();
            Set<SelectionKey> keys = selector.selectedKeys();
            for (SelectionKey key : keys) {
                if((key.readyOps() & SelectionKey.OP_ACCEPT) > 0){
                    logger.debug("accept connection");
                    ServerSocketChannel server = (ServerSocketChannel) key.channel();
                    // 获得和客户端连接的通道
                    SocketChannel sc = server.accept();
                    // 设置成非阻塞
                    sc.configureBlocking(false);
                    // 在这里可以发送消息给客户端
                    sc.write(ByteBuffer.wrap(new String("hello client").getBytes()));
                    // 在客户端 连接成功之后，为了可以接收到客户端的信息，需要给通道设置读的权限
                    sc.register(ZooServer.selector, SelectionKey.OP_READ);
                } else if ((key.readyOps() & SelectionKey.OP_READ) > 0){
                    logger.debug("reading from client");
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

}
