package com.yudylaw.demo.nio;

import com.google.protobuf.InvalidProtocolBufferException;
import com.yudylaw.demo.nio.proto.Zoo.IQType;
import com.yudylaw.demo.nio.proto.Zoo.Packet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.Set;

/**
 * @author liuyu3@xiaomi.com
 * @since 2014年12月19日
 */

public class ClientThread extends Thread {
    
    private static Selector selector = null;
    private static SelectionKey socketKey = null;
    private static ByteBuffer buffer = ByteBuffer.allocate(1 * 1024);
    private final static int TIMEOUT = 1000;
    private static SocketChannel channel = null;
    private final static LinkedList<Packet> outgoingQueue = new LinkedList<Packet>();
    private final static Logger logger = LoggerFactory.getLogger(ClientThread.class);
    private final static int PING_TIME = 10000;//30s
    
    public ClientThread(SocketAddress addr) throws IOException{
        selector = Selector.open();
        channel = SocketChannel.open();
        channel.configureBlocking(false);
        channel.socket().setSoLinger(false, -1);
        channel.socket().setTcpNoDelay(true);
        channel.connect(addr);//connect not open
        socketKey = channel.register(selector, SelectionKey.OP_CONNECT);
    }
    
    @Override
    public void run() {
        long now = System.currentTimeMillis();
        long lastPing = now;
        while (!channel.socket().isClosed()) {
            try {
                selector.select(TIMEOUT);
                now = System.currentTimeMillis();
                if(now - lastPing > PING_TIME){
                    lastPing = now;
                    sendPing();
                    enableWrite();
                }
                Set<SelectionKey> keys;
                synchronized (this) {
                    keys = selector.selectedKeys();
                }
                for (SelectionKey key : keys) {
                    if ((key.readyOps() & SelectionKey.OP_CONNECT) > 0) {
                        if (channel.isConnectionPending()) {
                            channel.finishConnect();
                        }
                        socketKey.interestOps(SelectionKey.OP_READ);
                        logger.debug("connectted now");
                    } else if ((key.readyOps() & SelectionKey.OP_READ) > 0) {
                        logger.debug("reading from server");
                        SocketChannel sock = (SocketChannel) key.channel();
                        int c = sock.read(buffer);
                        if (c < 0) {
                            // TODO 服务端断开
                            logger.debug("loss connection to server : " + sock.getRemoteAddress());
                            key.cancel();
                            close();
                            return;
                        }
                        read(c);
                        socketKey.interestOps(SelectionKey.OP_WRITE);
                    } else if ((key.readyOps() & SelectionKey.OP_WRITE) > 0) {
                        logger.debug("server is writable");
                        Packet packet = pollPacket();
                        if (packet != null) {
                            write(packet);
                        }
                        socketKey.interestOps(SelectionKey.OP_READ);
                    }
                }
                
                keys.clear();
                
                if (outgoingQueue.size() > 0) {
                    enableWrite();
                } else {
                    disableWrite();
                }
                
            } catch (Exception e) {
                logger.debug("client thread exception", e);
                close();
            }
        }
    }
    
    
    /**
     * TODO buffer复用
     * @param packet
     * @throws IOException
     */
    private void write(Packet packet) throws IOException{
        int len = packet.toByteArray().length;
        logger.debug("packet len is {}", len);
        ByteBuffer buf = ByteBuffer.allocate(len + 4);
        buf.putInt(len);//head is len, 4 bytes
        buf.flip();
        buf.put(packet.toByteArray());//after head is content
        buf.flip();
        //一次性无法保证写完，需要一直写，直到写结束
        while(buf.hasRemaining()) {
            channel.write(buf);
        }
    }
    
    public void read(int len) throws InvalidProtocolBufferException{
        if(len < 1){
            logger.debug("read {} size packet", len);
            return;
        }
        buffer.flip();//limit=position, position=0,为读做准备
        byte[] tmp = new byte[len];
        buffer.get(tmp);//position++ <= limit
        Packet packet = Packet.parseFrom(tmp);                
        buffer.clear();//position置为0，并不清除buffer内容
        logger.debug("received packet is {}", packet);
    }
    
    private void enableWrite() {
        int i = socketKey.interestOps();
        if ((i & SelectionKey.OP_WRITE) == 0) {
            socketKey.interestOps(i | SelectionKey.OP_WRITE);
        }
    }

    private void disableWrite() {
        int i = socketKey.interestOps();
        if ((i & SelectionKey.OP_WRITE) != 0) {
            socketKey.interestOps(i & (~SelectionKey.OP_WRITE));
        }
    }

    private Packet pollPacket(){
        Packet packet = outgoingQueue.poll();
        return packet;
    }
    
    private void sendPing(){
        Packet packet = Packet.newBuilder().setType(IQType.PING).build();
        outgoingQueue.add(packet);
    }
    
    public void close(){
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
}
