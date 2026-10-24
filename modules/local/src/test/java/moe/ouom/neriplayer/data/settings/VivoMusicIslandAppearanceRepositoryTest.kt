package moe.ouom.neriplayer.data.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.settings.appearance.VivoMusicIslandAppearance
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VivoMusicIslandAppearanceRepositoryTest {
    @Test
    fun defaultsPersistAndClampBothControls() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        IsolatedSettingsDataStore.withEmptySettings(context) {
            val repository = SettingsRepository(context)
            val defaults = VivoMusicIslandAppearance()
            assertEquals(60, defaults.maskOpacityPercent)
            assertEquals(2, defaults.blurRadius)
            assertEquals(defaults, repository.vivoMusicIslandAppearanceFlow.first())
            repository.setVivoMusicIslandAppearance(defaults.copy(maskOpacityPercent = 35, blurRadius = 7))
            assertEquals(defaults.copy(maskOpacityPercent = 35, blurRadius = 7),
                SettingsRepository(context).vivoMusicIslandAppearanceFlow.first())
            repository.setVivoMusicIslandAppearance(defaults.copy(maskOpacityPercent = -1, blurRadius = 99))
            assertEquals(defaults.copy(maskOpacityPercent = 0, blurRadius = 12), repository.vivoMusicIslandAppearanceFlow.first())
            repository.setVivoMusicIslandAppearance(defaults)
            assertEquals(defaults, repository.vivoMusicIslandAppearanceFlow.first())
        }
    }
}
