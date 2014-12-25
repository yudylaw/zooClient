package com.yudylaw.demo.nio.server;

import java.util.HashSet;
import java.util.Set;

/**
 * @author liuyu3@xiaomi.com
 * @since 2014年12月25日
 */

public class DataNode {

    private DataNode parent;
    private byte[] data;
    private Set<String> children;
    
    public DataNode(DataNode parent, byte[] data){
        this.parent = parent;
        this.data = data;
        this.children = new HashSet<String>();
    }
    
    public DataNode() {
    }
    
    public DataNode getParent() {
        return parent;
    }
    public void setParent(DataNode parent) {
        this.parent = parent;
    }
    public byte[] getData() {
        return data;
    }
    public void setData(byte[] data) {
        this.data = data;
    }
    public Set<String> getChildren() {
        return children;
    }
    public void setChildren(Set<String> children) {
        this.children = children;
    }

    public void addChild(String childName) {
        children.add(childName);
    }
    
}
