/*
 * Copyright 2009 Cedric Priscal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.ideastudios.serialport;

import android.util.Log;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.LineNumberReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 串口设备搜索：优先直接扫描 /dev（覆盖 ttyS/ttyUSB/ttyACM/ttyHS/ttyMT/ttyMSM 等），
 * 扫描失败时回退解析 /proc/tty/drivers。
 */
public class SerialPortFinder {

    private static final String TAG = "SerialPortFinder";

    /** 常见串口设备名模式 */
    private static final Pattern DEVICE_PATTERN =
            Pattern.compile("^(ttyS\\d+|ttyUSB\\d+|ttyACM\\d+|ttyHS\\d+|ttyMT\\d+|ttyMSM\\d+|ttyHSL\\d+|ttyGS\\d+|rfcomm\\d+).*");

    /** 扫描 /dev 下所有串口设备，返回绝对路径列表 */
    public String[] getAllDevicesPath() {
        List<String> devices = new ArrayList<>();
        File dev = new File("/dev");
        File[] files = dev.listFiles();
        if (files != null) {
            for (File file : files) {
                if (DEVICE_PATTERN.matcher(file.getName()).find()) {
                    Log.d(TAG, "Found new device: " + file);
                    devices.add(file.getAbsolutePath());
                }
            }
            if (!devices.isEmpty()) {
                Collections.sort(devices);
                return devices.toArray(new String[0]);
            }
        }

        // 兜底：解析 /proc/tty/drivers 中注册的串口驱动前缀
        return listFromProcDrivers();
    }

    private String[] listFromProcDrivers() {
        List<String> devices = new ArrayList<>();
        try {
            LineNumberReader r = new LineNumberReader(new FileReader("/proc/tty/drivers"));
            String l;
            while ((l = r.readLine()) != null) {
                // 驱动名可能含空格，不能用 split 提取驱动名
                String drivername = l.substring(0, 0x15).trim();
                String[] w = l.split(" +");
                if ((w.length >= 5) && (w[w.length - 1].equals("serial"))) {
                    Log.d(TAG, "Found new driver " + drivername + " on " + w[w.length - 4]);
                    devices.add("/dev/" + w[w.length - 4]);
                }
            }
            r.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        Collections.sort(devices);
        return devices.toArray(new String[0]);
    }
}
