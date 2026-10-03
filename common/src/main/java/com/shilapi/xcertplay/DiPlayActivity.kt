// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.andrerinas.openheadunit.AndroidAutoLauncher
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.ui.GroupedPage
import com.shilapi.xcertplay.ui.HomeActions
import com.shilapi.xcertplay.ui.HomeModel
import com.shilapi.xcertplay.ui.HomePage
import com.shilapi.xcertplay.ui.MultiPlayScreen
import com.shilapi.xcertplay.ui.MultiPlayTheme
import com.shilapi.xcertplay.ui.SettingGroup
import com.shilapi.xcertplay.ui.SettingItem
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** DiAuto's visual language, with a connection flow for an independent CarPlay receiver. */
class DiPlayActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var page by mutableStateOf("home")
    // Bumped whenever saved settings change, so the Compose screens read them again.
    private var uiVersion by mutableIntStateOf(0)
    private var carPlayStatus by mutableStateOf("")
    private var carPlayRunning by mutableStateOf(false)
    private var pendingCarHotspotSetup = false
    private var setupError: String? = null
    private var pendingWireless = false
    private var initialLaunch = true
    private var notificationTransport = true
    private var exportInProgress by mutableStateOf(false)
    private var navigationStreamType = 14
    private var testToneTrack: AudioTrack? = null
    private var toneStop: Runnable? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        connect(notificationTransport)
    }
    private val tick = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 1000) }
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choosePhone() else permissionHelp(getString(R.string.nearby_devices), getString(R.string.allow_nearby_devices_so_diplay_can_connect_to_your_paired))
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPreciseLocation()) return@registerForActivityResult reconnectForLocation()
        AirPlayPersistence.saveLocationReportingEnabled(this, false)
        render()
        permissionHelp(getString(R.string.location), getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p))
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }

    private var languagePreferenceAtCreate = AppLocale.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        languagePreferenceAtCreate = AppLocale.preference(this)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = BG; window.navigationBarColor = BG
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            hide(WindowInsetsCompat.Type.statusBars())
        }
        setupError = runCatching { DiPlayBootstrap.ensure(this) }.exceptionOrNull()?.let {
            android.util.Log.e("DiPlaySetup", "CarPlay authentication could not be loaded", it)
            getString(R.string.setup_error_auth)
        }
        pendingCarHotspotSetup = savedInstanceState?.getBoolean("pending_car_hotspot") ?: false
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        showContent()
        handleWirelessRecovery()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != "home") { page = "home"; render() }
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        page = intent.getStringExtra("page") ?: "home"; render()
        handleWirelessRecovery()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("page", page); outState.putBoolean("pending_car_hotspot", pendingCarHotspotSetup); super.onSaveInstanceState(outState) }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); render() }
    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < 33 && AppLocale.preference(this) != languagePreferenceAtCreate) {
            recreate()
            return
        }
        handler.removeCallbacks(tick); handler.post(tick)
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        if (!initialLaunch && (page == "home" || page == "settings" || page == "connection")) render()
        if (initialLaunch) {
            initialLaunch = false
            // Auto-connect repeats whatever connected last: CarPlay over its transport, or Android Auto.
            if (!CarPlayBackgroundSession.hasSession() && DiPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null) {
                if (DiPlayPreferences.lastProjection(this) == DiPlayPreferences.ANDROID_AUTO) {
                    handler.post { connectAndroidAuto(DiPlayPreferences.lastAndroidAutoMethod(this)) }
                } else if (setupError == null) {
                    handler.post { connect(AirPlayPersistence.loadWirelessEnabled(this)) }
                }
            }
        }
    }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

    private fun render() { uiVersion++ }

    private fun showContent() = setContent {
        MultiPlayTheme {
            uiVersion // read so a bump recomposes with fresh preferences
            MultiPlayScreen(
                // Sub-pages name themselves in the header; only home carries the app name.
                title = getString(when (page) {
                    "settings" -> R.string.settings
                    "connection" -> R.string.connection_setup
                    "about" -> R.string.about
                    else -> R.string.diplay
                }),
                home = page == "home",
                onBack = { page = if (page == "connection" || page == "about") "settings" else "home" },
                onSettings = { page = "settings" },
                onExit = { exitApp() },
            ) {
                when (page) {
                    "settings" -> GroupedPage(null, settingsGroups())
                    "connection" -> GroupedPage(getString(R.string.set_up_once_your_details_stay_saved_for_the_next_drive_cha), connectionGroups())
                    "about" -> GroupedPage(getString(R.string.carplay_at_home_in_your_car), aboutGroups())
                    else -> HomePage(homeModel(), homeActions)
                }
            }
        }
    }

    /** Ends CarPlay, stops the background services and closes the app process, so nothing keeps running. */
    private fun exitApp() {
        CarPlayBackgroundSession.stop {
            runOnUiThread {
                stopService(Intent(this, DiPlaySessionService::class.java))
                stopService(Intent(this, com.shilapi.xcertplay.network.CarPlayVpnService::class.java))
                finishAndRemoveTask()
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    private fun aaMethodLabel(method: String) = getString(when (method) {
        DiPlayPreferences.AA_USB -> R.string.method_usb
        DiPlayPreferences.AA_SELF_MODE -> R.string.method_self_mode
        else -> R.string.method_wireless
    })

    private fun connectAndroidAuto(method: String) {
        DiPlayPreferences.saveLastProjection(this, DiPlayPreferences.ANDROID_AUTO)
        DiPlayPreferences.saveLastAndroidAutoMethod(this, method)
        when (method) {
            DiPlayPreferences.AA_USB -> AndroidAutoLauncher.connectUsb(this)
            DiPlayPreferences.AA_SELF_MODE -> AndroidAutoLauncher.startSelfMode(this)
            else -> AndroidAutoLauncher.connectWireless(this)
        }
    }

    private val aaMethods = listOf(DiPlayPreferences.AA_WIRELESS, DiPlayPreferences.AA_USB, DiPlayPreferences.AA_SELF_MODE)

    private fun homeModel(): HomeModel {
        val wireless = AirPlayPersistence.loadWirelessEnabled(this)
        val notice = when {
            wireless && AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL && storedSsid().isBlank() ->
                getString(R.string.hotspot_not_set) to { page = "connection" }
            wireless && carHotspotOff() -> getString(R.string.msg_car_hotspot_off, storedSsid()) to { openCarWifiSettings() }
            else -> null
        }
        return HomeModel(
            carPlayStatus = carPlayStatus.ifEmpty { getString(R.string.ready_when_you_are) },
            carPlayRunning = carPlayRunning,
            carPlayWireless = wireless,
            carPlayEnabled = setupError == null,
            carPlayNotice = notice,
            androidAutoConnected = runCatching { AndroidAutoLauncher.isConnected(this) }.getOrDefault(false),
            androidAutoMethod = aaMethods.indexOf(DiPlayPreferences.lastAndroidAutoMethod(this)).coerceAtLeast(0),
            setupError = setupError?.let { it to { page = "settings" } },
            version = version(),
        )
    }

    private val homeActions by lazy {
        HomeActions(
            carPlayConnect = { if (CarPlayBackgroundSession.hasSession()) openProjection() else connect(AirPlayPersistence.loadWirelessEnabled(this)) },
            carPlayMethod = { wireless -> connect(wireless) },
            carPlayDisconnect = { CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus() } } },
            androidAutoConnect = {
                if (runCatching { AndroidAutoLauncher.isConnected(this) }.getOrDefault(false)) AndroidAutoLauncher.openProjection(this)
                else connectAndroidAuto(DiPlayPreferences.lastAndroidAutoMethod(this))
            },
            androidAutoMethod = { index -> connectAndroidAuto(aaMethods[index]); render() },
        )
    }

    private fun toggleItem(title: Int, description: Int?, value: Boolean, save: (Boolean) -> Unit) =
        SettingItem.Toggle(getString(title), description?.let(::getString), value) { save(it); render() }

    /** A row showing the current choice; a tap opens the choices, and a running session reconnects to apply it. */
    private fun choiceItem(title: String, options: List<String>, current: Int, reconnects: Boolean = true, save: (Int) -> Unit) =
        SettingItem.Link(title, options.getOrNull(current)) {
            var pending = current
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), current) { _, index -> pending = index }
                .setPositiveButton(getString(if (reconnects && CarPlayBackgroundSession.hasSession()) R.string.apply_and_reconnect else R.string.save)) { _, _ ->
                    if (pending != current) {
                        save(pending)
                        render()
                        if (reconnects && CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
                    }
                }.setNegativeButton(getString(R.string.cancel), null).show()
        }

    private fun channelItem(title: Int, navigation: Boolean): SettingItem {
        val current = if (navigation) AirPlayPersistence.loadNavigationAudioChannel(this) else AirPlayPersistence.loadMediaAudioChannel(this)
        return SettingItem.Link(getString(title), channelLabel(current)) {
            showChannelDialog(getString(title), current, navigation) { value ->
                if (value == current) return@showChannelDialog
                if (navigation) AirPlayPersistence.saveNavigationAudioChannel(this, value) else AirPlayPersistence.saveMediaAudioChannel(this, value)
                render()
                if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
            }
        }
    }

    private fun settingsGroups(): List<SettingGroup> {
        val sizes = com.shilapi.xcertplay.airplay.CarPlaySize.entries
        val size = com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(this))
        val buffers = com.shilapi.xcertplay.media.MediaAudioBuffer.presets
        return listOf(
            SettingGroup(getString(R.string.tab_connection), listOf(
                SettingItem.Link(getString(R.string.connection_setup), wirelessModeLabel()) { page = "connection" },
                SettingItem.Link(getString(R.string.choose_iphone), phoneLabel()) { choosePhone() },
                toggleItem(R.string.connect_when_diplay_opens, R.string.use_your_last_connection_type_and_selected_iphone, DiPlayPreferences.autoConnect(this)) { DiPlayPreferences.saveAutoConnect(this, it) },
                toggleItem(R.string.exit_when_disconnected, R.string.exit_when_disconnected_description, AirPlayPersistence.loadExitWhenDisconnected(this)) { AirPlayPersistence.saveExitWhenDisconnected(this, it) },
                toggleItem(R.string.report_location_to_iphone, R.string.sends_precise_android_location_as_carplay_gps_data_when_th, AirPlayPersistence.loadLocationReportingEnabled(this)) {
                    AirPlayPersistence.saveLocationReportingEnabled(this, it)
                    if (it && !hasPreciseLocation()) {
                        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    } else reconnectForLocation()
                },
            )),
            SettingGroup(getString(R.string.tab_display), listOf(
                choiceItem(getString(R.string.carplay_size), sizes.map { it.localizedLabel(this) }, sizes.indexOf(size)) {
                    AirPlayPersistence.saveWidthPhysicalMm(this, sizes[it].widthMillimeters)
                },
                choiceItem(getString(R.string.day_night_mode), listOf(getString(R.string.day_night_auto), getString(R.string.day_night_day), getString(R.string.day_night_night)),
                    AirPlayPersistence.loadDayNightMode(this), reconnects = false) { AirPlayPersistence.saveDayNightMode(this, it) },
                choiceItem(getString(R.string.resolution), listOf(getString(R.string.resolution_native), getString(R.string.s_80_lighter_load), getString(R.string.s_60_lightest_load)),
                    listOf(10, 8, 6).indexOf(AirPlayPersistence.loadDisplayScaleTenths(this)).coerceAtLeast(0)) { AirPlayPersistence.saveDisplayScaleTenths(this, listOf(10, 8, 6)[it]) },
                choiceItem(getString(R.string.frame_rate), listOf(getString(R.string.s_30_fps_lighter_load), getString(R.string.s_60_fps_smoother_motion)),
                    if (AirPlayPersistence.loadFps(this) == 60) 1 else 0) { AirPlayPersistence.saveFps(this, if (it == 1) 60 else 30) },
                toggleItem(R.string.efficient_video, R.string.use_hevc_leave_off_for_the_widest_head_unit_compatibility, AirPlayPersistence.loadHevcEnabled(this)) { AirPlayPersistence.saveHevcEnabled(this, it) },
                toggleItem(R.string.right_hand_drive, R.string.place_carplay_s_controls_closer_to_the_driver, AirPlayPersistence.loadRightHandDrive(this)) { AirPlayPersistence.saveRightHandDrive(this, it) },
                toggleItem(R.string.full_screen, R.string.hide_the_car_s_system_bars_while_carplay_is_open, AirPlayPersistence.loadHideTopBar(this) && AirPlayPersistence.loadHideBottomBar(this)) {
                    AirPlayPersistence.saveHideTopBar(this, it); AirPlayPersistence.saveHideBottomBar(this, it)
                },
                toggleItem(R.string.video_while_parked, R.string.video_while_parked_description, AirPlayPersistence.loadVideoWhileParked(this)) {
                    AirPlayPersistence.saveVideoWhileParked(this, it)
                    // The iPhone learns about video in car when CarPlay connects; switching off applies at once.
                    if (it && CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
                },
            )),
            SettingGroup(getString(R.string.tab_audio), listOfNotNull(
                toggleItem(R.string.contrib_audio_home_toggle_audio_focus, R.string.contrib_audio_home_toggle_audio_focus_desc, AirPlayPersistence.loadAudioFocusEnabled(this)) { AirPlayPersistence.saveAudioFocusEnabled(this, it) },
                if (resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)) {
                    toggleItem(R.string.advanced_audio_channel_mapping, R.string.use_usage_content_type_routing_instead_of_stream_type, AirPlayPersistence.loadAdvancedAudioChannelMapping(this)) { AirPlayPersistence.saveAdvancedAudioChannelMapping(this, it) }
                } else null,
                choiceItem(getString(R.string.music_buffer), listOf(getString(R.string.s_300_ms_default), getString(R.string.s_500_ms), getString(R.string.s_1000_ms_most_stable)),
                    buffers.indexOf(AirPlayPersistence.loadMediaBufferMillis(this)).coerceAtLeast(0)) { AirPlayPersistence.saveMediaBufferMillis(this, buffers[it]) },
                channelItem(R.string.contrib_audio_home_media_channel_label, navigation = false),
                channelItem(R.string.contrib_audio_home_nav_channel_label, navigation = true),
                SettingItem.Note(getString(R.string.contrib_audio_home_nav_channel_note)),
            )),
            SettingGroup(getString(R.string.android_auto), listOf(
                SettingItem.Link(getString(R.string.android_auto_settings), null) { AndroidAutoLauncher.openSettings(this) },
            )),
            SettingGroup(getString(R.string.tab_advanced), listOf(
                SettingItem.Link(getString(R.string.language_app_language), AppLocale.displayName(this, AppLocale.preference(this))) { AppLocale.showPicker(this) },
                SettingItem.Link(getString(R.string.app_permissions), null) { openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                SettingItem.Link(getString(R.string.bluetooth_settings), null) { openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                SettingItem.Link(getString(R.string.wireless_connection_help), null) { wirelessHelp() },
                SettingItem.Action(getString(if (exportInProgress) R.string.saving_report else R.string.save_diagnostic_report), enabled = !exportInProgress) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) exportDiagnostics() else chooseReportDestination()
                },
                SettingItem.Note(getString(R.string.nothing_is_sent_automatically_protocol_payloads_and_creden)),
                SettingItem.Link(getString(R.string.about_diplay), version()) { page = "about" },
            )),
        )
    }

    private fun connectionGroups(): List<SettingGroup> {
        val mode = if (pendingCarHotspotSetup) WirelessHotspotMode.MANUAL else AirPlayPersistence.loadWirelessHotspotMode(this)
        val link = mutableListOf<SettingItem>(
            SettingItem.Radio(getString(R.string.built_in_car_hotspot), getString(R.string.hotspot_mode_manual_desc), mode == WirelessHotspotMode.MANUAL) {
                pendingCarHotspotSetup = true; render()
            },
            SettingItem.Radio(getString(R.string.wifi_direct), getString(R.string.hotspot_mode_p2p_desc), mode == WirelessHotspotMode.WIFI_P2P) {
                pendingCarHotspotSetup = false; applyWirelessLink(WirelessHotspotMode.WIFI_P2P)
            },
        )
        if (mode == WirelessHotspotMode.MANUAL) {
            link += SettingItem.Note(getString(R.string.s_1_open_car_hotspot_settings_turn_the_hotspot_on_and_sele))
            link += SettingItem.Action(getString(R.string.open_car_hotspot_settings)) { openCarWifiSettings() }
            link += SettingItem.Action(if (pendingCarHotspotSetup) getString(R.string.save_hotspot_details_and_use_this_mode) else "${getString(R.string.edit_saved_hotspot_prefix)}${storedSsid()}") {
                askHotspotCredentials { ssid, password ->
                    saveHotspotCredentials(ssid, password)
                    pendingCarHotspotSetup = false
                    applyWirelessLink(WirelessHotspotMode.MANUAL)
                }
            }
            val off = carHotspotOff()
            link += SettingItem.Note(getString(when {
                pendingCarHotspotSetup -> R.string.finish_setup_save_your_hotspot_details_to_use_this_mode
                off -> R.string.hotspot_details_off
                else -> R.string.hotspot_details_saved
            }), warning = off)
        } else {
            link += SettingItem.Note(getString(R.string.turn_the_car_s_wi_fi_switch_on_allow_location_nearby_devic))
            link += SettingItem.Action(getString(R.string.open_car_wi_fi_settings)) { openCarClientWifiSettings() }
        }
        return listOf(
            SettingGroup(getString(R.string.s_1_choose_your_connection), link),
            SettingGroup(getString(R.string.s_2_pair_your_iphone), listOf(
                SettingItem.Note(getString(R.string.keep_bluetooth_and_wi_fi_on_your_iphone_pair_with_the_car)),
                SettingItem.Link(getString(R.string.choose_iphone), phoneLabel()) { choosePhone() },
                SettingItem.Link(getString(R.string.review_app_permissions), null) {
                    openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                },
            )),
            SettingGroup(getString(R.string.s_3_connect), listOf(
                SettingItem.Note(getString(R.string.return_from_car_settings_to_diplay_then_connect_accept_the)),
                SettingItem.Action(getString(R.string.connect_wireless), primary = true) { connect(true) },
            )),
            SettingGroup(getString(R.string.prefer_a_cable), listOf(
                SettingItem.Note(getString(R.string.use_a_usb_data_cable_and_the_car_s_usb_data_port_unlock_yo)),
                SettingItem.Action(getString(R.string.connect_with_usb)) { connect(false) },
            )),
        )
    }

    private fun aboutGroups() = listOf(
        SettingGroup("${getString(R.string.about_public_preview_prefix)}${version()}", listOf(
            SettingItem.Note(getString(R.string.an_independent_carplay_receiver_for_android_head_units_wir)),
        )),
        SettingGroup(getString(R.string.made_possible_by_open_source), listOf(
            SettingItem.Note(getString(R.string.receiver_based_on_xcertplay_licensed_under_gpl_3_0_diplay)),
        )),
    )

    private fun wirelessModeLabel() = getString(when (AirPlayPersistence.loadWirelessHotspotMode(this)) {
        WirelessHotspotMode.MANUAL -> R.string.built_in_car_hotspot
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> R.string.localonlyhotspot
        else -> R.string.wifi_direct
    })

    private fun phoneLabel() =
        if (DiPlayPreferences.phoneAddress(this) == null) getString(R.string.not_selected) else DiPlayPreferences.phoneName(this)

    // The car hotspot link needs the hotspot on; DiPlay only checks it (turning it on needs ADB-only permission).
    private fun carHotspotOff(): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            com.shilapi.xcertplay.network.CarHotspotStatus.isEnabled(this) == false

    private fun carHotspotOffDialog() {
        AlertDialog.Builder(this).setTitle(getString(R.string.car_hotspot_is_off))
            .setMessage(getString(R.string.msg_car_hotspot_connect, AirPlayPersistence.loadManualHotspotSsid(this)))
            .setPositiveButton(getString(R.string.open_car_settings)) { _, _ -> openCarWifiSettings() }
            .setNeutralButton(getString(R.string.connect)) { _, _ -> connect(true) }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    // BYD maps the AOSP tether action to its own hotspot screen; other firmware falls back to Wi-Fi settings.
    // BYD shows that screen as a dialog and closes it unless its own settings or the car home screen is on top,
    // so the home screen goes first.
    private fun openCarWifiSettings() {
        val hotspot = Intent("com.android.settings.WIFI_TETHER_SETTINGS")
        val target = packageManager.resolveActivity(hotspot, 0)?.activityInfo?.packageName
        if (target == null) {
            openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            return
        }
        if (target == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        if (runCatching { startActivity(hotspot) }.isSuccess) return
        openSystem(Intent(Settings.ACTION_WIRELESS_SETTINGS))
    }

    private fun openCarClientWifiSettings() {
        val wifi = Intent(Settings.ACTION_WIFI_SETTINGS)
        if (packageManager.resolveActivity(wifi, 0)?.activityInfo?.packageName == "com.byd.carsettings") {
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
        openSystem(wifi)
    }

    private fun showChannelDialog(title: String, current: Int, navigation: Boolean, onApply: (Int) -> Unit) {
        val preview = AudioChannelPreview { channel ->
            toast(getString(R.string.contrib_audio_home_channel_preview_unavailable, channel))
        }
        val channels = AirPlayPersistence.AUDIO_CHANNELS
        val labels = channels.map(Int::toString).toTypedArray()
        var selection = current.coerceIn(channels.first, channels.last)
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(labels, selection) { _, which ->
                selection = which
                preview.play(which, navigation)
            }
            .setPositiveButton(if (CarPlayBackgroundSession.hasSession()) getString(R.string.apply_and_reconnect) else getString(R.string.save)) { _, _ ->
                onApply(selection)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .setOnDismissListener { preview.close() }
            .show()
    }

    private fun channelLabel(value: Int): String = value.toString()

    private fun storedSsid() = AirPlayPersistence.loadManualHotspotSsid(this)
    private fun storedPassword() = AirPlayPersistence.loadManualHotspotPassphrase(this)
    private fun hotspotError(ssid: String, password: String) =
        com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(ssid, password)?.let { getString(it.messageResource()) }

    private fun saveHotspotCredentials(ssid: String, password: String) {
        AirPlayPersistence.saveManualHotspotSsid(this, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, password)
        AirPlayPersistence.saveManualHotspotSecurity(this,
            com.shilapi.xcertplay.orchestration.ManualHotspotValidation.securityFor(password))
        AirPlayPersistence.saveManualHotspotBand(this, com.shilapi.xcertplay.orchestration.ManualHotspotBand.AUTO)
        AirPlayPersistence.saveManualHotspotChannel(this, 0)
    }

    private fun askHotspotCredentials(done: (String, String) -> Unit) {
        val fields = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        fields.addView(label(getString(R.string.copy_these_from_the_car_s_hotspot_settings_use_5_ghz_if_av), 16, MUTED))
        val ssid = EditText(this).apply { hint = getString(R.string.hotspot_name); setText(storedSsid()); setSingleLine() }
        val password = EditText(this).apply {
            hint = getString(R.string.hotspot_password); setText(storedPassword()); setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        ssid.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        password.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        fun hideKeyboard() {
            val token = password.windowToken ?: ssid.windowToken
            (this.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(token, 0)
            ssid.clearFocus(); password.clearFocus()
        }
        ssid.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) { password.requestFocus(); true } else false
        }
        password.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { hideKeyboard(); true } else false
        }
        fields.addView(ssid); fields.addView(password)
        fields.addView(CheckBox(this).apply {
            text = getString(R.string.show_password)
            setOnCheckedChangeListener { _, checked ->
                password.transformationMethod = if (checked) null else android.text.method.PasswordTransformationMethod.getInstance()
                password.setSelection(password.text.length)
            }
        })
        val error = label("", 14, WARNING)
        error.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        fields.addView(error)
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.car_hotspot_details))
            .setView(ScrollView(this).apply { addView(fields) })
            .setPositiveButton(getString(R.string.save_details), null).setNegativeButton(getString(R.string.cancel)) { _, _ -> hideKeyboard() }
            .setNeutralButton(getString(R.string.hide_keyboard), null).create()
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { hideKeyboard() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = ssid.text.toString().trim()
                val secret = password.text.toString()
                val problem = hotspotError(name, secret)
                if (problem != null) error.text = problem
                else { hideKeyboard(); dialog.dismiss(); done(name, secret) }
            }
        }
        dialog.show()
    }

    private fun hasPreciseLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // The location component is part of the iAP2 identification, so a running session reconnects.
    private fun reconnectForLocation() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    private fun applyWirelessLink(mode: WirelessHotspotMode) {
        AirPlayPersistence.saveWirelessHotspotMode(this, mode)
        render()
        toast(getString(R.string.saved_for_your_next_connection))
    }

    private fun textInput(title: String, current: String, secret: Boolean, save: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            setSingleLine()
            inputType = if (secret) {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ -> save(input.text.toString().let { if (secret) it else it.trim() }) }
            .setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun connect(wireless: Boolean) {
        if (wireless && pendingCarHotspotSetup) { toast(getString(R.string.save_your_hotspot_details_in_connection_setup_first)); page = "connection"; render(); return }
        if (setupError != null) { toast(setupError!!); return }
        if (wireless && AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            hotspotError(storedSsid(), storedPassword()) != null) {
            pendingCarHotspotSetup = true
            page = "connection"
            render()
            toast(getString(R.string.save_the_name_and_password_from_the_car_s_hotspot_settings))
            return
        }
        if (wireless && carHotspotOff()) { carHotspotOffDialog(); return }
        if (wireless && DiPlayPreferences.phoneAddress(this) == null) {
            pendingWireless = true; choosePhone(); return
        }
        val preferences = getSharedPreferences("diplay", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !preferences.getBoolean("notification_asked", false)) {
            preferences.edit().putBoolean("notification_asked", true).apply()
            notificationTransport = wireless
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val open = {
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
            DiPlayPreferences.saveLastProjection(this, DiPlayPreferences.CARPLAY)
            openProjection()
        }
        if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        else open()
    }
    private fun openProjection() {
        startActivity(Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }
    private fun choosePhone() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT); return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            AlertDialog.Builder(this).setTitle(getString(R.string.turn_on_bluetooth))
                .setMessage(getString(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first))
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.later), null).show(); return
        }
        val devices = runCatching { adapter.bondedDevices.sortedBy { it.name ?: "" } }.getOrDefault(emptyList())
        if (devices.isEmpty()) {
            AlertDialog.Builder(this).setTitle(getString(R.string.pair_your_iphone))
                .setMessage(getString(R.string.on_your_iphone_open_settings_bluetooth_and_pair_with_the_c))
                .setPositiveButton(getString(R.string.open_bluetooth)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton(getString(R.string.got_it), null).show(); return
        }
        AlertDialog.Builder(this).setTitle(getString(R.string.choose_your_iphone))
            .setItems(devices.map { device ->
                val name = device.name ?: getString(R.string.paired_device)
                if (devices.count { it.name == device.name } > 1) "$name · ${device.address.takeLast(5)}" else name
            }.toTypedArray()) { _, index ->
                val device = devices[index]
                DiPlayPreferences.savePhone(this, device.address, device.name ?: "iPhone")
                val start = pendingWireless; pendingWireless = false
                render()
                if (start) connect(true)
            }.setNeutralButton(getString(R.string.pair_another)) { _, _ -> openSystem(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> pendingWireless = false }.show()
    }

    private fun wirelessHelp() {
        AlertDialog.Builder(this).setTitle(getString(R.string.wireless_connection_help))
            .setMessage(getString(R.string.pair_your_iphone_with_the_car_s_bluetooth_keep_wi_fi_on_an))
            .setPositiveButton(getString(R.string.got_it), null)
            .setNeutralButton(getString(R.string.reset_carplay_wi_fi)) { _, _ ->
                confirmWirelessReset()
            }.show()
    }

    private fun handleWirelessRecovery() {
        if (page != "wireless-recovery") return
        page = "home"; render()
        confirmWirelessReset()
    }

    private fun confirmWirelessReset() {
        AlertDialog.Builder(this).setTitle(getString(R.string.reset_carplay_wi_fi_2))
            .setMessage(getString(R.string.this_ends_the_existing_wi_fi_direct_connection_including_o))
            .setPositiveButton(getString(R.string.reset_and_connect)) { _, _ ->
                CarPlayBackgroundSession.stop { runOnUiThread { resetWirelessGroup() } }
            }.setNegativeButton(getString(R.string.cancel), null).show()
    }

    private fun resetWirelessGroup() {
        val manager = getSystemService(android.net.wifi.p2p.WifiP2pManager::class.java)
        if (manager == null) { toast(getString(R.string.this_head_unit_does_not_support_wi_fi_direct)); return }
        val channel = manager.initialize(this, mainLooper, null)
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) { channel.close(); connect(true); return@requestGroupInfo }
                manager.removeGroup(channel, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 4000
                        fun waitUntilRemoved() {
                            manager.requestGroupInfo(channel) { remaining ->
                                when {
                                    remaining == null -> { channel.close(); if (!isFinishing && !isDestroyed) connect(true) }
                                    android.os.SystemClock.elapsedRealtime() >= deadline -> {
                                        channel.close(); toast(getString(R.string.wi_fi_direct_is_still_busy_close_the_other_projection_app))
                                    }
                                    else -> handler.postDelayed({ waitUntilRemoved() }, 200)
                                }
                            }
                        }
                        waitUntilRemoved()
                    }
                    override fun onFailure(reason: Int) { channel.close(); toast(getString(R.string.could_not_reset_wi_fi_direct_close_the_other_projection_ap)) }
                })
            }
        } catch (_: SecurityException) {
            channel.close(); permissionHelp(getString(R.string.wireless_permissions), getString(R.string.allow_nearby_devices_and_on_older_android_versions_locatio))
        }
    }

    private fun refreshStatus() {
        carPlayRunning = CarPlayBackgroundSession.hasSession()
        carPlayStatus = when {
            setupError != null -> getString(R.string.setup_needs_attention)
            CarPlayBackgroundSession.active -> getString(R.string.carplay_connected)
            carPlayRunning -> getString(R.string.connecting_to_your_iphone)
            DiPlayPreferences.phoneAddress(this) != null -> "${getString(R.string.status_ready_for_prefix)}${DiPlayPreferences.phoneName(this)}"
            else -> getString(R.string.ready_when_you_are)
        }
    }
    private fun reportFileName() = "${APP_DISPLAY_NAME.replace(' ', '-')}-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    private fun chooseReportDestination() {
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        runCatching { export.launch(reportFileName()) }.onFailure {
            toast(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                getString(R.string.this_head_unit_could_not_open_a_save_location_please_try_s)
                else getString(R.string.this_head_unit_has_no_available_file_picker_to_save_the_re))
        }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        val appContext = applicationContext
        val fileName = reportFileName()
        Thread({
            val result = runCatching {
                val report = buildString {
                    appendLine("$APP_DISPLAY_NAME ${version()} · private beta diagnostic report")
                    appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
                    appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
                    appendLine("Authentication: local experimental beta identity; no remote fallback")
                    appendLine("CarPlay setup: ${if (setupError == null) "ready" else "authentication unavailable"}")
                    appendLine("Saved video preference (may differ from active session): ${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
                    appendLine("CarPlay size: ${com.shilapi.xcertplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
                    appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScaleTenths(appContext) * 10}%")
                    appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
                    appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
                    appendLine()
                    appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
                    appendLine(DisplayDiagnosticSnapshot.report(appContext))
                    appendLine()
                    for (name in SessionLogFile.REPORT_NAMES) {
                        val file = File(appContext.filesDir, "logs/$name")
                        if (file.isFile) {
                            appendLine("--- $name ---")
                            file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
                        }
                    }
                }
                if (uri != null) { DiagnosticExportStore.write(appContext.contentResolver, uri, report); uri }
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    DiagnosticExportStore.saveToDownloads(appContext.contentResolver, fileName, report)
                } else error("A save location is required")
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (result.isSuccess) {
                    val savedUri = result.getOrThrow()
                    AlertDialog.Builder(this).setTitle(getString(R.string.diagnostic_report_saved))
                        .setMessage(if (uri == null) "Downloads/$REPORTS_FOLDER/$fileName" else getString(R.string.your_report_was_saved_to_the_selected_location))
                        .setPositiveButton(getString(R.string.done), null)
                        .setNeutralButton(getString(R.string.share)) { _, _ ->
                            runCatching {
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(Intent.EXTRA_STREAM, savedUri)
                                    clipData = android.content.ClipData.newRawUri(getString(R.string.report_clip_label), savedUri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, getString(R.string.share_diagnostic_report)))
                            }.onFailure { toast(getString(R.string.report_saved_open_it_from_your_file_manager_to_share_it)) }
                        }.show()
                } else {
                    AlertDialog.Builder(this).setTitle(getString(R.string.could_not_save_the_report))
                        .setMessage(getString(R.string.check_that_storage_is_available_or_choose_another_save_loc))
                        .setPositiveButton(getString(R.string.choose_location)) { _, _ -> chooseReportDestination() }
                        .setNegativeButton(getString(R.string.close), null).show()
                }
            }
        }, "diplay-export").start()
    }
    private fun permissionHelp(title: String, body: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton(getString(R.string.app_settings)) { _, _ ->
            openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.setNegativeButton(getString(R.string.later), null).show()
    }
    private fun openSystem(intent: Intent) { runCatching { startActivity(intent) }.onFailure { toast(getString(R.string.open_this_setting_from_your_car_s_settings_app)) } }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun playTestTone(streamType: Int) {
        toneStop?.let { handler.removeCallbacks(it) }
        toneStop = null
        testToneTrack?.let { runCatching { it.stop(); it.release() } }
        testToneTrack = null
        var candidate: AudioTrack? = null
        val track = try {
            val pcm = assets.open("navigation_test.pcm").use { it.readBytes() }
            AudioTrack(streamType, 44100, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, pcm.size, AudioTrack.MODE_STREAM).also {
                candidate = it
                check(it.state == AudioTrack.STATE_INITIALIZED)
                check(it.write(pcm, 0, pcm.size) == pcm.size)
                it.play()
            }
        } catch (error: Exception) {
            val state = candidate?.state ?: AudioTrack.STATE_UNINITIALIZED
            candidate?.let { runCatching { it.release() } }
            Log.w("DiPlay", "playTestTone streamType=$streamType unavailable", error)
            toast(getString(R.string.audio_stream_unavailable, streamType, state))
            return
        }
        Log.i("DiPlay", "playTestTone streamType=$streamType state=${track.state} playState=${track.playState}")
        testToneTrack = track
        val stop = Runnable {
            track.stop()
            track.release()
            if (testToneTrack === track) testToneTrack = null
            toneStop = null
        }
        toneStop = stop
        handler.postDelayed(stop, 4500)
    }

    private val channelButtons = mutableListOf<Button>()

    private fun paintChannel(index: Int, selected: Boolean) {
        val target = channelButtons.getOrNull(index) ?: return
        target.isSelected = selected
        target.setTextColor(if (selected) BG else TEXT)
        target.background = android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(0x336F9FD9),
            rounded(if (selected) ACCENT else SURFACE, if (selected) ACCENT else BORDER),
            null
        )
    }

    private fun channelSelector(): ViewGroup {
        channelButtons.clear()
        val grid = GridLayout(this).apply {
            columnCount = 7
            rowCount = 3
            setPadding(0, dp(8), 0, dp(8))
        }
        for (i in 0..20) {
            val btn = Button(this).apply {
                text = i.toString()
                isAllCaps = false
                textSize = 16f
                minHeight = dp(48)
                stateListAnimator = null
                setOnClickListener {
                    val previous = navigationStreamType
                    navigationStreamType = i
                    if (previous != i) {
                        paintChannel(previous, false)
                        paintChannel(i, true)
                    }
                    playTestTone(i)
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(48)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            grid.addView(btn, params)
            channelButtons.add(btn)
            paintChannel(i, i == navigationStreamType)
        }
        return grid
    }
    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"
    private fun card() = column().apply { background = rounded(SURFACE, BORDER); setPadding(dp(24), dp(24), dp(24), dp(24)) }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun button(title: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 18f; setTextColor(if (primary) BG else TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x336F9FD9), rounded(if (primary) ACCENT else SURFACE, if (primary) ACCENT else BORDER), null)
        setPadding(dp(16), 0, dp(16), 0); minHeight = dp(56); stateListAnimator = null
        setOnClickListener { click() }
    }
    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(20).toFloat(); setStroke(dp(1), stroke) }
    private fun matchButton(top: Int = 0, height: Int = 68) = LinearLayout.LayoutParams(-1, dp(height)).apply { topMargin = dp(top) }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        private val BG = Color.rgb(20, 21, 25)
        private val SURFACE = Color.rgb(34, 36, 40)
        private val BORDER = Color.rgb(47, 68, 89)
        private val ACCENT = Color.rgb(110, 193, 255)
        private val TEXT = Color.rgb(241, 245, 252)
        private val MUTED = Color.rgb(154, 160, 170)
        private val WARNING = Color.rgb(255, 196, 128)
    }
}
