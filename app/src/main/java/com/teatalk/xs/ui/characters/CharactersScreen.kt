package com.teatalk.xs.ui.characters

import com.teatalk.xs.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.teatalk.xs.data.db.CharacterEntity
import com.teatalk.xs.ui.components.PillTag
import com.teatalk.xs.ui.components.SearchPill
import java.io.File
import kotlinx.coroutines.launch

private val CARD_MIME_TYPES = arrayOf("image/png", "image/jpeg", "application/json", "application/zip", "*/*")

/** 角色页：QQ 风大图网格（2 列）+ 搜索 + 分段筛选。 */
@Composable
fun CharactersScreen(
    viewModel: CharactersViewModel,
    onOpen: (Long) -> Unit,
) {
    val characters by viewModel.characters.collectAsState()
    val importing by viewModel.importing.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<CharacterEntity?>(null) }
    var filter by remember { mutableStateOf(0) } // 0 最近 / 1 全部 / 2 收藏

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importCard(uri)
    }

    // UiText → 当前语言字符串再弹 snackbar（切语言后文案会跟着变）
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
            // 标题行
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.characters_title), fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { picker.launch(CARD_MIME_TYPES) }, enabled = !importing) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(stringResource(if (importing) R.string.characters_importing else R.string.characters_import), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
            SearchPill(stringResource(R.string.characters_search_hint), Modifier.padding(horizontal = 20.dp))

            // 分段筛选
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(
                    R.string.characters_filter_recent,
                    R.string.characters_filter_all,
                    R.string.characters_filter_favorites,
                ).forEachIndexed { index, label ->
                    val selected = index == filter
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(15.dp))
                            .background(if (selected) cs.primaryContainer else cs.surfaceContainerHigh)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            stringResource(label),
                            fontSize = 12.5.sp,
                            color = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(pluralStringResource(R.plurals.characters_count, characters?.size ?: 0, characters?.size ?: 0), fontSize = 12.sp, color = cs.onSurfaceVariant)
            }

            val list = characters
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyCharacters(onImport = { picker.launch(CARD_MIME_TYPES) })
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(list, key = { it.id }) { character ->
                        CharacterCard(
                            character = character,
                            onClick = { onOpen(character.id) },
                            onLongClick = { pendingDelete = character },
                        )
                    }
                }
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.characters_delete_title)) },
            text = { Text(stringResource(R.string.characters_delete_confirm, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        viewModel.delete(target)
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** 大图卡：3:4 封面 + 底部渐变压字（QQ 资料卡风格）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CharacterCard(
    character: CharacterEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val file = character.avatarPath?.let { File(context.filesDir, it) }
    Column(
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.78f)
                .clip(RoundedCornerShape(16.dp))
                .background(cs.surfaceContainerHigh),
        ) {
            if (file != null && file.exists()) {
                AsyncImage(
                    model = file,
                    contentDescription = character.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    character.name.take(1),
                    fontSize = 44.sp,
                    color = cs.primary,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.62f),
                        ),
                    ),
            )
            Text(
                character.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
            PillTag(
                text = stringResource(R.string.tag_character),
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        val tag = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(character.tagsJson)
                .let { it as? kotlinx.serialization.json.JsonArray }
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?.take(2)?.joinToString(" · ")
        }.getOrNull()
        Text(
            tag?.takeIf { it.isNotBlank() } ?: sourceLabel(character.cardSource),
            fontSize = 11.5.sp,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyCharacters(onImport: () -> Unit) {
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
            Text(stringResource(R.string.characters_placeholder_letter), fontSize = 28.sp, color = cs.onSurfaceVariant, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.characters_empty_title), fontSize = 17.sp, fontWeight = FontWeight.Medium, color = cs.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.characters_empty_body),
            fontSize = 13.sp,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(cs.primary)
                .clickable(onClick = onImport)
                .padding(horizontal = 22.dp, vertical = 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.characters_import_cta), fontSize = 14.sp, color = cs.onPrimary, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun sourceLabel(source: String): String = when (source) {
    "PNG_V2" -> stringResource(R.string.source_png_v2)
    "PNG_V3" -> stringResource(R.string.source_png_v3)
    "JSON_V2", "JSON_V3" -> stringResource(R.string.source_json)
    "CHARX" -> stringResource(R.string.source_charx)
    else -> stringResource(R.string.source_local)
}
