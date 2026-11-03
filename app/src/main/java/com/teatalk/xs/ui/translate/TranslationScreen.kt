package com.teatalk.xs.ui.translate

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.teatalk.xs.R
import com.teatalk.xs.data.TranslationTarget
import com.teatalk.xs.ui.components.Hairline
import com.teatalk.xs.ui.theme.TypeScale

/**
 * 0.21.0 翻译设置。
 *
 * 翻译只作用于「给人看」的文本：目标语言决定回复翻成什么，自动翻译决定入站是否自动翻；
 * 翻译接口可单独指定（默认跟随聊天接口），缓存大小给一个可清理的出口。
 */
@Composable
fun TranslationScreen(
    viewModel: TranslationViewModel,
    onBack: () -> Unit,
) {
    val targetLang by viewModel.targetLang.collectAsState()
    val autoTranslate by viewModel.autoTranslate.collectAsState()
    val providerId by viewModel.providerId.collectAsState()
    val cacheSize by viewModel.cacheSize.collectAsState()
    val providers by viewModel.providerRows.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var languagePicker by remember { mutableStateOf(false) }
    var providerPicker by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val target = TranslationTarget.of(targetLang)

    val snackbarText = message?.let { msg ->
        if (msg.args.isEmpty()) stringResource(msg.res) else stringResource(msg.res, *msg.args.toTypedArray())
    }
    LaunchedEffect(snackbarText) {
        val text = snackbarText ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        viewModel.consumeMessage()
    }

    Scaffold(
        containerColor = cs.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .statusBarsPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = cs.onSurface,
                    )
                }
                Text(
                    stringResource(R.string.translate_title),
                    fontSize = TypeScale.s20,
                    fontWeight = FontWeight.Medium,
                    color = cs.onSurface,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Hairline()

            Column(Modifier.verticalScroll(rememberScrollState())) {
                SectionLabel(stringResource(R.string.translate_target))
                SettingCard {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { languagePicker = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(target.label, fontSize = TypeScale.s15, color = cs.onSurface)
                            Text(
                                if (targetLang == TranslationTarget.EN) {
                                    stringResource(R.string.translate_target_en_hint)
                                } else {
                                    target.promptName
                                },
                                fontSize = TypeScale.s12,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Text(
                            stringResource(R.string.edit),
                            fontSize = TypeScale.s13,
                            color = cs.primary,
                        )
                    }
                }

                SectionLabel(stringResource(R.string.translate_auto))
                SettingCard {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.translate_auto),
                                fontSize = TypeScale.s15,
                                color = cs.onSurface,
                            )
                            Text(
                                stringResource(R.string.translate_auto_sub),
                                fontSize = TypeScale.s12,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Switch(checked = autoTranslate, onCheckedChange = viewModel::setAutoTranslate)
                    }
                }

                SectionLabel(stringResource(R.string.translate_provider))
                SettingCard {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { providerPicker = true }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                providers.firstOrNull { it.id == providerId }?.name
                                    ?: stringResource(R.string.translate_provider_follow),
                                fontSize = TypeScale.s15,
                                color = cs.onSurface,
                            )
                            Text(
                                stringResource(R.string.translate_provider_hint),
                                fontSize = TypeScale.s12,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Text(
                            stringResource(R.string.edit),
                            fontSize = TypeScale.s13,
                            color = cs.primary,
                        )
                    }
                }

                SectionLabel(stringResource(R.string.translate_cache, cacheSize))
                SettingCard {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.clearCache() }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.translate_clear_cache),
                            fontSize = TypeScale.s15,
                            color = cs.error,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (languagePicker) {
        AlertDialog(
            onDismissRequest = { languagePicker = false },
            title = { Text(stringResource(R.string.translate_target)) },
            text = {
                Column {
                    TranslationTarget.ALL.forEach { item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setTargetLang(item.code)
                                    languagePicker = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                item.label,
                                fontSize = TypeScale.s15,
                                color = cs.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            if (item.code == targetLang) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = cs.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { languagePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (providerPicker) {
        AlertDialog(
            onDismissRequest = { providerPicker = false },
            title = { Text(stringResource(R.string.translate_provider)) },
            text = {
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.setProvider(null)
                                providerPicker = false
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.translate_provider_follow),
                            fontSize = TypeScale.s15,
                            color = cs.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (providerId == null) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = cs.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    providers.forEach { row ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setProvider(row.id)
                                    providerPicker = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(row.name, fontSize = TypeScale.s15, color = cs.onSurface)
                                Text(
                                    row.defaultModel.ifBlank { row.baseUrl },
                                    fontSize = TypeScale.s11,
                                    color = cs.onSurfaceVariant,
                                )
                            }
                            if (row.id == providerId) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = cs.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { providerPicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = TypeScale.s12,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(14.dp),
            ),
    ) {
        content()
    }
    Hairline(Modifier.padding(horizontal = 16.dp))
}
