package com.github.ideastudios.ui;

import android.app.Dialog;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.github.ideastudios.ComAssistantActivity;
import com.github.ideastudios.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 发送前参数对话框（分包大小/间隔）：DialogFragment 实现，转屏保留
 * （输入内容经实例状态恢复）；文件数据经静态暂存位传递（MB 级数据不能进
 * Bundle，会触发 TransactionTooLarge），发起发送或应用退出时清除。
 */
public class FileSendConfigDialogFragment extends DialogFragment {

    public static final String ARG_CHUNK = "chunk";
    public static final String ARG_INTERVAL = "interval";
    private static final String STATE_CHUNK_TEXT = "chunk_text";
    private static final String STATE_INTERVAL_TEXT = "interval_text";
    private static final int MAX_CHUNK = 4096;
    private static final int MAX_INTERVAL = 10_000;

    /** 待发送文件数据（同一时间只准备一份，发起发送或重新选文件时覆盖） */
    @Nullable
    private static byte[] sPendingData;

    public static void setPendingData(@Nullable byte[] data) {
        sPendingData = data;
    }

    public static void clearPendingData() {
        sPendingData = null;
    }

    @Nullable
    private EditText etChunk;
    @Nullable
    private EditText etInterval;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Bundle args = requireArguments();
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        etChunk = new EditText(requireContext());
        etChunk.setInputType(InputType.TYPE_CLASS_NUMBER);
        etChunk.setHint(getString(R.string.file_chunk_hint));
        etChunk.setText(savedInstanceState != null
                ? savedInstanceState.getString(STATE_CHUNK_TEXT, "")
                : String.valueOf(args.getInt(ARG_CHUNK)));
        etInterval = new EditText(requireContext());
        etInterval.setInputType(InputType.TYPE_CLASS_NUMBER);
        etInterval.setHint(getString(R.string.file_interval_hint));
        etInterval.setText(savedInstanceState != null
                ? savedInstanceState.getString(STATE_INTERVAL_TEXT, "")
                : String.valueOf(args.getInt(ARG_INTERVAL)));
        box.addView(etChunk);
        box.addView(etInterval);

        if (sPendingData == null) {
            // 进程被杀后恢复（数据已不在）：立即关闭
            dismissAllowingStateLoss();
        }

        return new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.file_send_title,
                        sPendingData != null ? sPendingData.length : 0))
                .setView(box)
                .setCancelable(true)
                .setPositiveButton(getString(R.string.file_send_start), (dialog, which) -> {
                    if (sPendingData != null && getActivity()
                            instanceof ComAssistantActivity) {
                        startSend();
                    }
                })
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .create();
    }

    private void startSend() {
        if (sPendingData == null || !(getActivity() instanceof ComAssistantActivity)) {
            return;
        }
        byte[] data = sPendingData;
        sPendingData = null;
        int chunk = Math.max(1, Math.min(parse(etChunk,
                requireArguments().getInt(ARG_CHUNK)), MAX_CHUNK));
        int interval = Math.max(0, Math.min(parse(etInterval,
                requireArguments().getInt(ARG_INTERVAL)), MAX_INTERVAL));
        ComAssistantActivity activity = (ComAssistantActivity) requireActivity();
        activity.getSettings().fileSendChunkSize = chunk;
        activity.getSettings().fileSendIntervalMs = interval;
        activity.saveSettings();
        activity.startFileSend(data, chunk, interval);
    }

    private static int parse(@Nullable EditText editText, int fallback) {
        if (editText == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(editText.getText().toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (etChunk != null) {
            outState.putString(STATE_CHUNK_TEXT, etChunk.getText().toString());
        }
        if (etInterval != null) {
            outState.putString(STATE_INTERVAL_TEXT, etInterval.getText().toString());
        }
    }

    @Override
    public void onDestroyView() {
        etChunk = null;
        etInterval = null;
        super.onDestroyView();
    }
}
