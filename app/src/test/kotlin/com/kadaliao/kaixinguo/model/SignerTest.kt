package com.kadaliao.kaixinguo.model

import com.sun.net.httpserver.HttpServer
import dev.openhongguo.ErrorCode
import dev.openhongguo.OpenhongguoException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress

class SignerTest {
    /** A fallback whose clients answer as local and remote say: null for an answer. */
    private class Fallback(withRemote: Boolean = true) {
        var clock = 1_000_000L
        val signer = SignerFallback("local") { clock }
        var local: ErrorCode? = null
        var remote: ErrorCode? = null
        private val asked = ArrayList<String>()

        init {
            if (withRemote) signer.setRemote("remote")
        }

        suspend fun play(): String = signer.call { c ->
            asked += c
            (if (c == "local") local else remote)?.let { throw OpenhongguoException(it, "$c: $it") }
            c
        }

        /** The code play fails with, or null when it answers. */
        suspend fun fails(): ErrorCode? =
            try {
                play()
                null
            } catch (e: OpenhongguoException) {
                e.code
            }

        fun took(): List<String> = asked.toList().also { asked.clear() }
    }

    /** While the built-in signing works, the signing service is not asked. */
    @Test
    fun builtinSigningFirst() = runTest {
        val f = Fallback()
        assertEquals("local", f.play())
        assertEquals(listOf("local"), f.took())
    }

    /** Errors that are not the signing's are not retried with the service. */
    @Test
    fun otherErrorsStay() = runTest {
        val f = Fallback()
        f.local = ErrorCode.CONTENT_UNAVAILABLE
        assertEquals(ErrorCode.CONTENT_UNAVAILABLE, f.fails())
        assertEquals(listOf("local"), f.took())
    }

    /**
     * When the server stops taking the built-in signing, playback is signed
     * by the service, and goes to the service first after that, until the
     * service fails and the built-in signing works again.
     */
    @Test
    fun fallBackToSigner() = runTest {
        val f = Fallback()
        f.local = ErrorCode.ANONYMOUS_LIMITED
        assertEquals("remote", f.play())
        assertEquals(listOf("local", "remote"), f.took())
        assertEquals("remote", f.play())
        assertEquals(listOf("remote"), f.took())
        assertTrue(f.signer.remoteFirst)

        f.local = null
        f.remote = ErrorCode.SIGNER_UNAVAILABLE
        assertEquals("local", f.play())
        assertEquals(listOf("remote", "local"), f.took())
        assertFalse("still signing with the service first", f.signer.remoteFirst)
    }

    @Test
    fun rejectedSignatureFallsBackToo() = runTest {
        val f = Fallback()
        f.local = ErrorCode.SIGNATURE_REJECTED
        assertEquals("remote", f.play())
        assertEquals(listOf("local", "remote"), f.took())
    }

    /** The service goes first for half an hour, then the built-in signing gets another try. */
    @Test
    fun builtinAgainAfterHalfAnHour() = runTest {
        val f = Fallback()
        f.local = ErrorCode.ANONYMOUS_LIMITED
        f.play()
        f.took()
        f.clock += REMOTE_FOR_MS - 1
        f.play()
        assertEquals(listOf("remote"), f.took())
        f.clock += 1
        assertFalse(f.signer.remoteFirst)
        f.local = null
        assertEquals("local", f.play())
        assertEquals(listOf("local"), f.took())
    }

    /** The service first, and it too stops getting answers: the built-in signing's answer clears it. */
    @Test
    fun builtinWorkingAgainClearsTheService() = runTest {
        val f = Fallback()
        f.local = ErrorCode.ANONYMOUS_LIMITED
        f.play()
        f.took()
        f.local = null
        f.remote = ErrorCode.ANONYMOUS_LIMITED
        assertEquals("local", f.play())
        assertEquals(listOf("remote", "local"), f.took())
        assertFalse(f.signer.remoteFirst)
    }

