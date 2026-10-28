package com.teatalk.xs.ui.worldbook

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teatalk.core.worldinfo.WorldInfoEntry
import com.teatalk.xs.R
import com.teatalk.xs.data.db.WorldBookEntity
import com.teatalk.xs.ui.components.Hairline
import com.teatalk.xs.ui.theme.TypeScale

private val WORLDBOOK_MIME_TYPES = arrayOf("application/json", "*/*")

/** 世界书管理（D6）：导入 ST 世界书 JSON、查看条目、删除。 */
@Composable
fun WorldBooksScreen(
    viewModel: WorldBooksViewModel,
    onBack: () -> Unit,
) {
    val books by viewModel.books.collectAsState()
    val importing by viewModel.importing.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var detail by remember { mutableStateOf<WorldBookEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<WorldBookEntity?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import(uri)
    }

    val snackbarText = message?.let { msg ->
        if (msg.args.isEmpty()) stringResource(msg.res) else stringResource(msg.res, *msg.args.toTypedArray())
    }
    LaunchedEffect(snackbarText) {
        val text = snackbarText ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }

    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
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
                Text(stringResource(R.string.worldbook_title), fontSize = TypeScale.s19, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { picker.launch(WORLDBOOK_MIME_TYPES) }, enabled = !importing) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(
                        stringResource(if (importing) R.string.characters_importing else R.string.worldbook_import),
                        fontSize = TypeScale.s14,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            val list = books
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyWorldBooks(onImport = { picker.launch(WORLDBOOK_MIME_TYPES) })
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { book ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { detail = book }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(book.name, fontSize = TypeScale.s15, fontWeight = FontWeight.Medium, color = cs.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    stringResource(R.string.worldbook_entry_count, book.entryCount),
                                    fontSize = TypeScale.s12,
                                    color = cs.onSurfaceVariant,
                                )
                            }
                            Text(
                                stringResource(R.string.delete),
                                fontSize = TypeScale.s12_5,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.clickable {
                                    val target = book
                                    pendingDelete = target
                                }.padding(start = 12.dp),
                            )
                        }
                        Hairline(insetStart = 20)
                    }
                }
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
    }

    detail?.let { book ->
        WorldBookDetailSheet(
            book = book,
            entries = viewModel.entriesOf(book),
            onDelete = { detail = null; pendingDelete = book },
            onDismiss = { detail = null },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.worldbook_delete_title)) },
            text = { Text(stringResource(R.string.worldbook_delete_confirm, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    viewModel.delete(target)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorldBookDetailSheet(
    book: WorldBookEntity,
    entries: List<WorldInfoEntry>,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(book.name, fontSize = TypeScale.s16, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.worldbook_entry_count, book.entryCount),
                        fontSize = TypeScale.s12,
                        color = cs.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.delete),
                    fontSize = TypeScale.s13,
                    color = cs.error,
                    modifier = Modifier.clickable(onClick = onDelete).padding(8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                entries.forEach { entry ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                entry.comment.ifBlank { "#${entry.uid}" },
                                fontSize = TypeScale.s13_5,
                                fontWeight = FontWeight.Medium,
                                color = cs.onBackground,
                            )
                            Spacer(Modifier.width(8.dp))
                            if (!entry.enabled) {
                                Text(stringResource(R.string.worldbook_entry_disabled), fontSize = TypeScale.s11, color = cs.error)
                            }
                        }
                        Text(
                            if (entry.keys.isEmpty()) stringResource(R.string.worldbook_entry_no_keys)
                            else stringResource(R.string.worldbook_entry_keys, entry.keys.joinToString(", ")),
                            fontSize = TypeScale.s11_5,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Text(
                            entry.content,
                            fontSize = TypeScale.s12_5,
                            color = cs.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Hairline()
                }
            }
        }
    }
}

@Composable
private fun EmptyWorldBooks(onImport: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(cs.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text("书", fontSize = TypeScale.s28, color = cs.onSurfaceVariant, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.worldbook_empty_title), fontSize = TypeScale.s17, fontWeight = FontWeight.Medium, color = cs.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.worldbook_empty_body), fontSize = TypeScale.s13, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(cs.primary)
                .clickable(onClick = onImport)
                .padding(horizontal = 22.dp, vertical = 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.worldbook_import_cta), fontSize = TypeScale.s14, color = cs.onPrimary, fontWeight = FontWeight.Medium)
        }
    }
}
