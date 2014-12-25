package com.yudylaw.demo.nio.server;

import com.yudylaw.demo.nio.proto.Zoo.EventType;
import com.yudylaw.demo.nio.proto.Zoo.WatcherEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * @author liuyu3@xiaomi.com
 * @since 2014年12月25日
 */

public class WatchManager {

    private final static Logger logger = LoggerFactory.getLogger(WatchManager.class);
    
    //key=path
    private final Map<String, HashSet<Watcher>> watchTable =
            new HashMap<String, HashSet<Watcher>>();
    
    private final HashMap<Watcher, HashSet<String>> watch2Paths =
            new HashMap<Watcher, HashSet<String>>();
    
    public synchronized void addWatch(String path, Watcher watcher) {
        HashSet<Watcher> list = watchTable.get(path);
        if (list == null) {
            // don't waste memory if there are few watches on a node
            // rehash when the 4th entry is added, doubling size thereafter
            // seems like a good compromise
            list = new HashSet<Watcher>(4);
            watchTable.put(path, list);
        }
        list.add(watcher);

        HashSet<String> paths = watch2Paths.get(watcher);
        if (paths == null) {
            // cnxns typically have many watches, so use default cap here
            paths = new HashSet<String>();
            watch2Paths.put(watcher, paths);
        }
        paths.add(path);
    }

    public synchronized void removeWatcher(Watcher watcher) {
        HashSet<String> paths = watch2Paths.remove(watcher);
        if (paths == null) {
            return;
        }
        for (String p : paths) {
            HashSet<Watcher> list = watchTable.get(p);
            if (list != null) {
                list.remove(watcher);
                if (list.size() == 0) {
                    watchTable.remove(p);
                }
            }
        }
    }

    public Set<Watcher> triggerWatch(String path, EventType type) {
        return triggerWatch(path, type, null);
    }

    /**
     * TODO 移除一次触发机制
     * @param path
     * @param type
     * @param supress
     * @return
     */
    public Set<Watcher> triggerWatch(String path, EventType type, Set<Watcher> supress) {
        WatcherEvent e = WatcherEvent.newBuilder().setPath(path).setType(type).build();
        HashSet<Watcher> watchers;
        synchronized (this) {
            //TODO 移除一次触发机制 watchTable.remove(path);
            watchers = watchTable.get(path);
            if (watchers == null || watchers.isEmpty()) {
                logger.debug("No watchers for " + path);
                return null;
            }
//            for (Watcher w : watchers) {
//                HashSet<String> paths = watch2Paths.get(w);
//                if (paths != null) {
//                    paths.remove(path);
//                }
//            }
        }
        for (Watcher w : watchers) {
            if (supress != null && supress.contains(w)) {
                continue;
            }
            w.process(e);
        }
        return watchers;
    }
    
}
