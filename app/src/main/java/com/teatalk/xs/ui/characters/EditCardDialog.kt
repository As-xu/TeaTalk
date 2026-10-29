package com.teatalk.xs.ui.characters

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teatalk.xs.R
import com.teatalk.xs.data.CardEdit
import com.teatalk.xs.data.CardViewParser
import com.teatalk.xs.data.db.CharacterEntity
import com.teatalk.xs.ui.theme.TypeScale

/**
 * 卡片编辑弹窗：字段行 + 单字段编辑器两段式。
 *
 * 为什么不做 11 个内联多行输入框：多行 BasicTextField 会截获竖向拖动，
 * 用户无法把弹层滚到「高级设定」（M3 审阅 P1-B4/P2-3）。行列表只有可点击的行，
 * 拖动由外层 scroll 独占；点某一行进入独立编辑器，正文编辑不再影响弹层滚动。
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

    var editing by remember { mutableStateOf<Int?>(null) }
    var draft by remember { mutableStateOf("") }

    val fields = listOf(
        Field(R.string.edit_field_name, name, { name = it }),
        Field(R.string.edit_field_tags, tags, { tags = it }),
        Field(R.string.edit_field_description, description, { description = it }),
        Field(R.string.edit_field_personality, personality, { personality = it }),
        Field(R.string.edit_field_scenario, scenario, { scenario = it }),
        Field(R.string.edit_field_first_mes, firstMes, { firstMes = it }),
        Field(R.string.edit_field_creator_notes, creatorNotes, { creatorNotes = it }),
        Field(R.string.edit_field_system_prompt, systemPrompt, { systemPrompt = it }),
        Field(R.string.edit_field_post_history, postHistory, { postHistory = it }),
        Field(R.string.edit_field_mes_example, mesExample, { mesExample = it }),
        Field(R.string.edit_field_alternate_greetings, alternateGreetings, { alternateGreetings = it }),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_card_title)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                fields.forEachIndexed { index, field ->
                    val isAdvanced = index == 7
                    if (isAdvanced) SectionLabel(stringResource(R.string.edit_section_advanced))
                    EditRow(stringResource(field.labelRes), field.value) {
                        editing = index
                        draft = field.value
                    }
                }
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

    val editIndex = editing
    if (editIndex != null) {
        val field = fields[editIndex]
        FieldEditor(
            title = stringResource(field.labelRes),
            value = draft,
            onValueChange = { draft = it },
            onDismiss = { editing = null },
            onConfirm = {
                field.onChange(draft.trimEnd())
                editing = null
            },
        )
    }
}

private data class Field(
    val labelRes: Int,
    val value: String,
    val onChange: (String) -> Unit,
)

@Composable
private fun SectionLabel(text: String) {
    val cs = MaterialTheme.colorScheme
    Spacer(Modifier.padding(top = 10.dp))
    Text(
        text,
        fontSize = TypeScale.s11_5,
        fontWeight = FontWeight.SemiBold,
        color = cs.primary,
    )
}

@Composable
private fun EditRow(label: String, value: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = TypeScale.s11_5, color = cs.onSurfaceVariant)
            Text(
                value.ifBlank { "—" },
                fontSize = TypeScale.s13,
                color = if (value.isBlank()) cs.onSurfaceVariant.copy(alpha = 0.5f) else cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("›", fontSize = TypeScale.s16, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun FieldEditor(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = TypeScale.s15, fontWeight = FontWeight.SemiBold) },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(cs.surfaceContainerHigh)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = TextStyle(fontSize = TypeScale.s13, color = cs.onBackground),
                    cursorBrush = SolidColor(cs.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.edit_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
