package dev.chenwuyi.leaf

import android.os.Bundle
import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chenwuyi.leaf.data.settings.AppearanceStore
import dev.chenwuyi.leaf.data.settings.ThemeMode
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.chenwuyi.leaf.data.notion.NotionOAuthSession
import dev.chenwuyi.leaf.ui.LeafApp
import dev.chenwuyi.leaf.ui.LeafViewModel
import dev.chenwuyi.leaf.ui.settings.SettingsViewModel
import dev.chenwuyi.leaf.ui.theme.LeafTheme

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        // Pull what changed in Notion while Leaf was away.
        val container = (application as LeafApplication).container
        if (container.connection.connection.value != null) container.scheduler.now()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as LeafApplication).container
        setContent {
            AppTheme(container.appearance) {
                val vm = viewModel { LeafViewModel(container.repository, seed = container.repository::seedSamplesIfEmpty) }
                val settings = viewModel {
                    SettingsViewModel(
                        setup = container.setup,
                        connection = container.connection.connection,
                        status = container.sync.status,
                        syncNow = container.scheduler::now,
                        afterDisconnect = container.repository::seedSamplesIfEmpty,
                        speechKey = container.speechKey,
                        oauth = NotionOAuthSession.shared,
                        appearanceStore = container.appearance,
                    )
                }
                LeafApp(vm, settings)
            }
        }
    }
}

/**
 * [LeafTheme] as 设置 › 外观 picks it: the reading font, and light or dark whatever the system says. The
 * system bars' icons follow the same choice, so a forced dark page never gets dark status-bar icons.
 */
@Composable
fun ComponentActivity.AppTheme(store: AppearanceStore, content: @Composable () -> Unit) {
    val appearance by store.appearance.collectAsStateWithLifecycle()
    val system = isSystemInDarkTheme()
    val dark = when (appearance.theme) {
        ThemeMode.System -> system
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    LaunchedEffect(dark) {
        val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        // SystemBarStyle.auto leaves contrast enforcement on, and some ROMs answer it with an opaque
        // scrim over the gesture bar, so the page stopped short of the screen's bottom edge.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
    }
    LeafTheme(darkTheme = dark, font = appearance.font, content = content)
}
