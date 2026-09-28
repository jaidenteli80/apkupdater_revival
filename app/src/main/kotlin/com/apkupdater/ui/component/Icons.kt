package com.apkupdater.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apkupdater.R
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.Source
import com.apkupdater.util.clickableNoRipple


@Composable
fun SourceIcon(source: Source, modifier: Modifier = Modifier) = Icon(
    painterResource(id = source.resourceId),
    source.name,
    modifier
)

@Composable
fun IgnoreIcon(ignored: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) = Icon(
    painter = painterResource(
        id = if(ignored) R.drawable.ic_visible_off else R.drawable.ic_visible
    ),
    contentDescription = stringResource(if (ignored) R.string.unignore_cd else R.string.ignore_cd),
    modifier = Modifier.clickableNoRipple(onClick).then(modifier)
)

@Composable
fun InstallIcon(onClick: () -> Unit, modifier: Modifier = Modifier) = Icon(
    painter = painterResource(R.drawable.ic_install),
    contentDescription = stringResource(R.string.install_cd),
    modifier = Modifier.clickableNoRipple(onClick).then(modifier)
)

@Composable
fun BoxScope.InstallProgressIcon(
    app: AppUpdate,
    onClick: () -> Unit
) {
    if (!(app.isInstalling) && app.error == null) {
        Box(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            if (!app.isPersistent) {
                InstallIcon(
                    { onClick() },
                    Modifier.size(24.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = stringResource(R.string.persistent_app_warning),
                    modifier = Modifier
                        .size(24.dp)
                        .clickableNoRipple { onClick() },
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    } else if (app.isInstalling) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
        ) {
            val progress = if (app.total > 0) app.progress.toFloat() / app.total.toFloat() else 0f
            if (app.total > 0 && app.progress < app.total) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    } else {
        app.error?.let { error ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f))
                    .clickableNoRipple { onClick() }
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        painter = painterResource(R.drawable.ic_visible_off),
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "RETRY",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshIcon(
    text: String,
    modifier: Modifier = Modifier
) = TooltipBox(
    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
    state = rememberTooltipState(),
    tooltip = { PlainTooltip { Text(text) } }
) {
    Icon(
        painter = painterResource(id = R.drawable.ic_refresh),
        contentDescription = text,
        modifier = modifier
    )
}
