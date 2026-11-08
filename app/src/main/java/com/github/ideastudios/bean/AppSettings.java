package com.github.ideastudios.bean;

import com.github.ideastudios.serial.SerialConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用设置（经 Gson 序列化为 JSON 持久化）
 */
public class AppSettings {
    /** 4 个串口页的配置 */
    public SerialConfig[] ports = new SerialConfig[4];
    /** 快捷命令库 */
    public List<QuickCommand> commands = new ArrayList<>();
    /** 显示时间戳 */
    public boolean showTimestamp = true;
    /** 接收/发送 Hex 模式 */
    public boolean hexMode = false;
    /** 自动清屏（达到上限行数时） */
    public boolean autoClear = true;
    /** 发送文件分包大小（字节） */
    public int fileSendChunkSize = 256;
    /** 发送文件分包间隔（毫秒） */
    public int fileSendIntervalMs = 20;

    public AppSettings() {
        for (int i = 0; i < ports.length; i++) {
            SerialConfig config = new SerialConfig();
            config.devicePath = "/dev/ttyS" + i;
            config.usbPortIndex = i;
            ports[i] = config;
        }
        // 预置两条示例命令
        commands.add(new QuickCommand("AT", "AT", false, true));
        commands.add(new QuickCommand("复位", "FF 00 01", true, false));
    }
}
