package com.andrerinas.openheadunit

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.aap.AapProjectionActivity
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.usb.UsbAccessoryMode
import com.andrerinas.openheadunit.connection.usb.UsbDeviceCompat
import com.andrerinas.openheadunit.connection.usb.UsbReceiver
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ExternalBtTransportPolicy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeAaHandshakeManager
import com.andrerinas.openheadunit.utils.AppPermissions
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.utils.VpnControl
import kotlin.concurrent.thread

/**
 * XiaoSha MultiPlay's own entry to Android Auto: its home card starts Self Mode, wireless and USB
 * here, the way Open Headunit's HomeFragment buttons do, without opening Open Headunit's screens.
 */
object AndroidAutoLauncher {
    fun isConnected(context: Context): Boolean = App.provide(context).commManager.isConnected

    fun openProjection(activity: Activity) {
        activity.startActivity(Intent(activity, AapProjectionActivity::class.java).putExtra(AapProjectionActivity.EXTRA_FOCUS, true))
    }

    /** Open Headunit's own settings, for its advanced Android Auto options. */
    fun openSettings(activity: Activity) {
        activity.startActivity(Intent().setClassName(activity, "com.andrerinas.openheadunit.main.SettingsActivity"))
    }

    fun startSelfMode(activity: Activity) {
        if (isConnected(activity)) { openProjection(activity); return }
        if (!AppPermissions.isOverlayGranted(activity)) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.overlay_permission_title)
                .setMessage(R.string.self_mode_overlay_permission_description)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        // Self Mode needs a network; offline it holds a local VPN slot, which needs one-time consent.
        val connectivity = activity.getSystemService(ConnectivityManager::class.java)
        if (connectivity.activeNetwork == null && VpnControl.isVpnAvailable()) {
            val consent = VpnControl.consentIntent(activity)
            if (consent != null) {
                activity.startActivity(consent)
                Toast.makeText(activity, R.string.self_mode_overlay_permission_description, Toast.LENGTH_LONG).show()
                return
            }
            VpnControl.startVpn(activity)
        }
        start(activity, AapService.ACTION_START_SELF_MODE)
    }

    fun connectUsb(activity: Activity) {
        if (isConnected(activity)) { openProjection(activity); return }
        AapService.instance?.liftUsbCancel("the XiaoSha MultiPlay USB button was pressed")
        val usbManager = activity.getSystemService(UsbManager::class.java)
        val devices = usbManager.deviceList.values.filter { UsbDeviceCompat.isConnectable(activity, it) }
        when (devices.size) {
            0 -> Toast.makeText(activity, R.string.no_android_auto_device_connected, Toast.LENGTH_LONG).show()
            1 -> connectUsbDevice(activity, usbManager, UsbDeviceCompat(devices[0]))
            else -> AlertDialog.Builder(activity)
                .setItems(devices.map { UsbDeviceCompat(it).uniqueName }.toTypedArray()) { _, index ->
                    connectUsbDevice(activity, usbManager, UsbDeviceCompat(devices[index]))
                }
                .show()
        }
    }

    private fun connectUsbDevice(activity: Activity, usbManager: UsbManager, device: UsbDeviceCompat) {
        if (device.isInAccessoryMode) {
            start(activity, AapService.ACTION_CHECK_USB)
        } else if (usbManager.hasPermission(device.wrappedDevice)) {
            val useLibusb = App.provide(activity).settings.useLibusb
            thread(name = "multiplay-aa-usb-switch") {
                val switched = UsbAccessoryMode(usbManager).connectAndSwitch(device.wrappedDevice, useLibusb)
                activity.runOnUiThread {
                    Toast.makeText(activity, if (switched) R.string.switching_to_android_auto else R.string.switch_failed, Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            ContextCompat.startForegroundService(activity, Intent(activity, AapService::class.java))
            usbManager.requestPermission(device.wrappedDevice, UsbReceiver.createPermissionPendingIntent(activity))
        }
    }

    fun connectWireless(activity: Activity) {
        if (isConnected(activity)) { openProjection(activity); return }
        when (App.provide(activity).settings.wifiConnectionMode) {
            WifiLauncherMode.NATIVE -> connectNative(activity)
            WifiLauncherMode.MANUAL -> openSettings(activity)
            else -> {
                if (!AapService.scanningState.value) start(activity, AapService.ACTION_START_WIRELESS_SCAN)
                Toast.makeText(activity, R.string.searching_phone, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Native Android Auto wireless: the phone is woken over Bluetooth, then joins over Wi-Fi.
    private fun connectNative(activity: Activity) {
        val route = NativeAaHandshakeManager.wifiButtonRoute(activity)
        if (route == ExternalBtTransportPolicy.WifiButton.MODULE) {
            start(activity, AapService.ACTION_NATIVE_AA_POKE); return
        }
        if (route == ExternalBtTransportPolicy.WifiButton.REFUSED) {
            Toast.makeText(activity, R.string.native_aa_poke_not_running, Toast.LENGTH_LONG).show(); return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 0)
            return
        }
        val settings = App.provide(activity).settings
        val phones = BluetoothHelper.driverCandidates(activity, settings.nativePreferredDeviceMac, settings.lastConnectedNativeMac).offered
        fun poke(mac: String) {
            start(activity, AapService.ACTION_NATIVE_AA_POKE) { putExtra(AapService.EXTRA_MAC, mac) }
            Toast.makeText(activity, R.string.searching_phone, Toast.LENGTH_SHORT).show()
        }
        when (phones.size) {
            0 -> Toast.makeText(activity, R.string.no_paired_bt_devices, Toast.LENGTH_LONG).show()
            1 -> poke(phones[0].address)
            else -> AlertDialog.Builder(activity)
                .setItems(phones.map { runCatching { it.name }.getOrNull() ?: it.address }.toTypedArray()) { _, index -> poke(phones[index].address) }
                .show()
        }
    }

    private fun start(context: Context, action: String, extras: Intent.() -> Unit = {}) {
        ContextCompat.startForegroundService(context, Intent(context, AapService::class.java).apply { this.action = action; extras() })
    }
}
