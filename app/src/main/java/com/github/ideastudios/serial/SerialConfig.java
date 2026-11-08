package com.github.ideastudios.serial;

import java.util.Locale;

/**
 * 串口配置（驱动类型、设备、波特率、数据位、停止位、校验位、流控）
 */
public class SerialConfig {

    /** 板载串口（/dev/ttyS* 等，通常需要 root 权限） */
    public static final int DRIVER_NATIVE = 0;
    /** USB OTG 串口（CH340/CP210x/FTDI/PL2303/CDC，免 root） */
    public static final int DRIVER_USB = 1;

    /* 校验位常量，与 usb-serial-for-android 及 native 层保持一致 */
    public static final int PARITY_NONE = 0;
    public static final int PARITY_ODD = 1;
    public static final int PARITY_EVEN = 2;
    public static final int PARITY_MARK = 3;
    public static final int PARITY_SPACE = 4;

    /* 流控常量 */
    public static final int FLOW_NONE = 0;
    public static final int FLOW_RTSCTS = 1;
    public static final int FLOW_XONXOFF = 2;

    public int driverType = DRIVER_NATIVE;
    /** 板载设备路径（如 /dev/ttyS1）；USB 模式下无意义 */
    public String devicePath = "/dev/ttyS0";
    /** USB 设备标识（仅 USB 模式有效） */
    public int usbVid = -1;
    public int usbPid = -1;
    public int usbPortIndex = 0;
    /** USB 设备显示名（如 "CH340"） */
    public String usbDeviceName = "";

    public int baudRate = 115200;
    public int dataBits = 8;
    public int stopBits = 1;
    public int parity = PARITY_NONE;
    public int flowControl = FLOW_NONE;
    /** 自动发送间隔（毫秒） */
    public int autoSendIntervalMs = 500;

    /** 人类可读的参数摘要，如 "115200 8N1" */
    public String describe() {
        char p;
        switch (parity) {
            case PARITY_ODD: p = 'O'; break;
            case PARITY_EVEN: p = 'E'; break;
            case PARITY_MARK: p = 'M'; break;
            case PARITY_SPACE: p = 'S'; break;
            default: p = 'N'; break;
        }
        String flow = flowControl == FLOW_RTSCTS ? " RTS/CTS"
                : flowControl == FLOW_XONXOFF ? " XON/XOFF" : "";
        return String.format(Locale.US, "%d %d%s%d%s", baudRate, dataBits, p, stopBits, flow);
    }
}
