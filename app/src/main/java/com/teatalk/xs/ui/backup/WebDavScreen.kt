package com.teatalk.xs.ui.backup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.teatalk.xs.R
import com.teatalk.xs.data.WebDavBackup
import com.teatalk.xs.ui.components.Hairline
import com.teatalk.xs.ui.theme.TypeScale

/** 我的 → 云备份（WebDAV）：配置服务器、立即备份、从远端列表恢复。 */
@Composable
fun WebDavScreen(
    viewModel: WebDavViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    val message = state.message
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message.resolve(context))
            viewModel.clearMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
                Text(
                    stringResource(R.string.webdav_title),
                    fontSize = TypeScale.s20,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp),
        ) {
            Text(
                stringResource(R.string.webdav_note),
                fontSize = TypeScale.s12,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    OutlinedTextField(
                        value = state.url,
                        onValueChange = viewModel::onUrlChange,
                        label = { Text(stringResource(R.string.webdav_url)) },
                        placeholder = { Text(stringResource(R.string.webdav_url_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = state.user,
                        onValueChange = viewModel::onUserChange,
                        label = { Text(stringResource(R.string.webdav_user)) },
                        placeholder = { Text(stringResource(R.string.webdav_user_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = viewModel::onPasswordChange,
                        label = { Text(stringResource(R.string.webdav_password)) },
                        placeholder = { Text(stringResource(R.string.webdav_password_hint)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.hasPassword) {
                        Text(
                            stringResource(R.string.webdav_password_keep, state.maskedPassword.orEmpty()),
                            fontSize = TypeScale.s11_5,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = state.dir,
                        onValueChange = viewModel::onDirChange,
                        label = { Text(stringResource(R.string.webdav_dir)) },
                        placeholder = { Text(stringResource(R.string.webdav_dir_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (state.url.trim().startsWith("http://", ignoreCase = true)) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.webdav_cleartext_warning),
                    fontSize = TypeScale.s11_5,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedAction(stringResource(R.string.webdav_test), enabled = !state.busy, onClick = viewModel::test)
                Button(onClick = viewModel::save, enabled = !state.busy) {
                    Text(stringResource(R.string.webdav_save))
                }
                TextButton(
                    onClick = viewModel::clearConfig,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                ) {
                    Text(stringResource(R.string.webdav_clear), fontSize = TypeScale.s12)
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = viewModel::backup,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.webdav_backup))
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.webdav_restore_section),
                    fontSize = TypeScale.s14,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = viewModel::refresh, enabled = !state.busy) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.webdav_refresh),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (state.loaded && state.backups.isEmpty()) {
                Text(
                    stringResource(R.string.webdav_empty),
                    fontSize = TypeScale.s12,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            state.backups.forEach { backup ->
                BackupRow(backup, enabled = !state.busy, onClick = { viewModel.askRestore(backup) })
                Hairline(insetStart = 0)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    val target = state.restoreTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissRestore,
            title = { Text(stringResource(R.string.webdav_restore)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.webdav_restore_confirm))
                    Text(
                        stringResource(
                            R.string.webdav_restore_name,
                            target.name,
                            WebDavViewModel.formatSize(target.size),
                        ),
                        fontSize = TypeScale.s13,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.webdav_restore_note),
                        fontSize = TypeScale.s12,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRestore) {
                    Text(stringResource(R.string.webdav_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRestore) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun OutlinedAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text,
            fontSize = TypeScale.s13,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun BackupRow(backup: WebDavBackup.RemoteBackup, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(backup.name, fontSize = TypeScale.s14)
            Text(
                WebDavViewModel.formatSize(backup.size),
                fontSize = TypeScale.s11_5,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(R.string.webdav_restore),
            fontSize = TypeScale.s13,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
