package com.yudylaw.demo.nio.server;

import com.yudylaw.demo.nio.proto.Zoo.EventType;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author liuyu3@xiaomi.com
 * @since 2014年12月25日
 */

public class DataTree {

    /**
     * This hashtable provides a fast lookup to the datanodes. The tree is the
     * source of truth and is where all the locking occurs
     */
    private final ConcurrentHashMap<String, DataNode> nodes =
        new ConcurrentHashMap<String, DataNode>();
    
    private final WatchManager watchManager = new WatchManager();
    
    private static final String rootPath = "/";
    
    public DataTree() {
        DataNode rootNode = new DataNode(null, new byte[0]);
        nodes.put("", rootNode);
    }
    
    public void addDataNode(String path, DataNode node) {
        nodes.put(path, node);
    }

    public DataNode getNode(String path) {
        return nodes.get(path);
    }
    
    public void setData(String path, byte[] data){
        DataNode node = nodes.get(path);
        if(node != null){
            synchronized(node){
                node.setData(data);
            }
            watchManager.triggerWatch(path, EventType.NodeDataChanged);
        }
    }
    
    public void deleteNode(String path) throws Exception{
        int lastSlash = path.lastIndexOf('/');
        String parentName = path.substring(0, lastSlash);
        String childName = path.substring(lastSlash + 1);
        DataNode parent = nodes.get(parentName);
        if (parent == null) {
            throw new Exception("parent node not found");
        }
        synchronized (parent) {
            Set<String> children = parent.getChildren();
            if (children == null || !children.contains(childName)) {
                throw new Exception(path + " node exists");
            }
            nodes.remove(path);
        }
        watchManager.triggerWatch(path, EventType.NodeDeleted);
    }
    
    public String createNode(String path, byte data[]) throws Exception{
        int lastSlash = path.lastIndexOf('/');
        String parentName = path.substring(0, lastSlash);
        String childName = path.substring(lastSlash + 1);
        DataNode parent = nodes.get(parentName);
        if (parent == null) {
            throw new Exception("parent node not found");
        }
        synchronized (parent) {
            Set<String> children = parent.getChildren();
            if (children != null) {
                if (children.contains(childName)) {
                    throw new Exception("child node exists");
                }
            }
            DataNode child = new DataNode(parent, data);
            parent.addChild(childName);
            nodes.put(path, child);
        }
        watchManager.triggerWatch(path, EventType.NodeCreated);
        return path;
    }

    public void addWatch(String path, Watcher watcher) {
        watchManager.addWatch(path, watcher);
    }
}
