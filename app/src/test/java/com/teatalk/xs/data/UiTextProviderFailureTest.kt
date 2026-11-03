package com.teatalk.xs.data

import com.teatalk.xs.R
import com.teatalk.xs.data.net.HttpStatusException
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `UiText.providerFailure` 的文案合同。
 *
 * 「测试连接 / 拉取模型」失败时，用户以前会看到英文 `HTTP 401 Unauthorized`；
 * 这层映射决定最终展示哪条本地化提示，所以按状态码与异常类型逐条锁定。
 */
class UiTextProviderFailureTest {

    private fun res(error: Throwable): Int = UiText.providerFailure(error).res

    @Test
    fun unauthorized401() {
        assertEquals(
            R.string.providers_fail_unauthorized,
            res(HttpStatusException(401, "Unauthorized")),
        )
    }

    @Test
    fun forbidden403() {
        assertEquals(
            R.string.providers_fail_forbidden,
            res(HttpStatusException(403, "Forbidden")),
        )
    }

    @Test
    fun notFound404() {
        assertEquals(
            R.string.providers_fail_not_found,
            res(HttpStatusException(404, "Not Found")),
        )
    }

    @Test
    fun rateLimited429() {
        assertEquals(
            R.string.providers_fail_rate_limited,
            res(HttpStatusException(429, "Too Many Requests")),
        )
    }

    @Test
    fun serverError5xxCarriesStatusCode() {
        val text = UiText.providerFailure(HttpStatusException(503, "Service Unavailable"))
        assertEquals(R.string.providers_fail_server, text.res)
        assertEquals(listOf<Any>(503), text.args)
    }

    @Test
    fun otherStatusKeepsShortCode() {
        val text = UiText.providerFailure(HttpStatusException(418, "Teapot"))
        assertEquals(R.string.providers_test_fail, text.res)
        assertEquals(listOf<Any>("HTTP 418"), text.args)
    }

    @Test
    fun timeoutMapsToTimeout() {
        assertEquals(
            R.string.providers_fail_timeout,
            res(SocketTimeoutException("timeout")),
        )
    }

    @Test
    fun ioFailureMapsToNetwork() {
        assertEquals(
            R.string.providers_fail_network,
            res(IOException("unreachable")),
        )
    }

    @Test
    fun unknownErrorFallsBackToShortMessage() {
        val text = UiText.providerFailure(IllegalStateException("boom"))
        assertEquals(R.string.providers_test_fail, text.res)
        assertEquals(listOf<Any>("boom"), text.args)
    }

    @Test
    fun unknownErrorWithoutMessageUsesGenericText() {
        assertEquals(
            R.string.providers_fail_unknown,
            res(IllegalStateException()),
        )
    }

    @Test
    fun badUrlExplainsScheme() {
        val text = UiText.providerFailure(
            IllegalArgumentException(
                "Expected URL scheme 'http' or 'https' but no scheme was found",
            ),
        )
        assertEquals(R.string.providers_fail_bad_url, text.res)
    }
}
