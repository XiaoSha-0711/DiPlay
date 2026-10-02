package com.shilapi.xcertplay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
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
        for (page in listOf("home", "connection", "settings", "about")) {
            runCatching {
                val intent = Intent().putExtra("page", page)
                val activity = Robolectric.buildActivity(DiPlayActivity::class.java, intent).setup().get()
                val content = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val body = (content as? ScrollView)?.getChildAt(0) ?: content
                val width = activity.resources.displayMetrics.widthPixels
                body.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                body.layout(0, 0, body.measuredWidth, body.measuredHeight)
                val bitmap = Bitmap.createBitmap(body.measuredWidth, body.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.rgb(20, 21, 25))
                body.draw(canvas)
                File(dir, "$orientation-$page.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }.onFailure { println("UI screenshot $orientation-$page failed: $it") }
        }
    }
}
