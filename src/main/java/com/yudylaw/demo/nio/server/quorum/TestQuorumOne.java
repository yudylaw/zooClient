package com.yudylaw.demo.nio.server.quorum;

import com.yudylaw.demo.nio.server.quorum.QuorumPeer.QuorumServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * @author liuyu3@xiaomi.com
 * @since 2014年12月30日
 */

public class TestQuorumOne {

    public static void main(String[] args) {
        long myid = 1;
        long localZxid = 101;
        long localEpoch = 12;
        try {
            QuorumConfig config = new QuorumConfig(myid, localZxid, localEpoch);
            QuorumPeer peer = new QuorumPeer(config);
            peer.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
