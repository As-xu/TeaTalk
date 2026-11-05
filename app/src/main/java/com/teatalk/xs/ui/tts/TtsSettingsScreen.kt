package com.teatalk.xs.ui.tts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.teatalk.xs.R
import com.teatalk.xs.data.tts.TtsController
import com.teatalk.xs.ui.theme.TypeScale
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 0.25.0：朗读设置。
 *
 * 只放真正影响听感的三个参数（语速、音高、自动朗读）+ 试听；引擎/音色交给系统设置，
 * 避免在 App 里重复造一套不可控的列表（系统 TTS 的音色随厂商变化）。
 */
@Composable
fun TtsSettingsScreen(
    controller: TtsController,
    rate: Float,
    pitch: Float,
    autoRead: Boolean,
    onRateChange: (Float) -> Unit,
    onRateCommit: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onPitchCommit: (Float) -> Unit,
    onAutoReadChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val state by controller.state.collectAsState()
    val failed by controller.failed.collectAsState()
    val speaking = state.speaking
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val unavailable = stringResource(R.string.tts_unavailable)
    val rateLabel = stringResource(R.string.tts_rate)
    val pitchLabel = stringResource(R.string.tts_pitch)

    // 试听失败要看得见：否则按钮弹回「试听」，用户以为没点到。
    LaunchedEffect(failed) {
        if (failed) {
            controller.clearFailure()
            scope.launch { snackbarHost.showSnackbar(unavailable) }
        }
    }
    val previewText = stringResource(R.string.tts_preview_text)
    var draftRate by remember(rate) { mutableFloatStateOf(rate) }
    var draftPitch by remember(pitch) { mutableFloatStateOf(pitch) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    stringResource(R.string.tts_title),
                    fontSize = TypeScale.s20,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                stringResource(R.string.tts_note),
                fontSize = TypeScale.s12_5,
                lineHeight = TypeScale.s12_5 * 1.5f,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.VolumeUp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.tts_rate),
                            fontSize = TypeScale.s14,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            String.format(Locale.US, "%.2f×", draftRate),
                            fontSize = TypeScale.s13,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = draftRate,
                        onValueChange = {
                            draftRate = it
                            onRateChange(it)
                        },
                        // 只在松手时落盘：拖动过程中每帧写 SharedPreferences 是纯浪费。
                        onValueChangeFinished = { onRateCommit(draftRate) },
                        valueRange = 0.5f..1.5f,
                        steps = 9,
                        // TalkBack 只读「滑块 50%」，把标签挂到滑杆本身上。
                        modifier = Modifier.semantics {
                            contentDescription = rateLabel
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.tts_pitch),
                            fontSize = TypeScale.s14,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            String.format(Locale.US, "%.2f", draftPitch),
                            fontSize = TypeScale.s13,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = draftPitch,
                        onValueChange = {
                            draftPitch = it
                            onPitchChange(it)
                        },
                        onValueChangeFinished = { onPitchCommit(draftPitch) },
                        valueRange = 0.5f..1.5f,
                        steps = 9,
                        modifier = Modifier.semantics {
                            contentDescription = pitchLabel
                        },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.tts_auto_read),
                            fontSize = TypeScale.s14,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            stringResource(R.string.tts_auto_read_sub),
                            fontSize = TypeScale.s11_5,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoRead, onCheckedChange = onAutoReadChange)
                }
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = {
                    if (speaking) {
                        controller.stop()
                    } else {
                        controller.preview(previewText)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    if (speaking) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (speaking) R.string.tts_stop else R.string.tts_preview))
            }

            if (speaking && state.total > 1) {
                Text(
                    stringResource(R.string.tts_progress, state.index + 1, state.total),
                    fontSize = TypeScale.s11_5,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.tts_hint_system),
                fontSize = TypeScale.s11_5,
                lineHeight = TypeScale.s11_5 * 1.5f,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
