package moe.ouom.neriplayer.ui.screen.tab.settings.component

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.Coil
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.service.presentation.VivoMusicIslandPreview
import moe.ouom.neriplayer.data.local.media.displayCoverUrl
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.appearance.MAX_VIVO_ISLAND_BLUR_RADIUS
import moe.ouom.neriplayer.data.model.settings.appearance.VivoMusicIslandAppearance
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSlider
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import kotlin.math.roundToInt

@Composable
internal fun VivoMusicIslandSettings(repository: SettingsRepository) {
    val saved by repository.vivoMusicIslandAppearanceFlow.collectAsStateWithLifecycle(VivoMusicIslandAppearance())
    var draft by remember { mutableStateOf(saved) }
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(saved) { if (!dragging) draft = saved }
    val song by PlayerManager.currentSongFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coverSource = song?.displayCoverUrl(context)
    val artwork by produceState<Bitmap?>(null, coverSource) {
        value = null
        if (!coverSource.isNullOrBlank()) {
            val request = ImageRequest.Builder(context).data(coverSource).size(512).allowHardware(false).build()
            value = Coil.imageLoader(context).execute(request).drawable?.toBitmap()
        }
    }
    fun save() {
        dragging = false
        val next = draft
        scope.launch { repository.setVivoMusicIslandAppearance(next) }
    }
    VivoMusicIslandAppearanceControls(draft, song, artwork,
        onChange = { dragging = true; draft = it }, onFinished = ::save,
        onReset = { draft = VivoMusicIslandAppearance(); save() })
}

@Composable
internal fun VivoMusicIslandAppearanceControls(
    appearance: VivoMusicIslandAppearance,
    song: SongItem?,
    artwork: Bitmap?,
    onChange: (VivoMusicIslandAppearance) -> Unit,
    onFinished: () -> Unit,
    onReset: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        IslandAppearanceSlider(
            stringResource(R.string.settings_vivo_island_mask),
            stringResource(R.string.settings_vivo_island_percent, appearance.maskOpacityPercent),
            appearance.maskOpacityPercent.toFloat(), 0f..100f, 0,
            onChange = { onChange(appearance.copy(maskOpacityPercent = it.roundToInt())) },
            onFinished = onFinished,
        )
        IslandAppearanceSlider(
            stringResource(R.string.settings_vivo_island_blur),
            stringResource(R.string.settings_vivo_island_pixels, appearance.blurRadius),
            appearance.blurRadius.toFloat(), 0f..MAX_VIVO_ISLAND_BLUR_RADIUS.toFloat(), MAX_VIVO_ISLAND_BLUR_RADIUS - 1,
            onChange = { onChange(appearance.copy(blurRadius = it.roundToInt())) },
            onFinished = onFinished,
        )
        AndroidView(
            factory = { VivoMusicIslandPreview(it) },
            update = { it.update(song, artwork, appearance) },
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).testTag("vivo-island-preview"),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            MiuixSettingsTextButton(onClick = onReset) {
                Text(stringResource(R.string.settings_vivo_island_reset))
            }
        }
    }
}

@Composable
private fun IslandAppearanceSlider(
    title: String, label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int,
    onChange: (Float) -> Unit, onFinished: () -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        MiuixSettingsSlider(value, onChange, modifier = Modifier.semantics { contentDescription = title },
            valueRange = range, steps = steps, onValueChangeFinished = onFinished)
    }
}
