package dev.kinash.smartscales

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.kinash.smartscales.core.data.repository.SettingsRepository
import dev.kinash.smartscales.ui.SmartScalesNavHost
import dev.kinash.smartscales.ui.theme.SmartScalesTheme
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var settings: SettingsRepository

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showProfileOf(intent)
    }

    /** A reminder opens the app on the profile it reminds. */
    private fun showProfileOf(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_PROFILE, -1L)?.takeIf { it > 0 } ?: return
        lifecycleScope.launch { settings.selectProfile(id) }
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
    }
}
