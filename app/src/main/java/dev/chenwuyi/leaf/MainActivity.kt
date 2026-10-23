package dev.chenwuyi.leaf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
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
            LeafTheme {
                val vm = viewModel { LeafViewModel(container.repository, seed = container.repository::seedSamplesIfEmpty) }
                val settings = viewModel {
                    SettingsViewModel(
                        setup = container.setup,
                        connection = container.connection.connection,
                        status = container.sync.status,
                        syncNow = container.scheduler::now,
                        afterDisconnect = container.repository::seedSamplesIfEmpty,
                    )
                }
                LeafApp(vm, settings)
            }
        }
    }
}
