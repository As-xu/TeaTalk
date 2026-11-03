package com.teatalk.xs.ui.regex

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teatalk.xs.R
import com.teatalk.xs.data.RegexRule
import com.teatalk.xs.data.db.RegexRuleEntity
import com.teatalk.xs.ui.components.Hairline
import com.teatalk.xs.ui.theme.TypeScale

/**
 * V1 正则脚本管理：ST 风格的正则替换（prompt 出站 / display 入站）。
 *
 * 交互对齐世界书页：头部返回 + 新建，列表行可启停/编辑/删除，编辑用对话框内联预览。
 */
@Composable
fun RegexRulesScreen(
    viewModel: RegexRulesViewModel,
    onBack: () -> Unit,
) {
    val rules by viewModel.rules.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<RegexRule?>(null) }
    var creating by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<RegexRule?>(null) }

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
                        stringResource(R.string.regex_title),
                        fontSize = TypeScale.s19,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onBackground,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { creating = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(stringResource(R.string.regex_new), fontSize = TypeScale.s14, fontWeight = FontWeight.Medium)
                    }
                }
                Hairline()

                val list = rules
                when {
                    list == null -> Unit
                    list.isEmpty() -> EmptyRegexRules(onCreate = { creating = true })
                    else -> androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
                        items(list.size, key = { list[it].id }) { index ->
                            val rule = list[index]
                            RuleRow(
                                rule = rule,
                                onToggle = { viewModel.toggle(rule, it) },
                                onEdit = { editing = rule },
                                onDelete = { pendingDelete = rule },
                            )
                            Hairline(Modifier.padding(start = 16.dp))
                        }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        val base = editing
        RuleEditorDialog(
            initial = base,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { rule ->
                viewModel.save(rule)
                creating = false
                editing = null
            },
            preview = { rule, sample -> viewModel.preview(rule, sample) },
        )
    }

    pendingDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.regex_delete_title)) },
            text = { Text(stringResource(R.string.regex_delete_body, rule.name.ifBlank { rule.pattern })) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(rule)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.delete), color = cs.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun RuleRow(
    rule: RegexRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                rule.name.ifBlank { rule.pattern },
                fontSize = TypeScale.s15,
                fontWeight = FontWeight.Medium,
                color = cs.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.regex_scope) + " · " + scopeLabel(rule.scope) + " · " + rule.flags,
                fontSize = TypeScale.s12,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(checked = rule.enabled, onCheckedChange = onToggle)
        TextButton(onClick = onEdit) { Text(stringResource(R.string.edit), fontSize = TypeScale.s13) }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.delete),
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun EmptyRegexRules(onCreate: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.regex_empty_title),
            fontSize = TypeScale.s17,
            fontWeight = FontWeight.Medium,
            color = cs.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.regex_empty_body),
            fontSize = TypeScale.s13,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onCreate) { Text(stringResource(R.string.regex_empty_cta)) }
    }
}

@Composable
private fun RuleEditorDialog(
    initial: RegexRule?,
    onDismiss: () -> Unit,
    onSave: (RegexRule) -> Unit,
    preview: (RegexRule, String) -> String,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var pattern by remember { mutableStateOf(initial?.pattern ?: "") }
    var replacement by remember { mutableStateOf(initial?.replacement ?: "") }
    var flags by remember { mutableStateOf(initial?.flags ?: "g") }
    var scope by remember { mutableStateOf(initial?.scope ?: RegexRuleEntity.SCOPE_PROMPT) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var sortIndex by remember { mutableStateOf((initial?.sortIndex ?: 0).toString()) }
    var sample by remember { mutableStateOf("") }

    val draft = RegexRule(
        id = initial?.id ?: 0L,
        name = name,
        pattern = pattern,
        replacement = replacement,
        flags = flags,
        scope = scope,
        enabled = enabled,
        sortIndex = sortIndex.toIntOrNull() ?: 0,
    )
    val previewText = preview(draft, sample)
    val cs = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initial == null) R.string.regex_new_title else R.string.regex_edit_title))
        },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.regex_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text(stringResource(R.string.regex_pattern)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    label = { Text(stringResource(R.string.regex_replacement)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = flags,
                        onValueChange = { flags = it.filter { c -> c.isLetter() } },
                        label = { Text(stringResource(R.string.regex_flags)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = sortIndex,
                        onValueChange = { sortIndex = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.regex_sort)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.regex_scope), fontSize = TypeScale.s13, color = cs.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    RegexRuleEntity.SCOPES.forEach { option ->
                        TextButton(onClick = { scope = option }) {
                            Text(
                                scopeLabel(option),
                                fontSize = TypeScale.s13,
                                fontWeight = if (scope == option) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (scope == option) cs.primary else cs.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.regex_enabled), fontSize = TypeScale.s13, color = cs.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = sample,
                    onValueChange = { sample = it },
                    label = { Text(stringResource(R.string.regex_preview_hint)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (previewText.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.regex_preview), fontSize = TypeScale.s12, color = cs.onSurfaceVariant)
                    Text(previewText, fontSize = TypeScale.s13, color = cs.onBackground)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) { Text(stringResource(R.string.edit_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun scopeLabel(scope: String): String = stringResource(
    when (scope) {
        RegexRuleEntity.SCOPE_DISPLAY -> R.string.regex_scope_display
        RegexRuleEntity.SCOPE_BOTH -> R.string.regex_scope_both
        else -> R.string.regex_scope_prompt
    },
)
