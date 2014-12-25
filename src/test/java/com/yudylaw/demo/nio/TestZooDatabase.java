package com.yudylaw.demo.nio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.yudylaw.demo.nio.proto.Zoo.WatcherEvent;
import com.yudylaw.demo.nio.server.DataNode;
import com.yudylaw.demo.nio.server.Watcher;
import com.yudylaw.demo.nio.server.ZooDatabase;

import org.junit.Before;
import org.junit.Test;

/**
 * @author liuyu3@xiaomi.com
 * @since 2014年12月25日
 */

public class TestZooDatabase {
    
    private ZooDatabase zooDb = new ZooDatabase();
    
    @Before
    public void init(){
        zooDb.addWatch("/node", new Watcher() {
            
            public void process(WatcherEvent event) {
                System.out.println("process [" + event + "]");
            }
        });
    }
    
    @Test
    public void create(){
        byte[] data = new byte[1];
        try {
            String path = zooDb.create("/node", data);
            assertEquals("/node", path);
        } catch (Exception e) {
            e.printStackTrace();
            fail();
        }
    }
    
    @Test
    public void get(){
        create();
        DataNode node = zooDb.get("/node");
        assertTrue(node != null);
        
        node = zooDb.get("/node2");
        assertTrue(node == null);
    }
    
    @Test
    public void delete(){
       create();
       try {
           //TODO 一次触发
           zooDb.delete("/node");
        } catch (Exception e) {
            e.printStackTrace();
            fail();
        }
    }
    
    @Test
    public void update(){
        zooDb.update("/node", new byte[2]);
    }
    
}
