package com.github.ideastudios.serial;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.ideastudios.R;
import com.github.ideastudios.bean.ComBean;

import java.util.Arrays;

/**
 * 文件分块发送任务：发送线程、进度与完成落日志独立于 Activity/Fragment 生命周期，
 * 转屏重建时发送继续，进度对话框经 {@link #current()} 重新挂接。同一时间仅一个任务。
 *
 * 完成时：TX 摘要写入会话层日志（由当前页面渲染）+ Toast 提示，均不依赖 UI 存活。
 */
public final class FileSendTask {

    /** UI 进度回调（主线程） */
    public interface Listener {
        void onProgress(int sent, int total);

        void onDone(int sent, boolean cancelled);
    }

    private static final int SUMMARY_HEAD_LEN = 64;

    private static volatile FileSendTask current;

    /** 当前进行中的任务，无则返回 null */
    @Nullable
    public static FileSendTask current() {
        return current;
    }

    /** 创建并启动发送任务；已有任务进行中时返回 null（不覆盖旧任务） */
    @Nullable
    public static FileSendTask start(@NonNull Context context, @NonNull SerialControl control,
            int portIndex, @NonNull byte[] data, int chunk, int interval) {
        synchronized (FileSendTask.class) {
            if (current != null) {
                return null;
            }
            FileSendTask task = new FileSendTask(context.getApplicationContext(),
                    control, portIndex, data, chunk, interval);
            current = task;
            new Thread(task::run, "file-send").start();
            return task;
        }
    }

    private final Context appContext;
    private final SerialControl control;
    private final int portIndex;
    private final byte[] data;
    private final int chunk;
    private final int interval;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile Thread worker;
    private volatile boolean cancelled;
    private volatile boolean finished;
    private volatile int sent;
    private volatile Listener uiListener;

    private FileSendTask(@NonNull Context appContext, @NonNull SerialControl control,
            int portIndex, @NonNull byte[] data, int chunk, int interval) {
        this.appContext = appContext;
        this.control = control;
        this.portIndex = portIndex;
        this.data = data;
        this.chunk = chunk;
        this.interval = interval;
    }

    /** 取消发送（同时中断分块间休眠，尽快生效） */
    public void cancel() {
        cancelled = true;
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
    }

    public int getSent() {
        return sent;
    }

    public int getTotal() {
        return data.length;
    }

    public boolean isFinished() {
        return finished;
    }

    public void setUiListener(@Nullable Listener listener) {
        this.uiListener = listener;
    }

    /** 注销 UI 监听者；仅当当前监听者一致时清除（转屏时旧对话框销毁晚于新对话框挂接） */
    public void detachUi(@Nullable Listener listener) {
        if (uiListener == listener) {
            uiListener = null;
        }
    }

    private void run() {
        worker = Thread.currentThread();
        int done = 0;
        try {
            while (done < data.length && !cancelled) {
                int end = Math.min(done + chunk, data.length);
                control.send(Arrays.copyOfRange(data, done, end));
                done = end;
                sent = done;
                notifyProgress(done, data.length);
                if (interval > 0) {
                    Thread.sleep(interval);
                }
            }
        } catch (InterruptedException e) {
            cancelled = true;
        } catch (Throwable e) {
            // 意外异常（驱动/连接异常状态）：必须标记结束并清空 current，
            // 否则任务永久卡死，后续所有文件发送都会被"任务进行中"挡住
            e.printStackTrace();
            cancelled = true;
        }
        finished = true;
        appendTxSummary(done);
        clearCurrent(this);
        boolean cancelledFinal = cancelled;
        int sentFinal = done;
        mainHandler.post(() -> {
            Listener l = uiListener;
            if (l != null) {
                l.onDone(sentFinal, cancelledFinal);
                uiListener = null;
            }
            Toast.makeText(appContext, appContext.getString(
                    cancelledFinal ? R.string.send_cancelled : R.string.send_done),
                    Toast.LENGTH_SHORT).show();
        });
    }

    /** TX 摘要写入会话层：大文件只记前 64 字节 + 剩余字节说明，避免单条日志过大 */
    private void appendTxSummary(int done) {
        if (done <= 0) {
            return;
        }
        int headLen = Math.min(done, SUMMARY_HEAD_LEN);
        byte[] summary;
        if (done > headLen) {
            byte[] head = Arrays.copyOfRange(data, 0, headLen);
            byte[] tail = appContext.getString(
                    R.string.summary_tail, done - headLen).getBytes();
            summary = new byte[head.length + tail.length];
            System.arraycopy(head, 0, summary, 0, head.length);
            System.arraycopy(tail, 0, summary, head.length, tail.length);
        } else {
            summary = Arrays.copyOfRange(data, 0, headLen);
        }
        SerialSession.get(appContext).log(portIndex, new ComBean(
                control.getConfig().devicePath, summary, summary.length,
                ComBean.DIR_TX, System.currentTimeMillis()));
    }

    private void notifyProgress(int done, int total) {
        mainHandler.post(() -> {
            Listener l = uiListener;
            if (l != null) {
                l.onProgress(done, total);
            }
        });
    }

    private static void clearCurrent(FileSendTask task) {
        synchronized (FileSendTask.class) {
            if (current == task) {
                current = null;
            }
        }
    }
}
