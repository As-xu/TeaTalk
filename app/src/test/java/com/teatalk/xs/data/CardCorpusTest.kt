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
    fun `every card parses with a name and importable content`() {
        val files = cards()
        val failures = mutableListOf<String>()
        files.forEach { file ->
            val outcome = runCatching { CardParser.parse(file.readBytes()) }
            outcome.onSuccess { imported ->
                val name = imported.card.data.name
                if (name.isBlank()) failures += "${file.name}: 名称为空"
                val hasContent = imported.card.data.description.isNotBlank() ||
                    imported.card.data.firstMes.isNotBlank() ||
                    imported.card.data.personality.isNotBlank()
                if (!hasContent) failures += "${file.name}: 没有任何可用的设定/开场白"
            }.onFailure { error ->
                val reason = (error as? CardImportException)?.reason?.name ?: error::class.simpleName
                failures += "${file.name}: $reason ${error.message.orEmpty()}"
            }
        }
        assertTrue(
            "共 ${files.size} 张卡，失败 ${failures.size} 张：\n" + failures.joinToString("\n"),
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
