package app.mygachalist

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mygachalist.ui.AppRoot
import app.mygachalist.ui.theme.AppTheme
import app.mygachalist.work.Notifier

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var openGameId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // The top bar is always a dark colour (brand blue or dark surface), so status icons stay light.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openGameId = intent?.getStringExtra(Notifier.EXTRA_GAME_ID)
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val s = settings ?: return@setContent // wait for the stored theme instead of flashing the wrong one
            AppTheme(s.theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(vm, openGameId) { openGameId = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Notifier.EXTRA_GAME_ID)?.let { openGameId = it }
    }
}
