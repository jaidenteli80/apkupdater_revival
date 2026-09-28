package com.apkupdater.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apkupdater.R
import com.apkupdater.data.ui.AppsUiState
import com.apkupdater.prefs.Prefs
import com.apkupdater.ui.component.DefaultErrorScreen
import com.apkupdater.ui.component.InstalledGrid
import com.apkupdater.ui.component.InstalledItem
import com.apkupdater.ui.component.LoadingGrid
import com.apkupdater.ui.component.TvInstalledGrid
import com.apkupdater.ui.component.TvInstalledItem
import com.apkupdater.ui.theme.statusBarColor
import com.apkupdater.viewmodel.AppsViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel


@Composable
fun AppsScreen(
	viewModel: AppsViewModel = koinViewModel()
) {
	val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
	val processingMessage by viewModel.processingMessage.collectAsStateWithLifecycle()
	if (isProcessing) {
		ProcessingDialog(processingMessage)
	}

	viewModel.state().collectAsStateWithLifecycle().value.onLoading {
		AppsScreenLoading(viewModel, it)
	}.onError {
		AppsScreenError()
	}.onSuccess {
		AppsScreenSuccess(viewModel, it)
	}
}

@Composable
fun ProcessingDialog(message: String) = Dialog(
    onDismissRequest = { },
    properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = message.ifEmpty { "Processing apps list..." },
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterRow(
    viewModel: AppsViewModel,
    excludeSystem: Boolean,
    excludeAppStore: Boolean,
    excludeDisabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = !excludeSystem,
            onClick = { viewModel.onSystemClick() },
            label = { Text(if (!excludeSystem) "System Apps: Shown" else "System Apps: Hidden") }
        )
        FilterChip(
            selected = !excludeAppStore,
            onClick = { viewModel.onAppStoreClick() },
            label = { Text(if (!excludeAppStore) "Store Apps: Shown" else "Store Apps: Hidden") }
        )
        FilterChip(
            selected = !excludeDisabled,
            onClick = { viewModel.onDisabledClick() },
            label = { Text(if (!excludeDisabled) "Disabled Apps: Shown" else "Disabled Apps: Hidden") }
        )
    }
}

@Composable
fun AppsScreenSuccess(viewModel: AppsViewModel, state: AppsUiState.Success) = Column {
	var isSearching by remember { mutableStateOf(false) }
	var searchQuery by remember { mutableStateOf("") }

	val filteredApps = remember(state.apps, searchQuery) {
		if (searchQuery.isEmpty()) state.apps
		else state.apps.filter { it.name.contains(searchQuery, true) || it.packageName.contains(searchQuery, true) }
	}

	AppsTopBar(isSearching, searchQuery, { searchQuery = it }, { isSearching = !isSearching; if (!isSearching) searchQuery = "" })
	FilterRow(viewModel, state.excludeSystem, state.excludeAppStore, state.excludeDisabled)
    
    if (filteredApps.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.no_apps_found),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(16.dp)
            )
        }
    } else {
        if (koinInject<Prefs>().androidTvUi.get()) {
            TvInstalledGrid {
                items(filteredApps) {
                    TvInstalledItem(it) { app -> viewModel.ignore(app) }
                }
            }
        } else {
            InstalledGrid {
                items(filteredApps) {
                    InstalledItem(it) { app -> viewModel.ignore(app) }
                }
            }
        }
    }
}

@Composable
fun AppsScreenLoading(viewModel: AppsViewModel, state: AppsUiState.Loading) = Column {
	AppsTopBar(false, "", {}, {})
	FilterRow(viewModel, state.excludeSystem, state.excludeAppStore, state.excludeDisabled)
	Box(modifier = Modifier.fillMaxSize()) {
        LoadingGrid()
		Column(
			horizontalAlignment = Alignment.CenterHorizontally,
			modifier = Modifier.align(Alignment.Center).padding(16.dp)
		) {
			Text(
				text = state.stage.ifEmpty { "Loading apps..." },
				style = MaterialTheme.typography.bodyLarge
			)
			Spacer(modifier = Modifier.height(16.dp))
			LinearProgressIndicator(
				progress = { state.progress },
				modifier = Modifier.fillMaxWidth().height(8.dp),
			)
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsTopBar(
	isSearching: Boolean,
	searchQuery: String,
	onSearchQueryChanged: (String) -> Unit,
	onSearchToggle: () -> Unit
) = TopAppBar(
	title = {
		if (isSearching) {
			OutlinedTextField(
				value = searchQuery,
				onValueChange = onSearchQueryChanged,
				placeholder = { Text("Search apps...") },
				singleLine = true,
				modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
				colors = OutlinedTextFieldDefaults.colors(
					focusedBorderColor = Color.Transparent,
					unfocusedBorderColor = Color.Transparent
				)
			)
		} else {
			Text(stringResource(R.string.tab_apps))
		}
	},
	colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.statusBarColor()),
	windowInsets = WindowInsets(0),
	actions = {
		IconButton(onClick = onSearchToggle) {
			Icon(if (isSearching) Icons.Default.Close else Icons.Default.Search, contentDescription = "Search")
		}
	},
	navigationIcon = {
		Box(Modifier.minimumInteractiveComponentSize().size(40.dp), Alignment.Center) {
			Icon(Icons.Filled.Home, "Tab Icon")
		}
	}
)

@Composable
fun AppsScreenError() = DefaultErrorScreen()
