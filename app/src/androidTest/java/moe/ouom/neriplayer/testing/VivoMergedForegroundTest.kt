package moe.ouom.neriplayer.testing

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Characterizes OEM retagging using an isolated ID, without replacing a user's media notice. */
@ManualInstrumentedTest
@RequiresApi(31)
@RunWith(AndroidJUnit4::class)
class VivoMergedForegroundTest {
    @Test
    fun superXRetaggingKeepsTheForegroundIdentitySeparate() {
        android.util.Log.i("VivoFgProbe", "enter")
        assumeTrue(InstrumentationRegistry.getArguments().getString("runVivoMergedForeground") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        assumeTrue(Build.MANUFACTURER.equals("vivo", ignoreCase = true))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val id = VivoForegroundProbeService.ID
        assumeFalse(AudioPlayerService.isForegroundActiveForDiagnostics())
        assumeTrue(manager.areNotificationsEnabled())
        assumeTrue(manager.activeNotifications.none { it.id == id })
        assumeTrue(NotificationManager::class.java.getDeclaredMethod("getSceneStatus", String::class.java,
            String::class.java).invoke(manager, context.packageName, "NERIPLAYER_MUSIC") == true)
        android.util.Log.i("VivoFgProbe", "conditions checked")
        val channel = "vivo_merged_foreground_probe"
        manager.createNotificationChannel(NotificationChannel(channel, "Vivo foreground test", NotificationManager.IMPORTANCE_LOW))
        // The ADB test driver brings MainActivity to the foreground after instrumentation starts.
        // Require normal visible-app FGS admission without changing player or activity ownership.
        await("Bring the test application to the foreground") {
            var resumed = false
            instrumentation.runOnMainSync {
                resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .any { it.packageName == context.packageName }
            }
            resumed
        }
        android.util.Log.i("VivoFgProbe", "activity resumed")
        val session = MediaSession(context, "vivo-foreground-probe")
        val service = Intent(context, VivoForegroundProbeService::class.java)
        try {
            val notification = Notification.Builder.recoverBuilder(context, customNotification(context))
                .setChannelId(channel)
                .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
            android.util.Log.i("VivoFgProbe", "notification built")
            // Match AudioPlayerService: admit a small bootstrap first, then update its existing ID.
            val bootstrap = Notification.Builder(context, channel)
                .setSmallIcon(moe.ouom.neriplayer.common.R.drawable.ic_notification_small)
                .setContentTitle("Vivo foreground bootstrap")
                .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
                .setOngoing(true)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
            context.startForegroundService(Intent(service).putExtra(VivoForegroundProbeService.EXTRA_NOTIFICATION, bootstrap))
            await("Bootstrap foreground notice was not posted") { manager.activeNotifications.any { it.id == id } }
            assertForeground(context)
            manager.notify(id, notification)
            await("Merged foreground notice did not enter SuperX") {
                manager.activeNotifications.any { it.id == id && it.tag == "VIVO_SUPERX_TAG" }
            }
            assertForeground(context)
            val first = manager.activeNotifications.filter { it.id == id }
            println("Merged FGS start: ${first.map { "tag=${it.tag}, flags=${it.notification.flags}" }}")
            assertEquals("Recheck the firmware's notification identity behavior", 2, first.size)
            assertTrue("Bootstrap lost its foreground flag",
                first.single { it.tag == null }.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
            assertEquals("SuperX unexpectedly acquired foreground ownership", 0,
                first.single { it.tag == "VIVO_SUPERX_TAG" }.notification.flags and Notification.FLAG_FOREGROUND_SERVICE)

            val update = Notification.Builder.recoverBuilder(context, notification).setContentTitle("Vivo merged update").build()
            update.extras.putInt("notification.superx.operation", 1)
            manager.notify(id, update)
            await("Merged update was not delivered") {
                manager.activeNotifications.any { it.id == id &&
                    it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "Vivo merged update" }
            }
            assertEquals("Update changed the notification identities", 2, manager.activeNotifications.count { it.id == id })
            assertForeground(context)
            println("Merged FGS update: ${manager.activeNotifications.filter { it.id == id }.map { "tag=${it.tag}, flags=${it.notification.flags}" }}")
            context.stopService(service)
            await("Foreground service did not stop") { !VivoForegroundProbeService.running }
            await("Stopping the service left its foreground notification") {
                manager.activeNotifications.none { it.id == id && it.tag == null }
            }
            // The OEM card has independent ownership; explicitly remove its actual identity.
            manager.activeNotifications.filter { it.id == id }.forEach { manager.cancel(it.tag, it.id) }
            await("Explicit cancellation left a retagged notification") { manager.activeNotifications.none { it.id == id } }
        } finally {
            context.stopService(service)
            manager.activeNotifications.filter { it.id == id }.forEach { manager.cancel(it.tag, it.id) }
            manager.cancel(id)
            session.release()
        }
    }

    private fun assertForeground(context: Context) {
        @Suppress("DEPRECATION")
        val active = context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == VivoForegroundProbeService::class.java.name && it.foreground }
        assertTrue("ActivityManager lost the foreground service", active && VivoForegroundProbeService.running)
    }

    private fun customNotification(context: Context): Notification {
        val type = Class.forName("moe.ouom.neriplayer.core.player.service.presentation.VivoMusicIslandBridge")
        val bridge = type.getDeclaredConstructor(Context::class.java).newInstance(context)
        val lyricType = Class.forName("moe.ouom.neriplayer.core.player.service.notification.StatusBarLyricNotificationState")
        val lyric = lyricType.getDeclaredConstructor(Boolean::class.javaPrimitiveType, String::class.java).newInstance(false, null)
        val inputsType = Class.forName("moe.ouom.neriplayer.core.player.service.presentation.PlaybackServiceNotificationRenderInputs")
        val inputs = inputsType.declaredConstructors.single { it.parameterCount == 13 }.newInstance(
            SongItem(-VivoForegroundProbeService.ID.toLong(), "Vivo merged foreground test", "Android test", "test", 0L, 60_000L, null),
            "Android test", false, false, false, false, false, lyric, null, null, 15_000L, null, 60_000L,
        )
        return type.declaredMethods.single { it.name.startsWith("notification$") }.invoke(bridge, inputs, 0) as Notification
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue(message, condition())
    }
}
