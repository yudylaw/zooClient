package com.yudylaw.demo.nio.client;

import com.google.protobuf.ByteString;
import com.yudylaw.demo.nio.proto.Zoo.IQType;
import com.yudylaw.demo.nio.proto.Zoo.OpType;
import com.yudylaw.demo.nio.proto.Zoo.Packet;
import com.yudylaw.demo.nio.proto.Zoo.Request;
import com.yudylaw.demo.nio.server.Watcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * @author liuyu3@xiaomi.com
 * @since 2014年12月16日
 */

public class ZooClient {
    private final static Logger logger = LoggerFactory.getLogger(ZooClient.class);
    private ClientCnxn clientCnxn;
    
    public ZooClient(String host, int port, Watcher watcher){
        InetSocketAddress addr = new InetSocketAddress(host, port);
        try {
            addShutdownHook();
            clientCnxn = new ClientCnxn(addr, this, watcher);
            clientCnxn.start();
        } catch (Exception e) {
            logger.error("error to start client thread", e);
        }
    }
    
    public void create(String path, byte[] data) throws InterruptedException{
        ByteString bytes = ByteString.copyFrom(data);
        Request req = Request.newBuilder().setPath(path).setData(bytes).setType(OpType.CREATE).build();
        Packet packet = Packet.newBuilder().setContent(req.toByteString()).setType(IQType.REQUEST).build();
        clientCnxn.addPacket(packet);
        //Packet 合并
        RequestPacket rp = new RequestPacket(false, packet);
        //TODO　未发送成功直接放入pending ????
        clientCnxn.addPendingRequest(rp);
        //lock
        synchronized (rp) {
            while(!rp.isFinished()){
                rp.wait();
            }
        }
    }
    
    public void addShutdownHook(){
        Runtime.getRuntime().addShutdownHook(new Thread() {
            
            public void run() {
                clientCnxn.close();
            }
        });
    }
    
}
