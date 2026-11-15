package com.github.ideastudios.serial;

import android.content.Context;

import androidx.annotation.Nullable;

import com.github.ideastudios.bean.ComBean;

/**
 * 带监听的串口控制类：读线程数据转发给当前绑定的监听者。
 * 实例由 {@link SerialSession} 持有，生命周期独立于 Activity/Fragment，
 * 转屏重建 UI 时串口连接与收发状态不受影响。
 *
 * @author benjaminwan, ideastudios
 */
public class SerialControl extends SerialHelper {

    /** 串口数据监听（UI 层注册，读线程回调） */
    public interface OnDataListener {
        void onSerialData(ComBean bean);
    }

    private volatile OnDataListener listener;

    public SerialControl(Context context) {
        super(context);
    }

    public void setListener(@Nullable OnDataListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onDataReceived(ComBean bean) {
        OnDataListener l = listener;
        if (l != null) {
            l.onSerialData(bean);
        }
    }
}
