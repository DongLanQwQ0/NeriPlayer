package moe.ouom.neriplayer.ui.screen.tab.settings.component

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSlider
import moe.ouom.neriplayer.widget.PLAYBACK_WIDGET_TUNING_MAX_RADIUS_PX
import moe.ouom.neriplayer.widget.PlaybackWidgetBlurTuning
import moe.ouom.neriplayer.widget.PlaybackWidgetSize
import moe.ouom.neriplayer.widget.playbackWidgetBackdrop
import kotlin.math.roundToInt

@Composable
internal fun PlaybackWidgetBlurTuningControls(artwork: Bitmap?) {
    val context = LocalContext.current
    var radius by remember { mutableIntStateOf(PlaybackWidgetBlurTuning.radius(context)) }
    val requestedRadius = radius
    val preview by produceState<Bitmap?>(null, artwork, radius) {
        val source = artwork ?: return@produceState
        // Coalesce drag events; never decode a cover or blur on the UI thread.
        delay(75)
        value = withContext(Dispatchers.Default) {
            playbackWidgetBackdrop(source, PlaybackWidgetSize(360, 240), 24f,
                applyScrim = true, renderScale = 2f, blurRadiusPx = requestedRadius)
        }
    }
    val title = stringResource(R.string.widget_blur_tuning_title)
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text("$radius px", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
        MiuixSettingsSlider(radius.toFloat(), { radius = it.roundToInt() },
            modifier = Modifier.semantics { contentDescription = title },
            valueRange = 0f..PLAYBACK_WIDGET_TUNING_MAX_RADIUS_PX.toFloat(),
            steps = PLAYBACK_WIDGET_TUNING_MAX_RADIUS_PX - 1,
            onValueChangeFinished = { PlaybackWidgetBlurTuning.setRadius(context, radius) })
        preview?.let { bitmap ->
            Image(bitmap.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(1.5f)
                    .clip(RoundedCornerShape(24.dp)).testTag("widget-blur-tuning-preview"))
        }
    }
}
