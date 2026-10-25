package com.teatalk.design.screens

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teatalk.design.Avatar
import com.teatalk.design.R
import com.teatalk.design.SampleChar
import com.teatalk.design.SectionTitle
import com.teatalk.design.Tag
import com.teatalk.design.sampleChars

private val hero = sampleChars[0]

@Composable
fun DetailSample() {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(cs.background).verticalScroll(rememberScrollState())) {
        // 封面 + 渐变 + 名字
        Box(Modifier.fillMaxWidth().height(370.dp)) {
            Image(
                painter = painterResource(hero.cover),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0.35f to Color.Transparent,
                        1f to cs.background,
                    ),
                ),
            )
            Row(
                Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(34.dp).background(Color(0x33000000), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(34.dp).background(Color(0x33000000), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = Color.White, modifier = Modifier.size(19.dp))
                }
            }
            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp)) {
                Text(hero.name, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text("作者 ${hero.author} · v1.2 · 世界书 9 条", fontSize = 12.sp, color = Color(0xCCFFFFFF))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    hero.tags.forEach { t ->
                        Box(
                            Modifier.background(Color(0x33FFFFFF), RoundedCornerShape(7.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) { Text(t, fontSize = 11.sp, color = Color.White) }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
        }

        // 主 CTA
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.weight(1.55f).height(46.dp)
                    .background(cs.primary, RoundedCornerShape(23.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("开始聊天", fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = cs.onPrimary)
            }
            Box(
                Modifier.weight(1f).height(46.dp)
                    .background(cs.surfaceContainerHigh, RoundedCornerShape(23.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("世界书 9", fontSize = 14.sp, color = cs.onBackground)
            }
        }

        Spacer(Modifier.height(8.dp))
        SectionTitle("简介")
        Text(
            hero.intro,
            fontSize = 14.5.sp,
            lineHeight = 23.sp,
            color = cs.onBackground,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        Spacer(Modifier.height(10.dp))
        SectionTitle("开场白")
        Box(
            Modifier.padding(horizontal = 20.dp).fillMaxWidth()
                .background(cs.surfaceContainer, RoundedCornerShape(14.dp))
                .padding(14.dp),
        ) {
            Text(
                "「你来得比约定的晚了一刻钟……不过，我等你。」\n\n茶已经续过一次水，她伸手把杯子推到你面前，指尖在杯沿停了一下。",
                fontSize = 14.sp,
                lineHeight = 22.sp,
                color = cs.onBackground,
            )
        }

        Spacer(Modifier.height(10.dp))
        SectionTitle("设定", trailing = "折叠")
        listOf(
            "性格" to "克制、耐心、擅长沉默；谈判桌上从不先开口。",
            "外貌" to "黑色长发低束，烟灰西装，右手常年戴一枚旧戒指。",
            "场景" to "雨夜茶室，二层靠窗的位置，合同摊开在桌上。",
        ).forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp)) {
                Text(k, fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.width(46.dp))
                Text(v, fontSize = 14.sp, lineHeight = 21.sp, color = cs.onBackground)
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "角色卡来源：PNG_V3 · 已通过校验",
            fontSize = 11.sp,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(28.dp).navigationBarsPadding())
    }
}

private data class Bubble(val text: String, val mine: Boolean)

private val bubbles = listOf(
    Bubble(
        "*你推门进来时，风把檐下的灯吹得晃了一下。她抬头看你，没有起身。*\n\n「坐吧。茶还热。」",
        false,
    ),
    Bubble("路上堵了一会儿。合同你看了吗？", true),
    Bubble("「看了。」她把杯子推过来，「但我想听你自己说一遍。」", false),
    Bubble("*她的视线落在你的手上，停了大概两秒，然后移开。*", false),
)

@Composable
fun ChatSample() {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(cs.background)) {
        // 顶栏
        Column(Modifier.statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = cs.onBackground,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Avatar(hero.cover, 36.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        hero.name,
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onBackground,
                    )
                    Text("在线 · 角色扮演模式", fontSize = 11.sp, color = cs.onSurfaceVariant)
                }
                Icon(Icons.Filled.MoreVert, contentDescription = "更多", tint = cs.onBackground, modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(8.dp))
            }
            Box(
                Modifier.fillMaxWidth().height(0.6.dp)
                    .background(cs.outlineVariant.copy(alpha = 0.5f)),
            )
        }

        Column(
            Modifier.weight(1f).padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                "今天 13:12",
                fontSize = 11.sp,
                color = cs.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            bubbles.forEach { b -> BubbleRow(b) }
            Spacer(Modifier.height(10.dp))
        }

        // 输入栏
        Row(
            Modifier.fillMaxWidth().background(cs.surface)
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "更多", tint = cs.onBackground, modifier = Modifier.size(23.dp))
            Spacer(Modifier.width(10.dp))
            Row(
                Modifier.weight(1f).height(40.dp)
                    .background(cs.surfaceContainerHigh, RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("说点什么…", fontSize = 14.sp, color = cs.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.EmojiEmotions, contentDescription = "表情", tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(38.dp).background(cs.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    tint = cs.onPrimary,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

@Composable
private fun BubbleRow(b: Bubble) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = if (b.mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!b.mine) {
            Avatar(hero.cover, 34.dp)
            Spacer(Modifier.width(9.dp))
        }
        Box(
            Modifier
                .widthIn(max = 268.dp)
                .background(
                    if (b.mine) cs.primary else cs.surfaceContainer,
                    RoundedCornerShape(
                        topStart = if (b.mine) 16.dp else 4.dp,
                        topEnd = if (b.mine) 4.dp else 16.dp,
                        bottomStart = 16.dp,
                        bottomEnd = 16.dp,
                    ),
                )
                .padding(horizontal = 13.dp, vertical = 9.dp),
        ) {
            Text(
                b.text,
                fontSize = 14.5.sp,
                lineHeight = 22.sp,
                color = if (b.mine) cs.onPrimary else cs.onBackground,
            )
        }
        if (b.mine) Spacer(Modifier.width(2.dp))
    }
}
