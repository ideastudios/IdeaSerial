package com.github.ideastudios.store;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.github.ideastudios.R;
import com.github.ideastudios.bean.AppSettings;
import com.github.ideastudios.bean.QuickCommand;
import com.google.gson.Gson;

import java.util.ArrayList;

/**
 * 设置持久化：Gson JSON 存入 SharedPreferences，替代旧的 Java 序列化 + Base64 方案
 */
public class SettingsStore {

    private static final String PREFS_NAME = "serial_settings";
    private static final String KEY_SETTINGS_JSON = "settings_json";

    private final Gson gson = new Gson();
    private final SharedPreferences prefs;
    private final Context appContext;

    public SettingsStore(@NonNull Context context) {
        appContext = context.getApplicationContext();
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 读取设置；无存档或解析失败时返回默认设置 */
    @NonNull
    public AppSettings load() {
        String json = prefs.getString(KEY_SETTINGS_JSON, null);
        if (json != null) {
            try {
                AppSettings settings = gson.fromJson(json, AppSettings.class);
                if (settings != null) {
                    // 防御旧存档缺字段
                    if (settings.ports == null || settings.ports.length != 4) {
                        settings.ports = new AppSettings().ports;
                    }
                    if (settings.commands == null) {
                        settings.commands = new ArrayList<>();
                    }
                    return settings;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return createDefault();
    }

    /** 全新默认设置：预置命令名按当前应用语言本地化 */
    @NonNull
    private AppSettings createDefault() {
        AppSettings settings = new AppSettings();
        settings.commands.add(new QuickCommand("AT", "AT", false, true));
        settings.commands.add(new QuickCommand(
                appContext.getString(R.string.preset_reset), "FF 00 01", true, false));
        return settings;
    }

    public void save(@NonNull AppSettings settings) {
        prefs.edit().putString(KEY_SETTINGS_JSON, gson.toJson(settings)).apply();
    }
}
