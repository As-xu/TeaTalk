package com.teatalk.xs.data

import com.teatalk.core.card.CardImportException
import com.teatalk.core.card.CardParser
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 0.26.0：真实角色卡兼容回归。
 *
 * 仓库里不能放第三方卡（版权 + 体积），所以样本目录由调用方提供：
 *
 *     ./gradlew :app:testDebugUnitTest --tests '*CardCorpusTest*' \
 *         -Dteatalk.cards.dir=D:/Project/TeaTalkApp/cards20
 *
 * 目录缺失时整组跳过（CI/日常构建不受影响）。
 */
class CardCorpusTest {

    private val dir: File? = System.getProperty("teatalk.cards.dir")?.let(::File)

    private fun cards(): List<File> {
        val root = dir
        assumeTrue("未提供 -Dteatalk.cards.dir，跳过真实卡回归", root != null && root.isDirectory)
        val files = root!!.walkTopDown()
            .filter { it.isFile }
            .filter { it.extension.lowercase() in setOf("png", "json", "charx") }
            .toList()
        assumeTrue("样本目录里没有 png/json/charx：${root.absolutePath}", files.isNotEmpty())
        return files
    }

    @Test
    fun `every card parses with a name`() {
        val files = cards()
        val failures = mutableListOf<String>()
        val emptyBodies = mutableListOf<String>()
        val shellOnly = mutableListOf<String>()
        files.forEach { file ->
            val outcome = runCatching { CardParser.parse(file.readBytes()) }
            outcome.onSuccess { imported ->
                val data = imported.card.data
                if (data.name.isBlank()) failures += "${file.name}: 名称为空"
                // 空壳卡（只有名字/标签）是合法数据，不是解析缺陷：单独计数，不判失败。
                val hasContent = data.description.isNotBlank() ||
                    data.firstMes.isNotBlank() ||
                    data.personality.isNotBlank() ||
                    data.scenario.isNotBlank()
                if (!hasContent) emptyBodies += file.name
                // first_mes 是 <音乐>/<开场白> 这类占位、且没有可用的备用开场白：
                // 卡片数据问题，不算解析缺陷；App 会退回 greeting_fallback，不插占位符。
                if (Greetings.isShell(data.firstMes) && data.alternateGreetings.none { !Greetings.isShell(it) }) {
                    shellOnly += file.name
                }
            }.onFailure { error ->
                val reason = (error as? CardImportException)?.reason?.name ?: error::class.simpleName
                failures += "${file.name}: $reason ${error.message.orEmpty()}"
            }
        }
        println(
            "CardCorpus: ${files.size} 张，解析成功 ${files.size - failures.size} 张，" +
                "空壳卡 ${emptyBodies.size} 张${if (emptyBodies.isEmpty()) "" else "：" + emptyBodies.joinToString(", ")}，" +
                "占位开场白且无备用 ${shellOnly.size} 张${if (shellOnly.isEmpty()) "" else "：" + shellOnly.joinToString(", ")}",
        )
        assertTrue(
            "共 ${files.size} 张卡，解析失败 ${failures.size} 张：\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun `cn cards keep chinese text`() {
        val files = cards().filter { it.extension.lowercase() == "png" || it.extension.lowercase() == "json" }
        val broken = mutableListOf<String>()
        files.forEach { file ->
            runCatching { CardParser.parse(file.readBytes()) }
                .onSuccess { imported ->
                    val data = imported.card.data
                    val text = data.name + data.description + data.firstMes
                    if (text.contains('\uFFFD')) broken += "${file.name}: 出现替换字符（编码损坏）"
                }
        }
        assertTrue("编码异常：\n" + broken.joinToString("\n"), broken.isEmpty())
    }
}
