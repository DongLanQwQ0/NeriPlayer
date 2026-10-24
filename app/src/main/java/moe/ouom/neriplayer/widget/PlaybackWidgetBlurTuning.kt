package moe.ouom.neriplayer.widget

import android.content.Context
import androidx.core.content.edit
import moe.ouom.neriplayer.BuildConfig

internal const val PLAYBACK_WIDGET_TUNING_MAX_RADIUS_PX = 48

internal fun playbackWidgetTuningRadius(enabled: Boolean, stored: Int): Int =
    if (enabled) stored.coerceIn(0, PLAYBACK_WIDGET_TUNING_MAX_RADIUS_PX)
    else PLAYBACK_WIDGET_DEFAULT_BLUR_RADIUS_PX

/** Kept outside backed-up user settings: this preference only applies to opted-in local builds. */
internal object PlaybackWidgetBlurTuning {
    private const val PREFS = "playback_widget_blur_tuning"
    private const val RADIUS = "radius"

    fun radius(context: Context): Int {
        if (!BuildConfig.WIDGET_BLUR_TUNING) return PLAYBACK_WIDGET_DEFAULT_BLUR_RADIUS_PX
        return playbackWidgetTuningRadius(true, context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(RADIUS, PLAYBACK_WIDGET_DEFAULT_BLUR_RADIUS_PX))
    }

    fun setRadius(context: Context, radius: Int) {
        check(BuildConfig.WIDGET_BLUR_TUNING)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt(RADIUS, radius.coerceIn(0, PLAYBACK_WIDGET_TUNING_MAX_RADIUS_PX))
        }
        PlaybackWidgetUpdater.refreshForConfigurationChange(context)
    }
}
