package moe.ouom.neriplayer.testing

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.compose.remote.core.RcPlatformServices
import androidx.compose.remote.core.operations.RootContentBehavior
import androidx.compose.remote.core.operations.Utils
import androidx.compose.remote.core.operations.layout.managers.ColumnLayout
import androidx.compose.remote.core.operations.layout.managers.RowLayout
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression.ADD
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression.DIV
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression.MAX
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression.MIN
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression.MUL
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression.SUB
import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.compose.remote.creation.actions.HostAction
import androidx.compose.remote.creation.actions.ValueFloatChange
import androidx.compose.remote.creation.actions.ValueFloatExpressionChange
import androidx.compose.remote.creation.modifiers.RecordingModifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.io.File

/** A paused-player prototype: local animation and target delivery, with no backend acknowledgement. */
@ManualInstrumentedTest
@RequiresApi(35)
@RunWith(AndroidJUnit4::class)
class VivoRemoteSeekAnimationTest {
    private val targetFractions = listOf(0.2f, 0.5f, 0.8f)
    private val firstActionId = 0x4e50fa00
    private val progressColor = 0xff00d0cf.toInt()

    @Test
    fun tapsAnimateLocallyBeforeAnyPlaybackConfirmation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runRemoteSeekAnimation") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val action = "${context.packageName}.SEEK_ANIMATION_PROBE"
        val targets = LinkedBlockingQueue<Int>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == action) targets.offer(intent.getIntExtra("step", -1))
            }
        }
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        val pending = targetFractions.indices.map { step ->
            PendingIntent.getBroadcast(context, firstActionId + step,
                Intent(action).setPackage(context.packageName).putExtra("step", step),
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        try {
            val remote = seekDocument().apply {
                pending.forEachIndexed { step, intent -> setOnClickPendingIntent(firstActionId + step, intent) }
            }
            if (InstrumentationRegistry.getArguments().getString("remoteSeekBringToForeground") == "true") {
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nSEEK_AWAIT_FOREGROUND\n") })
                val deadline = SystemClock.elapsedRealtime() + 30_000L
                var resumed = false
                do {
                    instrumentation.runOnMainSync {
                        resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .any { it.packageName == context.packageName }
                    }
                    if (!resumed) Thread.sleep(50)
                } while (!resumed && SystemClock.elapsedRealtime() < deadline)
                assertTrue("Bring the test application to the foreground", resumed)
            }
            val activity = instrumentation.startActivitySync(
                Intent(context, TestComposeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                lateinit var view: View
                instrumentation.runOnMainSync {
                    view = remote.apply(activity, null)
                    activity.setContentView(view, ViewGroup.LayoutParams(360, 160))
                }
                instrumentation.waitForIdleSync()
                var frame = 0
                fun width(): Int {
                    val bitmap = Bitmap.createBitmap(360, 160, Bitmap.Config.ARGB_8888)
                    instrumentation.runOnMainSync { view.draw(Canvas(bitmap)) }
                    val result = (24 until 336).count { x -> bitmap.getPixel(x, 102) == progressColor }
                    assertTrue("Click feedback leaked outside the horizontal progress bar",
                        (24 until 336).all { x -> bitmap.getPixel(x, 80) == 0xff101820.toInt() })
                    File(context.getExternalFilesDir(null), "vivo-seek-frame-${frame++}.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                    return result
                }
                fun tap(step: Int) {
                    instrumentation.runOnMainSync {
                        val now = SystemClock.uptimeMillis()
                        val x = 24f + (step + 0.5f) * 104f
                        for (type in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                            val event = MotionEvent.obtain(now, now, type, x, 102f, 0)
                            view.dispatchTouchEvent(event)
                            event.recycle()
                        }
                    }
                }
                // Advance, rewind, then select the middle. Never rebuild/reapply the document.
                for (step in listOf(2, 0, 1)) {
                    val before = width()
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nSEEK_BEFORE step=$step width=$before view=${view.width}x${view.height}\n") })
                    tap(step)
                    assertEquals("Tap did not deliver its target", step, targets.poll(1, TimeUnit.SECONDS))
                    val first = width()
                    Thread.sleep(120)
                    val intermediate = width()
                    val expected = (312 * targetFractions[step]).toInt()
                    val deadline = SystemClock.uptimeMillis() + 2_000
                    var settled: Int
                    do {
                        Thread.sleep(40)
                        settled = width()
                    } while (kotlin.math.abs(settled - expected) > 3 && SystemClock.uptimeMillis() < deadline)
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("stream", "\nSEEK step=$step widths=[$before,$first,$intermediate,$settled] expected=$expected\n")
                    })
                    assertTrue("Seek did not reach its target", kotlin.math.abs(settled - expected) <= 3)
                    assertTrue("Seek jumped immediately instead of animating", kotlin.math.abs(first - settled) > 5)
                    assertTrue("Seek had no intermediate frame",
                        intermediate in (minOf(before, settled) + 2)..(maxOf(before, settled) - 2))
                }
                // Retarget during an unfinished transition, still without a host acknowledgement.
                tap(2)
                assertEquals(2, targets.poll(1, TimeUnit.SECONDS))
                Thread.sleep(80)
                val beforeRetarget = width()
                tap(0)
                assertEquals(0, targets.poll(1, TimeUnit.SECONDS))
                val firstRetarget = width()
                Thread.sleep(120)
                val intermediateRetarget = width()
                val expected = (312 * targetFractions[0]).toInt()
                val deadline = SystemClock.uptimeMillis() + 2_000
                var settled: Int
                do {
                    Thread.sleep(40)
                    settled = width()
                } while (kotlin.math.abs(settled - expected) > 3 && SystemClock.uptimeMillis() < deadline)
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "\nSEEK_RETARGET widths=[$beforeRetarget,$firstRetarget,$intermediateRetarget,$settled]\n")
                })
                assertTrue("Retarget jumped at its first frame", kotlin.math.abs(firstRetarget - beforeRetarget) <= 8)
                assertTrue("Retarget did not animate back to its new target",
                    intermediateRetarget in (settled + 2)..(beforeRetarget - 2))
                assertTrue("Retarget did not settle", kotlin.math.abs(settled - expected) <= 3)
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
            }
        } finally {
            pending.forEach(PendingIntent::cancel)
            context.unregisterReceiver(receiver)
        }
    }

    @Suppress("RestrictedApi")
    private fun seekDocument(): RemoteViews {
        val writer = RemoteComposeWriter(360, 160, "Seek animation probe", 6, 0, RcPlatformServices.None)
        writer.setRootContentBehavior(RootContentBehavior.NONE, RootContentBehavior.ALIGNMENT_CENTER,
            RootContentBehavior.SIZING_SCALE, RootContentBehavior.SCALE_FILL_WIDTH)
        val target = writer.addFloatConstant(0.1f)
        val start = writer.addFloatConstant(0.1f)
        val startedAt = writer.addFloatConstant(0f)
        writer.startRoot()
        writer.startBox(RecordingModifier().size(360, 160))
        writer.startCanvas(RecordingModifier().size(360, 160))
        val clock = writer.floatExpression(RemoteComposeWriter.TIME_IN_CONTINUOUS_SEC)
        val elapsed = writer.floatExpression(clock, startedAt, SUB, 0.4f, DIV, 0f, MAX, 1f, MIN)
        // Smoothstep from the visible value captured at the most recent click.
        val eased = writer.floatExpression(elapsed, elapsed, MUL, 3f, 2f, elapsed, MUL, SUB, MUL)
        val fraction = writer.floatExpression(target, start, SUB, eased, MUL, start, ADD)
        val right = writer.floatExpression(fraction, 312f, MUL, 24f, ADD)
        writer.endCanvas()
        writer.startColumn(RecordingModifier().size(360, 160), ColumnLayout.START, ColumnLayout.TOP)
        writer.startCanvas(RecordingModifier().size(360, 78))
        writer.endCanvas()
        writer.startRow(RecordingModifier().size(360, 48), RowLayout.START, RowLayout.TOP)
        writer.startCanvas(RecordingModifier().size(24, 48))
        writer.endCanvas()
        targetFractions.forEachIndexed { step, fractionTarget ->
            writer.startCanvas(RecordingModifier().size(104, 48)
                .onClick(
                    ValueFloatExpressionChange(Utils.idFromNan(start), Utils.idFromNan(fraction)),
                    ValueFloatExpressionChange(Utils.idFromNan(startedAt), Utils.idFromNan(clock)),
                    ValueFloatChange(Utils.idFromNan(target), fractionTarget),
                    HostAction(firstActionId + step)))
            writer.endCanvas()
        }
        writer.endRow()
        writer.endColumn()
        // Draw above the hit regions: keep click semantics without the platform's vertical ripple.
        writer.startCanvas(RecordingModifier().size(360, 160))
        writer.rcPaint.setColor(0xff101820.toInt()).commit()
        writer.drawRoundRect(0f, 0f, 360f, 160f, 16f, 16f)
        writer.rcPaint.setColor(0xff394550.toInt()).commit()
        writer.drawRoundRect(24f, 98f, 336f, 106f, 4f, 4f)
        writer.rcPaint.setColor(progressColor).commit()
        writer.drawRoundRect(24f, 98f, right, 106f, 4f, 4f)
        writer.endCanvas()
        writer.endBox()
        writer.endRoot()
        return RemoteViews(RemoteViews.DrawInstructions.Builder(listOf(writer.encodeToByteArray())).build())
    }
}
