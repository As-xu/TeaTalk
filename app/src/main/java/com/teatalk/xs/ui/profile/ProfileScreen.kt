package com.teatalk.xs.ui.profile

import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.OutlinedTextField
import com.teatalk.xs.data.model.AppPrefs
import com.teatalk.xs.R
import com.teatalk.xs.data.model.SamplingPreset
import com.teatalk.xs.data.model.SamplingPresetStore
import com.teatalk.xs.ui.components.Hairline
import com.teatalk.xs.TeaTalkApp
import com.teatalk.xs.data.StDirectoryImporter
import com.teatalk.xs.ui.theme.TypeScale
import kotlinx.coroutines.launch

/** 应用内语言选项：null = 跟随系统。 */
private data class LanguageOption(val tag: String?, val labelRes: Int)

private val LANGUAGE_OPTIONS = listOf(
    LanguageOption(null, R.string.language_system),
    LanguageOption("zh-CN", R.string.language_zh),
    LanguageOption("en", R.string.language_en),
)

/** 我的：QQ 风分组卡片（身份卡 + 设置分组）。 */
@Composable
fun ProfileScreen(
    onOpenModelSettings: () -> Unit = {},
    onOpenWorldBooks: () -> Unit = {},
    onOpenRegex: () -> Unit = {},
    onOpenQuickReplies: () -> Unit = {},
    onOpenTranslation: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenCharacters: () -> Unit = {},
    onShowComingSoon: (Int) -> Unit = {},
    currentLanguageTag: String? = null,
    onLanguageChange: (String?) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    var showLanguageDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // M3：个人资料名（替换 prompt/开场白里的 {{user}}）
    val prefs = remember(context) { AppPrefs(context) }
    var userName by remember { mutableStateOf(prefs.userName) }
    var showPersonaDialog by remember { mutableStateOf(false) }
    // M3 步骤 4：ST 预设（采样参数）
    var preset by remember { mutableStateOf(SamplingPresetStore.read(context)) }
    var showPresetDialog by remember { mutableStateOf(false) }
    // M4-4：SillyTavern data/default-user 整目录导入（加固后支持 .zip）
    var showStImportChooser by remember { mutableStateOf(false) }
    // M4 加固：备份导出 / 恢复
    var showBackupChooser by remember { mutableStateOf(false) }
    var backupBusy by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var stSummary by remember { mutableStateOf<StDirectoryImporter.Summary?>(null) }
    var stImporting by remember { mutableStateOf(false) }
    var currentTreeUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val scope = rememberCoroutineScope()
    fun runStImport(zipUri: Uri?) {
        stImporting = true
        scope.launch {
            val container = (context.applicationContext as TeaTalkApp).container
            val importer = StDirectoryImporter(context, container.characterRepository, container.worldBookRepository)
            val summary = if (zipUri == null) currentTreeUri?.let { importer.import(it) } else importer.importZip(zipUri)
            stImporting = false
            if (summary == null) {
                Toast.makeText(context, context.getString(R.string.st_import_failed), Toast.LENGTH_SHORT).show()
            } else {
                stSummary = summary
            }
        }
    }
    val stImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            currentTreeUri = uri
            runStImport(null)
        }
    }
    val stZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runStImport(uri)
    }
    val backupExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            backupBusy = true
            scope.launch {
                val container = (context.applicationContext as TeaTalkApp).container
                val bytes = runCatching { container.backupRepository.export() }.getOrNull()
                val ok = if (bytes == null) false else runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    true
                }.getOrDefault(false)
                backupBusy = false
                backupMessage = context.getString(if (ok) R.string.backup_exported else R.string.backup_failed)
            }
        }
    }
    val backupRestoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            backupBusy = true
            scope.launch {
                val container = (context.applicationContext as TeaTalkApp).container
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
                val summary = if (bytes == null) null else runCatching {
                    container.backupRepository.restore(bytes)
                }.getOrNull()
                backupBusy = false
                backupMessage = if (summary == null) {
                    context.getString(R.string.backup_failed)
                } else {
                    context.getString(
                        R.string.backup_restored,
                        summary.cards,
                        summary.worldBooks,
                        summary.chats,
                        summary.messages,
                        summary.skipped,
                    )
                }
            }
        }
    }
    val importPreset = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val raw = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            val parsed = raw?.let { SamplingPresetStore.parseStPreset(it) }
            if (parsed != null) {
                SamplingPresetStore.write(context, parsed)
                preset = parsed
                Toast.makeText(context, context.getString(R.string.preset_imported), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, context.getString(R.string.preset_import_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }
    val currentLabel = stringResource(
        LANGUAGE_OPTIONS.firstOrNull { it.tag == currentLanguageTag }?.labelRes ?: R.string.language_system,
    )

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        // 身份卡（点击改称呼）
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        listOf(cs.primaryContainer, cs.surfaceContainerHigh),
                    ),
                )
                .clickable { showPersonaDialog = true }
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(cs.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("茶", fontSize = TypeScale.s22, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        userName,
                        fontSize = TypeScale.s18,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.profile_persona_bio),
                        fontSize = TypeScale.s12,
                        color = cs.onSurfaceVariant,
                    )
                }
                Text(stringResource(R.string.edit), fontSize = TypeScale.s12_5, color = cs.primary)
            }
        }

        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            SettingRow(Icons.Filled.Settings, R.string.profile_model, R.string.profile_model_sub, onOpenModelSettings)
            SettingRow(
                Icons.Filled.Palette,
                R.string.profile_theme,
                R.string.profile_theme_sub,
                { onShowComingSoon(R.string.profile_theme_soon) },
            )
            SettingRow(Icons.Filled.Language, R.string.profile_language, null, { showLanguageDialog = true }, trailing = currentLabel)
            SettingRow(Icons.Filled.UploadFile, R.string.profile_import, R.string.profile_import_sub, onOpenCharacters)
            SettingRow(Icons.Filled.MenuBook, R.string.profile_worldbook, R.string.profile_worldbook_sub, onOpenWorldBooks)
            SettingRow(Icons.Filled.Tune, R.string.profile_regex, R.string.profile_regex_sub, onOpenRegex)
            SettingRow(Icons.Filled.Edit, R.string.profile_quick_replies, R.string.profile_quick_replies_sub, onOpenQuickReplies)
            SettingRow(Icons.Filled.Language, R.string.profile_translate, R.string.profile_translate_sub, onOpenTranslation)
            SettingRow(
                Icons.Filled.Tune,
                R.string.profile_preset,
                null,
                { showPresetDialog = true },
                trailing = preset?.let { SamplingPresetStore.summary(it) } ?: stringResource(R.string.preset_none),
            )
        }
        Spacer(Modifier.height(12.dp))
        SettingsGroup {
            SettingRow(
                Icons.Filled.Download,
                R.string.profile_st_import,
                null,
                {
                    if (stImporting) {
                        Toast.makeText(context, context.getString(R.string.st_importing), Toast.LENGTH_SHORT).show()
                    } else {
                        showStImportChooser = true
                    }
                },
            )
            SettingRow(
                Icons.Filled.SystemUpdateAlt,
                R.string.profile_backup,
                R.string.profile_backup_sub,
                {
                    if (backupBusy) {
                        Toast.makeText(context, context.getString(R.string.st_importing), Toast.LENGTH_SHORT).show()
                    } else {
                        showBackupChooser = true
                    }
                },
            )
            SettingRow(
                Icons.Filled.Info,
                R.string.profile_about,
                R.string.profile_about_sub,
                onOpenAbout,
                subtitleArgs = listOf(com.teatalk.xs.BuildConfig.VERSION_NAME),
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.profile_footnote),
            fontSize = TypeScale.s11_5,
            color = cs.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
    }

    if (showStImportChooser) {
        AlertDialog(
            onDismissRequest = { showStImportChooser = false },
            title = { Text(stringResource(R.string.st_import_chooser_title)) },
            text = { Text(stringResource(R.string.st_import_chooser_hint)) },
            confirmButton = {
                TextButton(onClick = {
                    showStImportChooser = false
                    stZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                }) {
                    Text(stringResource(R.string.st_import_chooser_zip))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showStImportChooser = false
                    stImportLauncher.launch(null)
                }) {
                    Text(stringResource(R.string.st_import_chooser_folder))
                }
            },
        )
    }

    if (showBackupChooser) {
        AlertDialog(
            onDismissRequest = { showBackupChooser = false },
            title = { Text(stringResource(R.string.backup_choose_title)) },
            text = { Text(stringResource(R.string.backup_choose_hint)) },
            confirmButton = {
                TextButton(onClick = {
                    showBackupChooser = false
                    backupExportLauncher.launch("teatalk-backup-${System.currentTimeMillis()}.zip")
                }) { Text(stringResource(R.string.backup_export)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBackupChooser = false
                    backupRestoreLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                }) { Text(stringResource(R.string.backup_restore)) }
            },
        )
    }

    backupMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { backupMessage = null },
            title = { Text(stringResource(R.string.backup_choose_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { backupMessage = null }) { Text(stringResource(R.string.close)) }
            },
        )
    }

    stSummary?.let { summary ->
        val empty = summary.total == 0
        AlertDialog(
            onDismissRequest = { stSummary = null },
            title = { Text(stringResource(if (empty) R.string.st_import_empty_title else R.string.st_import_done_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.st_import_cards, summary.cards, summary.cardsFailed))
                    Text(stringResource(R.string.st_import_worlds, summary.worldBooks, summary.worldBooksFailed))
                    Text(stringResource(R.string.st_import_chats, summary.chats, summary.chatsSkipped))
                    val duplicates = summary.cardsSkipped + summary.worldBooksSkipped
                    if (duplicates > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.st_import_skipped_duplicates, duplicates),
                            fontSize = TypeScale.s12,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    if (empty) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.st_import_empty_hint))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { stSummary = null }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }

    if (showPresetDialog) {
        AlertDialog(
            onDismissRequest = { showPresetDialog = false },
            title = { Text(stringResource(R.string.preset_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.preset_dialog_hint),
                        fontSize = TypeScale.s12,
                        color = cs.onSurfaceVariant,
                    )
                    Text(
                        preset?.let { SamplingPresetStore.summary(it) } ?: stringResource(R.string.preset_none),
                        fontSize = TypeScale.s13,
                        fontWeight = FontWeight.Medium,
                        color = cs.onSurface,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPresetDialog = false
                        importPreset.launch(arrayOf("application/json", "text/*"))
                    },
                ) { Text(stringResource(R.string.preset_import)) }
            },
            dismissButton = {
                Row {
                    if (preset != null) {
                        TextButton(
                            onClick = {
                                SamplingPresetStore.clear(context)
                                preset = null
                                showPresetDialog = false
                                Toast.makeText(context, context.getString(R.string.preset_cleared), Toast.LENGTH_SHORT).show()
                            },
                        ) { Text(stringResource(R.string.preset_clear), color = cs.error) }
                    }
                    TextButton(onClick = { showPresetDialog = false }) { Text(stringResource(R.string.close)) }
                }
            },
        )
    }

    if (showPersonaDialog) {
        var draft by remember { mutableStateOf(userName) }
        AlertDialog(
            onDismissRequest = { showPersonaDialog = false },
            title = { Text(stringResource(R.string.persona_edit_title)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.persona_edit_hint)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val next = draft.trim().ifBlank { "User" }
                    prefs.userName = next
                    userName = next
                    showPersonaDialog = false
                }) { Text(stringResource(R.string.edit_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showPersonaDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
            containerColor = cs.surfaceContainerHigh,
        )
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.profile_language_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.profile_language_dialog_hint),
                        fontSize = TypeScale.s12,
                        color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    LANGUAGE_OPTIONS.forEach { option ->
                        val selected = option.tag == currentLanguageTag
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    showLanguageDialog = false
                                    if (option.tag != currentLanguageTag) onLanguageChange(option.tag)
                                }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(option.labelRes),
                                fontSize = TypeScale.s14_5,
                                color = if (selected) cs.primary else cs.onSurface,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) { Text(stringResource(R.string.close)) }
            },
        )
    }
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        content()
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    titleRes: Int,
    subtitleRes: Int?,
    onClick: () -> Unit,
    trailing: String? = null,
    subtitleArgs: List<Any> = emptyList(),
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(cs.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(titleRes), fontSize = TypeScale.s14_5, color = cs.onBackground)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitleRes?.let { stringResource(it, *subtitleArgs.toTypedArray()) }
                        ?: trailing.orEmpty(),
                    fontSize = TypeScale.s11_5,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = cs.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
        }
        Hairline(insetStart = 58)
    }
}
