package com.magistrat.musicplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.magistrat.musicplayer.player.AudioEffects

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val eq by AudioEffects.state.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Equalizer") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
                actions = {
                    Switch(
                        checked = eq.enabled,
                        onCheckedChange = { AudioEffects.setEnabled(context, it) },
                        enabled = eq.available,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .navigationBarsPadding()
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            if (!eq.available) {
                EmptyHint("Der Equalizer ist verfügbar, sobald einmal etwas abgespielt wurde. Manche Geräte unterstützen ihn nicht.")
                return@Column
            }
            Text(
                "Voreinstellung",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AudioEffects.PRESETS.keys.toList() + AudioEffects.CUSTOM) { name ->
                    FilterChip(
                        selected = eq.enabled && eq.preset == name,
                        onClick = { AudioEffects.setPreset(context, name) },
                        label = { Text(name) },
                    )
                }
            }
            Text(
                "Tipp: „Sprache“ macht Hörbücher und Podcasts deutlich verständlicher.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                "Bänder",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            )
            eq.bands.forEach { band ->
                val level = eq.levels.getOrNull(band.index) ?: 0
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(formatHz(band.centerHz), Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = level.toFloat(),
                        onValueChange = { AudioEffects.setBand(context, band.index, it.toInt()) },
                        valueRange = band.minMb.toFloat()..band.maxMb.toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "%+.1f dB".format(level / 100f),
                        Modifier
                            .width(64.dp)
                            .padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun formatHz(hz: Int): String = if (hz >= 1000) "%.1f kHz".format(hz / 1000f).replace(".0 ", " ") else "$hz Hz"
