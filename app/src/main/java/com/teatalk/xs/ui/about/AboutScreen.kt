package com.teatalk.xs.ui.about

import android.content.Intent
import android.os.Build
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teatalk.core.log.TeaTalkLog
import com.teatalk.xs.BuildConfig
import com.teatalk.xs.R
import com.teatalk.xs.TeaTalkApp
import com.teatalk.xs.data.db.TeaTalkDatabase
import com.teatalk.xs.log.Diagnostics
import com.teatalk.xs.ui.components.Hairline
import kotlinx.coroutines.delay
import com.teatalk.xs.ui.theme.TypeScale

/** 关于 + 诊断日志（设置 → 我的 → 关于 TeaTalk）。 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as TeaTalkApp).container }
    val cs = MaterialTheme.colorScheme
    var debugEnabled by remember { mutableStateOf(container.logSettings.debugEnabled) }
    var lines by remember { mutableStateOf(List(0) { "" }) }

    // 打开期间每 2 秒刷新一次缓冲快照（演示/排查用，量小无压力）
    LaunchedEffect(Unit) {
        while (true) {
            lines = Diagnostics.recentLines(container.logRing, limit = 300)
            delay(2000)
        }
    }

    Column(Modifier.fillMaxSize().background(cs.background).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(
                stringResource(R.string.profile_about),
                fontSize = TypeScale.s19,
                fontWeight = FontWeight.SemiBold,
                color = cs.onBackground,
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    InfoRow(stringResource(R.string.about_version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    InfoRow(stringResource(R.string.about_package), context.packageName)
                    InfoRow(stringResource(R.string.about_device), "${Build.MANUFACTURER} ${Build.MODEL}")
                    InfoRow(stringResource(R.string.about_android), "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    InfoRow(stringResource(R.string.about_db), TeaTalkDatabase.VERSION.toString())
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(cs.surface)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.logs_debug), fontSize = TypeScale.s15, fontWeight = FontWeight.Medium, color = cs.onSurface)
                            Text(
                                stringResource(R.string.logs_debug_sub),
                                fontSize = TypeScale.s12,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Switch(
                            checked = debugEnabled,
                            onCheckedChange = {
                                debugEnabled = it
                                container.setDebugLogging(it)
                            },
                        )
                    }
                    Hairline()
                    ActionRow(stringResource(R.string.logs_export), stringResource(R.string.logs_export_sub)) {
                        val file = Diagnostics.exportToFile(context, container.logRing)
                        runCatching {
                            context.startActivity(Intent.createChooser(Diagnostics.shareIntent(context, file), null))
                        }
                    }
                    Hairline()
                    ActionRow(stringResource(R.string.logs_clear), stringResource(R.string.logs_clear_sub)) {
                        container.logRing.clear()
                        TeaTalkLog.i("Ui", "log.clear")
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                    Text(stringResource(R.string.logs_recent), fontSize = TypeScale.s14, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
                    Spacer(Modifier.weight(1f))
                    Text("${lines.size}", fontSize = TypeScale.s12, color = cs.onSurfaceVariant)
                }
            }
            if (lines.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.logs_empty),
                        fontSize = TypeScale.s13,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }
            } else {
                items(lines) { line ->
                    Text(
                        line,
                        fontSize = TypeScale.s10,
                        lineHeight = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = TypeScale.s13, color = cs.onSurfaceVariant, modifier = Modifier.width(120.dp), maxLines = 1)
        Text(value, fontSize = TypeScale.s13, color = cs.onSurface, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(title, fontSize = TypeScale.s15, fontWeight = FontWeight.Medium, color = cs.onSurface)
        Text(subtitle, fontSize = TypeScale.s12, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    }
}
