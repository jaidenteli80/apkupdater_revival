package com.apkupdater.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.apkupdater.BuildConfig
import com.apkupdater.R
import com.apkupdater.data.ui.GitHubSource
import com.apkupdater.data.ui.SettingsUiState
import com.apkupdater.ui.component.ButtonSetting
import com.apkupdater.ui.component.DropDownSetting
import com.apkupdater.ui.component.LargeTitle
import com.apkupdater.ui.component.LoadingImageApp
import com.apkupdater.ui.component.MediumText
import com.apkupdater.ui.component.MediumTitle
import com.apkupdater.ui.component.SegmentedButtonSetting
import com.apkupdater.ui.component.SliderSetting
import com.apkupdater.ui.component.SourceIcon
import com.apkupdater.ui.component.SwitchSetting
import com.apkupdater.ui.theme.statusBarColor
import com.apkupdater.util.isAndroidTv
import com.apkupdater.viewmodel.ActionState
import com.apkupdater.viewmodel.SettingsViewModel
import org.koin.compose.viewmodel.koinViewModel


@Composable
fun SettingsScreen(viewModel: SettingsViewModel = koinViewModel()) = Column {
	val state = viewModel.state.collectAsStateWithLifecycle().value
    val actionState = viewModel.actionState.collectAsStateWithLifecycle().value

    // Global loading bar for actions
    if (actionState !is ActionState.Idle) {
        ActionDialog(actionState, viewModel::dismissActionState)
    }

	when (state) {
		SettingsUiState.Settings -> {
			SettingsTopBar(viewModel)
			Settings(viewModel)
		}
		SettingsUiState.About -> {
			AboutTopBar(viewModel)
			About()
		}
	}
}

@Composable
fun ActionDialog(state: ActionState, onDismiss: () -> Unit) = Dialog(
    onDismissRequest = { if (state !is ActionState.Loading) onDismiss() },
    properties = DialogProperties(
        dismissOnBackPress = state !is ActionState.Loading,
        dismissOnClickOutside = state !is ActionState.Loading,
    )
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            horizontalAlignment = CenterHorizontally
        ) {
            Text(
                text = when (state) {
                    is ActionState.Loading -> state.message
                    is ActionState.Success -> state.message
                    is ActionState.Error -> state.message
                    else -> ""
                },
                style = MaterialTheme.typography.titleMedium
            )
            
            if (state is ActionState.Loading) {
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp))
            } else {
                Spacer(modifier = Modifier.height(16.dp))
            }

            if (state !is ActionState.Loading) {
                Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        }
    }
}

