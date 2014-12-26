package com.yudylaw.demo.nio.client;

import com.google.protobuf.InvalidProtocolBufferException;
import com.yudylaw.demo.nio.proto.Zoo.IQType;
import com.yudylaw.demo.nio.proto.Zoo.Packet;
import com.yudylaw.demo.nio.proto.Zoo.WatcherEvent;
import com.yudylaw.demo.nio.server.Watcher;

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
    private ZooClient zooClient;
    private Watcher watcher;
    private volatile boolean connected = false;
    
    /**
     * 内部类可以很好地共享私有变量
     * 减少变量的交叉持有，传递，特别是遇到EVENT/IO之类
     */
    private EventThread eventThread;
    private SendThread sendThread;
    
    public ClientCnxn(SocketAddress addr, ZooClient zooClient, Watcher watcher) throws IOException {
        this.zooClient = zooClient;
        this.watcher = watcher;
        this.eventThread = new EventThread();
        this.sendThread = new SendThread(addr);
    }
    
    public void start() throws InterruptedException{
        this.eventThread.start();
        this.sendThread.start();
    }
    
    final static UncaughtExceptionHandler uncaughtExceptionHandler = new UncaughtExceptionHandler() {
        public void uncaughtException(Thread t, Throwable e) {
            logger.error("from " + t.getName(), e);
        }
    };
    
    public void addPacket(Packet packet){
        sendThread.addPacket(packet);
    }
    
    public void addPendingRequest(RequestPacket rp){
        sendThread.addPendingRequest(rp);
    }
    
    class EventThread extends Thread {
        private volatile boolean running = true;
        private final LinkedBlockingQueue<Object> waitingEvents =
                new LinkedBlockingQueue<Object>();

        public EventThread() {
            setName("EventThread");
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
            while(running){
                try {
                    WatcherEvent event = (WatcherEvent) waitingEvents.take();
                    processEvent(event);
                } catch (InterruptedException e) {
                    logger.error("error is ", e);
                }
            }
        }
        
        public void close(){
            running = false;
        }
        
        private void processEvent(Object event) {
            if(event instanceof WatcherEvent){
                watcher.process((WatcherEvent)event);
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
        /**
         * These are the packets that need to be sent.
         */
        private final LinkedList<Packet> outgoingQueue = new LinkedList<Packet>();    
        /**
         * These are the packets that have been sent and are waiting for a response.
         */
        private final LinkedList<RequestPacket> pendingQueue = new LinkedList<RequestPacket>();
        private final int PING_TIME = 30000;//30s
        
        public SendThread(SocketAddress addr) throws IOException{
            setUncaughtExceptionHandler(uncaughtExceptionHandler);
            setName("SendThread");
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
                        } else if ((key.readyOps() & SelectionKey.OP_WRITE) > 0) {
                            logger.debug("server is writable");
                            Packet packet = pollPacket();
                            if (packet != null) {
                                write(packet);
                            }
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
                case SET_WATCHES:
                    break;
                case RESPONSE:
//                    Response resp = Response.parseFrom(packet.getContent());
                    RequestPacket rp = null;
                    synchronized (pendingQueue) {
                        rp = pendingQueue.remove();
                    }
                    if(rp != null){
                        //lock
                        synchronized(rp){
                            rp.setFinished(true);
                            logger.debug("notify client {}", rp);
                            rp.notifyAll();
                        }
                    }
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

        private void addPacket(Packet packet){
            outgoingQueue.add(packet);
        }
        
        private void addPendingRequest(RequestPacket rp){
            synchronized(pendingQueue){
                pendingQueue.add(rp);
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
        eventThread.close();
    }
    
}
