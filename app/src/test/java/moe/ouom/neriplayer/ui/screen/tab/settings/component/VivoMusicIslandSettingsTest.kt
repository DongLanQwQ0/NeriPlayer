package moe.ouom.neriplayer.ui.screen.tab.settings.component

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.core.view.allViews
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.widget.ImageView
import android.graphics.drawable.BitmapDrawable
import moe.ouom.neriplayer.core.player.service.presentation.VivoMusicIslandPreview
import java.io.File
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.appearance.VivoMusicIslandAppearance
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "zh-rCN-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VivoMusicIslandSettingsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun slidersUpdateThePreviewImmediatelyAndResetBothValues() {
        var appearance by mutableStateOf(VivoMusicIslandAppearance())
        val cover = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val song = SongItem(1, "Morning Light", "Album artist", "Album", 0, 180_000, null)
        rule.setContent {
            MaterialTheme {
                VivoMusicIslandAppearanceControls(appearance, song, cover,
                    onChange = { appearance = it }, onFinished = {},
                    onReset = { appearance = VivoMusicIslandAppearance() })
            }
        }
        val preview = rule.onNodeWithTag("vivo-island-preview").assertIsDisplayed()
        val before = renderedPreview()
        val dark = Color.red(before.getPixel(before.width - 12, 12))
        rule.onNodeWithContentDescription("遮罩浓度").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        rule.onNodeWithText("0%").assertIsDisplayed()
        val after = renderedPreview()
        assertTrue("Preview did not brighten while changing the mask", Color.red(after.getPixel(after.width - 12, 12)) > dark + 75)
        rule.onNodeWithContentDescription("模糊半径").performSemanticsAction(SemanticsActions.SetProgress) { it(6f) }
        rule.onNodeWithText("6 px").assertIsDisplayed()
        val blurBottom = rule.onNodeWithContentDescription("模糊半径").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(preview.fetchSemanticsNode().boundsInRoot.top >= blurBottom)
        rule.onNodeWithText("恢复默认").performClick()
        rule.onNodeWithText("60%").assertIsDisplayed()
        rule.onNodeWithText("2 px").assertIsDisplayed()
        val output = File("build/reports/vivo-island-settings/controls.png")
        output.parentFile.mkdirs()
        renderedPreview().let { image ->
            output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun renderedPreview(): Bitmap {
        var bitmap: Bitmap? = null
        rule.runOnIdle {
            val view = rule.activity.window.decorView.allViews.filterIsInstance<VivoMusicIslandPreview>().single()
            bitmap = ((view as ImageView).drawable as BitmapDrawable).bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        return checkNotNull(bitmap)
    }
}
