package com.github.ideastudios.serialport;

import androidx.annotation.NonNull;

import com.github.ideastudios.serial.SerialConfig;
import com.github.ideastudios.serial.SerialConnection;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 板载串口连接（native JNI，走 /dev/ttyS*，通常需要 root 权限）
 */
public class NativeSerialConnection implements SerialConnection {

    private final SerialConfig config;
    private SerialPort serialPort;
    private InputStream inputStream;
    private OutputStream outputStream;

    public NativeSerialConnection(@NonNull SerialConfig config) {
        this.config = config;
    }

    @Override
    public void open() throws IOException {
        serialPort = new SerialPort(new File(config.devicePath), config.baudRate,
                config.dataBits, config.stopBits, config.parity, config.flowControl, 0);
        inputStream = serialPort.getInputStream();
        outputStream = serialPort.getOutputStream();
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        int size = inputStream.read(buffer);
        if (size < 0) {
            // EOF：设备被系统或其他进程关闭
            throw new IOException("串口流已结束");
        }
        return size;
    }

    @Override
    public void write(byte[] data) throws IOException {
        outputStream.write(data);
        outputStream.flush();
    }

    @Override
    public void close() {
        if (serialPort != null) {
            serialPort.close();
            serialPort = null;
        }
        inputStream = null;
        outputStream = null;
    }
}
