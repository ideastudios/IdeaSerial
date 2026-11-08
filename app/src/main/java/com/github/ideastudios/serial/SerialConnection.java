package com.github.ideastudios.serial;

import java.io.IOException;

/**
 * 串口连接抽象：屏蔽板载 native 串口与 USB OTG 串口的差异
 */
public interface SerialConnection {

    /** 打开连接并完成参数配置 */
    void open() throws IOException;

    /**
     * 读取数据。
     *
     * @return 读取的字节数；USB 模式超时无数据时返回 0；串口关闭/EOF 抛 IOException
     */
    int read(byte[] buffer) throws IOException;

    /** 写数据，出错抛 IOException */
    void write(byte[] data) throws IOException;

    /** 关闭连接，幂等 */
    void close();
}
