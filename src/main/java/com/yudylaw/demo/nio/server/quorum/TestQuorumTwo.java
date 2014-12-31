package com.yudylaw.demo.nio.server.quorum;

import java.io.IOException;

/**
 * @author liuyu3@xiaomi.com
 * @since 2014年12月30日
 */

public class TestQuorumTwo {

    public static void main(String[] args) {
        long myid = 2;
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
