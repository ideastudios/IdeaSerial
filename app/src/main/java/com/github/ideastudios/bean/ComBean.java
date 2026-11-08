package com.github.ideastudios.bean;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 串口数据帧（一条日志）
 *
 * @author benjaminwan, ideastudios
 */
public class ComBean {
    /** 接收方向 */
    public static final int DIR_RX = 0;
    /** 发送方向 */
    public static final int DIR_TX = 1;

    public int direction = DIR_RX;
    public byte[] bRec = new byte[0];
    public long timeMillis;
    public String sComPort = "";

    public ComBean(String sPort, byte[] buffer, int size, int direction, long timeMillis) {
        this.sComPort = sPort;
        this.bRec = new byte[size];
        System.arraycopy(buffer, 0, this.bRec, 0, size);
        this.direction = direction;
        this.timeMillis = timeMillis;
    }

    /** 毫秒级时间戳文本 */
    public String getFormattedTime() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
                .format(new Date(timeMillis));
    }
}
