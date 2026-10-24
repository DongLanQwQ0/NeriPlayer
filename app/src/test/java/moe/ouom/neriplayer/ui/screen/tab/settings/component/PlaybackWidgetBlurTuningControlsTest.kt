package moe.ouom.neriplayer.ui.screen.tab.settings.component

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import moe.ouom.neriplayer.widget.PlaybackWidgetBlurTuning
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaybackWidgetBlurTuningControlsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun `local tuning slider previews and persists different radii without a playback command`() {
        val cover = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        rule.setContent { MaterialTheme { PlaybackWidgetBlurTuningControls(cover) } }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTag("widget-blur-tuning-preview").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("widget-blur-tuning-preview").assertIsDisplayed()
        for (radius in listOf(0, 1, 2, 6, 24)) {
            rule.onNodeWithContentDescription("小组件模糊（调试）")
                .performSemanticsAction(SemanticsActions.SetProgress) { it(radius.toFloat()) }
            rule.onNodeWithText("$radius px").assertIsDisplayed()
            assertEquals(radius, PlaybackWidgetBlurTuning.radius(rule.activity))
        }
    }
}
