package dev.chenwuyi.leaf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.chenwuyi.leaf.data.InMemoryLeafRepository
import dev.chenwuyi.leaf.data.SampleData
import dev.chenwuyi.leaf.ui.LeafApp
import dev.chenwuyi.leaf.ui.LeafViewModel
import dev.chenwuyi.leaf.ui.theme.LeafTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LeafTheme {
                // M0 runs on sample data; M1 swaps in the Room + Notion repository.
                val vm = viewModel { LeafViewModel(InMemoryLeafRepository(SampleData.scribbles, SampleData.essays)) }
                LeafApp(vm)
            }
        }
    }
}
