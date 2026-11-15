package com.github.ideastudios.ui;

import android.app.Dialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.github.ideastudios.R;
import com.github.ideastudios.serial.FileSendTask;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 文件发送进度对话框：与 {@link FileSendTask} 生命周期解耦，
 * 转屏重建后自动重新挂接任务并显示当前进度；取消按钮走任务取消。
 */
public class FileSendDialogFragment extends DialogFragment implements FileSendTask.Listener {

    @Nullable
    private FileSendTask task;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        task = FileSendTask.current();
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.menu_send_file))
                .setCancelable(false)
                .setNegativeButton(getString(R.string.dialog_cancel),
                        (d, which) -> {
                            if (task != null) {
                                task.cancel();
                            }
                        });
        if (task == null) {
            // 防御：任务已结束（极端重建时序），立即关闭
            dismissAllowingStateLoss();
            return builder.setMessage("").create();
        }
        task.setUiListener(this);
        if (task.isFinished()) {
            // 任务在挂接前已完成，避免残留空进度对话框
            dismissAllowingStateLoss();
        }
        return builder.setMessage(getString(R.string.progress_format,
                task.getSent(), task.getTotal())).create();
    }

    @Override
    public void onProgress(int sent, int total) {
        if (getDialog() instanceof AlertDialog) {
            ((AlertDialog) getDialog()).setMessage(
                    getString(R.string.progress_format, sent, total));
        }
    }

    @Override
    public void onDone(int sent, boolean cancelled) {
        dismissAllowingStateLoss();
    }

    @Override
    public void onDestroy() {
        // 身份比对式注销：转屏时旧对话框销毁晚于新对话框挂接，不能误清新监听
        if (task != null) {
            task.detachUi(this);
        }
        task = null;
        super.onDestroy();
    }
}
