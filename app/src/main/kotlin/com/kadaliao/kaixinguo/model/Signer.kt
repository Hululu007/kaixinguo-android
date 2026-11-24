package com.kadaliao.kaixinguo.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.openhongguo.ErrorCode
import dev.openhongguo.OpenhongguoException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.UnknownServiceException
import kotlin.coroutines.cancellation.CancellationException

/**
 * How long playback keeps signing with the service after the built-in
 * signing failed where the service succeeded, before trying the built-in
 * signing again.
 */
const val REMOTE_FOR_MS = 30 * 60 * 1000L

/**
 * SignerFallback is the client that signs with the SDK's built-in signing,
 * [local], and maybe one that signs with an openhongguo-signer service the
 * viewer set up, [remote]. Only playback, which the server's changes to
 * signing broke each time, goes through [call]: when the built-in signing
 * gets nothing back and the service gets an answer, playback asks the
 * service first for a while. Everything else asks [local] alone.
 */
class SignerFallback<C : Any>(val local: C, private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private var remote: C? = null
    private var until = 0L // remote first until then

    /** Has playback fall back to `client`, or to none. */
    fun setRemote(client: C?) {
        synchronized(lock) {
            remote = client
            until = 0L
        }
    }

    val hasRemote: Boolean get() = synchronized(lock) { remote != null }

    /** Whether playback asks the service first now. */
    val remoteFirst: Boolean get() = order().first !== local

    /** The clients to ask, in turn: the one whose signing worked last first, and null for no second. */
    private fun order(): Pair<C, C?> = synchronized(lock) {
        val r = remote
        if (r != null && now() < until) r to local else local to r
    }

    /** Notes that s's signing got the answer where the other's did not. */
    private fun worked(s: C) {
        synchronized(lock) {
            until = if (s === remote) now() + REMOTE_FOR_MS else 0L
        }
    }

    /**
     * Runs f with the client whose signing works now, and with the other
     * when that one's signing fails. When both fail, the error is the
     * built-in signing's.
     */
    suspend fun <T> call(f: suspend (C) -> T): T {
        val (first, second) = order()
        val err = try {
            return f(first)
        } catch (e: OpenhongguoException) {
            if (second == null || !signingFailed(e) && e.code != ErrorCode.SIGNER_UNAVAILABLE) throw e
            e
        }
        debugLog { "signer: ${if (first === local) "built-in" else "service"} failed: ${err.code}" }
        try {
            return f(second).also { worked(second) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is OpenhongguoException && e.code == ErrorCode.DRM_PROTECTED) {
                worked(second) // signed through: an answer, if not a playable one
                throw e
            }
            debugLog { "signer: ${if (second === local) "built-in" else "service"} failed too: ${(e as? OpenhongguoException)?.code ?: e}" }
            throw if (first === local) err else e
        }
    }
}

/** Whether the built-in signing may be behind err: when the server stops taking it, requests come back empty or rejected. */
fun signingFailed(err: Throwable): Boolean =
    isCode(err, ErrorCode.ANONYMOUS_LIMITED, ErrorCode.SIGNATURE_REJECTED)

/**
 * SignerSettings is the signing service playback falls back to, as the
 * settings keep it; an empty url is none.
 */
data class SignerSettings(val url: String = "", val token: String = "") {
    val isEmpty: Boolean get() = url.isEmpty()

    companion object {
        /** The settings in file; missing or unreadable ones are none. */
        fun load(file: File): SignerSettings =
            try {
                val o = JSONObject(file.readText())
                SignerSettings(o.optString("url"), o.optString("token"))
            } catch (e: Exception) {
                SignerSettings()
            }
    }

    /** Writes the settings to file, readable by the app alone; none removes it. */
    fun save(file: File) {
        if (isEmpty) {
            if (file.exists() && !file.delete()) throw IOException("删不掉 ${file.name}")
            return
        }
        file.parentFile?.mkdirs()
        val o = JSONObject().put("url", url)
        if (token.isNotEmpty()) o.put("token", token)
        val tmp = File(file.path + ".tmp")
        tmp.writeText(o.toString())
        tmp.setReadable(false, false)
        tmp.setReadable(true, true)
        if (!tmp.renameTo(file)) throw IOException("写不进 ${file.name}")
    }
}