    /** With both failing, the error is the built-in signing's, not the service's. */
    @Test
    fun signerDownKeepsBuiltinError() = runTest {
        val f = Fallback()
        f.local = ErrorCode.ANONYMOUS_LIMITED
        f.remote = ErrorCode.SIGNER_UNAVAILABLE
        assertEquals(ErrorCode.ANONYMOUS_LIMITED, f.fails())
        assertFalse("signing with the service first after it failed", f.signer.remoteFirst)

        // The same with the service first, as after it last worked.
        f.remote = null
        f.play()
        f.took()
        assertTrue(f.signer.remoteFirst)
        f.remote = ErrorCode.SIGNER_UNAVAILABLE
        f.local = ErrorCode.RATE_LIMITED
        assertEquals(ErrorCode.RATE_LIMITED, f.fails())
        assertEquals(listOf("remote", "local"), f.took())
    }

    /** An episode the service signs through to, if one under DRM, is an answer: the service goes first. */
    @Test
    fun drmProtectedIsAnAnswer() = runTest {
        val f = Fallback()
        f.local = ErrorCode.ANONYMOUS_LIMITED
        f.remote = ErrorCode.DRM_PROTECTED
        assertEquals(ErrorCode.DRM_PROTECTED, f.fails())
        assertTrue(f.signer.remoteFirst)
    }

    /** Without a service, nothing is retried. */
    @Test
    fun noSigner() = runTest {
        val f = Fallback(withRemote = false)
        f.local = ErrorCode.ANONYMOUS_LIMITED
        assertEquals(ErrorCode.ANONYMOUS_LIMITED, f.fails())
        assertEquals(listOf("local"), f.took())
    }

    /** Setting another service, or none, starts again from the built-in signing. */
    @Test
    fun settingTheServiceStartsOver() = runTest {
        val f = Fallback()
        f.local = ErrorCode.ANONYMOUS_LIMITED
        f.play()
        assertTrue(f.signer.remoteFirst)
        f.signer.setRemote("remote")
        assertFalse(f.signer.remoteFirst)
        f.signer.setRemote(null)
        assertFalse(f.signer.hasRemote)
    }

    @Test
    fun settingsFile() {
        val file = File(tempDir(), "no_backup/signer.json")
        val s = SignerSettings("https://signer.example.com", "t")
        s.save(file)
        assertTrue(file.canRead())
        assertEquals(s, SignerSettings.load(file))
        SignerSettings().save(file)
        assertFalse("cleared settings left their file", file.exists())
        assertEquals(SignerSettings(), SignerSettings.load(file))
        file.parentFile!!.mkdirs()
        file.writeText("{not json")
        assertEquals(SignerSettings(), SignerSettings.load(file))
    }

    @Test
    fun checkUrl() {
        for ((raw, ok) in mapOf(
            "" to true, "https://pp.example.com" to true, "http://127.0.0.1:19830" to true,
            "pp.example.com" to false, "ftp://x" to false, "https://" to false, "https://a b" to false,
        )) {
            assertEquals("checkSignerUrl($raw)", ok, checkSignerUrl(raw) == "")
        }
    }

    private var server: HttpServer? = null

    @After
    fun stop() {
        server?.stop(0)
    }

    /** A signing service on this computer; it is ready while `ready` says so, and takes the token "right". */
    private fun signerService(ready: () -> Boolean): String {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun reply(x: com.sun.net.httpserver.HttpExchange, code: Int, body: String) {
            val b = body.toByteArray()
            x.sendResponseHeaders(code, b.size.toLong())
            x.responseBody.use { it.write(b) }
        }
        srv.createContext("/") { x ->
            when (x.requestURI.path) {
                "/ready" -> reply(x, 200, """{"service":"openhongguo-signer","ready":${ready()}}""")
                "/feature-hash" ->
                    if (x.requestMethod != "POST" || x.requestHeaders.getFirst("X-Signer-Token") != "right") {
                        reply(x, 401, """{"error":"unauthorized"}""")
                    } else {
                        reply(x, 400, """{"error":"url is required"}""")
                    }
                else -> reply(x, 404, "404 page not found")
            }
        }
        srv.start()
        server = srv
        return "http://127.0.0.1:${srv.address.port}"
    }

