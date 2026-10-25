package com.teatalk.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class DesignTab(val label: String) { Chats("聊天"), Characters("角色"), Profile("我的") }

@Composable
fun BottomNav(current: DesignTab) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(cs.surface)
            .navigationBarsPadding()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DesignTab.entries.forEach { tab ->
            val active = tab == current
            val tint = if (active) cs.primary else cs.onSurfaceVariant
            Column(
                Modifier
                    .weight(1f)
                    .clickable { },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = when (tab) {
                        DesignTab.Chats -> Icons.AutoMirrored.Filled.Chat
                        DesignTab.Characters -> Icons.Filled.Groups
                        DesignTab.Profile -> Icons.Filled.Person
                    },
                    contentDescription = tab.label,
                    tint = tint,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    tab.label,
                    fontSize = 10.sp,
                    color = tint,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
fun SearchField(hint: String = "搜索角色 / 会话") {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(cs.surfaceContainerHigh, RoundedCornerShape(19.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(hint, fontSize = 13.5.sp, color = cs.onSurfaceVariant)
    }
}

@Composable
fun Avatar(cover: Int, size: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.unit.Dp = size / 2) {
    Image(
        painter = painterResource(cover),
        contentDescription = null,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(shape))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
    )
}

@Composable
fun Tag(text: String, tinted: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val bg = if (tinted) cs.primaryContainer.copy(alpha = 0.55f) else cs.surfaceContainerHigh
    val fg = if (tinted) cs.onPrimaryContainer else cs.onSurfaceVariant
    Box(
        Modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(text, fontSize = 11.sp, color = fg)
    }
}

@Composable
fun SectionTitle(text: String, trailing: String? = null) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(14.dp).background(cs.primary, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
        Spacer(Modifier.weight(1f))
        if (trailing != null) Text(trailing, fontSize = 12.sp, color = cs.onSurfaceVariant)
    }
}

@Composable
fun RowDivider(insetStart: androidx.compose.ui.unit.Dp = 0.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = insetStart, end = 20.dp)
            .height(0.6.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    )
}

@Composable
fun UnreadBadge(count: Int) {
    Box(
        Modifier
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text("$count", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun OneLineText(text: String, color: Color, size: Int = 13) {
    Text(
        text,
        fontSize = size.sp,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun HeroGradient(painter: Painter): Painter = painter
