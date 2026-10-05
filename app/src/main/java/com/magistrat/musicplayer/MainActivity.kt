package com.magistrat.musicplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.magistrat.musicplayer.player.PlayerConnection
import com.magistrat.musicplayer.ui.MusicApp
import com.magistrat.musicplayer.ui.MusicTheme
import com.magistrat.musicplayer.ui.extractUrl
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var sharedUrl by mutableStateOf<String?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PlayerConnection.connect(this)
        handleIntent(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MusicTheme {
                MusicApp(sharedUrl = sharedUrl, onSharedUrlConsumed = { sharedUrl = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        lifecycleScope.launch { PlayerConnection.saveNow() }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT))?.let { sharedUrl = it }
        }
    }
}
