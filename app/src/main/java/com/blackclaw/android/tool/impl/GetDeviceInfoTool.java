package com.blackclaw.android.tool.impl;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;

import com.blackclaw.android.ClawApplication;
import com.blackclaw.android.tool.BaseTool;
import com.blackclaw.android.tool.ToolParameter;
import com.blackclaw.android.tool.ToolResult;
import com.blackclaw.android.utils.XLog;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * Direct system queries without UI navigation.
 * Supports: battery, wifi, storage, bluetooth, screen.
 */
public class GetDeviceInfoTool extends BaseTool {

    private static final String TAG = "GetDeviceInfoTool";

    @Override
    public String getName() { return "get_device_info"; }

    @Override
    public String getDisplayName() { return "Device Info"; }

    @Override
    public String getDescriptionEN() {
        return "Get device system info directly without navigating Settings UI. "
                + "Categories: battery, wifi, storage, bluetooth, screen, device, time. "
                + "Much faster than opening Settings — use this first for system queries.";
    }

    @Override
    public String getDescriptionCN() {
        return "Get device system info directly without navigating Settings UI. "
                + "Categories: battery, wifi, storage, bluetooth, screen, device, time.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("category", "string",
                        "Info category: 'battery', 'wifi', 'storage', 'bluetooth', or 'screen'", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String category = requireString(params, "category").toLowerCase().trim();
        Context ctx = ClawApplication.Companion.getInstance();

        try {
            switch (category) {
                case "battery": return getBatteryInfo(ctx);
                case "wifi": return getWifiInfo(ctx);
                case "storage": return getStorageInfo();
                case "bluetooth": return getBluetoothInfo();
                case "screen": return getScreenInfo(ctx);
                case "device": return getDeviceDetails();
                case "time": return getCurrentTime();
                default:
                    return ToolResult.error("Unknown category: " + category
                            + ". Use: battery, wifi, storage, bluetooth, screen, device, time");
            }
        } catch (Exception e) {
            XLog.e(TAG, "Failed to get " + category + " info", e);
            return ToolResult.error("Failed to get " + category + " info: " + e.getMessage());
        }
    }

    private ToolResult getBatteryInfo(Context ctx) {
        BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
        int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        int status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;

        // Get battery temperature from sticky broadcast
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryIntent = ctx.registerReceiver(null, filter);
        int tempRaw = batteryIntent != null ? batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) : 0;
        float tempC = tempRaw / 10.0f;

        StringBuilder sb = new StringBuilder();
        sb.append("Battery: ").append(level).append("%");
        sb.append(charging ? ", charging" : ", not charging");
        if (tempC > 0) sb.append(", ").append(String.format("%.1f°C", tempC));

