package moe.ouom.neriplayer.data.model.settings.appearance

import kotlin.math.roundToInt

const val DEFAULT_VIVO_ISLAND_MASK_PERCENT = 60
const val DEFAULT_VIVO_ISLAND_BLUR_RADIUS = 2
const val MAX_VIVO_ISLAND_BLUR_RADIUS = 12

data class VivoMusicIslandAppearance(
    val maskOpacityPercent: Int = DEFAULT_VIVO_ISLAND_MASK_PERCENT,
    val blurRadius: Int = DEFAULT_VIVO_ISLAND_BLUR_RADIUS,
) {
    fun normalized() = copy(
        maskOpacityPercent = maskOpacityPercent.coerceIn(0, 100),
        blurRadius = blurRadius.coerceIn(0, MAX_VIVO_ISLAND_BLUR_RADIUS),
    )

    // Retain the existing top-to-bottom gradient; 0 removes the entire mask.
    val bottomScrimAlpha: Int get() = (maskOpacityPercent.coerceIn(0, 100) * 255f / 100f).roundToInt()
    val topScrimAlpha: Int get() = (bottomScrimAlpha * 184f / 214f).roundToInt()
}
