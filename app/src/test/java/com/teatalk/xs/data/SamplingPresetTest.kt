package com.teatalk.xs.data

import com.teatalk.xs.data.model.SamplingPresetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** ST 预设解析（M3 步骤 4）：字段别名、字符串数值、缺字段不崩。 */
class SamplingPresetTest {

    @Test
    fun parsesStAliases() {
        val preset = SamplingPresetStore.parseStPreset(
            """{"temp_openai":"0.65","top_p_openai":0.9,"openai_max_tokens":"512","freq_pen_openai":"0.2","pres_pen_openai":0.1}""",
        )
        assertNotNull(preset)
        assertEquals(0.65f, preset!!.temperature)
        assertEquals(0.9f, preset.topP)
        assertEquals(512, preset.maxTokens)
        assertEquals(0.2f, preset.frequencyPenalty)
        assertEquals(0.1f, preset.presencePenalty)
    }

    @Test
    fun parsesGenericNames() {
        val preset = SamplingPresetStore.parseStPreset(
            """{"temperature":1.1,"top_p":0.95,"max_tokens":1024,"frequency_penalty":0.0,"presence_penalty":0.0}""",
        )
        assertEquals(1.1f, preset!!.temperature)
        assertEquals(0.95f, preset.topP)
        assertEquals(1024, preset.maxTokens)
    }

    @Test
    fun ignoresUnrelatedPreset() {
        // 提示词顺序类预设没有采样字段：应视为「没导入」，而不是写入一堆 null。
        assertNull(SamplingPresetStore.parseStPreset("""{"prompts":[{"identifier":"main"}]}"""))
    }

    @Test
    fun toleratesBrokenJson() {
        assertNull(SamplingPresetStore.parseStPreset("not json"))
    }

    @Test
    fun summaryListsOnlyPresentValues() {
        assertEquals("temp 0.7", SamplingPresetStore.summary(com.teatalk.xs.data.model.SamplingPreset(temperature = 0.7f)))
    }
}
