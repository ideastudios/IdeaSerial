package com.github.ideastudios.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.ideastudios.ComAssistantActivity;
import com.github.ideastudios.R;
import com.github.ideastudios.bean.AppSettings;
import com.github.ideastudios.bean.ComBean;
import com.github.ideastudios.bean.QuickCommand;
import com.github.ideastudios.databinding.DialogPortConfigBinding;
import com.github.ideastudios.databinding.FragmentPortBinding;
import com.github.ideastudios.serial.SerialConfig;
import com.github.ideastudios.serialport.UsbSerialConnection;
import com.github.ideastudios.util.MyFunc;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 单个串口页：状态行 + 快捷命令 + TX/RX 着色日志 + 发送区 + 自动发送
 */
public class PortFragment extends Fragment {

    /** 未读帧数变化回调（Tab 徽标） */
    public interface UnreadListener {
        void onUnreadChanged(int portIndex, int unread);
    }

    private static final String ARG_INDEX = "port_index";
    private static final int MAX_ENTRIES = 5000;
    private static final int PENDING_DRAIN_THRESHOLD = 200;
    private static final long FLUSH_INTERVAL_MS = 120;

    private int portIndex;
    private FragmentPortBinding binding;
    private LogAdapter adapter;
    private AppSettings settings;
    private ComAssistantActivity.SerialControl helper;
    private final List<ComBean> pending = new ArrayList<>();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable flushRunnable;
    private int unread;
    private boolean autoScroll = true;

    public static PortFragment newInstance(int index) {
        PortFragment fragment = new PortFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_INDEX, index);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        portIndex = requireArguments().getInt(ARG_INDEX);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentPortBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ComAssistantActivity activity = (ComAssistantActivity) requireActivity();
        settings = activity.getSettings();
        helper = activity.getSerialControl(portIndex);
        helper.setListener(this::onSerialData);

