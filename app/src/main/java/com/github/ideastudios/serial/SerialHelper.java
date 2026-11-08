package com.github.ideastudios.serial;

import android.content.Context;

import androidx.annotation.NonNull;

import com.github.ideastudios.bean.ComBean;
import com.github.ideastudios.serialport.NativeSerialConnection;
import com.github.ideastudios.serialport.UsbSerialConnection;
import com.github.ideastudios.util.MyFunc;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 串口辅助工具类：面向 {@link SerialConnection} 抽象，兼容板载串口与 USB OTG 串口。
 * 读线程按驱动特性读取（native 阻塞流 / USB 超时轮询），无固定休眠，高波特率不丢帧。
 *
 * @author benjaminwan, ideastudios
 */
public abstract class SerialHelper {

    private static final int READ_BUFFER_SIZE = 4096;

    private final Context appContext;
    private SerialConfig config = new SerialConfig();
    private SerialConnection connection;
    private ReadThread mReadThread;
    private SendThread mSendThread;
    private volatile boolean _isOpen = false;
    private byte[] _bLoopData = new byte[]{0x30};
    private int iDelay = 500;
    private final AtomicLong rxCount = new AtomicLong();
    private final AtomicLong txCount = new AtomicLong();
    /** 写锁：自动发送线程、手动发送、文件发送可能并发写同一端口 */
    private final Object writeLock = new Object();

    //----------------------------------------------------
    public SerialHelper(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    public SerialHelper(@NonNull Context context, @NonNull SerialConfig config) {
        this(context);
        this.config = config;
    }

    //----------------------------------------------------
    public synchronized void open() throws SecurityException, IOException {
        if (_isOpen) {
            return;
        }
        connection = createConnection(config);
        try {
            connection.open();
        } catch (IOException | RuntimeException e) {
            // 打开失败时清理半初始化的连接（如 USB 的 UsbDeviceConnection），避免泄漏
            connection.close();
            connection = null;
            throw e;
        }
        _isOpen = true;
        mReadThread = new ReadThread();
        mReadThread.start();
        mSendThread = new SendThread();
        mSendThread.setSuspendFlag();
        mSendThread.start();
    }

    private SerialConnection createConnection(@NonNull SerialConfig config) throws IOException {
        if (config.driverType == SerialConfig.DRIVER_USB) {
            return new UsbSerialConnection(appContext, config);
        }
        return new NativeSerialConnection(config);
    }

    //----------------------------------------------------
    public synchronized void close() {
        _isOpen = false;
        if (mReadThread != null) {
            mReadThread.interrupt();
        }
        if (mSendThread != null) {
            mSendThread.interrupt();
        }
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    //----------------------------------------------------
    public void send(byte[] bOutArray) {
        if (!_isOpen || connection == null || bOutArray == null || bOutArray.length == 0) {
            return;
        }
        synchronized (writeLock) {
            try {
                connection.write(bOutArray);
                txCount.addAndGet(bOutArray.length);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    //----------------------------------------------------
    public void sendHex(String sHex) {
        byte[] bOutArray = MyFunc.HexToByteArr(sHex);
        send(bOutArray);
    }

    //----------------------------------------------------
    public void sendTxt(String sTxt) {
        byte[] bOutArray = sTxt.getBytes();
        send(bOutArray);
    }

    //----------------------------------------------------
    private class ReadThread extends Thread {
        @Override
        public void run() {
            byte[] buffer = new byte[READ_BUFFER_SIZE];
            while (!isInterrupted() && _isOpen && connection != null) {
                try {
                    // native 阻塞读：无数据时挂起，天然背压；
                    // USB 超时轮询：无数据返回 0，100ms 一次，不忙等也不丢帧
                    int size = connection.read(buffer);
                    if (size > 0) {
                        rxCount.addAndGet(size);
                        byte[] data = new byte[size];
                        System.arraycopy(buffer, 0, data, 0, size);
                        onDataReceived(new ComBean(config.devicePath, data, size,
                                ComBean.DIR_RX, System.currentTimeMillis()));
                    }
                } catch (Throwable e) {
                    if (_isOpen) {
                        e.printStackTrace();
                    }
                    return;
                }
            }
        }
    }

    //----------------------------------------------------
    private class SendThread extends Thread {
        private volatile boolean suspendFlag = true;// 控制线程的执行

        @Override
        public void run() {
            while (!isInterrupted()) {
                synchronized (this) {
                    while (suspendFlag && !isInterrupted()) {
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
                if (isInterrupted()) {
                    return;
                }
                send(getbLoopData());
                try {
                    Thread.sleep(iDelay);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        //线程暂停
        public void setSuspendFlag() {
            this.suspendFlag = true;
        }

        //唤醒线程
        public synchronized void setResume() {
            this.suspendFlag = false;
            notify();
        }
    }

    //----------------------------------------------------
    public boolean isOpen() {
        return _isOpen;
    }

    //----------------------------------------------------
    public SerialConfig getConfig() {
        return config;
    }

    /** 关闭状态下才能更换配置 */
    public boolean setConfig(@NonNull SerialConfig config) {
        if (_isOpen) {
            return false;
        }
        this.config = config;
        return true;
    }

    //----------------------------------------------------
    public long getRxCount() {
        return rxCount.get();
    }

    public long getTxCount() {
        return txCount.get();
    }

    //----------------------------------------------------
    public byte[] getbLoopData() {
        return _bLoopData;
    }

    //----------------------------------------------------
    public void setbLoopData(byte[] bLoopData) {
        this._bLoopData = bLoopData;
    }

    //----------------------------------------------------
    public void setTxtLoopData(String sTxt) {
        this._bLoopData = sTxt.getBytes();
    }

    //----------------------------------------------------
    public void setHexLoopData(String sHex) {
        this._bLoopData = MyFunc.HexToByteArr(sHex);
    }

    //----------------------------------------------------
    public int getiDelay() {
        return iDelay;
    }

    //----------------------------------------------------
    public void setiDelay(int iDelay) {
        this.iDelay = iDelay;
    }

    //----------------------------------------------------
    public void startSend() {
        if (mSendThread != null) {
            mSendThread.setResume();
        }
    }

    //----------------------------------------------------
    public void stopSend() {
        if (mSendThread != null) {
            mSendThread.setSuspendFlag();
        }
    }

    //----------------------------------------------------
    protected abstract void onDataReceived(ComBean ComRecData);
}
