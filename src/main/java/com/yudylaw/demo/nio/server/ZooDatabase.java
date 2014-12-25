package com.yudylaw.demo.nio.server;


/**
 * @author liuyu3@xiaomi.com
 * @since 2014年12月25日
 */

public class ZooDatabase {
    
    protected DataTree dataTree;
    
    public ZooDatabase() {
        this.dataTree = new DataTree();
    }

    /**
     * TODO path 验证应该在外部
     * @param path /xx/yy
     * @param data
     * @return
     * @throws Exception
     */
    public String create(String path, byte[] data) throws Exception {
        return dataTree.createNode(path, data);
    }
    
    public DataNode get(String path){
        return dataTree.getNode(path);
    }
    
    public void delete(String path) throws Exception{
        dataTree.deleteNode(path);
    }
    
    public void update(String path, byte[] data){
        dataTree.setData(path, data);
    }

    public void addWatch(String path, Watcher watcher) {
        dataTree.addWatch(path, watcher);
    }
}
