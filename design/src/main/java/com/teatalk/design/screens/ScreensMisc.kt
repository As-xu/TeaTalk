package com.teatalk.design.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teatalk.design.Avatar
import com.teatalk.design.BottomNav
import com.teatalk.design.DesignTab
import com.teatalk.design.PaletteSet
import com.teatalk.design.R

@Composable
fun ProfileSample() {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(cs.background)) {
        Column(Modifier.statusBarsPadding().verticalScroll(rememberScrollState()).weight(1f)) {
            // 头部
            Box(
                Modifier.fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to cs.primaryContainer.copy(alpha = 0.65f),
                            1f to cs.background,
                        ),
                    ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(R.drawable.cover_1, 62.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("旅人", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = cs.onBackground)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "在雨夜茶馆等一杯茶",
                            fontSize = 12.5.sp,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Box(
                        Modifier.background(cs.surface.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) { Text("编辑", fontSize = 12.sp, color = cs.onBackground) }
                }
            }

            Spacer(Modifier.height(4.dp))
            GroupCard {
                SettingRow(Icons.Filled.Tune, "模型与接口", "未配置 · M1 接入", tint = cs.primary)
                SettingRow(Icons.Filled.Palette, "主题与明暗", "奶油茶 · 跟随系统", tint = cs.primary)
            }
            GroupCard {
                SettingRow(Icons.Filled.PhotoLibrary, "导入角色卡", "PNG / JSON / CHARX", tint = cs.primary)
                SettingRow(Icons.Filled.Backup, "备份与导出", "包含角色卡与聊天记录", tint = cs.primary)
            }
            GroupCard {
                SettingRow(Icons.Filled.Info, "关于 TeaTalk", "0.1.0 · AGPL-3.0 · 数据只存本机", tint = cs.primary)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "卸载 App 会删除本机数据，请先备份",
                fontSize = 11.sp,
                color = cs.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
        }
        BottomNav(DesignTab.Profile)
    }
}

@Composable
private fun GroupCard(content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .background(cs.surface, RoundedCornerShape(16.dp)),
    ) { content() }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, tint: androidx.compose.ui.graphics.Color) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).background(tint.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = cs.onBackground, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 11.5.sp, color = cs.onSurfaceVariant)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = cs.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
fun SpecSample(palette: PaletteSet) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().background(cs.background).statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text("设计规格 · ${palette.label}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = cs.onBackground)
        Spacer(Modifier.height(4.dp))
        Text("作为实现的验收基准：颜色 → 字号 → 尺寸 → 组件", fontSize = 12.sp, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))

        SectionLabel("颜色")
        swatches(
            "primary" to palette.primary,
            "primaryContainer" to palette.primaryContainer,
            "background" to palette.background,
            "surface" to palette.surface,
            "surfaceContainerHigh" to palette.surfaceContainerHigh,
            "secondary" to palette.secondary,
            "tertiary" to palette.tertiary,
            "outline" to palette.outline,
            "onBackground" to palette.onBackground,
        )

        Spacer(Modifier.height(18.dp))
        SectionLabel("字号 / 行高")
        specLines(
            "页面大标题" to "24sp · Bold",
            "区块标题" to "14sp · SemiBold",
            "列表主标题 / 会话名" to "16sp · Medium",
            "正文 / 气泡" to "14.5sp · 行高 22sp",
            "辅助信息" to "12sp",
            "时间 / 标签" to "11sp",
            "底栏标签" to "10sp",
        )

        Spacer(Modifier.height(18.dp))
        SectionLabel("尺寸 / 圆角")
        specLines(
            "左右安全边距" to "20dp（列表）/ 16dp（聊天）",
            "会话行高 / 头像" to "74dp / 52dp（圆角 14dp）",
            "角色卡封面" to "宽高比 0.74 · 圆角 16dp · 网格间距 12dp",
            "主按钮高度 / 圆角" to "46dp / 23dp（胶囊）",
            "气泡圆角" to "16dp，贴头像角 4dp；最大宽 268dp",
            "输入栏高度 / 发送按钮" to "40dp / 38dp 圆形",
            "底栏高度" to "56dp + 手势条安全区",
        )

        Spacer(Modifier.height(18.dp))
        SectionLabel("交互原则")
        Text(
            "· 信息密度标准（QQ 级），单行截断，不做多栏\n" +
                "· 气泡不做微信式对称，长文可满宽\n" +
                "· 只有 3 种弹层：Dialog / BottomSheet / Snackbar\n" +
                "· 手势均有等价按钮；长按 = 菜单\n" +
                "· 动效只保留：列表进出、Sheet 升降、消息出现\n" +
                "· 主题：跟随系统 / 常亮 / 常暗（M2 设置页）",
            fontSize = 13.sp,
            lineHeight = 22.sp,
            color = cs.onBackground,
        )
        Spacer(Modifier.height(30.dp).navigationBarsPadding())
    }
}

@Composable
private fun SectionLabel(text: String) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(13.dp).background(cs.primary, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(7.dp))
        Text(text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
    }
}

@Composable
private fun swatches(vararg pairs: Pair<String, androidx.compose.ui.graphics.Color>) {
    val cs = MaterialTheme.colorScheme
    pairs.toList().chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (name, color) ->
                Column(Modifier.weight(1f)) {
                    Box(
                        Modifier.fillMaxWidth().height(38.dp)
                            .background(color, RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(name, fontSize = 9.5.sp, color = cs.onSurfaceVariant, maxLines = 1)
                }
            }
            if (row.size < 3) repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun specLines(vararg pairs: Pair<String, String>) {
    val cs = MaterialTheme.colorScheme
    pairs.forEach { (k, v) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(k, fontSize = 12.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.width(120.dp))
            Text(v, fontSize = 12.5.sp, color = cs.onBackground, lineHeight = 18.sp)
        }
    }
}
