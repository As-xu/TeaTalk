package com.teatalk.xs.data

import com.teatalk.xs.data.net.ChatError

/**
 * 多源降级（0.23.0）：哪些失败值得换接口重试、以及换的顺序。
 *
 * 原则：**只有「换一个接口可能就好了」的失败才切换**。
 * - 429 / 5xx / 超时 / 断网 / 服务端中途报错 → 切；
 * - 200 但 body 是空的 / HTML 错误页 / 没有终止事件（BAD_RESPONSE）→ 切：坏中转的典型症状；
 * - 401 / 400 / 404 / 没 key / 没模型 → 配置问题，换接口只会重复失败，直接报错。
 *
 * 纯函数，便于单测（不碰 Room / 网络）。
 */
object FallbackPolicy {

    /** 该错误是否值得换下一个接口重试。 */
    fun isRetryable(error: ChatError): Boolean = when (error) {
        ChatError.RATE_LIMITED, ChatError.TIMEOUT, ChatError.NETWORK, ChatError.BAD_RESPONSE -> true
        is ChatError.HTTP -> error.code == 408 || error.code == 429 || error.code in 500..599
        is ChatError.SERVER -> true
        else -> false
    }

    /**
     * 尝试顺序：主接口在前，备用按用户配置顺序去重。
     *
     * @param primaryId 当前激活的 provider id（可能为 null：还没配置过）
     * @param fallbackIds 用户在「备用接口」里勾选的顺序
     */
    fun order(primaryId: String?, fallbackIds: List<String>): List<String> {
        val out = ArrayList<String>(fallbackIds.size + 1)
        primaryId?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        fallbackIds.forEach { id ->
            val clean = id.trim()
            if (clean.isNotEmpty() && clean !in out) out.add(clean)
        }
        return out
    }

    /**
     * 本轮失败后是否还要继续尝试下一个候选。
     *
     * @param producedText 已经吐出过内容就不能再切：否则两条回复会拼进同一条消息。
     * @param remaining 后面还剩几个候选
     */
    fun shouldContinue(error: ChatError, producedText: Boolean, remaining: Int): Boolean =
        !producedText && remaining > 0 && isRetryable(error)
}
