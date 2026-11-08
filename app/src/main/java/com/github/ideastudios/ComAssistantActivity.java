package com.github.ideastudios;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.graphics.Color;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.github.ideastudios.bean.AppSettings;
import com.github.ideastudios.bean.ComBean;
import com.github.ideastudios.databinding.ActivityMainBinding;
import com.github.ideastudios.serial.SerialConfig;
import com.github.ideastudios.serial.SerialHelper;
import com.github.ideastudios.serialport.SerialPortFinder;
import com.github.ideastudios.serialport.UsbSerialConnection;
import com.github.ideastudios.store.SettingsStore;
import com.github.ideastudios.ui.PortFragment;
import com.github.ideastudios.ui.PortPagerAdapter;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 串口调试助手主界面：TabLayout + ViewPager2，4 个串口页后台持续收发。
 * 串口支持板载（root）与 USB OTG（免 root）两种模式。
 *
 * @author benjaminwan, ideastudios
 */
public class ComAssistantActivity extends AppCompatActivity implements PortFragment.UnreadListener {

    private static final String ACTION_USB_PERMISSION = "com.github.ideastudios.USB_PERMISSION";

    private ActivityMainBinding binding;
    private AppSettings settings;
    private SettingsStore store;
    private SerialPortFinder finder;
    private final SerialControl[] controls = new SerialControl[PortPagerAdapter.PORT_COUNT];
    private final int[] unreadCounts = new int[PortPagerAdapter.PORT_COUNT];
    private final TextView[] tabTitles = new TextView[PortPagerAdapter.PORT_COUNT];
    private final TextView[] tabBadges = new TextView[PortPagerAdapter.PORT_COUNT];

    private ActivityResultLauncher<String> filePicker;
    private ActivityResultLauncher<String> logSaver;
    private volatile boolean fileSendCancelled;
    private String pendingLogText;

    /** USB 权限请求结果回调 */
    public interface UsbPermissionCallback {
        void onResult(boolean granted);
    }

    /** 带监听的串口控制类：读线程数据转发给当前绑定的 Fragment */
    public static class SerialControl extends SerialHelper {

        /** 串口数据监听 */
        public interface OnDataListener {
            void onSerialData(ComBean bean);
        }

        private OnDataListener listener;

        public SerialControl(Context context) {
            super(context);
        }

        public void setListener(@Nullable OnDataListener listener) {
            this.listener = listener;
        }

