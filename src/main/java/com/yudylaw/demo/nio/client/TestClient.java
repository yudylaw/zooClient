package com.yudylaw.demo.nio.client;

import com.yudylaw.demo.nio.proto.Zoo.WatcherEvent;
import com.yudylaw.demo.nio.server.Watcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author liuyu3@xiaomi.com
 * @since 2014年12月24日
 * 测试类
 */

public class TestClient {

    private final static Logger logger = LoggerFactory.getLogger(TestClient.class);
    
    public static void main(String[] args) {
        
        ZooClient client = new ZooClient("localhost", 7878, new Watcher() {
            public void process(WatcherEvent event) {
                logger.debug("process event {}", event);
            }
        });
        
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

}
