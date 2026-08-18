package dev.mahlernim.timelinevisualizer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.creations.CreationStore
import dev.mahlernim.timelinevisualizer.data.TimelineSourceStore
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ja-w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayStoreScreenshotTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        assumeTrue("Set GENERATE_STORE_SCREENSHOTS=true to render store screenshots", shouldGenerate())
        context.getSharedPreferences("display", Context.MODE_PRIVATE).edit().clear().commit()
        CreationStore(context).clear()
        TimelineSourceStore(context).clearForTest()
    }

    @After
    fun tearDown() {
        if (!shouldGenerate()) return
        context.getSharedPreferences("display", Context.MODE_PRIVATE).edit().clear().commit()
        CreationStore(context).clear()
        TimelineSourceStore(context).clearForTest()
    }

    @Test
    fun renderJapaneseStoreScreenshots() {
        val output = repoRoot().resolve("play-store/assets/screenshots/ja-JP").apply { mkdirs() }
        repoRoot().resolve("play-store/assets/screenshots/en-US/01-start.png")
            .copyTo(output.resolve("01-start.png"), overwrite = true)

        val privacyController = Robolectric.buildActivity(MainActivity::class.java).setup()
        val privacyActivity = privacyController.get()
        privacyActivity.findViewById<View>(R.id.importButton).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        render(privacyActivity.window.decorView, output.resolve("02-privacy.png"), ShadowDialog.getLatestDialog()?.window?.decorView)
        privacyController.close()

        context.getSharedPreferences("display", Context.MODE_PRIVATE).edit()
            .putBoolean("map_privacy_accepted_v1", true)
            .commit()
        val fixture = repoRoot().resolve("test-fixtures/seoul-bohol-sample.json")
        val intent = Intent(Intent.ACTION_VIEW, Uri.fromFile(fixture))
        val editorController = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        val editorActivity = editorController.get()
        waitForEditor(editorActivity)
        editorActivity.currentFocus?.clearFocus()
        val scroll = findNestedScrollView(editorActivity.window.decorView)
        scroll?.scrollTo(0, 0)
        shadowOf(Looper.getMainLooper()).idle()
        render(editorActivity.window.decorView, output.resolve("03-create.png"))

        scroll?.scrollTo(0, (390 * editorActivity.resources.displayMetrics.density).toInt())
        shadowOf(Looper.getMainLooper()).idle()
        render(editorActivity.window.decorView, output.resolve("04-preview.png"))
        editorController.close()

        assertEquals(
            listOf("01-start.png", "02-privacy.png", "03-create.png", "04-preview.png"),
            output.listFiles()!!.map(File::getName).sorted(),
        )
    }

    private fun waitForEditor(activity: MainActivity) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (activity.findViewById<View>(R.id.editorGroup).visibility == View.VISIBLE) return
            Thread.sleep(25)
        }
        error("Timeline fixture did not finish loading")
    }

    private fun render(root: View, output: File, overlay: View? = null) {
        val width = 1080
        val height = 1920
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        root.measure(widthSpec, heightSpec)
        root.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        root.draw(canvas)
        overlay?.let {
            val overlayWidthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST)
            val overlayHeightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST)
            it.measure(overlayWidthSpec, overlayHeightSpec)
            it.layout(0, 0, it.measuredWidth, it.measuredHeight)
            canvas.save()
            canvas.translate((width - it.measuredWidth) / 2f, (height - it.measuredHeight) / 2f)
            it.draw(canvas)
            canvas.restore()
        }
        output.parentFile?.mkdirs()
        FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun repoRoot(): File {
        val workingDirectory = System.getProperty("user.dir")
            ?: error("The working directory is unavailable")
        var current = File(workingDirectory).absoluteFile
        while (!current.resolve("settings.gradle.kts").isFile) {
            current = current.parentFile ?: error("Repository root was not found")
        }
        return current
    }

    private fun findNestedScrollView(view: View): NestedScrollView? {
        if (view is NestedScrollView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findNestedScrollView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun shouldGenerate(): Boolean = System.getenv("GENERATE_STORE_SCREENSHOTS") == "true"
}