        @Override
        protected void onDataReceived(ComBean bean) {
            if (listener != null) {
                listener.onSerialData(bean);
            }
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new SettingsStore(this);
        settings = store.load();
        finder = new SerialPortFinder();
        for (int i = 0; i < controls.length; i++) {
            controls[i] = new SerialControl(this);
        }

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        // 沉浸式：内容延伸到状态栏/导航栏下方，由 insets 负责避让
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        final int barTypes = WindowInsetsCompat.Type.systemBars()
                | WindowInsetsCompat.Type.displayCutout();
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(barTypes);
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, 0, bars.right, Math.max(bars.bottom, ime.bottom));
            return windowInsets;
        });
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(barTypes);
            v.setPadding(v.getPaddingLeft(), bars.top, v.getPaddingRight(), v.getPaddingBottom());
            return windowInsets;
        });

        filePicker = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        onFilePicked(uri);
                    }
                });
        logSaver = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"),
                uri -> {
                    if (uri != null) {
                        onSaveLogTarget(uri);
                    }
                });

        binding.viewPager.setAdapter(new PortPagerAdapter(this));
        // 保留全部页面视图：offscreen 页的 Fragment 才能持续接收数据并累计未读徽标
        binding.viewPager.setOffscreenPageLimit(PortPagerAdapter.PORT_COUNT - 1);
        new TabLayoutMediator(binding.tabLayout, binding.viewPager,
                (tab, position) -> {
                    View custom = getLayoutInflater().inflate(R.layout.tab_port,
                            binding.tabLayout, false);
                    TextView title = custom.findViewById(R.id.textTabTitle);
                    TextView badge = custom.findViewById(R.id.textTabBadge);
                    title.setText(portLabel(position));
                    tabTitles[position] = title;
                    tabBadges[position] = badge;
                    tab.setCustomView(custom);
                    updateTabBadge(position);
                }).attach();
        binding.tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(@NonNull TabLayout.Tab tab) {
                int pos = tab.getPosition();
                unreadCounts[pos] = 0;
                updateTabBadge(pos);
                PortFragment fragment = (PortFragment) getSupportFragmentManager()
                        .findFragmentByTag("f" + pos);
                if (fragment != null) {
                    fragment.markSeen();
                }
            }

            @Override
            public void onTabUnselected(@NonNull TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(@NonNull TabLayout.Tab tab) {
            }
        });

        registerUsbReceivers();
    }

    private String portLabel(int position) {
        return "COM " + (char) ('A' + position);
    }

    //----------------------------------------------------
    // 未读徽标
    //----------------------------------------------------
    @Override
    public void onUnreadChanged(int portIndex, int count) {
        if (portIndex < 0 || portIndex >= unreadCounts.length) {
            return;
        }
        unreadCounts[portIndex] = count;
        updateTabBadge(portIndex);
    }

    private void updateTabBadge(int position) {
        TextView badge = tabBadges[position];
        if (badge == null) {
            return;
        }
        int count = unreadCounts[position];
        if (count > 0) {
            badge.setText(String.valueOf(count));
            badge.setVisibility(View.VISIBLE);
        } else {
            badge.setVisibility(View.GONE);
        }
    }

    //----------------------------------------------------
    // 供 PortFragment 使用的支撑方法
    //----------------------------------------------------
    public AppSettings getSettings() {
        return settings;
    }

    public SerialControl getSerialControl(int index) {
        return controls[index];
    }

    public void saveSettings() {
        store.save(settings);
    }

    public List<String> listNativeDevicePaths() {
        return Arrays.asList(finder.getAllDevicesPath());
    }

    public List<UsbSerialConnection.UsbPortInfo> listUsbPortInfos() {
        return UsbSerialConnection.listAvailablePorts(this);
    }

    public void showPortError(String title, String message) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    public void requestUsbPermission(@NonNull UsbDevice device,
                                     @NonNull UsbPermissionCallback callback) {
        pendingUsbCallback = callback;
        UsbManager usbManager = (UsbManager) getSystemService(USB_SERVICE);
        if (usbManager == null) {
            callback.onResult(false);
            return;
        }
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION), flags);
        usbManager.requestPermission(device, pi);
    }

    private UsbPermissionCallback pendingUsbCallback;

    //----------------------------------------------------
    // USB 权限/拔出广播
    //----------------------------------------------------
    private void registerUsbReceivers() {
        IntentFilter permissionFilter = new IntentFilter(ACTION_USB_PERMISSION);
        IntentFilter detachFilter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbPermissionReceiver, permissionFilter, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(usbDetachReceiver, detachFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbPermissionReceiver, permissionFilter);
            registerReceiver(usbDetachReceiver, detachFilter);
        }
    }

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) {
                return;
            }
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device == null) {
                return;
            }
            boolean granted = intent.getParcelableExtra(UsbManager.EXTRA_PERMISSION_GRANTED) != null;
            if (pendingUsbCallback != null) {
                UsbPermissionCallback callback = pendingUsbCallback;
                pendingUsbCallback = null;
                callback.onResult(granted);
            }
        }
    };

    private final BroadcastReceiver usbDetachReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device == null) {
                return;
            }
            boolean closed = false;
            for (SerialControl control : controls) {
                SerialConfig config = control.getConfig();
                if (control.isOpen() && config.driverType == SerialConfig.DRIVER_USB
                        && config.usbVid == device.getVendorId()
                        && config.usbPid == device.getProductId()) {
                    control.close();
                    closed = true;
                }
            }
            if (closed) {
                runOnUiThread(() -> {
                    Toast.makeText(ComAssistantActivity.this,
                            getString(R.string.usb_detached), Toast.LENGTH_SHORT).show();
                    PortFragment fragment = currentFragment();
                    if (fragment != null) {
                        fragment.refreshPortState();
                    }
                });
            }
        }
    };

    //----------------------------------------------------
    // 菜单：发送文件 / 保存日志 / 清空日志
    //----------------------------------------------------
    @Override
    public boolean onCreateOptionsMenu(@NonNull android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @SuppressLint("NonConstantResourceId")
    @Override
    public boolean onOptionsItemSelected(@NonNull android.view.MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_send_file) {
            filePicker.launch("*/*");
            return true;
        } else if (id == R.id.action_save_log) {
            saveCurrentLog();
            return true;
        } else if (id == R.id.action_clear_log) {
            PortFragment fragment = currentFragment();
            if (fragment != null) {
                fragment.clearLog();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Nullable
    private PortFragment currentFragment() {
        int position = binding.viewPager.getCurrentItem();
        return (PortFragment) getSupportFragmentManager().findFragmentByTag("f" + position);
    }

    @Nullable
    private SerialControl currentControl() {
        return controls[binding.viewPager.getCurrentItem()];
    }

    //----------------------------------------------------
    // 发送文件（SAF 选择 + 分块发送）
    //----------------------------------------------------
    private void onFilePicked(@NonNull Uri uri) {
        PortFragment fragment = currentFragment();
        if (fragment == null || !fragment.isPortOpen()) {
            Toast.makeText(this, R.string.port_not_open, Toast.LENGTH_SHORT).show();
            return;
        }
        byte[] data;
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) {
                throw new IOException("无法打开文件");
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                bos.write(buffer, 0, n);
            }
            in.close();
            data = bos.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, getString(R.string.file_read_failed), Toast.LENGTH_SHORT).show();
            return;
        }
        if (data.length == 0) {
            Toast.makeText(this, getString(R.string.file_empty), Toast.LENGTH_SHORT).show();
            return;
        }
        showFileSendDialog(data);
    }

    private void showFileSendDialog(byte[] data) {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText etChunk = new EditText(this);
        etChunk.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etChunk.setHint(getString(R.string.file_chunk_hint));
        etChunk.setText(String.valueOf(settings.fileSendChunkSize));
        final EditText etInterval = new EditText(this);
        etInterval.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etInterval.setHint(getString(R.string.file_interval_hint));
        etInterval.setText(String.valueOf(settings.fileSendIntervalMs));
        box.addView(etChunk);
        box.addView(etInterval);

        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.file_send_title, data.length))
                .setView(box)
                .setPositiveButton(getString(R.string.file_send_start), (dialog, which) -> {
                    int chunk = settings.fileSendChunkSize;
                    int interval = settings.fileSendIntervalMs;
                    try {
                        chunk = Integer.parseInt(etChunk.getText().toString());
                    } catch (Exception ignored) {
                    }
                    try {
                        interval = Integer.parseInt(etInterval.getText().toString());
                    } catch (Exception ignored) {
                    }
                    chunk = Math.max(1, Math.min(chunk, 4096));
                    interval = Math.max(0, Math.min(interval, 10_000));
                    settings.fileSendChunkSize = chunk;
                    settings.fileSendIntervalMs = interval;
                    saveSettings();
                    startFileSend(data, chunk, interval);
                })
                .setNegativeButton(getString(R.string.dialog_cancel), null)
                .show();
    }

    private void startFileSend(byte[] data, int chunk, int interval) {
        final SerialControl control = currentControl();
        if (control == null || !control.isOpen()) {
            Toast.makeText(this, R.string.port_not_open, Toast.LENGTH_SHORT).show();
            return;
        }
        final PortFragment fragment = currentFragment();
        fileSendCancelled = false;
        androidx.appcompat.app.AlertDialog progress = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.menu_send_file))
                .setMessage(getString(R.string.progress_format, 0, data.length))
                .setCancelable(false)
                .setNegativeButton(getString(R.string.dialog_cancel),
                        (dialog, which) -> fileSendCancelled = true)
                .create();
        progress.show();
        new Thread(() -> {
            int sent = 0;
            try {
                while (sent < data.length && !fileSendCancelled) {
                    int end = Math.min(sent + chunk, data.length);
                    control.send(Arrays.copyOfRange(data, sent, end));
                    sent = end;
                    if (interval > 0) {
                        Thread.sleep(interval);
                    }
                    int finalSent = sent;
                    runOnUiThread(() -> progress.setMessage(
                            getString(R.string.progress_format, finalSent, data.length)));
                }
            } catch (InterruptedException e) {
                fileSendCancelled = true;
            }
            final int done = sent;
            runOnUiThread(() -> {
                progress.dismiss();
                if (fragment != null && done > 0) {
                    // 大文件只记录前 64 字节 + 摘要，避免单条日志过大导致卡顿/OOM
                    int headLen = Math.min(done, 64);
                    byte[] summary;
                    if (done > headLen) {
                        byte[] head = Arrays.copyOfRange(data, 0, headLen);
                        byte[] tail = ("... [+ " + (done - headLen) + " bytes]").getBytes();
                        summary = new byte[head.length + tail.length];
                        System.arraycopy(head, 0, summary, 0, head.length);
                        System.arraycopy(tail, 0, summary, head.length, tail.length);
                    } else {
                        summary = Arrays.copyOfRange(data, 0, headLen);
                    }
                    fragment.logTx(summary);
                }
                Toast.makeText(this,
                        getString(fileSendCancelled ? R.string.send_cancelled : R.string.send_done),
                        Toast.LENGTH_SHORT).show();
            });
        }, "file-send").start();
    }

    //----------------------------------------------------
    // 保存日志（SAF）
    //----------------------------------------------------
    private void saveCurrentLog() {
        PortFragment fragment = currentFragment();
        if (fragment == null) {
            return;
        }
        String text = fragment.exportLogText();
        if (text.isEmpty()) {
            Toast.makeText(this, getString(R.string.log_empty), Toast.LENGTH_SHORT).show();
            return;
        }
        pendingLogText = text;
        String name = "serial_log_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date()) + ".txt";
        logSaver.launch(name);
    }

    private void onSaveLogTarget(@NonNull Uri uri) {
        try {
            OutputStream out = getContentResolver().openOutputStream(uri);
            if (out == null) {
                throw new IOException("无法创建文件");
            }
            out.write(pendingLogText != null ? pendingLogText.getBytes() : new byte[0]);
            out.close();
            Toast.makeText(this, getString(R.string.save_ok), Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, getString(R.string.save_failed), Toast.LENGTH_SHORT).show();
        }
        pendingLogText = null;
    }

    //----------------------------------------------------
    @Override
    protected void onPause() {
        saveSettings();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        for (SerialControl control : controls) {
            control.close();
        }
        saveSettings();
        try {
            unregisterReceiver(usbPermissionReceiver);
            unregisterReceiver(usbDetachReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        super.onDestroy();
    }
}