        // 日志列表
        adapter = new LogAdapter(this::showEntryMenu);
        adapter.setDisplayMode(settings.hexMode ? LogAdapter.MODE_HEX : LogAdapter.MODE_TXT);
        adapter.setShowTimestamp(settings.showTimestamp);
        LinearLayoutManager layoutManager = new LinearLayoutManager(requireContext());
        binding.recyclerLog.setLayoutManager(layoutManager);
        binding.recyclerLog.setAdapter(adapter);
        binding.recyclerLog.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (lm != null) {
                    int last = lm.findLastCompletelyVisibleItemPosition();
                    autoScroll = last == RecyclerView.NO_POSITION
                            || last >= adapter.getItemCount() - 1;
                }
            }
        });

        // 状态行
        binding.buttonOpen.setOnClickListener(v -> togglePort());
        binding.buttonConfig.setOnClickListener(v -> showConfigDialog());
        updatePortInfo();
        updateOpenState(false);
        updateCounters();

        // Hex 模式（全局显示选项）
        binding.switchHex.setChecked(settings.hexMode);
        binding.switchHex.setOnCheckedChangeListener((buttonView, isChecked) -> {
            settings.hexMode = isChecked;
            adapter.setDisplayMode(isChecked ? LogAdapter.MODE_HEX : LogAdapter.MODE_TXT);
            if (helper.isOpen() && binding.switchAuto.isChecked()) {
                updateLoopData();
            }
            activity.saveSettings();
        });

        // 发送
        binding.buttonSend.setOnClickListener(v -> sendCurrent());

        // 自动发送
        binding.editTextInterval.setText(
                String.valueOf(settings.ports[portIndex].autoSendIntervalMs));
        binding.switchAuto.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                if (!helper.isOpen()) {
                    buttonView.setChecked(false);
                    toast(getString(R.string.port_not_open));
                    return;
                }
                updateLoopData();
                helper.setiDelay(parseInterval());
                helper.startSend();
            } else {
                helper.stopSend();
            }
        });
        binding.editTextSend.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (binding != null && binding.switchAuto.isChecked()) {
                    updateLoopData();
                }
            }
        });
        binding.editTextInterval.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (binding == null) {
                    return;
                }
                int delay = parseInterval();
                settings.ports[portIndex].autoSendIntervalMs = delay;
                if (binding.switchAuto.isChecked()) {
                    helper.setiDelay(delay);
                }
            }
        });

        buildChips();
    }

    @Override
    public void onResume() {
        super.onResume();
        markSeen();
        refreshPortState();
        startFlush();
    }

    @Override
    public void onPause() {
        stopFlush();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        stopFlush();
        if (helper != null) {
            helper.setListener(null);
        }
        binding = null;
        super.onDestroyView();
    }

    //----------------------------------------------------
    // 数据接收（读线程回调）
    //----------------------------------------------------
    private void onSerialData(ComBean bean) {
        List<ComBean> drain = null;
        synchronized (pending) {
            pending.add(bean);
            // 后台页（非当前 Tab）flush 暂停时防止无限积压，达到阈值直接排水入库
            if (pending.size() >= PENDING_DRAIN_THRESHOLD) {
                drain = new ArrayList<>(pending);
                pending.clear();
            }
        }
        if (drain != null) {
            final List<ComBean> batch = drain;
            uiHandler.post(() -> {
                if (binding != null) {
                    adapter.append(batch);
                    adapter.trimTo(MAX_ENTRIES);
                    updateCounters();
                }
            });
        }
        if (!isResumed()) {
            unread++;
            uiHandler.post(() -> {
                if (getActivity() instanceof UnreadListener) {
                    ((UnreadListener) getActivity()).onUnreadChanged(portIndex, unread);
                }
            });
        }
    }

    //----------------------------------------------------
    // 批量刷新（合并高频数据到列表）
    //----------------------------------------------------
    private void startFlush() {
        stopFlush();
        flushRunnable = new Runnable() {
            @Override
            public void run() {
                if (binding == null) {
                    return;
                }
                List<ComBean> batch = null;
                synchronized (pending) {
                    if (!pending.isEmpty()) {
                        batch = new ArrayList<>(pending);
                        pending.clear();
                    }
                }
                if (batch != null) {
                    adapter.append(batch);
                    adapter.trimTo(MAX_ENTRIES);
                    if (autoScroll) {
                        binding.recyclerLog.scrollToPosition(
                                Math.max(adapter.getItemCount() - 1, 0));
                    }
                    updateCounters();
                }
                uiHandler.postDelayed(this, FLUSH_INTERVAL_MS);
            }
        };
        uiHandler.post(flushRunnable);
    }

    private void stopFlush() {
        if (flushRunnable != null) {
            uiHandler.removeCallbacks(flushRunnable);
            flushRunnable = null;
        }
    }

    //----------------------------------------------------
    // 打开/关闭串口（含 USB 权限引导）
    //----------------------------------------------------
    private void togglePort() {
        ComAssistantActivity activity = (ComAssistantActivity) requireActivity();
        if (helper.isOpen()) {
            helper.close();
            updateOpenState(false);
            updateCounters();
            return;
        }
        SerialConfig config = settings.ports[portIndex];
        helper.setConfig(config);
        if (config.driverType == SerialConfig.DRIVER_USB) {
            UsbSerialConnection.UsbPortInfo info = findUsbInfo(config);
            if (info == null) {
                toast("未检测到匹配的 USB 串口设备，请检查连接后重新配置");
                return;
            }
            if (!info.hasPermission) {
                activity.requestUsbPermission(info.device, granted -> {
                    if (!isAdded()) {
                        return; // 授权弹窗期间页面已销毁
                    }
                    if (granted) {
                        doOpen();
                    } else {
                        toast("未授予 USB 权限，无法打开串口");
                    }
                });
                return;
            }
        }
        doOpen();
    }

    private UsbSerialConnection.UsbPortInfo findUsbInfo(SerialConfig config) {
        for (UsbSerialConnection.UsbPortInfo info :
                ((ComAssistantActivity) requireActivity()).listUsbPortInfos()) {
            if (info.device.getVendorId() == config.usbVid
                    && info.device.getProductId() == config.usbPid
                    && info.portIndex == config.usbPortIndex) {
                return info;
            }
        }
        return null;
    }

    private void doOpen() {
        try {
            helper.open();
            updateOpenState(true);
            updateCounters();
            toast(getString(R.string.open_ok));
        } catch (SecurityException e) {
            ((ComAssistantActivity) requireActivity()).showPortError(
                    getString(R.string.open_fail),
                    "没有串口读/写权限。板载串口（/dev/ttyS*）通常需要 root 权限：请确认设备已 root，"
                            + "并在 superuser 授权弹窗中允许本应用；如无需 root，请改用 USB 串口模式。");
        } catch (IOException e) {
            ((ComAssistantActivity) requireActivity()).showPortError(
                    getString(R.string.open_fail),
                    e.getMessage() != null ? e.getMessage() : "未知错误");
        }
    }

    private void updateOpenState(boolean open) {
        binding.buttonOpen.setText(open ? R.string.close_port : R.string.open_port);
    }

    private void updatePortInfo() {
        SerialConfig c = settings.ports[portIndex];
        String device = c.driverType == SerialConfig.DRIVER_USB ? c.usbDeviceName : c.devicePath;
        binding.textPortInfo.setText(device + " · " + c.describe());
    }

    private void updateCounters() {
        binding.textCounter.setText("RX " + helper.getRxCount() + " / TX " + helper.getTxCount());
    }

    //----------------------------------------------------
    // 发送
    //----------------------------------------------------
    private void sendCurrent() {
        String text = binding.editTextSend.getText().toString();
        if (text.isEmpty()) {
            return;
        }
        if (!helper.isOpen()) {
            toast(getString(R.string.port_not_open));
            return;
        }
        byte[] data = binding.switchHex.isChecked()
                ? MyFunc.HexToByteArr(text) : text.getBytes();
        helper.send(data);
        logTx(data);
    }

    private void sendCommand(QuickCommand cmd) {
        if (!helper.isOpen()) {
            toast(getString(R.string.port_not_open));
            return;
        }
        byte[] data = cmd.isHex ? MyFunc.HexToByteArr(cmd.content) : cmd.content.getBytes();
        if (cmd.appendNewline) {
            byte[] withNewline = new byte[data.length + 2];
            System.arraycopy(data, 0, withNewline, 0, data.length);
            withNewline[data.length] = 0x0D;
            withNewline[data.length + 1] = 0x0A;
            data = withNewline;
        }
        helper.send(data);
        logTx(data);
    }

    /** 发送完成后补一条 TX 日志 */
    public void logTx(byte[] data) {
        onSerialData(new ComBean(settings.ports[portIndex].devicePath, data, data.length,
                ComBean.DIR_TX, System.currentTimeMillis()));
    }

    private void updateLoopData() {
        String text = binding.editTextSend.getText().toString();
        if (binding.switchHex.isChecked()) {
            helper.setHexLoopData(text);
        } else {
            helper.setTxtLoopData(text);
        }
    }

    private int parseInterval() {
        try {
            int value = Integer.parseInt(binding.editTextInterval.getText().toString());
            return value > 0 ? value : 500;
        } catch (Exception e) {
            return 500;
        }
    }

    //----------------------------------------------------
    // 快捷命令
    //----------------------------------------------------
    private void buildChips() {
        binding.chipGroupCommands.removeAllViews();
        for (final QuickCommand cmd : settings.commands) {
            Chip chip = new Chip(requireContext());
            chip.setText(cmd.name);
            chip.setCloseIconVisible(false);
            chip.setOnClickListener(v -> sendCommand(cmd));
            chip.setOnLongClickListener(v -> {
                showCommandEditor(cmd);
                return true;
            });
            binding.chipGroupCommands.addView(chip);
        }
        Chip addChip = new Chip(requireContext());
        addChip.setText("＋ 新增");
        addChip.setCloseIconVisible(false);
        addChip.setOnClickListener(v -> showCommandEditor(null));
        binding.chipGroupCommands.addView(addChip);
    }

    private void showCommandEditor(@Nullable final QuickCommand existing) {
        Context ctx = requireContext();
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText etName = new EditText(ctx);
        etName.setHint("名称");
        final EditText etContent = new EditText(ctx);
        etContent.setHint("内容（Hex 模式支持空格分隔）");
        final CheckBox cbHex = new CheckBox(ctx);
        cbHex.setText("Hex 模式发送");
        final CheckBox cbNew = new CheckBox(ctx);
        cbNew.setText("自动追加换行 (CRLF)");
        box.addView(etName);
        box.addView(etContent);
        box.addView(cbHex);
        box.addView(cbNew);

        if (existing != null) {
            etName.setText(existing.name);
            etContent.setText(existing.content);
            cbHex.setChecked(existing.isHex);
            cbNew.setChecked(existing.appendNewline);
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(ctx)
                .setTitle(existing == null ? "新增命令" : "编辑命令")
                .setView(box)
                .setPositiveButton("保存", (dialog, which) -> {
                    QuickCommand cmd = existing != null ? existing : new QuickCommand();
                    cmd.name = etName.getText().toString().trim();
                    cmd.content = etContent.getText().toString();
                    if (cmd.name.isEmpty()) {
                        cmd.name = cmd.content.length() > 8
                                ? cmd.content.substring(0, 8) : cmd.content;
                    }
                    cmd.isHex = cbHex.isChecked();
                    cmd.appendNewline = cbNew.isChecked();
                    if (existing == null) {
                        settings.commands.add(cmd);
                    }
                    ((ComAssistantActivity) requireActivity()).saveSettings();
                    buildChips();
                })
                .setNegativeButton("取消", null);
        if (existing != null) {
            builder.setNeutralButton("删除", (dialog, which) -> {
                settings.commands.remove(existing);
                ((ComAssistantActivity) requireActivity()).saveSettings();
                buildChips();
            });
        }
        builder.show();
    }

    //----------------------------------------------------
    // 端口配置对话框
    //----------------------------------------------------
    private void showConfigDialog() {
        ComAssistantActivity activity = (ComAssistantActivity) requireActivity();
        final SerialConfig config = settings.ports[portIndex];
        DialogPortConfigBinding d = DialogPortConfigBinding.inflate(getLayoutInflater());

        // 模式
        d.radioNative.setChecked(config.driverType == SerialConfig.DRIVER_NATIVE);
        d.radioUsb.setChecked(config.driverType == SerialConfig.DRIVER_USB);

        // 设备列表
        final List<String> nativePaths = activity.listNativeDevicePaths();
        final List<UsbSerialConnection.UsbPortInfo> usbInfos = activity.listUsbPortInfos();
        List<String> usbNames = new ArrayList<>();
        for (UsbSerialConnection.UsbPortInfo info : usbInfos) {
            usbNames.add(info.displayName);
        }
        if (usbNames.isEmpty()) {
            usbNames.add("未检测到 USB 串口设备");
        }
        final ArrayAdapter<String> nativeAdapter =
                spinnerAdapter(nativePaths.toArray(new String[0]));
        final ArrayAdapter<String> usbAdapter = spinnerAdapter(usbNames.toArray(new String[0]));

        Runnable applyDeviceAdapter = () -> {
            boolean usb = d.radioUsb.isChecked();
            d.spinnerDevice.setAdapter(usb ? usbAdapter : nativeAdapter);
            d.editTextManualPath.setEnabled(!usb);
        };
        applyDeviceAdapter.run();
        if (config.driverType == SerialConfig.DRIVER_NATIVE) {
            int sel = nativePaths.indexOf(config.devicePath);
            if (sel >= 0) {
                d.spinnerDevice.setSelection(sel);
            }
        } else {
            for (int i = 0; i < usbInfos.size(); i++) {
                UsbSerialConnection.UsbPortInfo info = usbInfos.get(i);
                if (info.device.getVendorId() == config.usbVid
                        && info.device.getProductId() == config.usbPid
                        && info.portIndex == config.usbPortIndex) {
                    d.spinnerDevice.setSelection(i);
                    break;
                }
            }
        }
        d.radioGroupMode.setOnCheckedChangeListener((group, checkedId) -> applyDeviceAdapter.run());

        // 波特率
        final String[] bauds = getResources().getStringArray(R.array.baudrates_value);
        d.spinnerBaud.setAdapter(spinnerAdapter(bauds));
        for (int i = 0; i < bauds.length; i++) {
            if (bauds[i].equals(String.valueOf(config.baudRate))) {
                d.spinnerBaud.setSelection(i);
                break;
            }
        }

        // 参数
        d.spinnerDataBits.setAdapter(spinnerAdapter(new String[]{"5", "6", "7", "8"}));
        d.spinnerDataBits.setSelection(Math.max(0, Math.min(3, config.dataBits - 5)));
        d.spinnerParity.setAdapter(spinnerAdapter(new String[]{"无", "奇校验", "偶校验", "标校验", "空校验"}));
        d.spinnerParity.setSelection(Math.max(0, Math.min(4, config.parity)));
        d.spinnerStopBits.setAdapter(spinnerAdapter(new String[]{"1", "2"}));
        d.spinnerStopBits.setSelection(config.stopBits == 2 ? 1 : 0);
        d.spinnerFlow.setAdapter(spinnerAdapter(new String[]{"无", "RTS/CTS", "XON/XOFF"}));
        d.spinnerFlow.setSelection(Math.max(0, Math.min(2, config.flowControl)));

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("串口设置")
                .setView(d.getRoot())
                .setPositiveButton("确定", (dialog, which) -> {
                    boolean wasOpen = helper.isOpen();
                    config.driverType = d.radioUsb.isChecked()
                            ? SerialConfig.DRIVER_USB : SerialConfig.DRIVER_NATIVE;
                    if (config.driverType == SerialConfig.DRIVER_NATIVE) {
                        String manual = d.editTextManualPath.getText().toString().trim();
                        if (!manual.isEmpty()) {
                            config.devicePath = manual;
                        } else if (!nativePaths.isEmpty()
                                && d.spinnerDevice.getSelectedItemPosition() < nativePaths.size()) {
                            config.devicePath = nativePaths.get(
                                    d.spinnerDevice.getSelectedItemPosition());
                        }
                    } else if (!usbInfos.isEmpty()
                            && d.spinnerDevice.getSelectedItemPosition() < usbInfos.size()) {
                        UsbSerialConnection.UsbPortInfo info =
                                usbInfos.get(d.spinnerDevice.getSelectedItemPosition());
                        config.usbVid = info.device.getVendorId();
                        config.usbPid = info.device.getProductId();
                        config.usbPortIndex = info.portIndex;
                        String name = info.device.getProductName();
                        config.usbDeviceName = name != null ? name : info.displayName;
                        config.devicePath = info.displayName;
                    }
                    try {
                        config.baudRate = Integer.parseInt(
                                bauds[d.spinnerBaud.getSelectedItemPosition()]);
                    } catch (Exception ignored) {
                    }
                    config.dataBits = d.spinnerDataBits.getSelectedItemPosition() + 5;
                    config.parity = d.spinnerParity.getSelectedItemPosition();
                    config.stopBits = d.spinnerStopBits.getSelectedItemPosition() == 1 ? 2 : 1;
                    config.flowControl = d.spinnerFlow.getSelectedItemPosition();
                    activity.saveSettings();
                    updatePortInfo();
                    if (wasOpen) {
                        toast("配置已保存，下次打开串口时生效");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private ArrayAdapter<String> spinnerAdapter(String[] items) {
        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    //----------------------------------------------------
    // 日志条目菜单（复制/分享）
    //----------------------------------------------------
    private void showEntryMenu(ComBean entry) {
        String text = adapter.entryToText(entry);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("日志条目")
                .setItems(new CharSequence[]{"复制", "分享"}, (dialog, which) -> {
                    if (which == 0) {
                        ClipboardManager cm = (ClipboardManager)
                                requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("serial", text));
                        toast("已复制");
                    } else {
                        Intent send = new Intent(Intent.ACTION_SEND);
                        send.setType("text/plain");
                        send.putExtra(Intent.EXTRA_TEXT, text);
                        startActivity(Intent.createChooser(send, "分享日志"));
                    }
                })
                .show();
    }

    //----------------------------------------------------
    // 供 Activity 调用的接口
    //----------------------------------------------------
    public boolean isPortOpen() {
        return helper != null && helper.isOpen();
    }

    /** 页面重新可见 / Tab 切回时清零未读计数 */
    public void markSeen() {
        unread = 0;
        if (getActivity() instanceof UnreadListener) {
            ((UnreadListener) getActivity()).onUnreadChanged(portIndex, 0);
        }
    }

    /** 外部事件（如 USB 拔出）导致端口被关闭后，同步按钮与自动发送开关状态 */
    public void refreshPortState() {
        if (binding == null) {
            return;
        }
        boolean open = helper != null && helper.isOpen();
        updateOpenState(open);
        if (!open && binding.switchAuto.isChecked()) {
            binding.switchAuto.setChecked(false);
        }
        updateCounters();
    }

    public String exportLogText() {
        return adapter.exportAllText();
    }

    public void clearLog() {
        adapter.clear();
    }

    private void toast(String msg) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }

    /** 简化版 TextWatcher */
    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void afterTextChanged(Editable s) {
        }
    }
}
