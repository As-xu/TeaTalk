package com.teatalk.xs.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 保留策略：远端最多 10 份、本地快照最多 3 份，按名字（时间戳）倒序保留。 */
class WebDavRetentionTest {

    @Test
    fun keepsNewestTenByTimestampName() {
        // 名字带毫秒后缀（yyyyMMdd-HHmmss-SSS），字典序 = 时间序。
        val names = listOf(
            "backup-20260101-000000-000.zip",
            "backup-20260102-000000-000.zip",
            "backup-20260103-000000-000.zip",
            "backup-20260104-000000-000.zip",
        )
        val stale = WebDavRetention.stale(names.shuffled(), 2)
        // 保留最新的两份，删掉最早的两份（硬编码期望，防止实现被改成升序还「自证正确」）。
        assertEquals(
            setOf("backup-20260101-000000-000.zip", "backup-20260102-000000-000.zip"),
            stale.toSet(),
        )
    }

    @Test
    fun fewerThanKeepDeletesNothing() {
        assertTrue(WebDavRetention.stale(listOf("backup-1.zip", "backup-2.zip"), 10).isEmpty())
    }

    @Test
    fun emptyListIsSafe() {
        assertTrue(WebDavRetention.stale(emptyList(), 3).isEmpty())
    }

    @Test
    fun keepZeroDeletesEverything() {
        val names = listOf("backup-1.zip", "backup-2.zip")
        assertEquals(names.toSet(), WebDavRetention.stale(names, 0).toSet())
    }
}
