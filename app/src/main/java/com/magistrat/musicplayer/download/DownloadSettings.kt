package com.magistrat.musicplayer.download

import android.content.Context

/** Einstellungen fuer YouTube-Downloads. */
data class DownloadSettings(
    /** Lautstaerke angleichen (ffmpeg loudnorm) */
    val normalize: Boolean,
    /** Werbung / Nicht-Musik-Teile per SponsorBlock herausschneiden */
    val sponsorBlock: Boolean,
) {
    companion object {
        private const val PREFS = "download"

        fun get(context: Context): DownloadSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return DownloadSettings(
                normalize = p.getBoolean("normalize", true),
                sponsorBlock = p.getBoolean("sponsorblock", true),
            )
        }

        fun set(context: Context, s: DownloadSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("normalize", s.normalize)
                .putBoolean("sponsorblock", s.sponsorBlock)
                .apply()
        }
    }
}
