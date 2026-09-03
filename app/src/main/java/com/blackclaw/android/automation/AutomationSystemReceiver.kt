package com.blackclaw.android.automation

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.BatteryManager
import android.os.PowerManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.telephony.TelephonyManager
import android.provider.Settings
import android.provider.Telephony
import com.blackclaw.android.utils.XLog

/** Small system-event adapter; profiles do the matching and execution locally. */
class AutomationSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val app = context.applicationContext
        runCatching {
            when (action) {
                Intent.ACTION_BOOT_COMPLETED,
                "android.intent.action.QUICKBOOT_POWERON" ->
                    AutomationProfileEngine.emitSystemEvent(app, AutomationProfileStore.TriggerType.BOOT)

                Intent.ACTION_SCREEN_ON ->
                    AutomationProfileEngine.emitSystemEvent(app, AutomationProfileStore.TriggerType.SCREEN, mapOf("state" to "on"))
                Intent.ACTION_SCREEN_OFF ->
                    AutomationProfileEngine.emitSystemEvent(app, AutomationProfileStore.TriggerType.SCREEN, mapOf("state" to "off"))
                Intent.ACTION_USER_PRESENT ->
                    AutomationProfileEngine.emitSystemEvent(app, AutomationProfileStore.TriggerType.SCREEN, mapOf("state" to "unlocked"))

                Intent.ACTION_POWER_CONNECTED -> {
                    AutomationProfileEngine.emitSystemEvent(app, AutomationProfileStore.TriggerType.CHARGING, mapOf("charging" to "true"))
                    emitBattery(app, intent)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    AutomationProfileEngine.emitSystemEvent(app, AutomationProfileStore.TriggerType.CHARGING, mapOf("charging" to "false"))
                    emitBattery(app, intent)
                }
                Intent.ACTION_BATTERY_LOW, Intent.ACTION_BATTERY_OKAY,
                Intent.ACTION_BATTERY_CHANGED -> emitBattery(app, intent)

                Intent.ACTION_HEADSET_PLUG ->
                    AutomationProfileEngine.emitSystemEvent(app, AutomationProfileStore.TriggerType.HEADSET, mapOf(
                        "connected" to (intent.getIntExtra("state", 0) == 1).toString(),
                        "name" to intent.getStringExtra("name").orEmpty(),
                    ))
                TelephonyManager.ACTION_PHONE_STATE_CHANGED ->
                    AutomationProfileEngine.emitSystemEvent(app, AutomationProfileStore.TriggerType.CALL_STATE, mapOf(
                        "state" to when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                            TelephonyManager.EXTRA_STATE_RINGING -> "ringing"
                            TelephonyManager.EXTRA_STATE_OFFHOOK -> "offhook"
                            else -> "idle"
                        },
                        "number" to intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty(),
                    ))
                Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> {
                    val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                    val body = messages.joinToString("") { it.messageBody.orEmpty() }
                    val sender = messages.firstOrNull()?.originatingAddress.orEmpty()
                    AutomationProfileEngine.emitSystemEvent(app, AutomationProfileStore.TriggerType.SMS_RECEIVED, mapOf(
                        "sender" to sender, "body" to body,
                    ))
                }
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                    AutomationProfileEngine.emitSystemEvent(app, AutomationProfileStore.TriggerType.BLUETOOTH, mapOf(
                        "connected" to (action == BluetoothDevice.ACTION_ACL_CONNECTED).toString(),
                        "name" to bluetoothDeviceName(app, intent),
                    ))
                WifiManager.NETWORK_STATE_CHANGED_ACTION,
                ConnectivityManager.CONNECTIVITY_ACTION -> emitConnectivity(app)
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    val enabled = Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
                    AutomationProfileEngine.emitSystemEvent(app, AutomationProfileStore.TriggerType.AIRPLANE_MODE,
                        mapOf("value" to enabled.toString()))
                    emitConnectivity(app)
                }
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                    AutomationProfileEngine.emitSystemEvent(app, AutomationProfileStore.TriggerType.POWER_SAVE,
                        mapOf("value" to pm.isPowerSaveMode.toString()))
                }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                    AutomationProfileEngine.emitSystemEvent(app, AutomationProfileStore.TriggerType.DEVICE_IDLE,
                        mapOf("value" to pm.isDeviceIdleMode.toString()))
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED -> emitUsb(app, intent)
                Intent.ACTION_DEVICE_STORAGE_LOW -> AutomationProfileEngine.emitSystemEvent(app,
                    AutomationProfileStore.TriggerType.STORAGE, mapOf("state" to "low"))
                Intent.ACTION_DEVICE_STORAGE_OK -> AutomationProfileEngine.emitSystemEvent(app,
                    AutomationProfileStore.TriggerType.STORAGE, mapOf("state" to "ok"))
                Intent.ACTION_TIMEZONE_CHANGED -> AutomationProfileEngine.emitSystemEvent(app,
                    AutomationProfileStore.TriggerType.TIMEZONE, mapOf("id" to java.util.TimeZone.getDefault().id))
                Intent.ACTION_LOCALE_CHANGED -> AutomationProfileEngine.emitSystemEvent(app,
                    AutomationProfileStore.TriggerType.LOCALE, mapOf("tag" to java.util.Locale.getDefault().toLanguageTag()))
            }
        }.onFailure { XLog.w("AutomationSystemReceiver", "System event failed: $action", it) }
    }

    private fun emitBattery(context: Context, intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val charging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1).let {
            it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
        }
        if (level >= 0) AutomationProfileEngine.emitSystemEvent(
            context, AutomationProfileStore.TriggerType.BATTERY,
            mapOf("level" to ((level * 100) / scale).toString(), "charging" to charging.toString()),
        )
    }

    private fun emitConnectivity(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val transport = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
            else -> "none"
        }
        AutomationProfileEngine.emitSystemEvent(context, AutomationProfileStore.TriggerType.CONNECTIVITY, mapOf(
            "state" to if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) "online" else "offline",
            "transport" to transport,
        ))
        val wifiConnected = transport == "wifi"
        val ssid = if (wifiConnected) runCatching {
            SavedPlaceStore.normalizeSsid(
                (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo?.ssid.orEmpty()
            )
        }.getOrDefault("") else ""
        AutomationProfileEngine.emitSystemEvent(context, AutomationProfileStore.TriggerType.WIFI, mapOf(
            "connected" to wifiConnected.toString(),
            "ssid" to ssid,
        ))
    }

    private fun emitUsb(context: Context, intent: Intent) {
        val connected = intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        }
        AutomationProfileEngine.emitSystemEvent(context, AutomationProfileStore.TriggerType.USB, mapOf(
            "connected" to connected.toString(),
            "vendor_id" to (device?.vendorId?.toString().orEmpty()),
            "product_id" to (device?.productId?.toString().orEmpty()),
            "name" to device?.deviceName.orEmpty(),
        ))
    }

    /**
     * Device names require BLUETOOTH_CONNECT on Android 12+. Broadcasts can
     * still arrive while the user has revoked that permission, so keep the
     * automation event useful without risking a SecurityException.
     */
    private fun bluetoothDeviceName(context: Context, intent: Intent): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return ""

        return runCatching {
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)?.name.orEmpty()
        }.getOrDefault("")
    }
}
