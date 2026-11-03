package com.teatalk.xs.ui.quick

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teatalk.xs.R
import com.teatalk.xs.data.db.QuickReplyEntity
import com.teatalk.xs.ui.components.Hairline
import com.teatalk.xs.ui.theme.TypeScale

/**
 * 0.19.0 快速回复管理：预存常用片段（旁白模板 / OOC 指令 / 场景切换），聊天页点一下插入。
 *
 * 交互对齐正则脚本页与世界书页：头部返回 + 新建，行内启停/编辑/删除，对话框内联编辑。
 */
@Composable
fun QuickRepliesScreen(
    viewModel: QuickRepliesViewModel,
    onBack: () -> Unit,
) {
    val rows by viewModel.replies.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<QuickReplyEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<QuickReplyEntity?>(null) }

    val snackbarText = message?.let { msg ->
        if (msg.args.isEmpty()) stringResource(msg.res) else stringResource(msg.res, *msg.args.toTypedArray())
    }
    LaunchedEffect(snackbarText) {
        val text = snackbarText ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }

    val cs = MaterialTheme.colorScheme
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = cs.background,
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 6.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                    Text(
                        stringResource(R.string.quick_replies_title),
                        fontSize = TypeScale.s19,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onBackground,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { creating = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(stringResource(R.string.quick_replies_new), fontSize = TypeScale.s14, fontWeight = FontWeight.Medium)
                    }
                }
                Hairline()

                val list = rows
                when {
                    list == null -> Unit
                    list.isEmpty() -> EmptyQuickReplies(onCreate = { creating = true })
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(list.size, key = { list[it].id }) { index ->
                            val row = list[index]
                            QuickReplyRow(
                                row = row,
                                onToggle = { viewModel.toggle(row, it) },
                                onEdit = { editing = row },
                                onDelete = { pendingDelete = row },
                            )
                            Hairline(Modifier.padding(start = 16.dp))
                        }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        QuickReplyDialog(
            initial = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { row ->
                viewModel.save(row)
                creating = false
                editing = null
            },
        )
    }

    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.quick_reply_delete_title), fontSize = TypeScale.s17) },
            text = {
                Text(
                    stringResource(R.string.quick_reply_delete_body, row.displayLabel()),
                    fontSize = TypeScale.s14,
                    color = cs.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(row)
                    pendingDelete = null
                }) { Text(stringResource(R.string.delete), color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun QuickReplyRow(
    row: QuickReplyEntity,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.displayLabel(),
                fontSize = TypeScale.s15_5,
                fontWeight = FontWeight.Medium,
                color = if (row.enabled) cs.onSurface else cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.title.isNotBlank()) {
                Text(
                    row.content.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty(),
                    fontSize = TypeScale.s13,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Switch(checked = row.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit), modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun EmptyQuickReplies(onCreate: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.quick_replies_empty_title),
            fontSize = TypeScale.s17,
            fontWeight = FontWeight.Medium,
            color = cs.onSurface,
        )
        Text(
            stringResource(R.string.quick_replies_empty_body),
            fontSize = TypeScale.s13,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        TextButton(onClick = onCreate, modifier = Modifier.padding(top = 12.dp)) {
            Text(stringResource(R.string.quick_replies_empty_cta), fontSize = TypeScale.s14, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun QuickReplyDialog(
    initial: QuickReplyEntity?,
    onDismiss: () -> Unit,
    onSave: (QuickReplyEntity) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var content by remember { mutableStateOf(initial?.content.orEmpty()) }
    var sortIndex by remember { mutableStateOf((initial?.sortIndex ?: 0).toString()) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    val canSave = content.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(if (initial == null) R.string.quick_replies_new_title else R.string.quick_reply_edit_title),
                fontSize = TypeScale.s17,
            )
        },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.quick_reply_title_field), fontSize = TypeScale.s13) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.quick_reply_content), fontSize = TypeScale.s13) },
                    minLines = 4,
                    maxLines = 8,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = sortIndex,
                    onValueChange = { sortIndex = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text(stringResource(R.string.quick_reply_sort), fontSize = TypeScale.s13) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.quick_reply_enabled),
                        fontSize = TypeScale.s14,
                        color = cs.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        QuickReplyEntity(
                            id = initial?.id ?: 0L,
                            title = title.trim(),
                            content = content.trim(),
                            sortIndex = sortIndex.toIntOrNull() ?: 0,
                            enabled = enabled,
                            createdAt = initial?.createdAt ?: 0L,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.edit_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
