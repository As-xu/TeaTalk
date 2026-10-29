package com.teatalk.xs.ui.characters

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teatalk.xs.R
import com.teatalk.xs.data.CardEdit
import com.teatalk.xs.data.CardViewParser
import com.teatalk.xs.data.db.CharacterEntity
import com.teatalk.xs.ui.theme.TypeScale

/**
 * 卡片编辑弹窗（D6）：名称 / 标签 / 简介 / 性格 / 场景 / 开场白 / 创作者注释。
 *
 * 只改文本字段，不动头像、来源与其余扩展字段（cardJson 里其它键原样保留）。
 */
@Composable
fun EditCardDialog(
    character: CharacterEntity,
    onDismiss: () -> Unit,
    onSave: (CardEdit) -> Unit,
) {
    val card = remember(character.id) { CardViewParser.parse(character.cardJson) }
    var name by rememberSaveable { mutableStateOf(character.name) }
    var tags by rememberSaveable { mutableStateOf(card.tags.joinToString(", ")) }
    var description by rememberSaveable { mutableStateOf(card.description) }
    var personality by rememberSaveable { mutableStateOf(card.personality) }
    var scenario by rememberSaveable { mutableStateOf(card.scenario) }
    var firstMes by rememberSaveable { mutableStateOf(card.firstMes) }
    var creatorNotes by rememberSaveable { mutableStateOf(card.creatorNotes) }
    var systemPrompt by rememberSaveable { mutableStateOf(card.systemPrompt) }
    var postHistory by rememberSaveable { mutableStateOf(card.postHistoryInstructions) }
    var mesExample by rememberSaveable { mutableStateOf(card.mesExample) }
    var alternateGreetings by rememberSaveable { mutableStateOf(card.alternateGreetings.joinToString("\n")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_card_title)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EditField(R.string.edit_field_name, name, singleLine = true) { name = it }
                EditField(R.string.edit_field_tags, tags, singleLine = true) { tags = it }
                EditField(R.string.edit_field_description, description, minLines = 5) { description = it }
                EditField(R.string.edit_field_personality, personality, minLines = 3) { personality = it }
                EditField(R.string.edit_field_scenario, scenario, minLines = 3) { scenario = it }
                EditField(R.string.edit_field_first_mes, firstMes, minLines = 4) { firstMes = it }
                EditField(R.string.edit_field_creator_notes, creatorNotes, minLines = 2) { creatorNotes = it }
                EditField(R.string.edit_field_system_prompt, systemPrompt, minLines = 3) { systemPrompt = it }
                EditField(R.string.edit_field_post_history, postHistory, minLines = 2) { postHistory = it }
                EditField(R.string.edit_field_mes_example, mesExample, minLines = 4) { mesExample = it }
                EditField(R.string.edit_field_alternate_greetings, alternateGreetings, minLines = 3) { alternateGreetings = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    CardEdit(
                        name = name.trim().ifBlank { character.name },
                        tags = tags.split(',', '，')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinct(),
                        description = description.trim(),
                        personality = personality.trim(),
                        scenario = scenario.trim(),
                        firstMes = firstMes.trim(),
                        creatorNotes = creatorNotes.trim(),
                        systemPrompt = systemPrompt.trim(),
                        postHistoryInstructions = postHistory.trim(),
                        mesExample = mesExample.trim(),
                        alternateGreetings = alternateGreetings.split('\n')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() },
                    ),
                )
            }) { Text(stringResource(R.string.edit_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun EditField(
    labelRes: Int,
    value: String,
    singleLine: Boolean = false,
    minLines: Int = 1,
    onValueChange: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(labelRes), fontSize = TypeScale.s11_5, color = cs.onSurfaceVariant)
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 3.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(cs.surfaceContainerHigh)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                minLines = minLines,
                textStyle = TextStyle(fontSize = TypeScale.s13, color = cs.onBackground),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
