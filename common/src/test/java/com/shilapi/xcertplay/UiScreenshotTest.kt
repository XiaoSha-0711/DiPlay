package com.shilapi.xcertplay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders each home-screen page to common/build/screenshots, which CI uploads, so the interface can
 * be reviewed without a device. It never fails the build: a page that cannot render is only logged.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class UiScreenshotTest {
    @Test @Config(qualifiers = "zh-rTW-w411dp-h891dp-port-xxhdpi")
    fun phonePortrait() = capture("portrait")

    @Test @Config(qualifiers = "zh-rTW-w891dp-h411dp-land-xxhdpi")
    fun phoneLandscape() = capture("landscape")

    private fun capture(orientation: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        val pages = listOf("home", "connection", "settings", "about")
        for (entry in pages) {
            val page = entry.substringBefore(':')
            val tab = entry.substringAfter(':', "")
            runCatching {
                val intent = Intent().putExtra("page", page).apply { if (tab.isNotEmpty()) putExtra("tab", tab) }
                val activity = Robolectric.buildActivity(DiPlayActivity::class.java, intent).setup().get()
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                val body = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val width = activity.resources.displayMetrics.widthPixels
                // Lay the page out much taller than the screen so the whole scroll is drawn, then trim
                // the empty background below the content.
                val tall = activity.resources.displayMetrics.heightPixels * 5
                body.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(tall, View.MeasureSpec.EXACTLY),
                )
                body.layout(0, 0, width, tall)
                val bitmap = Bitmap.createBitmap(width, tall, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(BACKGROUND)
                body.draw(canvas)
                val row = IntArray(width)
                var bottom = tall
                while (bottom > 1) {
                    bitmap.getPixels(row, 0, width, 0, bottom - 1, width, 1)
                    if (row.any { it != BACKGROUND }) break
                    bottom--
                }
                val trimmed = Bitmap.createBitmap(bitmap, 0, 0, width, (bottom + 48).coerceAtMost(tall))
                File(dir, "$orientation-${entry.replace(':', '-')}.png").outputStream().use { trimmed.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }.onFailure { println("UI screenshot $orientation-$entry failed: $it") }
        }
    }

    private companion object {
        val BACKGROUND = Color.rgb(20, 21, 25)
    }
}
