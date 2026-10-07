package com.magistrat.musicplayer.player

import android.content.Context
import android.media.audiofx.Equalizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Ein Frequenzband des Geraete-Equalizers. */
data class EqBand(val index: Int, val centerHz: Int, val minMb: Int, val maxMb: Int)

data class EqState(
    val enabled: Boolean = false,
    val preset: String = "Normal",
    /** Pegel je Band in Millibel (1/100 dB) */
    val levels: List<Int> = emptyList(),
    val bands: List<EqBand> = emptyList(),
    /** false, solange kein Equalizer verbunden ist (z. B. Wiedergabe noch nie gestartet) */
    val available: Boolean = false,
)

/**
 * Equalizer auf der Audio-Session des Players mit eigenen Voreinstellungen.
 * Die Voreinstellungen sind als Kurve (dB je Frequenz) definiert und werden auf die
 * vom Geraet angebotenen Baender (meist 5) umgerechnet.
 */
object AudioEffects {
    private const val PREFS = "equalizer"
    const val CUSTOM = "Eigene"

    /** Voreinstellungen: Frequenz (Hz) -> Verstaerkung (dB) */
    val PRESETS: Map<String, (Int) -> Double> = linkedMapOf(
        "Normal" to { _ -> 0.0 },
        "Bass" to { f -> when { f <= 100 -> 6.0; f <= 300 -> 4.0; f <= 1000 -> 1.0; else -> 0.0 } },
        "Sprache" to { f -> when { f <= 150 -> -6.0; f <= 400 -> -2.0; f <= 1000 -> 1.0; f <= 4000 -> 4.0; else -> 2.0 } },
        "Klassik" to { f -> when { f <= 150 -> 4.0; f <= 500 -> 2.0; f <= 2000 -> 0.0; f <= 6000 -> 2.0; else -> 4.0 } },
        "Pop" to { f -> when { f <= 150 -> -1.0; f <= 500 -> 2.0; f <= 2000 -> 4.0; f <= 6000 -> 2.0; else -> -1.0 } },
        "Rock" to { f -> when { f <= 150 -> 5.0; f <= 500 -> 3.0; f <= 2000 -> -1.0; f <= 6000 -> 3.0; else -> 5.0 } },
        "Höhen" to { f -> when { f <= 2000 -> 0.0; f <= 6000 -> 3.0; else -> 5.0 } },
    )

    private var eq: Equalizer? = null
    private val _state = MutableStateFlow(EqState())
    val state: StateFlow<EqState> = _state

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Vom PlaybackService aufgerufen, sobald die Audio-Session bekannt ist. */
    fun attach(context: Context, audioSessionId: Int) {
        release()
        try {
            val e = Equalizer(0, audioSessionId)
            eq = e
            val range = e.bandLevelRange
            val bands = (0 until e.numberOfBands).map { i ->
                EqBand(i, e.getCenterFreq(i.toShort()) / 1000, range[0].toInt(), range[1].toInt())
            }
            val p = prefs(context)
            val preset = p.getString("preset", "Normal") ?: "Normal"
            val saved = p.getString("levels", null)?.split(',')?.mapNotNull { it.toIntOrNull() }
            val levels = if (preset == CUSTOM && saved != null && saved.size == bands.size) saved else levelsFor(preset, bands)
            _state.value = EqState(
                enabled = p.getBoolean("enabled", false),
                preset = preset,
                levels = levels,
                bands = bands,
                available = true,
            )
            apply()
        } catch (t: Throwable) {
            Log.w("AudioEffects", "Equalizer nicht verfuegbar", t)
            eq = null
            _state.value = _state.value.copy(available = false)
        }
    }

    fun release() {
        try {
            eq?.release()
        } catch (_: Throwable) {
        }
        eq = null
    }

    private fun levelsFor(preset: String, bands: List<EqBand>): List<Int> {
        val curve = PRESETS[preset] ?: PRESETS.getValue("Normal")
        return bands.map { b -> (curve(b.centerHz) * 100).toInt().coerceIn(b.minMb, b.maxMb) }
    }

    private fun apply() {
        val e = eq ?: return
        val s = _state.value
        try {
            s.levels.forEachIndexed { i, mb -> e.setBandLevel(i.toShort(), mb.toShort()) }
            e.enabled = s.enabled
        } catch (t: Throwable) {
            Log.w("AudioEffects", "Equalizer-Fehler", t)
        }
    }

    private fun save(context: Context) {
        val s = _state.value
        prefs(context).edit()
            .putBoolean("enabled", s.enabled)
            .putString("preset", s.preset)
            .putString("levels", s.levels.joinToString(","))
            .apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        _state.value = _state.value.copy(enabled = enabled)
        apply()
        save(context)
    }

    fun setPreset(context: Context, preset: String) {
        val s = _state.value
        _state.value = s.copy(preset = preset, enabled = true, levels = if (preset == CUSTOM) s.levels else levelsFor(preset, s.bands))
        apply()
        save(context)
    }

    fun setBand(context: Context, index: Int, mb: Int) {
        val s = _state.value
        val levels = s.levels.toMutableList()
        if (index !in levels.indices) return
        levels[index] = mb
        _state.value = s.copy(levels = levels, preset = CUSTOM, enabled = true)
        apply()
        save(context)
    }
}
