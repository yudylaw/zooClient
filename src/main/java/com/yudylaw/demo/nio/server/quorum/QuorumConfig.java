package com.yudylaw.demo.nio.server.quorum;

import com.yudylaw.demo.nio.server.quorum.QuorumPeer.QuorumServer;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;


/**
 * @author liuyu3@xiaomi.com
 * @since 2014年12月30日
 * mock data
 */

public class QuorumConfig {

    public final static Map<Long, QuorumServer> quorumPeers = new HashMap<Long, QuorumServer>();
    
    static{
        QuorumServer s1 = new QuorumServer(1, new InetSocketAddress("10.237.107.19", 12211), new InetSocketAddress("10.237.107.19", 12221));
        QuorumServer s2 = new QuorumServer(2, new InetSocketAddress("10.237.107.19", 12212), new InetSocketAddress("10.237.107.19", 12222));
        QuorumServer s3 = new QuorumServer(3, new InetSocketAddress("10.237.107.19", 12213), new InetSocketAddress("10.237.107.19", 12223));
        quorumPeers.put(s1.id, s1);
        quorumPeers.put(s2.id, s2);
        quorumPeers.put(s3.id, s3);
    }
    public long myid;
    public int tickTime = 2000;
    public int initLimit = 10;
    public int syncLimit = 5;
    public boolean quorumListenOnAllIPs = true;
    
    //mock data
    public long localZxid;
    public long localEpoch;
    
    public QuorumConfig(long myid, long localZxid, long localEpoch) {
        this.myid = myid;
        this.localZxid = localZxid;
        this.localEpoch = localEpoch;
    }
    
}
