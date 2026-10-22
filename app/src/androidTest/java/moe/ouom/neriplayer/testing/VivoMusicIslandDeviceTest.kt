package moe.ouom.neriplayer.testing

import android.app.NotificationManager
import android.app.Notification
import android.app.NotificationChannel
import android.content.Context
import android.os.Build
import moe.ouom.neriplayer.data.model.SongItem
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Scene configuration requires explicit ADB opt-in; the cancellation test only owns fixture notices. */
@RunWith(AndroidJUnit4::class)
class VivoMusicIslandDeviceTest {
    @Test
    fun cancellationUsesTheActualNotificationIdentity() {
        assumeTrue(Build.MANUFACTURER.equals("vivo", ignoreCase = true))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        assumeTrue(manager.areNotificationsEnabled())
        val id = 0x4e505649
        val controlId = id + 100
        val tag = "VIVO_SUPERX_TAG"
        val channel = "vivo_music_island_test"
        manager.createNotificationChannel(NotificationChannel(channel, "vivo music test", NotificationManager.IMPORTANCE_LOW))
        val control = Notification.Builder(context, channel)
            .setSmallIcon(moe.ouom.neriplayer.common.R.drawable.ic_notification_small)
            .setContentTitle("vivo music test").build()
        assumeTrue(NotificationManager::class.java.getDeclaredMethod("getSceneStatus", String::class.java,
            String::class.java).invoke(manager, context.packageName, "NERIPLAYER_MUSIC") == true)
        manager.createNotificationChannel(NotificationChannel("neriplayer_vivo_music_island", "vivo 原子岛", NotificationManager.IMPORTANCE_LOW))
        val type = Class.forName("moe.ouom.neriplayer.core.player.service.presentation.VivoMusicIslandBridge")
        val bridge = type.getDeclaredConstructor(Context::class.java).newInstance(context)
        val lyricType = Class.forName("moe.ouom.neriplayer.core.player.service.notification.StatusBarLyricNotificationState")
        val lyric = lyricType.getDeclaredConstructor(Boolean::class.javaPrimitiveType, String::class.java)
            .newInstance(false, null)
        val inputsType = Class.forName("moe.ouom.neriplayer.core.player.service.presentation.PlaybackServiceNotificationRenderInputs")
        val inputs = inputsType.declaredConstructors.single { it.parameterCount == 13 }.newInstance(
            SongItem(1L, "vivo music test", "androidTest", "test", 0L, 60_000L, null),
            "androidTest", false, true, false, false, false, lyric, null, null, 15_000L, "A real lyric line", 30_000L,
        )
        val notification = type.declaredMethods.single { it.name.startsWith("notification$") }
            .invoke(bridge, inputs, 0) as Notification
        // Inflate the actual production RemoteViews: catches unsupported widgets and remote setters.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val remote = notification.extras.getParcelable("notification.superx.customTemplate", android.widget.RemoteViews::class.java)!!
            val card = remote.apply(context, null)
            fun id(name: String) = context.resources.getIdentifier(name, "id", context.packageName)
            assertEquals("00:15", card.findViewById<android.widget.TextView>(id("vivo_music_elapsed")).text.toString())
            assertEquals("00:30", card.findViewById<android.widget.TextView>(id("vivo_music_duration")).text.toString())
            assertEquals("A real lyric line", card.findViewById<android.widget.TextView>(id("vivo_music_artist")).text.toString())
            assertEquals(500, card.findViewById<android.widget.ProgressBar>(id("vivo_music_progress")).progress)
        }
        try {
            manager.notify(id, notification)
            manager.notify(controlId, control)
            await("Tagged fixture was not posted") {
                manager.activeNotifications.any { it.id == id && it.tag == tag } &&
                    manager.activeNotifications.any { it.id == controlId }
            }
            type.getDeclaredMethod("clear").invoke(bridge)
            await("Music island was not cancelled") { manager.activeNotifications.none { it.id == id } }
            assertTrue(manager.activeNotifications.any { it.id == controlId })
        } finally {
            manager.cancel(tag, id)
            manager.cancel(controlId)
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000L
        while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue(message, condition())
    }

    @Test
    fun configureSceneForExplicitAdbTest() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("configureVivoScene") == "true")
        assumeTrue(Build.MANUFACTURER.equals("vivo", ignoreCase = true))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        val scene = "NERIPLAYER_MUSIC"
        val enabled = InstrumentationRegistry.getArguments().getString("vivoSceneEnabled", "true") == "true"
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.STATUS_BAR_SERVICE")
        try {
            NotificationManager::class.java.getDeclaredMethod("setSuperXInfosSceneList",
                List::class.java, List::class.java, List::class.java, List::class.java,
            ).invoke(manager, listOf(scene), listOf(enabled.toString()), listOf(context.packageName), listOf(enabled.toString()))
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
        assertTrue(NotificationManager::class.java.getDeclaredMethod("getSceneStatus",
            String::class.java, String::class.java,
        ).invoke(manager, context.packageName, scene) == enabled)
    }
}
