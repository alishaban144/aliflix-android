package com.aliflix.app.talk

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aliflix.app.ui.theme.AliflixMobileTheme
import kotlinx.coroutines.launch

class TalkVoiceActivity : ComponentActivity() {
    internal val voice by lazy { TalkVoiceSession(this, lifecycleScope, restoreHistory=!(com.aliflix.app.BuildConfig.DEBUG&&intent.getBooleanExtra("validationFreshHistory",false))) }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) lifecycleScope.launch { voice.connect() } else voice.permissionDenied()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(intent.getBooleanExtra("textMode",false))voice.setTextMode(true)
        enableEdgeToEdge(statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { AliflixMobileTheme { TalkVoiceScreen(voice, onStart = ::startListening, onClose = ::finish) } }
    }
    private fun startListening() {
        if(voice.state.value.textMode){lifecycleScope.launch { voice.connect() };return}
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            lifecycleScope.launch { voice.connect() }
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    // The microphone and sockets never survive the visible experience.
    // Returning requires an explicit retry; a backgrounded phone cannot record.
    override fun onStop() { voice.pauseSession(); super.onStop() }
    override fun onDestroy() { voice.close(); super.onDestroy() }
}
