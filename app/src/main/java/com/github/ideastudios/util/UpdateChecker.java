package com.github.ideastudios.util;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 应用内更新检查：拉取 release 流水线维护的 feed 分支 latest.json（version/url/sha256），
 * 与当前 versionName 比对，发现新版本后由调用方打开浏览器下载 APK。
 */
public final class UpdateChecker {

    /** feed 分支上的更新描述文件（release.yml 发版时自动更新） */
    private static final String FEED_URL =
            "https://raw.githubusercontent.com/ideastudios/IdeaSerial/feed/latest.json";

    /** latest.json 原始结构 */
    private static class Feed {
        @Nullable String version;
        @Nullable String url;
        @Nullable String sha256;
    }

    /** 解析出的新版本信息 */
    public static class ReleaseInfo {
        /** 形如 v0.2.1 的 tag */
        @NonNull public final String version;
        /** APK 下载直链 */
        @NonNull public final String url;

        ReleaseInfo(@NonNull String version, @NonNull String url) {
            this.version = version;
            this.url = url;
        }
    }

    /** 回调均在主线程执行 */
    public interface Listener {
        /** 发现比当前 versionName 更新的版本 */
        void onNewVersion(@NonNull ReleaseInfo release);

        /** 已是最新版本 */
        void onUpToDate();

        /** 网络错误/解析失败等 */
        void onError(@NonNull String message);
    }

    private UpdateChecker() {
    }

    /** 后台线程拉取并比对，结果通过主线程 Handler 回调（Activity 销毁后由调用方自行判空） */
    public static void check(@NonNull Context context, @NonNull Listener listener) {
        final Context appContext = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                Feed feed = fetchFeed();
                if (feed == null || feed.version == null || feed.url == null) {
                    main.post(() -> listener.onError("invalid feed"));
                    return;
                }
                String current = currentVersion(appContext);
                if (isNewer(feed.version, current)) {
                    main.post(() -> listener.onNewVersion(
                            new ReleaseInfo(feed.version, feed.url)));
                } else {
                    main.post(listener::onUpToDate);
                }
            } catch (Exception e) {
                main.post(() -> listener.onError(e.getMessage() != null
                        ? e.getMessage() : e.getClass().getSimpleName()));
            }
        }, "update-check").start();
    }

    /** 拉取 latest.json 并用 Gson 解析（HttpURLConnection 自带 10s 超时，无第三方依赖） */
    @Nullable
    private static Feed fetchFeed() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(FEED_URL).openConnection();
        try {
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + conn.getResponseCode());
            }
            String body = readAll(conn.getInputStream());
            return new Gson().fromJson(body, Feed.class);
        } finally {
            conn.disconnect();
        }
    }

    @NonNull
    private static String readAll(@Nullable InputStream in) throws IOException {
        if (in == null) {
            throw new IOException("empty body");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) > 0) {
            bos.write(buffer, 0, n);
        }
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 当前 versionName（如 0.2.0） */
    @NonNull
    public static String currentVersion(@NonNull Context context) {
        try {
            String name = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            return name != null ? name : "";
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** feed.version（如 v0.2.1）是否比 current（如 0.2.0）更新 */
    public static boolean isNewer(@NonNull String feedVersion, @NonNull String current) {
        return compareVersions(feedVersion, current) > 0;
    }

    /** 逐段数值比较：v0.10.0 > v0.9.9；解析失败的段按 0 处理 */
    static int compareVersions(@NonNull String a, @NonNull String b) {
        String[] as = stripV(a).split("\\.");
        String[] bs = stripV(b).split("\\.");
        int len = Math.max(as.length, bs.length);
        for (int i = 0; i < len; i++) {
            int va = i < as.length ? parseIntSafe(as[i]) : 0;
            int vb = i < bs.length ? parseIntSafe(bs[i]) : 0;
            if (va != vb) {
                return va > vb ? 1 : -1;
            }
        }
        return 0;
    }

    private static String stripV(@Nullable String version) {
        String s = version == null ? "" : version.trim();
        return s.startsWith("v") || s.startsWith("V") ? s.substring(1) : s;
    }

    private static int parseIntSafe(@NonNull String part) {
        try {
            return Integer.parseInt(part.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
