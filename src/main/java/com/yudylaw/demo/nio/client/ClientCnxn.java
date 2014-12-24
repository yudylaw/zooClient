package com.yudylaw.demo.nio.client;

import com.google.protobuf.InvalidProtocolBufferException;
import com.yudylaw.demo.nio.proto.Zoo.IQType;
import com.yudylaw.demo.nio.proto.Zoo.Packet;
import com.yudylaw.demo.nio.proto.Zoo.WatcherEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author liuyu3@xiaomi.com
 * @since 2014年12月24日
 */

public class ClientCnxn {
    
    private final static Logger logger = LoggerFactory.getLogger(ClientCnxn.class);
    
    private volatile boolean connected = false;
    
    /**
     * 内部类可以很好地共享私有变量
     * 减少变量的交叉持有，传递，特别是遇到EVENT/IO之类
     */
    private EventThread eventThread;
    private SendThread sendThread;
    
    
    public ClientCnxn(SocketAddress addr) throws IOException {
        this.eventThread = new EventThread();
        this.sendThread = new SendThread(addr);
    }
    
    public void start() throws InterruptedException{
        this.eventThread.start();
        this.sendThread.start();
        //TODO
        Thread.currentThread().join();
    }
    
    final static UncaughtExceptionHandler uncaughtExceptionHandler = new UncaughtExceptionHandler() {
        public void uncaughtException(Thread t, Throwable e) {
            logger.error("from " + t.getName(), e);
        }
    };
    
    class EventThread extends Thread {
        
        private final LinkedBlockingQueue<Object> waitingEvents =
                new LinkedBlockingQueue<Object>();

        public EventThread() {
            super("EventThread");
            setUncaughtExceptionHandler(uncaughtExceptionHandler);
            setDaemon(true);
        }
        
        public void queueEvent(WatcherEvent event) {
            if(connected && event != null){
                waitingEvents.add(event);
            }
        }
        
        @Override
        public void run() {
            while(connected){
                try {
                    WatcherEvent event = (WatcherEvent) waitingEvents.take();
                    processEvent(event);
                } catch (InterruptedException e) {
                    logger.error("error is ", e);
                }
            }
        }
        
        private void processEvent(Object event) {
            if(event instanceof WatcherEvent){
                logger.debug("process event {}", event);
                //watcher.process(pair.event);
            }
        }
    }
    
    class SendThread extends Thread {
        
        private Selector selector = null;
        private SelectionKey socketKey = null;
        private ByteBuffer buffer = ByteBuffer.allocate(1 * 1024);
        private final int TIMEOUT = 1000;
        private SocketChannel channel = null;
        private SocketAddress localSocketAddr = null;
        private final LinkedList<Packet> outgoingQueue = new LinkedList<Packet>();
        private final int PING_TIME = 30000;//30s
        
        public SendThread(SocketAddress addr) throws IOException{
            setUncaughtExceptionHandler(uncaughtExceptionHandler);
            setDaemon(true);
            selector = Selector.open();
            channel = SocketChannel.open();
            channel.configureBlocking(false);
            //禁用close逗留,默认如此
            channel.socket().setSoLinger(false, -1);
            //禁用Negle算法，及时发送数据
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
                            connected = true;
                            localSocketAddr = channel.getLocalAddress();
                            socketKey.interestOps(SelectionKey.OP_READ);
                            logger.debug("connectted to server, client is {}", localSocketAddr);
                        } else if ((key.readyOps() & SelectionKey.OP_READ) > 0) {
                            logger.debug("reading from server, client is {}", localSocketAddr);
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
            logger.debug("{} write send a packet to server len is {}", new Object[]{localSocketAddr, len});
            ByteBuffer buf = ByteBuffer.allocate(len + 4);
            buf.putInt(len);//head is length, 4 bytes
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
            readResponse(packet);
            buffer.clear();//position置为0，并不清除buffer内容
            logger.debug("{} received packet is {}", new Object[]{localSocketAddr, packet});
        }
        
        public void readResponse(Packet packet) throws InvalidProtocolBufferException{
            switch (packet.getType()) {
                case PING:
                    break;
                case EVENT:
                    WatcherEvent event = WatcherEvent.parseFrom(packet.getContent());
                    eventThread.queueEvent(event);
                    break;
                case WATCHES:
                    break;
                default:
                    logger.warn("illeage packet type");
                    break;
            }
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
            connected = false;
            if (socketKey != null) {
                socketKey.cancel();
            }
            SocketChannel sock = (SocketChannel) socketKey.channel();
            logger.info("Closed socket connection " + sock.socket().getRemoteSocketAddress());
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
            if(selector != null && selector.isOpen()){
                try {
                    selector.close();
                } catch (IOException e) {
                    logger.debug("close selector", e);
                }
            }
        }
    }

    public void close() {
        sendThread.close();
    }
    
}
