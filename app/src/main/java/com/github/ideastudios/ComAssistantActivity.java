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
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.github.ideastudios.bean.AppSettings;
import com.github.ideastudios.databinding.ActivityMainBinding;
import com.github.ideastudios.serial.FileSendTask;
import com.github.ideastudios.serial.SerialConfig;
import com.github.ideastudios.serial.SerialControl;
import com.github.ideastudios.serial.SerialSession;
import com.github.ideastudios.serialport.SerialPortFinder;
import com.github.ideastudios.serialport.UsbSerialConnection;
import com.github.ideastudios.store.SettingsStore;
import com.github.ideastudios.ui.FileSendConfigDialogFragment;
import com.github.ideastudios.ui.FileSendDialogFragment;
import com.github.ideastudios.ui.PortFragment;
import com.github.ideastudios.ui.PortPagerAdapter;
import com.github.ideastudios.util.UpdateChecker;
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
    /** 会话层单例：串口连接/日志/未读计数跨转屏保留，UI 只负责渲染 */
    private SerialSession session;
    private final TextView[] tabTitles = new TextView[PortPagerAdapter.PORT_COUNT];
    private final TextView[] tabBadges = new TextView[PortPagerAdapter.PORT_COUNT];
    private final TextView[] tabDevices = new TextView[PortPagerAdapter.PORT_COUNT];

    private ActivityResultLauncher<String> filePicker;
    private ActivityResultLauncher<String> logSaver;
    private String pendingLogText;
    /** 应用级全屏开关：隐藏系统状态栏/导航栏（边缘滑动临时唤出），由菜单切换 */
    private boolean fullscreenMode;
    private MenuItem fullscreenMenuItem;
    /** 双击返回才退出应用：首次返回仅提示，2 秒内再按才退出，防止误触丢失日志 */
    private OnBackPressedCallback exitBackCallback;
    private long lastBackPressTime;
    /** 更新检查进行中标记：防止重复点击发起多个请求 */
    private boolean updateChecking;

    /** USB 权限请求结果回调 */
    public interface UsbPermissionCallback {
        void onResult(boolean granted);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new SettingsStore(this);
        settings = store.load();
        finder = new SerialPortFinder();
        session = SerialSession.get(getApplicationContext());

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
            // 全屏模式：顶部贴边，标题延伸进挖孔区（横屏左右侧的挖孔避让由根布局负责）
            int top = fullscreenMode ? 0 : bars.top;
            v.setPadding(v.getPaddingLeft(), top, v.getPaddingRight(), v.getPaddingBottom());
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
                    TextView device = custom.findViewById(R.id.textTabDevice);
                    title.setText(portLabel(position));
                    tabTitles[position] = title;
                    tabBadges[position] = badge;
                    tabDevices[position] = device;
                    tab.setCustomView(custom);
                    updateTabBadge(position);
                    updateTabDeviceInfo(position);
                }).attach();
        binding.tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(@NonNull TabLayout.Tab tab) {
                int pos = tab.getPosition();
                PortFragment fragment = (PortFragment) getSupportFragmentManager()
                        .findFragmentByTag("f" + pos);
                if (fragment != null) {
                    fragment.markSeen();
                } else {
                    session.markSeen(pos);
                }
                updateTabBadge(pos);
            }

            @Override
            public void onTabUnselected(@NonNull TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(@NonNull TabLayout.Tab tab) {
            }
        });

        registerUsbReceivers();

        if (savedInstanceState != null) {
            fullscreenMode = savedInstanceState.getBoolean("fullscreen", false);
        }

        // 双击返回才退出应用：单次返回只提示，避免误触退出丢日志
        exitBackCallback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                long now = System.currentTimeMillis();
                if (now - lastBackPressTime < 2000) {
                    finish();
                } else {
                    lastBackPressTime = now;
                    Toast.makeText(ComAssistantActivity.this,
                            getString(R.string.exit_confirm_hint), Toast.LENGTH_SHORT).show();
                }
            }
        };
        getOnBackPressedDispatcher().addCallback(this, exitBackCallback);
    }

    private String portLabel(int position) {
        return "COM " + (char) ('A' + position);
    }

    /** Tab 上的设备短名：USB 用设备名，板载取路径末段（如 ttyS1） */
    private String deviceShortName(int position) {
        SerialConfig c = settings.ports[position];
        if (c.driverType == SerialConfig.DRIVER_USB) {
            return c.usbDeviceName != null && !c.usbDeviceName.isEmpty()
                    ? c.usbDeviceName : c.devicePath;
        }
        String path = c.devicePath == null ? "" : c.devicePath;
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    /** 更新 Tab 第二行显示的绑定设备名（配置变更时由 PortFragment 调用） */
    public void updateTabDeviceInfo(int position) {
        TextView device = position >= 0 && position < tabDevices.length
                ? tabDevices[position] : null;
        if (device == null) {
            return;
        }
        String name = deviceShortName(position);
        if (name == null || name.isEmpty()) {
            device.setVisibility(View.GONE);
        } else {
            device.setVisibility(View.VISIBLE);
            device.setText(name);
        }
    }

    //----------------------------------------------------
    // 未读徽标
    //----------------------------------------------------
    @Override
    public void onUnreadChanged(int portIndex, int count) {
        if (portIndex < 0 || portIndex >= PortPagerAdapter.PORT_COUNT) {
            return;
        }
        updateTabBadge(portIndex);
    }

    private void updateTabBadge(int position) {
        TextView badge = tabBadges[position];
        if (badge == null) {
            return;
        }
        int count = session.getUnread(position);
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
        return session.getControl(index);
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
            for (int i = 0; i < PortPagerAdapter.PORT_COUNT; i++) {
                SerialControl control = session.getControl(i);
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
    // 菜单：全屏模式 / 发送文件 / 保存日志 / 清空日志 / 检查更新
    //----------------------------------------------------
    @Override
    public boolean onCreateOptionsMenu(@NonNull android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        fullscreenMenuItem = menu.findItem(R.id.action_fullscreen);
        updateFullscreenItemTitle();
        return true;
    }

    @SuppressLint("NonConstantResourceId")
    @Override
    public boolean onOptionsItemSelected(@NonNull android.view.MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_fullscreen) {
            fullscreenMode = !fullscreenMode;
            updateFullscreenItemTitle();
            applyFullscreen();
            return true;
        } else if (id == R.id.action_send_file) {
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
        } else if (id == R.id.action_check_update) {
            checkForUpdate();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    //----------------------------------------------------
    // 检查更新：后台拉取 feed 分支 latest.json，新版本弹窗由浏览器下载
    //----------------------------------------------------
    private void checkForUpdate() {
        if (updateChecking) {
            return;
        }
        updateChecking = true;
        Toast.makeText(this, getString(R.string.update_checking), Toast.LENGTH_SHORT).show();
        UpdateChecker.check(this, new UpdateChecker.Listener() {
            @Override
            public void onNewVersion(@NonNull UpdateChecker.ReleaseInfo release) {
                updateChecking = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                String current = UpdateChecker.currentVersion(ComAssistantActivity.this);
                new MaterialAlertDialogBuilder(ComAssistantActivity.this)
                        .setTitle(getString(R.string.update_found_title, release.version))
                        .setMessage(getString(R.string.update_found_message,
                                current, release.version))
                        .setPositiveButton(R.string.update_download,
                                (dialog, which) -> openDownloadPage(release.url))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }

            @Override
            public void onUpToDate() {
                updateChecking = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                Toast.makeText(ComAssistantActivity.this,
                        getString(R.string.update_latest), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(@NonNull String message) {
                updateChecking = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                Toast.makeText(ComAssistantActivity.this,
                        getString(R.string.update_check_failed), Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 用系统浏览器打开 APK 下载直链（安装交由用户手动完成） */
    private void openDownloadPage(@NonNull String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.update_no_browser),
                    Toast.LENGTH_SHORT).show();
        }
    }

    //----------------------------------------------------
    // 应用级全屏：隐藏系统栏（上/下边缘滑动临时唤出，松手自动隐藏），菜单切换
    //----------------------------------------------------
    private void applyFullscreen() {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (fullscreenMode) {
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsetsCompat.Type.systemBars());
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars());
        }
    }

    private void updateFullscreenItemTitle() {
        if (fullscreenMenuItem != null) {
            fullscreenMenuItem.setTitle(fullscreenMode
                    ? R.string.menu_exit_fullscreen : R.string.menu_fullscreen);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回到前台/重建后按全局开关恢复系统栏状态
        applyFullscreen();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("fullscreen", fullscreenMode);
    }

    @Nullable
    private PortFragment currentFragment() {
        int position = binding.viewPager.getCurrentItem();
        return (PortFragment) getSupportFragmentManager().findFragmentByTag("f" + position);
    }

    @Nullable
    private SerialControl currentControl() {
        return session.getControl(binding.viewPager.getCurrentItem());
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
        // 文件数据存静态暂存位（MB 级不能进 Bundle），由参数对话框消费
        FileSendConfigDialogFragment.setPendingData(data);
        FileSendConfigDialogFragment dialog = new FileSendConfigDialogFragment();
        Bundle args = new Bundle();
        args.putInt(FileSendConfigDialogFragment.ARG_CHUNK, settings.fileSendChunkSize);
        args.putInt(FileSendConfigDialogFragment.ARG_INTERVAL, settings.fileSendIntervalMs);
        dialog.setArguments(args);
        dialog.show(getSupportFragmentManager(), "file_send_config");
    }

    /** 由 FileSendConfigDialogFragment 在参数确认后调用 */
    public void startFileSend(byte[] data, int chunk, int interval) {
        SerialControl control = currentControl();
        if (control == null || !control.isOpen()) {
            Toast.makeText(this, R.string.port_not_open, Toast.LENGTH_SHORT).show();
            return;
        }
        // 发送任务独立于 Activity 生命周期：转屏时发送继续、进度对话框自动恢复
        FileSendTask task = FileSendTask.start(this, control,
                binding.viewPager.getCurrentItem(), data, chunk, interval);
        if (task == null) {
            Toast.makeText(this, getString(R.string.file_send_busy), Toast.LENGTH_SHORT).show();
            return;
        }
        new FileSendDialogFragment().show(getSupportFragmentManager(), "file_send");
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
        // 转屏重建（isFinishing=false）时保持串口连接，由会话层持有；
        // 真正退出应用（isFinishing=true）时关闭并释放端口，避免后台进程占用设备
        if (isFinishing()) {
            FileSendConfigDialogFragment.clearPendingData();
            for (int i = 0; i < PortPagerAdapter.PORT_COUNT; i++) {
                session.getControl(i).close();
            }
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