/** What is wrong with a service's address, or "". */
fun checkSignerUrl(raw: String): String {
    if (raw.isEmpty()) return ""
    val ok = try {
        val u = URI(raw)
        (u.scheme == "http" || u.scheme == "https") && !u.host.isNullOrEmpty()
    } catch (e: Exception) {
        false
    }
    return if (ok) "" else "请填写以 http:// 或 https:// 开头的地址"
}

/**
 * Asks the service at url whether it is ready to sign and takes token;
 * null when it is, else what went wrong in the viewer's words.
 */
suspend fun probeSigner(url: String, token: String): String? = withContext(Dispatchers.IO) {
    val base = url.trimEnd('/')
    val ready = try {
        signerDo("GET", "$base/ready", "", null)
    } catch (e: IOException) {
        debugLog { "signer: $e" }
        // Android lets the app reach https:// alone; the SDK, which has its own connections, still can.
        if (e is UnknownServiceException || e.message.orEmpty().contains("cleartext", ignoreCase = true)) {
            return@withContext "Android 不让开心果检查 http:// 地址，建议换成 https://"
        }
        return@withContext "连不上签名服务"
    }
    when {
        ready.first != 200 -> return@withContext "这个地址不是签名服务（HTTP ${ready.first}）"
        !(runCatching { JSONObject(ready.second).optBoolean("ready") }.getOrDefault(false)) ->
            return@withContext "签名服务还在启动，稍后再试"
    }
    // Signing nothing: the service checks the token before the request,
    // which it then refuses for having no URL.
    val code = try {
        signerDo("POST", "$base/feature-hash", token, "{}").first
    } catch (e: IOException) {
        debugLog { "signer: $e" }
        return@withContext "连不上签名服务"
    }
    when (code) {
        401 -> if (token.isEmpty()) "签名服务要求填写 token" else "token 不对"
        400 -> null
        else -> "签名服务返回 HTTP $code"
    }
}

/** The status and (at most 64 KB of) body of a request to the service. */
private fun signerDo(method: String, url: String, token: String, body: String?): Pair<Int, String> {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        c.instanceFollowRedirects = false
        if (token.isNotEmpty()) c.setRequestProperty("X-Signer-Token", token)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val text = stream?.use { s ->
            val buf = ByteArray(1 shl 16)
            var n = 0
            while (n < buf.size) {
                val r = s.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            String(buf, 0, n)
        }.orEmpty()
        return code to text
    } finally {
        c.disconnect()
    }
}

/**
 * SignerForm is what the settings' signing service section edits, until it
 * is saved: saving checks that the service answers as one, and a second
 * save keeps one that did not.
 */
class SignerForm(
    saved: SignerSettings,
    private val probe: suspend (String, String) -> String?,
    private val apply: (SignerSettings) -> String?,
) {
    var url by mutableStateOf(saved.url)
        private set
    var token by mutableStateOf(saved.token)
        private set
    var checking by mutableStateOf(false)
        private set

    /** Why the service did not answer as one should, or saving failed. */
    var error by mutableStateOf("")
        private set

    /** Saving again saves it all the same. */
    var unsure by mutableStateOf(false)
        private set

    val urlError: String get() = checkSignerUrl(url.trim())

    fun editUrl(s: String) {
        url = s
        error = ""
        unsure = false
    }

    fun editToken(s: String) {
        token = s
        error = ""
        unsure = false
    }

    /** Checks and saves; true when the settings are saved and in use. */
    suspend fun save(): Boolean {
        if (checking || urlError.isNotEmpty()) return false
        val u = url.trim()
        val s = SignerSettings(u, if (u.isEmpty()) "" else token.trim())
        if (!s.isEmpty && !unsure) {
            checking = true
            error = ""
            val problem = try {
                probe(s.url, s.token)
            } finally {
                checking = false
            }
            if (problem != null) {
                error = problem
                unsure = true
                return false
            }
        }
        val failed = apply(s)
        if (failed != null) {
            error = failed
            return false
        }
        return true
    }
}
