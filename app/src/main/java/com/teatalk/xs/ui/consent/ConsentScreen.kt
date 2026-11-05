package com.teatalk.xs.ui.consent

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.teatalk.xs.R
import com.teatalk.xs.ui.theme.TypeScale

/**
 * 0.26.0：首次启动的知情同意。
 *
 * 只做「告知 + 一次确认」：数据都在本机、聊天内容会发给你自己配置的模型服务、
 * 成人内容默认开启但可关。同意前不进入主界面，也不发任何网络请求。
 */
@Composable
fun ConsentScreen(
    onAccept: (nsfwAllowed: Boolean) -> Unit,
    onExit: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // 旋转屏幕不能把用户刚关掉的开关重置回默认值。
    var nsfwAllowed by rememberSaveable { mutableStateOf(true) }

    Column(
        Modifier
            .fillMaxSize()
            .background(cs.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
    ) {
        Spacer(Modifier.height(48.dp))
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(cs.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("茶", fontSize = TypeScale.s28, fontWeight = FontWeight.Bold, color = cs.primary)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.consent_title),
            fontSize = TypeScale.s22,
            fontWeight = FontWeight.SemiBold,
            color = cs.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.consent_sub),
            fontSize = TypeScale.s13,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(26.dp))

        ConsentPoint(stringResource(R.string.consent_local_title), stringResource(R.string.consent_local_body))
        ConsentPoint(stringResource(R.string.consent_key_title), stringResource(R.string.consent_key_body))
        ConsentPoint(stringResource(R.string.consent_content_title), stringResource(R.string.consent_content_body))

        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.consent_nsfw),
                    fontSize = TypeScale.s15,
                    fontWeight = FontWeight.Medium,
                    color = cs.onSurface,
                )
                Text(
                    stringResource(R.string.consent_nsfw_sub),
                    fontSize = TypeScale.s12,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Switch(checked = nsfwAllowed, onCheckedChange = { nsfwAllowed = it })
        }

        Spacer(Modifier.height(26.dp))
        Button(
            onClick = { onAccept(nsfwAllowed) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(26.dp),
        ) {
            Text(stringResource(R.string.consent_accept), fontSize = TypeScale.s15, fontWeight = FontWeight.SemiBold)
        }
        TextButton(
            onClick = onExit,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            Text(stringResource(R.string.consent_exit), fontSize = TypeScale.s13, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ConsentPoint(title: String, body: String) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(cs.primary),
            )
            Text(
                title,
                fontSize = TypeScale.s15,
                fontWeight = FontWeight.Medium,
                color = cs.onSurface,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        Text(
            body,
            fontSize = TypeScale.s13,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp),
        )
    }
}
