package com.magistrat.musicplayer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.magistrat.musicplayer.update.UpdateInfo
import com.magistrat.musicplayer.update.Updater
import kotlinx.coroutines.launch
import java.io.File

/** Bietet eine neue App-Version an, laedt sie herunter und startet die Installation. */
@Composable
fun UpdateDialog(info: UpdateInfo) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }
    val downloading = progress != null && apk == null && error == null

    fun installOrAskPermission(file: File) {
        if (Updater.canInstall(context)) Updater.install(context, file)
        else Updater.openInstallPermissionSettings(context)
    }

    AlertDialog(
        onDismissRequest = { if (!downloading) Updater.dismiss(context, skip = false) },
        title = { Text("Update verfügbar") },
        text = {
            Column {
                Text("Neue Version: ${info.name}\nInstalliert: ${Updater.currentVersionName} (Build ${Updater.currentVersionCode})")
                if (info.sizeBytes > 0) Text("Größe: ${info.sizeBytes / (1024 * 1024)} MB")
                progress?.let {
                    LinearProgressIndicator(
                        progress = { it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                }
                if (apk != null && !Updater.canInstall(context)) {
                    Text(
                        "Bitte im nächsten Bildschirm „Apps aus dieser Quelle zulassen“ aktivieren, dann zurückkehren und erneut auf „Installieren“ tippen.",
                        Modifier.padding(top = 12.dp),
                    )
                }
                error?.let { Text("Fehler: $it", Modifier.padding(top = 12.dp)) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = {
                    val file = apk
                    if (file != null) {
                        installOrAskPermission(file)
                    } else {
                        error = null
                        progress = 0f
                        scope.launch {
                            try {
                                val f = Updater.download(context, info) { progress = it }
                                apk = f
                                progress = 1f
                                installOrAskPermission(f)
                            } catch (e: Exception) {
                                error = e.message ?: e.javaClass.simpleName
                                progress = null
                            }
                        }
                    }
                },
            ) { Text(if (downloading) "Lädt…" else "Installieren") }
        },
        dismissButton = {
            if (!downloading) {
                TextButton(onClick = { Updater.dismiss(context, skip = false) }) { Text("Später") }
            }
        },
    )
}
