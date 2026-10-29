package moe.ouom.neriplayer.core.player.service.presentation

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.core.player.host.PlayerPresentationHost
import moe.ouom.neriplayer.core.player.testing.PlayerTestEnvironment
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PlaybackPresentationLocaleTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val savedLanguage = LanguageManager.getCurrentLanguage(context)
    private val savedLocale = Locale.getDefault()

    @After
    fun restoreLanguage() {
        LanguageManager.setLanguage(context, savedLanguage)
        Locale.setDefault(savedLocale)
    }

    @Test
    fun `service presentation resolves current language while preserving existing channel options`() {
        val port = AndroidPlaybackServicePresentationPort(context)
        val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
        manager.createNotificationChannel(
            NotificationChannel(AudioPlayerService.CHANNEL_ID, "Existing", NotificationManager.IMPORTANCE_HIGH)
                .apply { enableVibration(true) }
        )
        for ((language, ready, channel) in listOf(
            Triple(LanguageManager.Language.ENGLISH, "Ready", "NeriPlayer Playback"),
            Triple(LanguageManager.Language.CHINESE, "准备就绪", "NeriPlayer 播放"),
            Triple(LanguageManager.Language.ENGLISH, "Ready", "NeriPlayer Playback")
        )) {
            LanguageManager.setLanguage(context, language)
            port.ensureNotificationChannel()
            assertEquals(ready, port.widgetLabels().ready)
            assertEquals(ready, port.localizedString(CoreCommonR.string.widget_playback_ready, null))
            val updated = checkNotNull(manager.getNotificationChannel(AudioPlayerService.CHANNEL_ID))
            assertEquals(channel, updated.name)
            assertEquals(NotificationManager.IMPORTANCE_HIGH, updated.importance)
            assertTrue(updated.shouldVibrate())
        }
    }

    @Test
    fun `vivo bootstrap keeps the cached transparent icon while following language changes`() {
        val manufacturer = Build.MANUFACTURER
        try {
            ShadowBuild.setManufacturer("vivo")
            val presentation = mock(PlayerPresentationHost::class.java)
            `when`(presentation.mainActivityIntent(context)).thenReturn(Intent("test.OPEN").setPackage(context.packageName))
            PlayerTestEnvironment.install(presentation = presentation)
            val port = AndroidPlaybackServicePresentationPort(context)
            LanguageManager.setLanguage(context, LanguageManager.Language.ENGLISH)
            val english = port.buildBootstrapNotification()
            assertEquals(Icon.TYPE_BITMAP, english.smallIcon.type)
            assertEquals(LanguageManager.applyLanguage(context).getString(CoreCommonR.string.player_notification_preparing),
                english.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            LanguageManager.setLanguage(context, LanguageManager.Language.CHINESE)
            val chinese = port.buildBootstrapNotification()
            assertSame(english.smallIcon, chinese.smallIcon)
            assertEquals(LanguageManager.applyLanguage(context).getString(CoreCommonR.string.player_notification_preparing),
                chinese.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertTrue(english.extras.getCharSequence(Notification.EXTRA_TEXT) != chinese.extras.getCharSequence(Notification.EXTRA_TEXT))
        } finally {
            ShadowBuild.setManufacturer(manufacturer)
            PlayerTestEnvironment.reset()
        }
    }
}
