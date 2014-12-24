package com.yudylaw.demo.nio.server;

import com.yudylaw.demo.nio.proto.Zoo.WatcherEvent;

/**
 * @author liuyu3@xiaomi.com
 * @since 2014年12月23日
 */

public interface Watcher {
    
    abstract public void process(WatcherEvent event);
    
}
