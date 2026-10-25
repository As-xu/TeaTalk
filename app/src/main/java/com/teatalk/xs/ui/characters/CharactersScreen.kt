package com.teatalk.xs.ui.characters

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.teatalk.xs.R
import com.teatalk.xs.data.db.CharacterEntity
import com.teatalk.xs.ui.components.PillTag
import java.io.File
import kotlinx.coroutines.launch

private val CARD_MIME_TYPES = arrayOf("image/png", "image/jpeg", "application/json", "application/zip", "*/*")

/**
 * 角色卡库（D6）：搜索、最近/全部/收藏分段、标签筛选、长按菜单（收藏/置顶/编辑/删除）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharactersScreen(
    viewModel: CharactersViewModel,
    onOpen: (Long) -> Unit,
) {
    val cards by viewModel.cards.collectAsState()
    val query by viewModel.query.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val selectedTag by viewModel.selectedTag.collectAsState()
    val importing by viewModel.importing.collectAsState()
    val message by viewModel.message.collectAsState()
    val warnings by viewModel.warnings.collectAsState()

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var menuTarget by remember { mutableStateOf<CharacterEntity?>(null) }
    var editTarget by remember { mutableStateOf<CharacterEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<CharacterEntity?>(null) }
    var showWarnings by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importCard(uri)
    }

    // UiText → 当前语言字符串再弹 snackbar（切语言后文案会跟着变）
    val snackbarText = message?.let { msg ->
        val raw = if (msg.args.isEmpty()) stringResource(msg.res) else stringResource(msg.res, *msg.args.toTypedArray())
        // 角色名可能很长（真实卡里见过 300 字符），snackbar 里截断避免糊满屏幕
        if (raw.length > 60) raw.take(57) + "…" else raw
    }
    val warningsAction = stringResource(R.string.warnings_action)
    val hasWarnings = warnings.isNotEmpty()
    LaunchedEffect(snackbarText, hasWarnings) {
        val text = snackbarText ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = text,
            actionLabel = if (hasWarnings) warningsAction else null,
            withDismissAction = hasWarnings,
        )
        if (result == SnackbarResult.ActionPerformed) showWarnings = true
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
                    Text(
                        stringResource(if (importing) R.string.characters_importing else R.string.characters_import),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            // 搜索（可输入，实时过滤；点键盘搜索收起）
            SearchField(
                query = query,
                hint = stringResource(R.string.characters_search_hint),
                onQueryChange = viewModel::setQuery,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            // 分段筛选：最近 / 全部 / 收藏
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(
                    CharacterFilter.RECENT to R.string.characters_filter_recent,
                    CharacterFilter.ALL to R.string.characters_filter_all,
                    CharacterFilter.FAVORITES to R.string.characters_filter_favorites,
                ).forEach { (value, label) ->
                    FilterChip(selected = filter == value, label = stringResource(label)) { viewModel.setFilter(value) }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    pluralStringResource(R.plurals.characters_count, cards?.size ?: 0, cards?.size ?: 0),
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant,
                )
            }

            // 标签筛选行（有标签才出现）
            if (tags.isNotEmpty()) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        TagChip(
                            label = stringResource(R.string.characters_filter_all_tags),
                            selected = selectedTag == null,
                            onClick = { viewModel.selectTag(null) },
                        )
                    }
                    items(tags) { tag ->
                        TagChip(label = tag, selected = selectedTag == tag, onClick = {
                            viewModel.selectTag(if (selectedTag == tag) null else tag)
                        })
                    }
                }
            }

            val list = cards
            when {
                list == null -> Unit
                list.isEmpty() -> if (query.isBlank() && selectedTag == null && filter != CharacterFilter.FAVORITES) {
                    EmptyCharacters(onImport = { picker.launch(CARD_MIME_TYPES) })
                } else {
                    NoMatchCharacters(onClear = {
                        viewModel.setQuery("")
                        viewModel.selectTag(null)
                        viewModel.setFilter(CharacterFilter.RECENT)
                    })
                }
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
                            onLongClick = { menuTarget = character },
                        )
                    }
                }
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
    }

    // 长按操作菜单
    menuTarget?.let { target ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { menuTarget = null }, sheetState = sheetState) {
            CardActionSheet(
                character = target,
                onFavorite = { menuTarget = null; viewModel.toggleFavorite(target) },
                onPin = { menuTarget = null; viewModel.togglePinned(target) },
                onEdit = { menuTarget = null; editTarget = target },
                onDelete = { menuTarget = null; pendingDelete = target },
            )
        }
    }

    editTarget?.let { target ->
        EditCardDialog(
            character = target,
            onDismiss = { editTarget = null },
            onSave = { edit ->
                editTarget = null
                viewModel.saveCard(target, edit)
            },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.characters_delete_title)) },
            text = { Text(stringResource(R.string.characters_delete_confirm, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch { viewModel.delete(target) }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showWarnings) {
        WarningsSheet(warnings = warnings, onDismiss = { showWarnings = false; viewModel.consumeWarnings() })
    }
}

/** 可输入的搜索胶囊（BasicTextField，视觉与样张一致）。 */
@Composable
private fun SearchField(
    query: String,
    hint: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    Row(
        modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(cs.surfaceContainerHigh)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(hint, fontSize = 13.5.sp, color = cs.onSurfaceVariant)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.5.sp, color = cs.onBackground),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Text(
                stringResource(R.string.characters_search_clear),
                fontSize = 12.sp,
                color = cs.primary,
                modifier = Modifier.clickable { onQueryChange("") }.padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun FilterChip(selected: Boolean, label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(15.dp))
            .background(if (selected) cs.primaryContainer else cs.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            color = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun TagChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) cs.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = if (selected) cs.primary else cs.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

/** 大图卡：3:4 封面 + 底部渐变压字（QQ 资料卡风格）+ 置顶/收藏角标。 */
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
            Row(Modifier.align(Alignment.TopEnd).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (character.pinned) PillTag(text = stringResource(R.string.characters_pinned_badge))
                if (character.favorite) PillTag(text = "★", tinted = false)
                PillTag(text = stringResource(R.string.tag_character))
            }
        }
        Spacer(Modifier.height(8.dp))
        val tag = parseTags(character.tagsJson).take(2).joinToString(" · ")
        Text(
            tag.takeIf { it.isNotBlank() } ?: sourceLabel(character.cardSource),
            fontSize = 11.5.sp,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 长按菜单项。 */
@Composable
private fun CardActionSheet(
    character: CharacterEntity,
    onFavorite: () -> Unit,
    onPin: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            character.name,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = cs.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        SheetAction(
            label = stringResource(if (character.favorite) R.string.characters_menu_unfavorite else R.string.characters_menu_favorite),
            onClick = onFavorite,
        )
        SheetAction(
            label = stringResource(if (character.pinned) R.string.characters_menu_unpin else R.string.characters_menu_pin),
            onClick = onPin,
        )
        SheetAction(label = stringResource(R.string.characters_menu_edit), onClick = onEdit)
        SheetAction(label = stringResource(R.string.delete), onClick = onDelete, danger = true)
    }
}

@Composable
private fun SheetAction(label: String, onClick: () -> Unit, danger: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, color = if (danger) cs.error else cs.onBackground)
    }
}

/** 导入兼容提示明细。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WarningsSheet(warnings: List<String>, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.warnings_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.warnings_hint), fontSize = 12.5.sp, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            warnings.forEach { warning ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text("·", color = cs.primary, fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(warning, fontSize = 13.sp, color = cs.onBackground)
                }
            }
        }
    }
}

@Composable
private fun NoMatchCharacters(onClear: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.characters_no_match_title), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = cs.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.characters_no_match_body), fontSize = 13.sp, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.characters_search_clear),
            fontSize = 14.sp,
            color = cs.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(cs.primaryContainer.copy(alpha = 0.5f))
                .clickable(onClick = onClear)
                .padding(horizontal = 20.dp, vertical = 9.dp),
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
        Text(stringResource(R.string.characters_empty_body), fontSize = 13.sp, color = cs.onSurfaceVariant)
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
