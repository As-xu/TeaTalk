package com.teatalk.xs.data

import java.security.MessageDigest

/** 支持的目标语言。code 落库/存偏好，promptName 用在提示词里。 */
data class TranslationTarget(val code: String, val promptName: String, val label: String) {
    companion object {
        const val ZH_HANS = "zh-Hans"
        const val ZH_HANT = "zh-Hant"
        const val EN = "en"

        val ALL = listOf(
            TranslationTarget(ZH_HANS, "Simplified Chinese", "简体中文"),
            TranslationTarget(ZH_HANT, "Traditional Chinese", "繁體中文"),
            TranslationTarget(EN, "English", "English"),
        )

        fun of(code: String?): TranslationTarget = ALL.firstOrNull { it.code == code } ?: ALL.first()
    }
}

/**
 * 翻译缓存键：只由「目标语言 + 原文」决定。
 *
 * 刻意不含 provider/model：换模型不该让旧译文失效（用户要的是「这段我看得懂」，
 * 而不是「这段用当前模型重翻一遍」）；要强制重翻走 [force] 分支。
 */
object TranslationKey {

    fun hash(targetLang: String, text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest((targetLang + "\u0000" + text).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * 翻译提示词。
 *
 * 只做文本到文本的转换：保留换行、不解释、不加引号——角色卡与气泡里常混着
 * `*动作*`、`{{user}}` 这类标记，让模型「自由发挥」会把它们吃掉。
 */
object TranslationPrompt {

    fun system(target: TranslationTarget): String = buildString {
        append("You are a translation engine inside a roleplay chat app. ")
        append("Translate the user's message into ${target.promptName}. ")
        append("Keep formatting exactly: line breaks, `*action*` markers, {{user}}/{{char}} placeholders, ")
        append("quotes and emoji stay as they are. ")
        append("Do not explain, do not add commentary, do not wrap the result in quotes. ")
        append("Output only the translation.")
    }

    fun user(text: String): String = "Translate this:\n\n$text"
}
