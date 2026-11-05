package com.teatalk.xs.data.net

import com.teatalk.core.log.Redact
import com.teatalk.core.log.TeaTalkLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/** WebDAV 连接参数（密码只在内存里出现，日志走 [Redact]）。 */
data class WebDavConfig(
    val baseUrl: String,
    val username: String,
    val password: String,
) {
    fun redactedBase(): String = Redact.url(baseUrl.trim())
}

/** 远端条目：`path` 是相对 WebDAV 基址的路径（解码后、不含开头的 `/`）。 */
data class WebDavEntry(
    val path: String,
    val name: String,
    val isCollection: Boolean,
    val size: Long,
)

/** 统一的失败类型：`status` 为 null 表示网络/解析层失败。 */
class WebDavException(
    message: String,
    val status: Int? = null,
) : Exception(message)

/**
 * 0.24.0：最小 WebDAV 客户端（PROPFIND / MKCOL / PUT / GET / DELETE）。
 *
 * 取舍：
 * - 只用 Basic Auth（坚果云 / Nextcloud / 群晖都支持应用密码）；
 * - XML 用 JDK 的 DocumentBuilder 解析（JVM 单测里也能跑，不需要 android.util.Xml）；
 * - 路径统一用「解码后的段」表达，交给 OkHttp 逐段编码：中文目录、空格、`+`、`%`
 *   都不会被二次编码或误判（Nextcloud 的基址常含用户名）；
 * - 不做同步/冲突合并：备份是「整包上传」，恢复是「补缺导入」。
 */