    /** The settings' check tells a service that is not ready, a wrong token and a right one apart. */
    @Test
    fun probe() = runTest {
        var ready = true
        val url = signerService { ready }
        assertNull(probeSigner("$url/", "right"))
        assertEquals("token 不对", probeSigner(url, "wrong"))
        assertEquals("签名服务要求填写 token", probeSigner(url, ""))
        assertEquals("这个地址不是签名服务（HTTP 404）", probeSigner("$url/nothing", "right"))
        ready = false
        assertEquals("签名服务还在启动，稍后再试", probeSigner(url, "right"))
        server!!.stop(0)
        server = null
        assertEquals("连不上签名服务", probeSigner(url, "right"))
    }

    /**
     * A service that did not answer the check is saved by a second save, and
     * playback then falls back to it; clearing the address turns it off.
     */
    @Test
    fun formSavesAfterAFailedCheck() = runTest {
        val dir = tempDir()
        val catalog = FakeCatalog()
        val m = testModel(backgroundScope, catalog, dir = dir)
        var probed = 0
        m.probe = { _, _ -> probed++; "连不上签名服务" }
        val form = m.signerForm()
        assertEquals("", form.url)

        form.editUrl("signer.example.com")
        assertEquals("请填写以 http:// 或 https:// 开头的地址", form.urlError)
        assertFalse("saved an address that is not one", form.save())
        assertEquals(0, probed)

        form.editUrl(" https://signer.example.com ")
        form.editToken("tok")
        assertFalse(form.save())
        assertEquals("连不上签名服务", form.error)
        assertTrue(form.unsure)
        assertNull(catalog.signer)
        assertTrue("saved anyway", form.save())
        assertEquals(1, probed)
        assertEquals("https://signer.example.com" to "tok", catalog.signer)
        assertEquals(SignerSettings("https://signer.example.com", "tok"), m.signer)

        // The next launch falls back to it from the start, and the form opens with it.
        val catalog2 = FakeCatalog()
        val m2 = testModel(backgroundScope, catalog2, dir = dir)
        assertEquals("https://signer.example.com" to "tok", catalog2.signer)
        val form2 = m2.signerForm()
        assertEquals("https://signer.example.com", form2.url)
        assertEquals("tok", form2.token)

        form2.editUrl("")
        assertTrue(form2.save())
        assertNull(catalog2.signer)
        assertEquals(SignerSettings(), m2.signer)
        assertEquals(SignerSettings(), SignerSettings.load(File(dir, "data/signer.json")))
    }

    /** A service that answers the check is saved at once; editing after a failed check checks again. */
    @Test
    fun formChecksTheService() = runTest {
        val catalog = FakeCatalog()
        val m = testModel(backgroundScope, catalog)
        var answer: String? = "token 不对"
        m.probe = { _, _ -> answer }
        val form = m.signerForm()
        form.editUrl("https://signer.example.com")
        assertFalse(form.save())
        assertTrue(form.unsure)
        form.editToken("right")
        assertFalse("still sure to save after an edit", form.unsure)
        assertEquals("", form.error)
        answer = null
        assertTrue(form.save())
        assertNotNull(catalog.signer)
    }

    /** The service a launch cannot make a client for does not stop the app. */
    @Test
    fun badSavedServiceStillStarts() = runTest {
        val dir = tempDir()
        SignerSettings("https://signer.example.com", "").save(File(dir, "data/signer.json"))
        val catalog = object : Catalog by FakeCatalog() {
            override fun useSigner(url: String, token: String) = throw OpenhongguoException(ErrorCode.INVALID_OPTIONS, "bad")
        }
        try {
            AppModel(catalog, File(dir, "data"), File(dir, "cache"), backgroundScope, FakeVideo()) { _, _ -> "" }
        } catch (e: Exception) {
            fail("the model did not start: $e")
        }
    }
}
