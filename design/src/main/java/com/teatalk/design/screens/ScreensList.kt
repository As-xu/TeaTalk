package com.teatalk.design.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teatalk.design.Avatar
import com.teatalk.design.BottomNav
import com.teatalk.design.DesignTab
import com.teatalk.design.OneLineText
import com.teatalk.design.RowDivider
import com.teatalk.design.SearchField
import com.teatalk.design.Tag
import com.teatalk.design.UnreadBadge
import com.teatalk.design.sampleChars

@Composable
fun ChatListSample() {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(cs.background)) {
        Column(Modifier.statusBarsPadding()) {
            // QQ 式顶部：头像 + 昵称 + 在线态 + 搜索 + 加号
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(com.teatalk.design.R.drawable.cover_1, 46.dp, 23.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("旅人", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(Color(0xFF4CAF7D), CircleShape))
                        Spacer(Modifier.width(5.dp))
                        Text("在线 · 本地模式", fontSize = 11.5.sp, color = cs.onSurfaceVariant)
                    }
                }
                Icon(Icons.Filled.Search, contentDescription = "搜索", tint = cs.onBackground, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(16.dp))
                Box(
                    Modifier.size(30.dp).background(cs.surfaceContainerHigh, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "新建", tint = cs.onBackground, modifier = Modifier.size(19.dp))
                }
            }
            SearchField()
            Spacer(Modifier.height(10.dp))
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("会话", fontSize = 13.sp, color = cs.onSurfaceVariant, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text("3 位角色在等你", fontSize = 11.5.sp, color = cs.onSurfaceVariant)
        }

        Column(Modifier.weight(1f)) {
            sampleChars.forEachIndexed { index, ch ->
                Row(
                    Modifier.fillMaxWidth().height(74.dp).padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        Avatar(ch.cover, 52.dp, 14.dp)
                        if (ch.unread > 0) {
                            Box(Modifier.align(Alignment.BottomEnd)) { UnreadBadge(ch.unread) }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (index == 0) {
                                Box(
                                    Modifier.background(cs.primaryContainer, RoundedCornerShape(3.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                ) { Text("置顶", fontSize = 9.5.sp, color = cs.onPrimaryContainer) }
                                Spacer(Modifier.width(5.dp))
                            }
                            Text(
                                ch.name,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = cs.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.weight(1f))
                            Text(ch.time, fontSize = 11.sp, color = cs.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(5.dp))
                        OneLineText(ch.last, cs.onSurfaceVariant, 13)
                    }
                }
                if (index != sampleChars.lastIndex) RowDivider(insetStart = 84.dp)
            }
        }

        BottomNav(DesignTab.Chats)
    }
}

@Composable
fun CharactersSample() {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(cs.background)) {
        Column(Modifier.statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("角色", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = cs.onBackground)
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.background(cs.surfaceContainerHigh, RoundedCornerShape(18.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = cs.onBackground, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("导入", fontSize = 13.sp, color = cs.onBackground)
                }
            }
            SearchField("搜索角色 / 标签")
            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip("最近", selected = true)
                FilterChip("全部", selected = false)
                FilterChip("收藏", selected = false)
                Spacer(Modifier.weight(1f))
                Text("29 张", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(6.dp))
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp, end = 20.dp, top = 10.dp, bottom = 80.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(sampleChars.take(6)) { ch ->
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.74f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(cs.surfaceContainerHigh),
                    ) {
                        Image(
                            painter = painterResource(ch.cover),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                    0.5f to Color.Transparent,
                                    1f to Color(0xB3000000),
                                ),
                            ),
                        )
                        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                            Text(
                                ch.name,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(5.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                ch.tags.take(2).forEach { t ->
                                    Box(
                                        Modifier.background(Color(0x33FFFFFF), RoundedCornerShape(5.dp))
                                            .padding(horizontal = 6.dp, vertical = 1.5.dp),
                                    ) { Text(t, fontSize = 10.sp, color = Color.White) }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "作者 ${ch.author}",
                        fontSize = 11.sp,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        BottomNav(DesignTab.Characters)
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .background(
                if (selected) cs.primaryContainer else cs.surfaceContainerHigh,
                RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            color = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}
