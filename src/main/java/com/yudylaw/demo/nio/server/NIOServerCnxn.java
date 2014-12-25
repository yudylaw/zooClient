package com.yudylaw.demo.nio.server;

import com.google.protobuf.InvalidProtocolBufferException;
import com.yudylaw.demo.nio.proto.Zoo.EventType;
import com.yudylaw.demo.nio.proto.Zoo.IQType;
import com.yudylaw.demo.nio.proto.Zoo.Packet;
import com.yudylaw.demo.nio.proto.Zoo.Request;
import com.yudylaw.demo.nio.proto.Zoo.SetWatches;
import com.yudylaw.demo.nio.proto.Zoo.WatcherEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;

/**
 * @author liuyu3@xiaomi.com
 * @since 2014年12月18日
 */

public class NIOServerCnxn implements Watcher {
    
    private SocketChannel sock;
    private SelectionKey sk;
    private final LinkedList<Packet> outgoingQueue = new LinkedList<Packet>();
    private static ByteBuffer lenBuffer = ByteBuffer.allocate(4);
    private static ByteBuffer incomingBuffer = lenBuffer;
    private ServerThread server;
    
    private final static Logger logger = LoggerFactory.getLogger(NIOServerCnxn.class);
    
    public NIOServerCnxn(ServerThread server, SocketChannel sock, SelectionKey sk) throws SocketException {
        this.server = server;
        this.sock = sock;
        this.sk = sk;
        this.sock.socket().setTcpNoDelay(true);
        //拖延
        this.sock.socket().setSoLinger(true, 2);
        //重用
        this.sock.socket().setReuseAddress(true);
        //TODO mock event
//        mockEvent();
    }
    
    public void doIO(SelectionKey k) throws InterruptedException {
        try {
            if(sock == null){
                logger.debug("sock is null");
                return;
            }
            if (k.isReadable()) {
                logger.debug("client {} is readable", sock.getRemoteAddress());
                int c = sock.read(incomingBuffer);
                if(c < 0){
                    //客户端断开时会收到READ事件, 避免循环读
                    logger.debug("loss sock with " + sock.getRemoteAddress());
                    k.cancel();
                    close();
                    return;
                }
                //TODO
                if (incomingBuffer.remaining() == 0) {
                	boolean isPayload = false;
                	if(incomingBuffer == lenBuffer){
                		isPayload = readLength();
                	}else{
                		isPayload = true;
                	}
                	if(isPayload){
                		readPayload(k);
                	}
                }
            } else if (k.isWritable()){
                logger.debug("client {} is writable", sock.getRemoteAddress());
                if(outgoingQueue.size() > 0){
                    Packet packet = outgoingQueue.poll();
                    if(packet != null){
                        write(packet);
                    }
                }
            }
            if (outgoingQueue.size() > 0) {
                enableWrite();
            } else {
                disableWrite();
            }
        } catch (IOException e) {
            logger.debug("io exception", e);
            close();
        }
    }
    
    /** Read the request payload (everything following the length prefix) */
    private void readPayload(SelectionKey k) throws IOException, InterruptedException {
    	//TODO why not while?
        if (incomingBuffer.remaining() != 0) {
            int rc = sock.read(incomingBuffer);
            if (rc < 0) {
            	logger.debug("loss sock with " + sock.getRemoteAddress());
                k.cancel();
                close();
                return;
            }
        }

        if (incomingBuffer.remaining() == 0) {
            incomingBuffer.flip();
            //TODO
            Packet packet = Packet.parseFrom(incomingBuffer.array());
            readRequest(packet);
            incomingBuffer.clear();//position置为0，并不清除buffer内容
            logger.debug("received a packet from {}, packet is {}", new Object[]{sock.getRemoteAddress(), packet});
            lenBuffer.clear();
            //重置
            incomingBuffer = lenBuffer;
        }
    }
    
    private void readRequest(Packet packet) throws InvalidProtocolBufferException{
        switch (packet.getType()) {
            case PING:
                break;
            case EVENT:
                break;
            case WATCHES:
                SetWatches setWatches = SetWatches.parseFrom(packet.toByteArray());
                server.setWatcher(setWatches.getPath(), this);
                break;
            case REQUEST:
                Request req = Request.parseFrom(packet.getContent());
                server.handle(this, req);
                break;
            default:
                logger.warn("illeage packet type");
                break;
        }
    }
    
    private boolean readLength() throws IOException {
		incomingBuffer.flip();
		int len = incomingBuffer.getInt();
		if(len < 0 || len > Integer.MAX_VALUE){
			throw new IOException("Len error " + len);
		}
		logger.info("read a packet len is {}", len);
		incomingBuffer = ByteBuffer.allocate(len);
		incomingBuffer.clear();
		return true;
    }
    
    @Deprecated
    public void read(int len) throws InvalidProtocolBufferException{
        if(len < 1){
            logger.debug("read {} size packet", len);
            return;
        }
        incomingBuffer.flip();//limit=position, position=0,为读做准备
        byte[] tmp = new byte[len];
        incomingBuffer.get(tmp);//position++ <= limit
        Packet packet = Packet.parseFrom(tmp);                
        incomingBuffer.clear();//position置为0，并不清除buffer内容
        logger.debug("received packet is {}", packet);
    }
    
    /**
     * TODO buffer复用
     * @param packet
     * @throws IOException
     */
    private void write(Packet packet) throws IOException{
        ByteBuffer buf = ByteBuffer.allocate(1024);
        buf.clear();
        buf.put(packet.toByteArray());
        buf.flip();
        //一次性无法保证写完，需要一直写，直到写结束
        while(buf.hasRemaining()) {
            sock.write(buf);
        }
    }
    
    private void enableWrite() {
        int i = sk.interestOps();
        if ((i & SelectionKey.OP_WRITE) == 0) {
            sk.interestOps(i | SelectionKey.OP_WRITE);
        }
    }

    private void disableWrite() {
        int i = sk.interestOps();
        if ((i & SelectionKey.OP_WRITE) != 0) {
            sk.interestOps(i & (~SelectionKey.OP_WRITE));
        }
    }
    
    public void addPacket(Packet packet){
        outgoingQueue.add(packet);
    }
    
    /**
     * TODO
     */
    private void mockEvent(){
        WatcherEvent event = WatcherEvent.newBuilder().setType(EventType.NodeCreated).setPath("/root/test").build();
        Packet packet = Packet.newBuilder().setType(IQType.EVENT).setContent(event.toByteString()).build();
        outgoingQueue.add(packet);
    }
    
    private void sendPing(){
        Packet packet = Packet.newBuilder().setType(IQType.PING).build();
        try {
            outgoingQueue.add(packet);
            logger.debug("response a ping to client {}", sock.getRemoteAddress());
        } catch (IOException e) {
            logger.debug("error while send ping", e);
        }
    }
    
    public void close(){
        closeSock();
        server.removeCnxn(this);
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

    public void process(WatcherEvent event) {
        Packet packet = Packet.newBuilder().setContent(event.toByteString())
                .setType(IQType.EVENT).build();
        outgoingQueue.add(packet);
    }
    
}
