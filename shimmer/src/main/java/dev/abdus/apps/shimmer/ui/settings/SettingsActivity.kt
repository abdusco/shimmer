package dev.abdus.apps.shimmer.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.abdus.apps.shimmer.R
import dev.abdus.apps.shimmer.ui.ShimmerTheme

private const val TAG = "SettingsActivity"

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShimmerTheme {
                SettingsScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val sourcesActions = viewModel.sourcesActions
    val effectsActions = viewModel.effectsActions
    val gesturesActions = viewModel.gesturesActions

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painter = painterResource(R.drawable.shimmer), null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Shimmer", style = MaterialTheme.typography.headlineMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                SettingsTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = state.selectedTab == tab,
                        onClick = { viewModel.setTab(tab) },
                        label = { Text(tab.label) },
                        icon = { Icon(tab.icon, contentDescription = null) }
                    )
                }
            }
        },
    ) { paddingValues ->
        Surface(modifier = Modifier.fillMaxSize()) {
            when (state.selectedTab) {
                SettingsTab.SOURCES -> {
                    SourcesTab(
                        modifier = Modifier.padding(paddingValues),
                        state = state.sources,
                        actions = sourcesActions,
                    )
                }

                SettingsTab.EFFECTS -> EffectsTab(
                    modifier = Modifier.padding(paddingValues),
                    state = state.effects,
                    actions = effectsActions,
                )

                SettingsTab.GESTURES -> GesturesTab(
                    modifier = Modifier.padding(paddingValues),
                    state = state.gestures,
                    actions = gesturesActions,
                )
            }
        }
    }
}

enum class SettingsTab(val label: String, val icon: ImageVector) {
    SOURCES("Sources", Icons.Default.Folder),
    EFFECTS("Effects", Icons.Default.Palette),
    GESTURES("Gestures", Icons.Default.TouchApp)
}
