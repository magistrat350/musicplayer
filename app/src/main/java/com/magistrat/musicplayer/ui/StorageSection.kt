package com.magistrat.musicplayer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.magistrat.musicplayer.App
import com.magistrat.musicplayer.player.PlayerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Speicherort fuer Downloads waehlen und vorhandene Downloads dorthin verschieben. */
@Composable
fun StorageSection() {
    val context = LocalContext.current
    val app = App.instance
    val repo = app.repo
    var refresh by remember { mutableIntStateOf(0) }
    var folderName by remember { mutableStateOf<String?>(null) }
    var inAppStorage by remember { mutableIntStateOf(0) }
    var moving by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var resultText by remember { mutableStateOf<String?>(null) }
    var confirmMove by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        folderName = withContext(Dispatchers.IO) { repo.downloadFolderName() }
        inAppStorage = repo.countFilesInAppStorage()
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                repo.setDownloadTree(uri)
                resultText = null
                refresh++
                // Direkt anbieten, das bisher Geladene mitzunehmen
                app.appScope.launch {
                    if (repo.countFilesInAppStorage() > 0) withContext(Dispatchers.Main) { confirmMove = true }
                }
            } catch (e: Exception) {
                toast(context, "Ordner konnte nicht übernommen werden")
            }
        }
    }

    fun startMove() {
        moving = 0 to inAppStorage
        app.appScope.launch(Dispatchers.Main) {
            // Laufende Wiedergabe beenden, damit keine verschobene Datei mehr in der Warteschlange steht
            PlayerConnection.saveNow()
            PlayerConnection.controller?.let {
                it.stop()
                it.clearMediaItems()
            }
            val moved = try {
                repo.moveDownloadsToTree { done, total -> moving = done to total }
            } catch (e: Exception) {
                -1
            }
            moving = null
            resultText = if (moved < 0) "Verschieben fehlgeschlagen" else "$moved Datei(en) verschoben"
            refresh++
        }
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Speicherort", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                ) {
                    Text(folderName?.let { "Ordner: $it" } ?: "App-Speicher (versteckt)", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (folderName != null) "Songs landen in „Musik“, Hörbücher in „Hörbücher/<Titel>“ – sichtbar im Dateimanager und für andere Apps, bleiben auch nach dem Deinstallieren erhalten."
                        else "Nur für diese App sichtbar und wird beim Deinstallieren gelöscht. Wähle einen Ordner, z. B. „Music/MusicPlayer“.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickFolder.launch(null) }, enabled = moving == null) {
                Text(if (folderName == null) "Ordner wählen" else "Ordner ändern")
            }
            if (folderName != null) {
                TextButton(onClick = {
                    repo.setDownloadTree(null)
                    refresh++
                }, enabled = moving == null) { Text("App-Speicher nutzen") }
            }
        }
        if (folderName != null && inAppStorage > 0 && moving == null) {
            OutlinedButton(onClick = { confirmMove = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.DriveFileMove, null)
                Text("  $inAppStorage vorhandene Datei(en) hierher verschieben")
            }
        }
        moving?.let { (done, total) ->
            Text("Verschiebe… $done / $total", style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(
                progress = { if (total > 0) done / total.toFloat() else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        resultText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }

    if (confirmMove) {
        ConfirmDialog(
            title = "Vorhandene Downloads verschieben?",
            text = "Alle bisher geladenen Songs und Hörbücher werden in „${folderName ?: "den Ordner"}“ verschoben " +
                "und bekommen lesbare Namen. Playlists, Lesezeichen und Fortschritt bleiben erhalten. " +
                "Die laufende Wiedergabe wird dafür beendet.",
            confirm = "Verschieben",
            onDismiss = { confirmMove = false },
        ) {
            confirmMove = false
            startMove()
        }
    }
}
