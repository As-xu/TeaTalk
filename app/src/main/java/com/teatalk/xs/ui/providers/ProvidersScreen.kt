package com.teatalk.xs.ui.providers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.teatalk.xs.R
import com.teatalk.xs.data.UiText
import com.teatalk.xs.data.db.ModelProviderEntity
import com.teatalk.xs.ui.theme.TypeScale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(viewModel: ProvidersViewModel, onBack: () -> Unit) {
    val providers by viewModel.providers.collectAsState()
    val activeId by viewModel.activeId.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    val dialogState by viewModel.dialog.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<ModelProviderEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ModelProviderEntity?>(null) }

    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        snackbar.showSnackbar(m.resolve(context))
        viewModel.consumeMessage()
    }

    // 保存成功 → VM 自增 savedTick，这里据此关闭弹层（不再依赖回调里手动关）
    LaunchedEffect(dialogState.savedTick) {
        if (dialogState.savedTick > 0) {
            adding = false
            editing = null
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
                Text(
                    stringResource(R.string.providers_title),
                    fontSize = TypeScale.s20,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    viewModel.resetDialog()
                    adding = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.providers_add)) },
            )
        },
    ) { padding ->
        if (providers.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(64.dp).background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(20.dp),
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.providers_empty_title), fontSize = TypeScale.s16, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.providers_empty_hint),
                    fontSize = TypeScale.s13,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.providers_active_note),
                        fontSize = TypeScale.s12,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(items = providers, key = { it.id }) { provider ->
                    ProviderRow(
                        provider = provider,
                        active = provider.id == activeId,
                        maskedKey = viewModel.maskedKey(provider.id),
                        onClick = {
                            viewModel.resetDialog()
                            editing = provider
                        },
                        onSetActive = { viewModel.setActive(provider) },
                        onDelete = { pendingDelete = provider },
                    )
                }
                item { Spacer(Modifier.height(88.dp)) }
            }
        }
    }

    if (adding || editing != null) {
        val initial = editing
        key(initial?.id) {
            ProviderEditDialog(
                initial = initial,
                storedKeyMasked = initial?.let { viewModel.maskedKey(it.id) },
                state = dialogState,
                onDismiss = {
                    adding = false
                    editing = null
                },
                onSave = { name, baseUrl, apiKey, model ->
                    viewModel.save(initial?.id, name, baseUrl, apiKey, model)
                },
                onTest = { baseUrl, apiKey ->
                    viewModel.testConnection(initial?.id, baseUrl, apiKey)
                },
                onFetch = { baseUrl, apiKey ->
                    viewModel.fetchModels(initial?.id, baseUrl, apiKey)
                },
            )
        }
    }

    pendingDelete?.let { provider ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.providers_delete_title)) },
            text = { Text(stringResource(R.string.providers_delete_message, provider.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(provider)
                    pendingDelete = null
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ProviderRow(
    provider: ModelProviderEntity,
    active: Boolean,
    maskedKey: String?,
    onClick: () -> Unit,
    onSetActive: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (active) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(provider.name, fontSize = TypeScale.s15, fontWeight = FontWeight.Medium)
                Text(
                    provider.baseUrl,
                    fontSize = TypeScale.s12,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    stringResource(R.string.providers_row_model, provider.defaultModel.ifBlank { "-" }) +
                        " · " + (maskedKey ?: stringResource(R.string.providers_row_no_key)),
                    fontSize = TypeScale.s12,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!active) {
                TextButton(onClick = onSetActive) { Text(stringResource(R.string.providers_set_active), fontSize = TypeScale.s12) }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ProviderEditDialog(
    initial: ModelProviderEntity?,
    storedKeyMasked: String?,
    state: ProvidersDialogState,
    onDismiss: () -> Unit,
    onSave: (name: String, baseUrl: String, apiKey: String, model: String) -> Unit,
    onTest: (baseUrl: String, apiKey: String) -> Unit,
    onFetch: (baseUrl: String, apiKey: String) -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl.orEmpty()) }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(initial?.defaultModel.orEmpty()) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.padding(20.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(if (initial == null) R.string.providers_add else R.string.providers_edit),
                    fontSize = TypeScale.s17,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.providers_name)) },
                    singleLine = true,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.providers_base_url)) },
                    placeholder = { Text("https://api.openai.com/v1") },
                    singleLine = true,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.providers_api_key)) },
                    placeholder = { if (storedKeyMasked != null) Text(storedKeyMasked) },
                    singleLine = true,
                    enabled = !state.busy,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(stringResource(R.string.providers_model)) },
                    singleLine = true,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { onTest(baseUrl, apiKey) },
                        enabled = !state.busy,
                    ) { Text(stringResource(R.string.providers_test)) }
                    TextButton(
                        onClick = { onFetch(baseUrl, apiKey) },
                        enabled = !state.busy,
                    ) { Text(stringResource(R.string.providers_fetch_models)) }
                    if (state.busy) {
                        Spacer(Modifier.width(6.dp))
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
                state.note?.let { note ->
                    Text(
                        note.resolve(context),
                        fontSize = TypeScale.s12,
                        color = if (state.noteIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                if (state.models.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.providers_pick_model),
                        fontSize = TypeScale.s12,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.models.take(12).forEach { id ->
                        Row(
                            Modifier.fillMaxWidth().clickable { model = id }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (model == id) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(id, fontSize = TypeScale.s13)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    TextButton(
                        onClick = { onSave(name, baseUrl, apiKey, model) },
                        enabled = !state.busy,
                    ) {
                        Text(stringResource(R.string.edit_save), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