@Composable
fun About() = LazyColumn(
	Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
	item {
		Column(Modifier.padding(vertical = 16.dp)) {
			LoadingImageApp(BuildConfig.APPLICATION_ID)
			LargeTitle(stringResource(R.string.app_name), Modifier.align(CenterHorizontally))
			MediumText("2.0.0-Revived", Modifier.align(CenterHorizontally), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
		}
	}
	item {
		AboutItem(
			"GitHub - APKUpdater Revival",
			"Source code, releases, and bug reports for APKUpdater Revival.",
			"https://github.com/jaidenteli80/apkupdater_revival",
			{ SourceIcon(GitHubSource, Modifier.size(64.dp).align(CenterVertically)) }
		)
	}
}


@Composable
fun AboutItem(
	title: String,
	body: String,
	link: String,
	icon: @Composable RowScope.() -> Unit,
	handler: UriHandler = LocalUriHandler.current
) = OutlinedCard(
	Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { handler.openUri(link) }) {
	Row(Modifier.padding(8.dp)) {
		icon()
		Column(Modifier.padding(start = 16.dp)) {
			MediumTitle(title)
			MediumText(body, maxLines = 2)
		}
	}
}

@Composable
fun Settings(viewModel: SettingsViewModel) = LazyColumn {
	item {
		LargeTitle(stringResource(R.string.settings_ui), Modifier.padding(start = 16.dp, top = 16.dp))
		val tvUi = remember { mutableStateOf(viewModel.getAndroidTvUi()) }
		SwitchSetting(
			checked = viewModel.getAndroidTvUi(),
			onCheckedChange = {
				viewModel.setAndroidTvUi(it)
				tvUi.value = it
		   	},
			text = stringResource(R.string.settings_android_tv_ui),
			icon = R.drawable.ic_androidtv
		)
		if (!tvUi.value) {
			SliderSetting(
				{ viewModel.getPortraitColumns().toFloat() },
				{ viewModel.setPortraitColumns(it.toInt()) },
				stringResource(R.string.settings_portrait_columns),
				1f..4f,
				2,
				R.drawable.ic_portrait
			)
			SliderSetting(
				{ viewModel.getLandscapeColumns().toFloat() },
				{ viewModel.setLandscapeColumns(it.toInt()) },
				stringResource(R.string.settings_landscape_columns),
				1f..8f,
				6,
				R.drawable.ic_landscape
			)
		}
		SwitchSetting(
			checked = viewModel.getPlayTextAnimations(),
			onCheckedChange = { viewModel.setPlayTextAnimations(it) },
			text = stringResource(R.string.play_text_animations),
			icon = R.drawable.ic_animation
		)
		SegmentedButtonSetting(
			stringResource(R.string.theme),
			listOf(
				stringResource(R.string.theme_system),
				stringResource(R.string.theme_dark),
				stringResource(R.string.theme_light)
			),
			{ viewModel.getTheme() },
			{ viewModel.setTheme(it) },
			R.drawable.ic_theme
		)
	}

	item {
		LargeTitle(stringResource(R.string.settings_sources), Modifier.padding(start = 16.dp, top = 16.dp))
		SwitchSetting(
			checked = viewModel.getUseGitHub(),
			onCheckedChange = { viewModel.setUseGitHub(it) },
			text = stringResource(R.string.source_github),
			icon = R.drawable.ic_github
		)
		SwitchSetting(
			checked = viewModel.getUseGitLab(),
			onCheckedChange = { viewModel.setUseGitLab(it) },
			text = stringResource(R.string.source_gitlab),
			icon = R.drawable.ic_gitlab
		)
		SwitchSetting(
			checked = viewModel.getUseApkMirror(),
			onCheckedChange = { viewModel.setUseApkMirror(it) },
			text = stringResource(R.string.source_apkmirror),
			icon = R.drawable.ic_apkmirror
		)
		SwitchSetting(
			checked = viewModel.getUseFdroid(),
			onCheckedChange = { viewModel.setUseFdroid(it) },
			text = stringResource(R.string.source_fdroid),
			icon = R.drawable.ic_fdroid
		)
		SwitchSetting(
			checked = viewModel.getUseIzzy(),
			onCheckedChange = { viewModel.setUseIzzy(it) },
			text = stringResource(R.string.source_izzy),
			icon = R.drawable.ic_izzy
		)
		SwitchSetting(
			checked = viewModel.getUseAptoide(),
			onCheckedChange = { viewModel.setUseAptoide(it) },
			text = stringResource(R.string.source_aptoide),
			icon = R.drawable.ic_aptoide
		)
		SwitchSetting(
			checked = viewModel.getUseApkPure(),
			onCheckedChange = { viewModel.setUseApkPure(it) },
			text = stringResource(R.string.source_apkpure),
			icon = R.drawable.ic_apkpure
		)
		SwitchSetting(
			checked = viewModel.getUsePlay(),
			onCheckedChange = { viewModel.setUsePlay(it) },
			text = stringResource(R.string.source_play) + " (Alpha)",
			icon = R.drawable.ic_play
		)
	}

	item {
		LargeTitle(stringResource(R.string.settings_options), Modifier.padding(start = 16.dp, top = 16.dp))
		SwitchSetting(
			checked = viewModel.rootStatus.collectAsStateWithLifecycle().value,
			onCheckedChange = { viewModel.setRootInstall(it) },
			text = stringResource(R.string.root_install),
			icon = R.drawable.ic_root
		)
		SwitchSetting(
			checked = viewModel.getIgnoreAlpha(),
			onCheckedChange = { viewModel.setIgnoreAlpha(it) },
			text = stringResource(R.string.ignore_alpha),
			icon = R.drawable.ic_alpha
		)
		SwitchSetting(
			checked = viewModel.getIgnoreBeta(),
			onCheckedChange = { viewModel.setIgnoreBeta(it) },
			text = stringResource(R.string.ignore_beta),
			icon = R.drawable.ic_beta
		)
		SwitchSetting(
			checked = viewModel.getIgnorePreRelease(),
			onCheckedChange = { viewModel.setIgnorePreRelease(it) },
			text = stringResource(R.string.ignore_preRelease),
			icon = R.drawable.ic_pre_release
		)
		SwitchSetting(
			checked = viewModel.getUseSafeStores(),
			onCheckedChange = { viewModel.setUseSafeStores(it) },
			text = stringResource(R.string.use_safe_stores),
			icon = R.drawable.ic_safe
		)
	}

	item {
		val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
		LargeTitle("Periodic Background Checks", Modifier.padding(start = 16.dp, top = 16.dp))
		SwitchSetting(
			checked = viewModel.getEnableAlarm(),
			onCheckedChange = { viewModel.setEnableAlarm(it, launcher) },
			text = "Check for Updates in Background",
			icon = R.drawable.ic_alarm
		)
		if (LocalContext.current.isAndroidTv()) {
			DropDownSetting(
				text = "Preferred Check Hour",
				options = (0..23).map { "$it:00" },
				getValue = { viewModel.getAlarmHour() },
				setValue = { viewModel.setAlarmHour(it) },
				icon = R.drawable.ic_hour
			)
		} else {
			SliderSetting(
				getValue = { viewModel.getAlarmHour().toFloat() },
				setValue = { viewModel.setAlarmHour(it.toInt()) },
				text = "Preferred Check Hour",
				valueRange = 0f..23f,
				steps = 23,
				R.drawable.ic_hour
			)
		}
		SegmentedButtonSetting(
			"Check Frequency",
			listOf(
				stringResource(R.string.settings_alarm_daily),
				stringResource(R.string.settings_alarm_3day),
				stringResource(R.string.settings_alarm_weekly)
			),
			{ viewModel.getAlarmFrequency() },
			{ viewModel.setAlarmFrequency(it) },
			R.drawable.ic_frequency
		)
	}
	item {
		LargeTitle(stringResource(R.string.settings_utils), Modifier.padding(start = 16.dp, top = 16.dp))
		ButtonSetting(
			stringResource(R.string.copy_app_list),
			{ viewModel.copyAppList() },
			R.drawable.ic_root,
			R.drawable.ic_copy
		)
		ButtonSetting(
			stringResource(R.string.copy_app_logs),
			{ viewModel.copyAppLogs() },
			R.drawable.ic_root,
			R.drawable.ic_copy
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTopBar(viewModel: SettingsViewModel) = TopAppBar(
	title = { Text(stringResource(R.string.tab_settings)) },
	colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.statusBarColor()),
	windowInsets = WindowInsets(0),
	actions = {
		IconButton(onClick = { viewModel.setAbout() }) {
			Icon(painterResource(R.drawable.ic_info), stringResource(R.string.about))
		}
	},
	navigationIcon = {
		Box(Modifier.minimumInteractiveComponentSize().size(40.dp), Alignment.Center) {
			Icon(Icons.Filled.Settings, "Tab Icon")
		}
	}
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutTopBar(viewModel: SettingsViewModel) = TopAppBar(
	title = { Text(stringResource(R.string.about)) },
	colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.statusBarColor()),
	windowInsets = WindowInsets(0),
	navigationIcon = {
		IconButton(onClick = { viewModel.setSettings() }) {
			Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
		}
	}
)
