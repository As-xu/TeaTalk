package com.teatalk.xs.data

import com.teatalk.xs.data.net.ChatError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.23.0 多源降级：决策矩阵（纯函数，不碰网络与 Room）。 */
class FallbackPolicyTest {

    @Test
    fun retriesRateLimitTimeoutNetworkAndServerErrors() {
        assertTrue(FallbackPolicy.isRetryable(ChatError.RATE_LIMITED))
        assertTrue(FallbackPolicy.isRetryable(ChatError.TIMEOUT))
        assertTrue(FallbackPolicy.isRetryable(ChatError.NETWORK))
        assertTrue(FallbackPolicy.isRetryable(ChatError.SERVER("upstream broke")))
        assertTrue(FallbackPolicy.isRetryable(ChatError.HTTP(500)))
        assertTrue(FallbackPolicy.isRetryable(ChatError.HTTP(503)))
        assertTrue(FallbackPolicy.isRetryable(ChatError.HTTP(408)))
    }

    @Test
    fun doesNotRetryConfigurationErrors() {
        assertFalse(FallbackPolicy.isRetryable(ChatError.UNAUTHORIZED))
        assertFalse(FallbackPolicy.isRetryable(ChatError.HTTP(400)))
        assertFalse(FallbackPolicy.isRetryable(ChatError.HTTP(403)))
        assertFalse(FallbackPolicy.isRetryable(ChatError.HTTP(404)))
        assertFalse(FallbackPolicy.isRetryable(ChatError.NO_KEY))
        assertFalse(FallbackPolicy.isRetryable(ChatError.NO_MODEL))
        assertFalse(FallbackPolicy.isRetryable(ChatError.NO_PROVIDER))
        // 200 + 空 body/HTML 错误页是坏中转的常见表现，值得换一个接口再试。
        assertTrue(FallbackPolicy.isRetryable(ChatError.BAD_RESPONSE))
    }

    @Test
    fun continuesOnlyWhenRetryableAndNothingWasStreamed() {
        assertTrue(FallbackPolicy.shouldContinue(ChatError.RATE_LIMITED, producedText = false, remaining = 1))
        // 已经吐过字：拼两条回复比直接失败更糟。
        assertFalse(FallbackPolicy.shouldContinue(ChatError.RATE_LIMITED, producedText = true, remaining = 2))
        // 后面没有候选了。
        assertFalse(FallbackPolicy.shouldContinue(ChatError.TIMEOUT, producedText = false, remaining = 0))
        // 配置错误不切。
        assertFalse(FallbackPolicy.shouldContinue(ChatError.UNAUTHORIZED, producedText = false, remaining = 3))
    }

    @Test
    fun orderPutsPrimaryFirstThenDedupesFallbacks() {
        assertEquals(
            listOf("p1", "f1", "f2"),
            FallbackPolicy.order("p1", listOf("f1", "p1", "f2", "f1")),
        )
    }

    @Test
    fun orderSkipsBlankIdsAndHandlesMissingPrimary() {
        assertEquals(listOf("f1"), FallbackPolicy.order(null, listOf("", " f1 ", "  ")))
        assertEquals(emptyList<String>(), FallbackPolicy.order(null, emptyList()))
        assertEquals(listOf("p1"), FallbackPolicy.order("p1", emptyList()))
    }
}
