package com.yudylaw.demo.nio.client;

import com.yudylaw.demo.nio.proto.Zoo.Packet;

/**
 * @author liuyu3@xiaomi.com
 * @since 2014年12月25日
 */

public class RequestPacket {

    private boolean isFinished;
    private Packet packet;
    
    public RequestPacket(boolean isFinished, Packet packet) {
        this.isFinished = isFinished;
        this.packet = packet;
    }

    public boolean isFinished() {
        return isFinished;
    }

    public void setFinished(boolean isFinished) {
        this.isFinished = isFinished;
    }

    public Packet getPacket() {
        return packet;
    }

    public void setPacket(Packet packet) {
        this.packet = packet;
    }
    
}
