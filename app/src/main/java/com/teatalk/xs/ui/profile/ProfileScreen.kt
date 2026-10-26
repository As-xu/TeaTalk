package com.teatalk.xs.ui.profile

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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teatalk.xs.R
import com.teatalk.xs.ui.components.Hairline

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
    onOpenAbout: () -> Unit = {},
    currentLanguageTag: String? = null,
    onLanguageChange: (String?) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    var showLanguageDialog by remember { mutableStateOf(false) }
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
        // 身份卡
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        listOf(cs.primaryContainer, cs.surfaceContainerHigh),
                    ),
                )
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(cs.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("茶", fontSize = 22.sp, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.profile_persona_name),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onBackground,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.profile_persona_bio),
                        fontSize = 12.sp,
                        color = cs.onSurfaceVariant,
                    )
                }
                Text(stringResource(R.string.edit), fontSize = 12.5.sp, color = cs.primary)
            }
        }

        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            SettingRow(Icons.Filled.Settings, R.string.profile_model, R.string.profile_model_sub, onOpenModelSettings)
            SettingRow(Icons.Filled.Palette, R.string.profile_theme, R.string.profile_theme_sub, {})
            SettingRow(Icons.Filled.Language, R.string.profile_language, null, { showLanguageDialog = true }, trailing = currentLabel)
            SettingRow(Icons.Filled.UploadFile, R.string.profile_import, R.string.profile_import_sub, {})
            SettingRow(Icons.Filled.MenuBook, R.string.profile_worldbook, R.string.profile_worldbook_sub, onOpenWorldBooks)
        }
        Spacer(Modifier.height(12.dp))
        SettingsGroup {
            SettingRow(Icons.Filled.SystemUpdateAlt, R.string.profile_backup, R.string.profile_backup_sub, {})
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
            fontSize = 11.5.sp,
            color = cs.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.profile_language_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.profile_language_dialog_hint),
                        fontSize = 12.sp,
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
                                fontSize = 14.5.sp,
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
                Text(stringResource(titleRes), fontSize = 14.5.sp, color = cs.onBackground)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitleRes?.let { stringResource(it, *subtitleArgs.toTypedArray()) }
                        ?: trailing.orEmpty(),
                    fontSize = 11.5.sp,
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