        XLog.d(TAG, "Battery info: " + sb);
        return ToolResult.success(sb.toString());
    }

    private ToolResult getWifiInfo(Context ctx) {
        WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null || !wm.isWifiEnabled()) {
            return ToolResult.success("WiFi: disabled");
        }

        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network activeNetwork = cm != null ? cm.getActiveNetwork() : null;
        NetworkCapabilities caps = activeNetwork != null ? cm.getNetworkCapabilities(activeNetwork) : null;

        WifiInfo info = wm.getConnectionInfo();
        if (info == null || info.getNetworkId() == -1) {
            return ToolResult.success("WiFi: enabled but not connected");
        }

        String ssid = info.getSSID();
        if (ssid != null) ssid = ssid.replace("\"", "");
        int rssi = info.getRssi();
        int freq = info.getFrequency();
        int speed = info.getLinkSpeed();
        String band = freq > 4900 ? "5GHz" : "2.4GHz";

        StringBuilder sb = new StringBuilder();
        sb.append("WiFi: connected to '").append(ssid).append("'");
        sb.append(", ").append(band);
        sb.append(", signal ").append(rssi).append("dBm");
        sb.append(", ").append(speed).append("Mbps");

        XLog.d(TAG, "WiFi info: " + sb);
        return ToolResult.success(sb.toString());
    }

    private ToolResult getStorageInfo() {
        StatFs stat = new StatFs(Environment.getDataDirectory().getAbsolutePath());
        long totalBytes = stat.getTotalBytes();
        long freeBytes = stat.getAvailableBytes();
        long usedBytes = totalBytes - freeBytes;

        String total = formatBytes(totalBytes);
        String used = formatBytes(usedBytes);
        String free = formatBytes(freeBytes);
        int pct = (int) (usedBytes * 100 / totalBytes);

        String result = "Storage: " + used + " used of " + total + " (" + pct + "%), " + free + " free";
        XLog.d(TAG, "Storage info: " + result);
        return ToolResult.success(result);
    }

    private ToolResult getBluetoothInfo() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            return ToolResult.success("Bluetooth: not available on this device");
        }
        if (!adapter.isEnabled()) {
            return ToolResult.success("Bluetooth: disabled");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Bluetooth: enabled");

        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null && !bonded.isEmpty()) {
                sb.append(", paired devices: ");
                int i = 0;
                for (BluetoothDevice device : bonded) {
                    if (i > 0) sb.append(", ");
                    sb.append(device.getName() != null ? device.getName() : device.getAddress());
                    i++;
                    if (i >= 5) { sb.append("..."); break; }
                }
            }
        } catch (SecurityException e) {
            sb.append(" (cannot list devices — permission denied)");
        }

        XLog.d(TAG, "Bluetooth info: " + sb);
        return ToolResult.success(sb.toString());
    }

    private ToolResult getScreenInfo(Context ctx) {
        StringBuilder sb = new StringBuilder();

        // Brightness
        try {
            int brightness = Settings.System.getInt(ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            int maxBrightness = 255;
            int pct = brightness * 100 / maxBrightness;
            sb.append("Brightness: ").append(pct).append("%");
        } catch (Settings.SettingNotFoundException e) {
            sb.append("Brightness: unknown");
        }

        // Dark mode
        int nightMode = ctx.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        boolean isDark = nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        sb.append(", Dark mode: ").append(isDark ? "ON" : "OFF");

        // Auto-brightness
        try {
            int autoBrightness = Settings.System.getInt(ctx.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE);
            sb.append(", Auto-brightness: ").append(autoBrightness == 1 ? "ON" : "OFF");
        } catch (Settings.SettingNotFoundException ignored) {}

        XLog.d(TAG, "Screen info: " + sb);
        return ToolResult.success(sb.toString());
    }

    private ToolResult getDeviceDetails() {
        StringBuilder sb = new StringBuilder();
        sb.append("Android ").append(android.os.Build.VERSION.RELEASE);
        sb.append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")");
        sb.append(", Model: ").append(android.os.Build.MANUFACTURER).append(" ").append(android.os.Build.MODEL);
        sb.append(", Build: ").append(android.os.Build.DISPLAY);
        String security = android.os.Build.VERSION.SECURITY_PATCH;
        if (security != null && !security.isEmpty()) {
            sb.append(", Security patch: ").append(security);
        }
        XLog.d(TAG, "Device info: " + sb);
        return ToolResult.success(sb.toString());
    }

    private ToolResult getCurrentTime() {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.getDefault());
        String localTime = sdf.format(new java.util.Date());
        java.util.TimeZone tz = java.util.TimeZone.getDefault();
        String result = "Current time: " + localTime + " (timezone: " + tz.getID()
                + ", UTC offset: " + (tz.getRawOffset() / 3600000) + "h)";
        XLog.d(TAG, "Time info: " + result);
        return ToolResult.success(result);
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1_000_000_000L) {
            return String.format("%.1f GB", bytes / 1_000_000_000.0);
        } else {
            return String.format("%.0f MB", bytes / 1_000_000.0);
        }
    }
}
