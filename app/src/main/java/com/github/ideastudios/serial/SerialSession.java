package com.github.ideastudios.serial;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.ideastudios.bean.ComBean;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 串口会话层（单例）：持有全部端口的串口控制、收发日志与未读计数，
 * 生命周期独立于 Activity/Fragment——转屏重建 UI 时串口连接与日志不丢失。
 *
 * 数据流：SerialHelper 读线程 → {@link #log}（写入会话日志 → 通知当前 UI 监听者），
 * UI 侧只负责渲染，不再持有数据。
 */
public final class SerialSession {

    public static final int PORT_COUNT = 4;
    /** 每端口日志上限（条），超出后从头部丢弃 */
    public static final int LOG_LIMIT = 5000;

    /** UI 层数据监听：回调的 bean 均已写入会话日志（读线程回调） */
    public interface OnDataListener {
        void onSerialData(ComBean bean);
    }

    private static volatile SerialSession instance;

    public static SerialSession get(@NonNull Context context) {
        if (instance == null) {
            synchronized (SerialSession.class) {
                if (instance == null) {
                    instance = new SerialSession(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private static final class PortState {
        final SerialControl control;
        final List<ComBean> logs = Collections.synchronizedList(new ArrayList<>());
        volatile OnDataListener uiListener;
        int unread;

        PortState(Context appContext) {
            control = new SerialControl(appContext);
        }
    }

    private final PortState[] ports = new PortState[PORT_COUNT];

    private SerialSession(Context appContext) {
        for (int i = 0; i < PORT_COUNT; i++) {
            final PortState st = new PortState(appContext);
            final int index = i;
            // 读线程数据统一先入会话日志，再转发给当前 UI 监听者
            st.control.setListener(bean -> log(index, bean));
            ports[i] = st;
        }
    }

    public SerialControl getControl(int port) {
        return ports[port].control;
    }

    /** 写入一条日志（读线程 RX / 发送后 TX 均走此入口）：裁剪落盘并通知 UI 监听者 */
    public void log(int port, ComBean bean) {
        PortState st = ports[port];
        List<ComBean> logs = st.logs;
        logs.add(bean);
        if (logs.size() > LOG_LIMIT) {
            logs.subList(0, logs.size() - LOG_LIMIT).clear();
        }
        OnDataListener l = st.uiListener;
        if (l != null) {
            l.onSerialData(bean);
        }
    }

    /**
     * 注册 UI 监听者并原子返回历史日志快照（UI 重建时恢复）。
     * 加锁覆盖"注册 + 拷贝"全过程：期间新到数据在 log() 中阻塞等待，
     * 释放后只会走监听者通道，保证不丢帧也不重复。
     */
    public List<ComBean> attachUi(int port, OnDataListener listener) {
        PortState st = ports[port];
        List<ComBean> snapshot;
        synchronized (st.logs) {
            st.uiListener = listener;
            snapshot = new ArrayList<>(st.logs);
        }
        return snapshot;
    }

    /** 注销 UI 监听者；仅当当前监听者与传入实例一致时才清除（防旧页面销毁时误清新页面） */
    public void detachUi(int port, OnDataListener listener) {
        PortState st = ports[port];
        if (st.uiListener == listener) {
            st.uiListener = null;
        }
    }

    public void clearLogs(int port) {
        ports[port].logs.clear();
    }

    public synchronized int getUnread(int port) {
        return ports[port].unread;
    }

    public synchronized void bumpUnread(int port) {
        ports[port].unread++;
    }

    public synchronized void markSeen(int port) {
        ports[port].unread = 0;
    }
}
