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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teatalk.xs.ui.components.Hairline

/** 我的：QQ 风分组卡片（身份卡 + 三组设置入口）。 */
@Composable
fun ProfileScreen(onOpenModelSettings: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
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
                    Text("旅人", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
                    Spacer(Modifier.height(2.dp))
                    Text("在深夜读会客一样一杯茶", fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
                Text("编辑", fontSize = 12.5.sp, color = cs.primary)
            }
        }

        Spacer(Modifier.height(14.dp))
        SettingsGroup {
            SettingRow(Icons.Filled.Settings, "模型与接口", "未配置 · M1 接入", onOpenModelSettings)
            SettingRow(Icons.Filled.Palette, "主题与明暗", "跟随系统 · 奶油茶", {})
            SettingRow(Icons.Filled.UploadFile, "导入角色卡", "PNG / JSON / CHARX", {})
        }
        Spacer(Modifier.height(12.dp))
        SettingsGroup {
            SettingRow(Icons.Filled.SystemUpdateAlt, "备份与导出", "角色卡与聊天记录", {})
            SettingRow(Icons.Filled.Info, "关于 TeaTalk", "0.1.0 · AGPL-3.0 · 数据只存本机", {})
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "卸载 App 会删除全部本机数据，导出功能在 M1 提供。",
            fontSize = 11.5.sp,
            color = cs.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
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
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
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
                Text(title, fontSize = 14.5.sp, color = cs.onBackground)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 11.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