class WebDavClient(
    private val client: OkHttpClient = defaultClient(),
) {

    /** 根目录能不能访问：207/200 视为成功，401/403 单独提示「账号或密码不对」。 */
    suspend fun test(config: WebDavConfig): Unit = withContext(Dispatchers.IO) {
        val url = url(config, "")
        val code = propfind(config, url, depth = 0).first
        when {
            code == 207 || code == 200 -> Unit
            code == 401 || code == 403 -> throw WebDavException("unauthorized", code)
            else -> throw WebDavException("propfind failed", code)
        }
    }

    /** 逐级创建目录；已存在（405）视为成功。 */
    suspend fun makeDirs(config: WebDavConfig, path: String) = withContext(Dispatchers.IO) {
        var current = ""
        path.trim('/').split('/').filter { it.isNotBlank() }.forEach { segment ->
            current = if (current.isEmpty()) segment else "$current/$segment"
            val code = mkcol(config, url(config, current))
            if (code !in 200..299 && code != 405) throw WebDavException("mkcol failed", code)
        }
    }

    suspend fun list(config: WebDavConfig, dir: String): List<WebDavEntry> = withContext(Dispatchers.IO) {
        val (code, body) = propfind(config, url(config, dir, trailingSlash = true), depth = 1)
        // 目录还不存在（404/409）等价于「空列表」：第一次用云备份时目录往往还没建。
        if (code == 404 || code == 409) return@withContext emptyList()
        if (code != 207 && code != 200) throw WebDavException("list failed", code)
        parseEntries(body, baseUrl(config).pathSegments.filter { it.isNotEmpty() }, dir)
    }

    suspend fun put(config: WebDavConfig, path: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val request = authorized(config, url(config, path))
            .put(bytes.toRequestBody(ZIP))
            .build()
        val response = client.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) throw WebDavException("put failed", it.code)
        }
        TeaTalkLog.i("Net", "webdav.put", "path" to path, "bytes" to bytes.size)
    }

    /**
     * 下载整包。带大小护栏：恢复是唯一会把远端大文件读进内存的环节，
     * 配错地址指到一个共享盘上的超大文件时，不能直接 OOM。
     */
    suspend fun get(config: WebDavConfig, path: String): ByteArray = withContext(Dispatchers.IO) {
        val request = authorized(config, url(config, path)).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw WebDavException("get failed", response.code)
            val body = response.body ?: throw WebDavException("empty body")
            val declared = body.contentLength()
            if (declared > MAX_BACKUP_BYTES) throw WebDavException("backup too large", response.code)
            val stream = body.byteStream()
            val out = ByteArrayOutputStream(if (declared in 1..Int.MAX_VALUE.toLong()) declared.toInt() else 8192)
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MAX_BACKUP_BYTES) throw WebDavException("backup too large", response.code)
                out.write(buffer, 0, read)
            }
            if (total == 0L) throw WebDavException("empty body")
            out.toByteArray()
        }
    }

    /** 删除失败不抛（清理旧备份时不该因为一份删不掉就整体失败），只记日志。 */
    suspend fun delete(config: WebDavConfig, path: String): Boolean = withContext(Dispatchers.IO) {
        val request = authorized(config, url(config, path)).delete().build()
        client.newCall(request).execute().use {
            val ok = it.isSuccessful || it.code == 404
            if (!ok) TeaTalkLog.w("Net", "webdav.delete_fail", "path" to path, "code" to it.code)
            ok
        }
    }

    // ---- 内部 ----

    private fun propfind(config: WebDavConfig, url: String, depth: Int): Pair<Int, String> {
        val request = authorized(config, url)
            .header("Depth", depth.toString())
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML))
            .build()
        client.newCall(request).execute().use { response ->
            return response.code to (response.body?.string().orEmpty())
        }
    }

    private fun mkcol(config: WebDavConfig, url: String): Int {
        val request = authorized(config, url).method("MKCOL", EMPTY_BODY).build()
        client.newCall(request).execute().use { return it.code }
    }

    private fun authorized(config: WebDavConfig, url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(config.username, config.password, Charsets.UTF_8))

    private fun baseUrl(config: WebDavConfig): HttpUrl {
        val normalized = config.baseUrl.trim().trimEnd('/') + "/"
        return normalized.toHttpUrl()
    }

    /**
     * 相对路径拼接。路径统一用「解码后的段」表达，交给 OkHttp 逐段编码，
     * 这样中文目录 / 空格 / `+` / `%` 都不会出现二次编码或误判。
     */
    private fun url(config: WebDavConfig, path: String, trailingSlash: Boolean = false): String {
        val builder = baseUrl(config).newBuilder()
        path.trim('/').split('/').filter { it.isNotBlank() }.forEach { builder.addPathSegment(it) }
        val built = builder.build().toString()
        return if (trailingSlash && !built.endsWith("/")) "$built/" else built
    }

    private fun parseEntries(body: String, baseSegments: List<String>, dir: String): List<WebDavEntry> {
        if (body.isBlank()) return emptyList()
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // 防 XXE：备份列表不需要外部实体。Android 的解析器对部分 feature 不支持，
            // 所以逐个 try——支持就加固，不支持也不该让「列目录」直接失败。
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { isExpandEntityReferences = false }
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(body.toByteArray()))
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        val nodes = if (responses.length > 0) responses else doc.getElementsByTagName("response")
        val out = ArrayList<WebDavEntry>(nodes.length)
        for (i in 0 until nodes.length) {
            val response = nodes.item(i) as? Element ?: continue
            val href = childText(response, "href").trim()
            if (href.isEmpty()) continue
            // 不同服务端返回的 href 可能是绝对 URL、以 / 开头的绝对路径，或相对路径。
            val raw = (if (href.contains("://")) href.substringAfter("://").substringAfter('/', "") else href)
                .substringBefore('?')
                .substringBefore('#')
            // 统一解码成「段」，再按 base 的段前缀裁掉挂载路径：
            // Nextcloud 的基址常是 /remote.php/dav/files/<用户名>/，用户名可能是中文。
            val segments = percentDecode(raw).split('/').filter { it.isNotEmpty() }
            val relative = if (segments.size >= baseSegments.size &&
                segments.subList(0, baseSegments.size) == baseSegments
            ) {
                segments.drop(baseSegments.size)
            } else {
                segments
            }
            val path = relative.joinToString("/")
            val name = relative.lastOrNull().orEmpty()
            val isCollection = childText(response, "resourcetype").contains("collection") ||
                hasElement(response, "collection")
            val size = childText(response, "getcontentlength").toLongOrNull() ?: 0L
            // 跳过列目录时返回的自己：相对 base 的路径正好等于被列目录，或为空。
            val listingRoot = dir.trim('/')
            if (isCollection && (path.isEmpty() || path == listingRoot)) continue
            out += WebDavEntry(path = path, name = name, isCollection = isCollection, size = size)
        }
        return out
    }

    /** 百分号解码：只解 %XX，保留 `+`（URLDecoder 会把 `+` 当空格，这里不能那样）。 */
    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val out = ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val code = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null) {
                    out.write(code)
                    i += 3
                    continue
                }
            }
            val bytes = c.toString().toByteArray(Charsets.UTF_8)
            out.write(bytes, 0, bytes.size)
            i++
        }
        return out.toString("UTF-8")
    }

    private fun childText(parent: Element, localName: String): String {
        val nodes = parent.getElementsByTagNameNS("DAV:", localName)
        val fallback = if (nodes.length == 0) parent.getElementsByTagName(localName) else nodes
        return if (fallback.length == 0) "" else fallback.item(0).textContent.orEmpty()
    }

    private fun hasElement(parent: Element, localName: String): Boolean =
        parent.getElementsByTagNameNS("DAV:", localName).length > 0 ||
            parent.getElementsByTagName(localName).length > 0

    companion object {
        /** 单个备份包上限：正常备份 5–20 MB，超过这个数基本是地址配错或异常数据。 */
        private const val MAX_BACKUP_BYTES = 256L * 1024 * 1024

        private val ZIP = "application/zip".toMediaType()
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)

        private const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:displayname/>
    <d:resourcetype/>
    <d:getcontentlength/>
  </d:prop>
</d:propfind>"""

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
