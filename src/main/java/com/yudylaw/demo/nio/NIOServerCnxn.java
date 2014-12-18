package com.yudylaw.demo.nio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

/**
 * @author liuyu3@xiaomi.com
 * @since 2014年12月18日
 */

public class NIOServerCnxn {
    
    private SocketChannel sock;
    private SelectionKey sk;
    private static ByteBuffer buffer = ByteBuffer.allocate(1 * 1024);
    
    private final static Logger logger = LoggerFactory.getLogger(NIOServerCnxn.class);
    
    public NIOServerCnxn(SocketChannel sock, SelectionKey sk) throws SocketException {
        this.sock = sock;
        this.sk = sk;
        //TODO
        this.sock.socket().setTcpNoDelay(true);
        this.sock.socket().setSoLinger(true, 2);
        this.sk.interestOps(SelectionKey.OP_READ);
    }
    
    public void doIO(SelectionKey k) throws InterruptedException {
        try {
            if(sock == null){
                logger.debug("sock is null");
                return;
            }
            if (k.isReadable()) {
                logger.debug("client is readable");
                int c = sock.read(buffer);
                if(c < 0){
                    logger.debug("no more data to read");
                    return;
                }
                StringBuilder sb = new StringBuilder();
                buffer.flip();//limit=position, position=0,为读做准备
                byte[] tmp = new byte[c];
                buffer.get(tmp);//position++ <= limit
                sb.append(new String(tmp));
                buffer.clear();//position置为0，并不清除buffer内容
                logger.debug(sb.toString());
            } else if (k.isWritable()){
                logger.debug("client is writable");
                sock.write(ByteBuffer.wrap(new String("hello client").getBytes()));
                //TODO 根据需要
                k.interestOps(k.interestOps() & (~SelectionKey.OP_WRITE));
            }
        } catch (IOException e) {
            logger.debug("io exception", e);
        }
    }
    
    public void write() throws IOException{
        buffer = ByteBuffer.wrap(new String("hello client").getBytes());
        while(buffer.hasRemaining()){
            sock.write(buffer);
        }
    }
    
    public void close(){
        
        closeSock();
        
        if (sk != null) {
            try {
                // need to cancel this selection key from the selector
                sk.cancel();
            } catch (Exception e) {
                logger.debug("ignoring exception during selectionkey cancel", e);
            }
        }
        
    }
    
    public void closeSock (){

        if (sock == null) {
            return;
        }
        logger.info("Closed socket connection for client " + sock.socket().getRemoteSocketAddress());
        try {
            /*
             * The following sequence of code is stupid! You would think that
             * only sock.close() is needed, but alas, it doesn't work that way.
             * If you just do sock.close() there are cases where the socket
             * doesn't actually close...
             */
            sock.socket().shutdownOutput();
        } catch (IOException e) {
            // This is a relatively common exception that we can't avoid
            logger.debug("ignoring exception during output shutdown", e);
        }
        try {
            sock.socket().shutdownInput();
        } catch (IOException e) {
            // This is a relatively common exception that we can't avoid
            logger.debug("ignoring exception during input shutdown", e);
        }
        try {
            sock.socket().close();
        } catch (IOException e) {
            logger.debug("ignoring exception during socket close", e);
        }
        try {
            sock.close();
            // XXX The next line doesn't seem to be needed, but some posts
            // to forums suggest that it is needed. Keep in mind if errors in
            // this section arise.
            // factory.selector.wakeup();
        } catch (IOException e) {
            logger.debug("ignoring exception during socketchannel close", e);
        }
        sock = null;
    
    }
}
