package com.teatalk.xs.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teatalk.xs.ui.theme.TypeScale

/** 底部 Tab 的数据：图标 + 文案 + 路由 key。 */
data class TabItem(
    val key: String,
    @StringRes val labelRes: Int,
    val iconRes: Int,
)

/** QQ 风底栏：选中只换色 + 加粗，没有 M3 的胶囊指示器。 */
@Composable
fun AppBottomBar(
    tabs: List<TabItem>,
    currentKey: String,
    onSelect: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(cs.surface)) {
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val active = tab.key == currentKey
                val tint = if (active) cs.primary else cs.onSurfaceVariant
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { onSelect(tab.key) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(painterResource(tab.iconRes), contentDescription = stringResource(tab.labelRes), tint = tint, modifier = Modifier.size(23.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(tab.labelRes),
                        fontSize = TypeScale.s10,
                        color = tint,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** 顶部搜索胶囊（QQ 首页同构）；M0 只是占位，点击无跳转。 */
@Composable
fun SearchPill(hint: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(cs.surfaceContainerHigh),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(hint, fontSize = TypeScale.s13_5, color = cs.onSurfaceVariant)
    }
}

/** 分区标题：左侧 3dp 主色竖条（与 UX 样张一致）。 */
@Composable
fun SectionLabel(text: String, trailing: String? = null, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(14.dp).clip(RoundedCornerShape(2.dp)).background(cs.primary))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = TypeScale.s14, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
        Spacer(Modifier.weight(1f))
        if (trailing != null) Text(trailing, fontSize = TypeScale.s12, color = cs.onSurfaceVariant)
    }
}

/** 小标签胶囊。 */
@Composable
fun PillTag(text: String, tinted: Boolean = true, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val bg = if (tinted) cs.primaryContainer.copy(alpha = 0.6f) else cs.surfaceContainerHigh
    val fg = if (tinted) cs.onPrimaryContainer else cs.onSurfaceVariant
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(text, fontSize = TypeScale.s11, color = fg)
    }
}

/** 0.6dp 细分隔线（比 Material 的 Divider 更轻，接近 QQ）。 */
@Composable
fun Hairline(modifier: Modifier = Modifier, insetStart: Int = 0) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = insetStart.dp)
            .height(0.6.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    )
}

