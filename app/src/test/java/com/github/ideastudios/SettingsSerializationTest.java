package com.github.ideastudios;

import com.github.ideastudios.bean.AppSettings;
import com.github.ideastudios.bean.QuickCommand;
import com.github.ideastudios.serial.SerialConfig;
import com.github.ideastudios.util.MyFunc;
import com.google.gson.Gson;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 设置 JSON 序列化与 Hex 工具的纯 JVM 单元测试
 */
public class SettingsSerializationTest {

    @Test
    public void appSettings_roundTrip_keepsAllFields() {
        Gson gson = new Gson();
        AppSettings settings = new AppSettings();
        settings.hexMode = true;
        settings.showTimestamp = false;
        settings.commands.add(new QuickCommand("点亮", "01 02 03", true, false));
        settings.ports[1].driverType = SerialConfig.DRIVER_USB;
        settings.ports[1].usbVid = 0x1A86;
        settings.ports[1].usbPid = 0x7523;
        settings.ports[1].usbPortIndex = 2;
        settings.ports[1].baudRate = 921600;
        settings.ports[1].parity = SerialConfig.PARITY_EVEN;
        settings.ports[1].stopBits = 2;
        settings.ports[1].flowControl = SerialConfig.FLOW_RTSCTS;
        settings.ports[1].autoSendIntervalMs = 100;

        String json = gson.toJson(settings);
        AppSettings restored = gson.fromJson(json, AppSettings.class);

        assertTrue(restored.hexMode);
        assertFalse(restored.showTimestamp);
        assertEquals(3, restored.commands.size());
        assertEquals("点亮", restored.commands.get(2).name);
        assertTrue(restored.commands.get(2).isHex);
        assertEquals(SerialConfig.DRIVER_USB, restored.ports[1].driverType);
        assertEquals(0x1A86, restored.ports[1].usbVid);
        assertEquals(0x7523, restored.ports[1].usbPid);
        assertEquals(2, restored.ports[1].usbPortIndex);
        assertEquals(921600, restored.ports[1].baudRate);
        assertEquals(SerialConfig.PARITY_EVEN, restored.ports[1].parity);
        assertEquals(2, restored.ports[1].stopBits);
        assertEquals(SerialConfig.FLOW_RTSCTS, restored.ports[1].flowControl);
        assertEquals(100, restored.ports[1].autoSendIntervalMs);
        assertEquals(4, restored.ports.length);
    }

    @Test
    public void serialConfig_describe_formatsParams() {
        SerialConfig config = new SerialConfig();
        assertEquals("115200 8N1", config.describe());
        config.parity = SerialConfig.PARITY_EVEN;
        config.stopBits = 2;
        config.flowControl = SerialConfig.FLOW_RTSCTS;
        assertEquals("115200 8E2 RTS/CTS", config.describe());
    }

    @Test
    public void hexToByteArr_acceptsSpacesAndOddLength() {
        assertArrayEquals(new byte[]{(byte) 0xFF, 0x00, 0x11}, MyFunc.HexToByteArr("FF 00 11"));
        assertArrayEquals(new byte[]{(byte) 0xFF, 0x00, 0x11}, MyFunc.HexToByteArr("FF0011"));
        assertArrayEquals(new byte[]{0x0F}, MyFunc.HexToByteArr("F"));
        assertEquals(0, MyFunc.HexToByteArr(null).length);
    }
}
