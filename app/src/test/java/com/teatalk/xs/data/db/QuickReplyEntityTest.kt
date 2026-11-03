package com.teatalk.xs.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickReplyEntityTest {

    private fun row(title: String = "", content: String = "") =
        QuickReplyEntity(title = title, content = content)

    @Test
    fun titleWinsWhenPresent() {
        assertEquals("打招呼", row(title = "  打招呼  ", content = "你好呀").displayLabel())
    }

    @Test
    fun fallsBackToFirstNonBlankContentLine() {
        assertEquals(
            "你把杯子推过来。",
            row(content = "\n   \n你把杯子推过来。\n然后笑了笑").displayLabel(),
        )
    }

    @Test
    fun fallsBackToPlaceholderWhenEmpty() {
        assertEquals("", row().displayLabel())
    }
}
