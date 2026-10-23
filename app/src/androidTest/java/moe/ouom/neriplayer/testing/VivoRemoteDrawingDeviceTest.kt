package moe.ouom.neriplayer.testing

import android.os.Build
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.view.MotionEvent
import android.widget.RemoteViews
import androidx.compose.remote.core.RcPlatformServices
import androidx.compose.remote.core.operations.RootContentBehavior
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression.ADD
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression.MOD
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression.MUL
import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.annotation.RequiresApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Explicit firmware probes; owns only its fixture notification and never starts playback. */
@ManualInstrumentedTest
@RequiresApi(35)
@RunWith(AndroidJUnit4::class)
class VivoRemoteDrawingDeviceTest {
    private val clickId = 0x4e50f049

    @Test
    fun drawingClickAreaDispatchesItsPendingIntent() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runRemoteDrawingLocal") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val action = "${context.packageName}.DRAWING_PROBE_CLICK"
        val received = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == action) received.countDown()
            }
        }
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        val pending = PendingIntent.getBroadcast(context, clickId, Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            val remote = animatedProgress(clickId).apply { setOnClickPendingIntent(clickId, pending) }
            ActivityScenario.launch(TestComposeActivity::class.java).use { scenario ->
                lateinit var view: View
                scenario.onActivity { activity ->
                    view = remote.apply(activity, null)
                    activity.setContentView(view, android.view.ViewGroup.LayoutParams(360, 160))
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val now = android.os.SystemClock.uptimeMillis()
                    for (eventAction in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(now, now, eventAction, 160f, 102f, 0)
                        view.dispatchTouchEvent(event)
                        event.recycle()
                    }
                }
                assertTrue("Remote drawing click did not dispatch its PendingIntent", received.await(2, TimeUnit.SECONDS))
            }
        } finally {
            pending.cancel()
            context.unregisterReceiver(receiver)
        }
    }

    @Test
    fun platformDrawsTimeDrivenProgressWithoutRepublishing() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runRemoteDrawingLocal") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val remote = animatedProgress()
        lateinit var view: View
        instrumentation.runOnMainSync {
            view = remote.apply(instrumentation.targetContext, null)
            view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 360, 160)
        }
        val widths = (0..1).map {
            val bitmap = Bitmap.createBitmap(360, 160, Bitmap.Config.ARGB_8888)
            instrumentation.runOnMainSync { view.draw(Canvas(bitmap)) }
            val width = (24 until 336).count { x -> bitmap.getPixel(x, 102) == 0xff00d0cf.toInt() }
            bitmap.recycle()
            Thread.sleep(700)
            width
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nLOCAL_DRAWING_WIDTHS=$widths\n") })
        assertTrue("Progress did not move between rendered frames: $widths", kotlin.math.abs(widths[1] - widths[0]) > 20)
    }

    @Suppress("RestrictedApi")
    private fun animatedProgress(clickId: Int? = null, density: Float = 1f): RemoteViews {
        // Document dimensions are physical pixels; the host measures its intrinsic height.
        val writer = RemoteComposeWriter((360 * density).roundToInt(), (160 * density).roundToInt(),
            "Vivo animation probe", 6, 0, RcPlatformServices.None)
        writer.setRootContentBehavior(RootContentBehavior.NONE, RootContentBehavior.ALIGNMENT_CENTER,
            RootContentBehavior.SIZING_SCALE, RootContentBehavior.SCALE_FILL_WIDTH)
        writer.save()
        writer.scale(density, density)
        writer.rcPaint.setColor(0xff101820.toInt()).commit()
        writer.drawRoundRect(0f, 0f, 360f, 160f, 16f, 16f)
        writer.rcPaint.setColor(0xffffffff.toInt()).setTextSize(18f).commit()
        writer.drawTextAnchored("Remote animation probe", 24f, 54f, -1f, 0f, 0)
        writer.rcPaint.setColor(0xff394550.toInt()).commit()
        writer.drawRoundRect(24f, 98f, 336f, 106f, 4f, 4f)
        val right = writer.floatExpression(RemoteComposeWriter.TIME_IN_CONTINUOUS_SEC, 4f, MOD, 78f, MUL, 24f, ADD)
        writer.rcPaint.setColor(0xff00d0cf.toInt()).commit()
        writer.drawRoundRect(24f, 98f, right, 106f, 4f, 4f)
        if (clickId != null) writer.addClickArea(clickId, "Probe progress", 24f, 78f, 336f, 126f, null)
        writer.restore()
        return RemoteViews(RemoteViews.DrawInstructions.Builder(listOf(writer.encodeToByteArray())).build())
    }

    @Test
    fun superXHostRendersATimeDrivenProgressWithoutNotificationUpdates() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runVivoDrawingHost") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= 35 && Build.MANUFACTURER.equals("vivo", true))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val id = VivoForegroundProbeService.ID + 1
        assumeTrue(manager.areNotificationsEnabled() && manager.activeNotifications.none { it.id == id })
        assumeTrue(NotificationManager::class.java.getDeclaredMethod("getSceneStatus", String::class.java,
            String::class.java).invoke(manager, context.packageName, "NERIPLAYER_MUSIC") == true)
        val remote = animatedProgress(density = context.resources.displayMetrics.density)
        instrumentation.runOnMainSync { remote.apply(context, null) }
        val channel = "vivo_remote_drawing_probe"
        manager.createNotificationChannel(NotificationChannel(channel, "Vivo drawing test", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder.recoverBuilder(context,
            VivoMergedForegroundTest().fixtureNotification(context)).setChannelId(channel).build()
        notification.extras.putParcelable("notification.superx.customTemplate", remote)
        notification.extras.getBundle("notification.superx.island")!!
            .putParcelable("island.superx.customTemplate", remote)
        try {
            manager.notify(id, notification)
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000L
            while (manager.activeNotifications.none { it.id == id } && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            assertTrue("Drawing fixture was not posted", manager.activeNotifications.any { it.id == id })
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nDRAWING_HOST_READY id=$id\n") })
            // Capture via the exclusive ADB driver, avoiding ownership of the global accessibility service.
            Thread.sleep(20_000)
        } finally {
            manager.activeNotifications.filter { it.id == id }.forEach { manager.cancel(it.tag, it.id) }
            manager.cancel(id)
        }
    }

    @Test
    fun platformCanInflateDrawingInstructionsWithoutAnXmlLayout() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runVivoRemoteDrawing") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        assumeTrue(Build.MANUFACTURER.equals("vivo", ignoreCase = true))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val version = RemoteViews.DrawInstructions.getSupportedVersion()
            val remote = RemoteViews(RemoteViews.DrawInstructions.Builder(emptyList()).build())
            val view = remote.apply(instrumentation.targetContext, null)
            println("Remote drawing: version=$version, renderer=${view.javaClass.name}")
            assertTrue("The firmware did not create a RemoteCompose renderer", view.javaClass.name.contains("RemoteCompose"))
        }
    }
}
