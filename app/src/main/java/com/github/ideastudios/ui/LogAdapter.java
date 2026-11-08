package com.github.ideastudios.ui;

import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.github.ideastudios.R;
import com.github.ideastudios.bean.ComBean;
import com.github.ideastudios.util.MyFunc;

import java.util.ArrayList;
import java.util.List;

/**
 * 串口日志列表适配器：TX/RX 方向着色、时间戳开关、Hex/Txt 渲染。
 * 采用追加式 notify（日志场景只增不改，无需 DiffUtil）。
 */
public class LogAdapter extends RecyclerView.Adapter<LogAdapter.ViewHolder> {

    public static final int MODE_TXT = 0;
    public static final int MODE_HEX = 1;

    public interface OnEntryLongClickListener {
        void onEntryLongClick(ComBean entry);
    }

    private final List<ComBean> items = new ArrayList<>();
    private int displayMode = MODE_TXT;
    private boolean showTimestamp = true;
    private final OnEntryLongClickListener longClickListener;

    public LogAdapter(OnEntryLongClickListener longClickListener) {
        this.longClickListener = longClickListener;
    }

    /** 追加一批日志，增量刷新 */
    public void append(List<ComBean> batch) {
        if (batch.isEmpty()) {
            return;
        }
        int start = items.size();
        items.addAll(batch);
        notifyItemRangeInserted(start, batch.size());
    }

    /** 裁剪到 max 条（从头部移除） */
    public void trimTo(int max) {
        int overflow = items.size() - max;
        if (overflow > 0) {
            items.subList(0, overflow).clear();
            notifyItemRangeRemoved(0, overflow);
        }
    }

    public void clear() {
        items.clear();
        notifyDataSetChanged();
    }

    public void setDisplayMode(int mode) {
        if (mode != displayMode) {
            displayMode = mode;
            notifyDataSetChanged();
        }
    }

    public void setShowTimestamp(boolean show) {
        if (show != showTimestamp) {
            showTimestamp = show;
            notifyDataSetChanged();
        }
    }

    /** 条目转为纯文本（导出/分享用） */
    public String entryToText(ComBean e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.direction == ComBean.DIR_TX ? "[TX] " : "[RX] ");
        if (showTimestamp) {
            sb.append(e.getFormattedTime()).append(' ');
        }
        sb.append(displayMode == MODE_HEX ? MyFunc.ByteArrToHex(e.bRec) : new String(e.bRec));
        return sb.toString();
    }

    /** 全部日志导出为文本 */
    public String exportAllText() {
        StringBuilder sb = new StringBuilder();
        for (ComBean e : items) {
            sb.append(entryToText(e)).append("\r\n");
        }
        return sb.toString();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_log_entry, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ComBean entry = items.get(position);
        holder.text.setText(buildSpan(entry, holder.text.getContext()));
        holder.text.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                longClickListener.onEntryLongClick(entry);
                return true;
            }
            return false;
        });
    }

    @NonNull
    private CharSequence buildSpan(ComBean e, android.content.Context context) {
        String prefix = e.direction == ComBean.DIR_TX ? "[TX] " : "[RX] ";
        String time = showTimestamp ? e.getFormattedTime() + " " : "";
        String content = displayMode == MODE_HEX ? MyFunc.ByteArrToHex(e.bRec) : new String(e.bRec);
        String plain = prefix + time + content;

        SpannableString span = new SpannableString(plain);
        if (e.direction == ComBean.DIR_TX) {
            // TX 方向着色，RX 保持默认文字色
            span.setSpan(new ForegroundColorSpan(
                            ContextCompat.getColor(context, R.color.log_tx)),
                    0, prefix.length(), SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (!time.isEmpty()) {
            span.setSpan(new ForegroundColorSpan(
                            ContextCompat.getColor(context, R.color.log_time)),
                    prefix.length(), prefix.length() + time.length(),
                    SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return span;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView text;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.textLog);
        }
    }
}
