package com.github.ideastudios.serialport;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.ideastudios.serial.SerialConfig;
import com.github.ideastudios.serial.SerialConnection;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * USB OTG 串口连接（基于 usb-serial-for-android，免 root）
 * 支持 CH340/CH341、CP210x、FTDI、PL2303、CDC/ACM 等芯片
 */
public class UsbSerialConnection implements SerialConnection {

    private static final int READ_TIMEOUT_MS = 100;
    private static final int WRITE_TIMEOUT_MS = 1000;

    private final UsbManager usbManager;
    private final SerialConfig config;
    private final UsbSerialPort port;
    private UsbDeviceConnection deviceConnection;

    /**
     * @throws IOException 未找到匹配的 USB 串口设备
     */
    public UsbSerialConnection(@NonNull Context context, @NonNull SerialConfig config) throws IOException {
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        this.config = config;
        this.port = findPort(usbManager, config);
    }

    private static UsbSerialPort findPort(@NonNull UsbManager usbManager, @NonNull SerialConfig config) throws IOException {
        UsbSerialDriver driver = findDriver(usbManager, config);
        List<UsbSerialPort> ports = driver.getPorts();
        int index = config.usbPortIndex;
        if (index >= ports.size()) {
            index = 0;
        }
        return ports.get(index);
    }

    private static UsbSerialDriver findDriver(@NonNull UsbManager usbManager, @NonNull SerialConfig config) throws IOException {
        UsbSerialProber prober = UsbSerialProber.getDefaultProber();
        for (UsbSerialDriver driver : prober.findAllDrivers(usbManager)) {
            UsbDevice device = driver.getDevice();
            if (device.getVendorId() == config.usbVid && device.getProductId() == config.usbPid) {
                return driver;
            }
        }
        throw new IOException("未找到 USB 串口设备 (VID=" + config.usbVid + " PID=" + config.usbPid + ")");
    }

    @Override
    public void open() throws IOException {
        deviceConnection = usbManager.openDevice(port.getDevice());
        if (deviceConnection == null) {
            throw new IOException("无法打开 USB 设备：请先授予 USB 权限");
        }
        port.open(deviceConnection);
        int stopBits = config.stopBits == 2 ? UsbSerialPort.STOPBITS_2 : UsbSerialPort.STOPBITS_1;
        port.setParameters(config.baudRate, config.dataBits, stopBits, config.parity);
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        return port.read(buffer, READ_TIMEOUT_MS);
    }

    @Override
    public void write(byte[] data) throws IOException {
        port.write(data, WRITE_TIMEOUT_MS);
    }

    @Override
    public void close() {
        try {
            port.close();
        } catch (IOException ignored) {
        }
        if (deviceConnection != null) {
            deviceConnection.close();
            deviceConnection = null;
        }
    }

    //----------------------------------------------------
    /** USB 串口描述信息，供 UI 枚举选择 */
    public static class UsbPortInfo {
        public final UsbDevice device;
        public final UsbSerialPort port;
        public final int portIndex;
        public final String displayName;
        public final boolean hasPermission;

        UsbPortInfo(UsbDevice device, UsbSerialPort port, int portIndex, boolean hasPermission) {
            this.device = device;
            this.port = port;
            this.portIndex = portIndex;
            this.hasPermission = hasPermission;
            String productName = device.getProductName();
            this.displayName = String.format(Locale.US, "%s VID=%04X PID=%04X [%d]",
                    productName != null ? productName : "USB", device.getVendorId(),
                    device.getProductId(), portIndex);
        }
    }

    /** 列出当前插着的所有 USB 串口 */
    public static List<UsbPortInfo> listAvailablePorts(@NonNull Context context) {
        List<UsbPortInfo> result = new ArrayList<>();
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            return result;
        }
        UsbSerialProber prober = UsbSerialProber.getDefaultProber();
        for (UsbSerialDriver driver : prober.findAllDrivers(usbManager)) {
            UsbDevice device = driver.getDevice();
            List<UsbSerialPort> ports = driver.getPorts();
            for (int i = 0; i < ports.size(); i++) {
                result.add(new UsbPortInfo(device, ports.get(i), i,
                        usbManager.hasPermission(device)));
            }
        }
        return result;
    }

    /** 判断某 USB 设备当前是否已授权 */
    public static boolean hasPermission(@NonNull Context context, @Nullable UsbDevice device) {
        if (device == null) {
            return false;
        }
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        return usbManager != null && usbManager.hasPermission(device);
    }
}
