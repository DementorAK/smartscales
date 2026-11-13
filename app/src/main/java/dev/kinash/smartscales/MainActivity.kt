package dev.kinash.smartscales

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.kinash.smartscales.background.BackgroundMode
import dev.kinash.smartscales.core.data.repository.SettingsRepository
import dev.kinash.smartscales.ui.SmartScalesNavHost
import dev.kinash.smartscales.ui.theme.SmartScalesTheme
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var background: BackgroundMode

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) showProfileOf(intent)
        setContent {
            SmartScalesTheme {
                SmartScalesNavHost()
            }
        }
    }

    /**
     * The background mode, if on, runs again: it may have been stopped, and Android 12+ starts it only from here.
     * Its notification no longer shows the last weighing, which is in the app now.
     */
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { background.sync(seen = true) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showProfileOf(intent)
    }

    /** A reminder, or the background's notification of a weighing, opens the app on its profile. */
    private fun showProfileOf(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_PROFILE, -1L)?.takeIf { it > 0 } ?: return
        lifecycleScope.launch { settings.selectProfile(id) }
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
    }
}
