package com.shilapi.xcertplay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import com.shilapi.xcertplay.ui.HomeActions
import com.shilapi.xcertplay.ui.HomeModel
import com.shilapi.xcertplay.ui.HomePage
import com.shilapi.xcertplay.ui.MultiPlayScreen
import com.shilapi.xcertplay.ui.MultiPlayTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the pictures used in the README to common/build/screenshots/readme-*.png. The home screen
 * is drawn from a ready-to-connect model, so it shows none of the setup warnings of a test build.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ReadmeScreenshotTest {
    @Test @Config(qualifiers = "zh-rTW-w411dp-h891dp-port-xxhdpi")
    fun chinesePortrait() = capture("zh-TW-portrait")

    @Test @Config(qualifiers = "zh-rTW-w891dp-h411dp-land-xxhdpi")
    fun chineseLandscape() = capture("zh-TW-landscape", settings = false)

    @Test @Config(qualifiers = "en-w411dp-h891dp-port-xxhdpi")
    fun englishPortrait() = capture("en-portrait")

    @Test @Config(qualifiers = "en-w891dp-h411dp-land-xxhdpi")
    fun englishLandscape() = capture("en-landscape", settings = false)

    private fun capture(name: String, settings: Boolean = true) {
        runCatching {
            val activity = Robolectric.buildActivity(DiPlayActivity::class.java, Intent().putExtra("page", "home")).setup().get()
            val model = HomeModel(
                carPlayStatus = activity.getString(R.string.ready_when_you_are), carPlayRunning = false,
                carPlayWireless = true, carPlayEnabled = true, carPlayNotice = null,
                androidAutoConnected = false, androidAutoMethod = 0, setupError = null, version = "0.2.9",
            )
            val actions = HomeActions({}, {}, {}, {}, {}, {})
            activity.setContent {
                MultiPlayTheme {
                    MultiPlayScreen(activity.getString(R.string.diplay), home = true, onBack = {}, onSettings = {}, onExit = {}) {
                        HomePage(model, actions)
                    }
                }
            }
            save(activity, "readme-$name-home")
        }.onFailure { println("README screenshot $name home failed: $it") }
        if (!settings) return
        runCatching {
            val activity = Robolectric.buildActivity(DiPlayActivity::class.java, Intent().putExtra("page", "settings")).setup().get()
            save(activity, "readme-$name-settings", maxHeight = activity.resources.displayMetrics.heightPixels)
        }.onFailure { println("README screenshot $name settings failed: $it") }
    }

    private fun save(activity: DiPlayActivity, file: String, maxHeight: Int = Int.MAX_VALUE) {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val body = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val width = activity.resources.displayMetrics.widthPixels
        val tall = activity.resources.displayMetrics.heightPixels * 5
        body.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(tall, View.MeasureSpec.EXACTLY),
        )
        body.layout(0, 0, width, tall)
        val bitmap = Bitmap.createBitmap(width, tall, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { drawColor(BACKGROUND); body.draw(this) }
        val row = IntArray(width)
        var bottom = tall
        while (bottom > 1) {
            bitmap.getPixels(row, 0, width, 0, bottom - 1, width, 1)
            if (row.any { it != BACKGROUND }) break
            bottom--
        }
        val trimmed = Bitmap.createBitmap(bitmap, 0, 0, width, (bottom + 48).coerceAtMost(tall).coerceAtMost(maxHeight))
        File(File("build/screenshots").apply { mkdirs() }, "$file.png").outputStream().use { trimmed.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        val BACKGROUND = Color.rgb(20, 21, 25)
    }
}
