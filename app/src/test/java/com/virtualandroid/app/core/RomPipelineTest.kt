package com.virtualandroid.app.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RomPipelineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MiniServer
    private lateinit var zipBytes: ByteArray
    private val rangeHeaders: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
    private val base get() = "http://127.0.0.1:${server.port}"

    private fun buildZip(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("system.img")); z.write(ByteArray(1_500_000).also { Random(1).nextBytes(it) }); z.closeEntry()
            z.putNextEntry(ZipEntry("build.prop")); z.write("ro.build.version.release=7.1.2\n".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("dir/sub/readme.txt")); z.write("merhaba".toByteArray()); z.closeEntry()
        }
        return out.toByteArray()
    }

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        zipBytes = buildZip()
        server = MiniServer { req ->
            when (req.path) {
                // MediaFire benzeri sayfa: indirme bağlantısı base64 ile gizli
                "/page" -> {
                    val link = Base64.getEncoder().encodeToString("$base/file.zip".toByteArray())
                    Resp(200, "text/html; charset=utf-8",
                        """<html><body><a id="downloadButton" data-scrambled-url="$link">Download</a></body></html>""".toByteArray())
                }
                "/nopage" -> Resp(200, "text/html", "<html><body>dosya silindi</body></html>".toByteArray())
                // dosya yerine HTML döndüren (engel / limit sayfası)
                "/blocked" -> Resp(200, "text/html", "<html>limit doldu</html>".toByteArray())
                // Range destekli dosya
                "/file.zip" -> {
                    val range = req.headers["range"]
                    if (range != null) rangeHeaders.add(range)
                    val start = range?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull() ?: 0
                    if (start > 0) {
                        Resp(206, "application/zip", zipBytes.copyOfRange(start, zipBytes.size),
                            mapOf("Content-Range" to "bytes $start-${zipBytes.size - 1}/${zipBytes.size}"))
                    } else {
                        Resp(200, "application/zip", zipBytes)
                    }
                }
                else -> Resp(404, "text/plain", "yok".toByteArray())
            }
        }
    }

    @After
    fun tearDown() = server.stop()

    private fun entry(url: String, source: String = "direct", sha: String = "") =
        RomEntry("android7", "Android 7", source, url, sha)

    // ---------- MediaFire sayfa çözümleme ----------

    @Test
    fun extractsHrefLink() {
        val html = """<a class="input popsok" id="downloadButton" href="https://download1654.mediafire.com/abc/pjq/rom_7.zip?x=1&amp;y=2">Download (316MB)</a>"""
        assertEquals("https://download1654.mediafire.com/abc/pjq/rom_7.zip?x=1&y=2", Mediafire.extractDirectUrl(html))
    }

    @Test
    fun extractsScrambledLink() {
        val b64 = Base64.getEncoder().encodeToString("https://download1.mediafire.com/x/y/z.zip".toByteArray())
        assertEquals("https://download1.mediafire.com/x/y/z.zip",
            Mediafire.extractDirectUrl("""<a data-scrambled-url="$b64" href="javascript:void(0)">"""))
    }

    @Test
    fun noLinkReturnsNull() {
        assertNull(Mediafire.extractDirectUrl("<html><body>Dosya bulunamadı</body></html>"))
    }

    // ---------- Güvenli zip açma ----------

    @Test
    fun zipSlipIsRejected() {
        val evil = tmp.newFile("evil.zip")
        ZipOutputStream(evil.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("../../evil.txt")); z.write("x".toByteArray()); z.closeEntry()
        }
        val dest = File(tmp.root, "out/inner")
        try {
            SafeUnzip.extract(evil, dest, { _, _ -> }, { false })
            fail("ArchiveException bekleniyordu")
        } catch (e: ArchiveException) {
            // beklenen
        }
        assertFalse(File(tmp.root, "evil.txt").exists())
        assertFalse(File(tmp.root, "out/evil.txt").exists())
    }

    @Test
    fun normalZipIsExtracted() {
        val z = tmp.newFile("ok.zip").also { it.writeBytes(zipBytes) }
        val dest = File(tmp.root, "out")
        val r = SafeUnzip.extract(z, dest, { _, _ -> }, { false })
        assertEquals(3, r.files)
        assertEquals("merhaba", File(dest, "dir/sub/readme.txt").readText())
        assertTrue(File(dest, "build.prop").readText().contains("7.1.2"))
    }

    @Test
    fun notAZipIsRejected() {
        val f = tmp.newFile("fake.zip").also { it.writeText("bu zip değil") }
        try {
            SafeUnzip.extract(f, File(tmp.root, "out"), { _, _ -> }, { false })
            fail("ArchiveException bekleniyordu")
        } catch (e: ArchiveException) {
            // beklenen
        }
    }

    // ---------- Uçtan uca kurulum ----------

    @Test
    fun fullInstallFromMediafireStylePage() {
        val root = File(tmp.root, "roms")
        val installer = RomInstaller(root)
        val phases = mutableListOf<Phase>()
        var lastDownload: JobState? = null
        installer.install(entry("$base/page", "mediafire", sha(zipBytes)), { s ->
            if (phases.lastOrNull() != s.phase) phases.add(s.phase)
            if (s.phase == Phase.DOWNLOADING) lastDownload = s
        }, { false })

        assertTrue(installer.isReady("android7"))
        assertTrue(File(root, "android7/system.img").length() == 1_500_000L)
        assertFalse("zip açıldıktan sonra silinmeli", File(root, "android7.zip").exists())
        assertFalse(File(root, "android7.tmp").exists())
        assertEquals(listOf(Phase.RESOLVING, Phase.DOWNLOADING, Phase.VERIFYING, Phase.EXTRACTING, Phase.READY), phases)
        assertEquals(zipBytes.size.toLong(), lastDownload!!.done)
        assertEquals(zipBytes.size.toLong(), lastDownload!!.total)
    }

    @Test
    fun alreadyReadyIsNoOp() {
        val root = File(tmp.root, "roms")
        val installer = RomInstaller(root)
        installer.install(entry("$base/file.zip"), { }, { false })
        rangeHeaders.clear()
        var phase: Phase? = null
        installer.install(entry("$base/file.zip"), { phase = it.phase }, { false })
        assertEquals(Phase.READY, phase)
    }

    @Test
    fun htmlInsteadOfFileIsRejected() {
        val root = File(tmp.root, "roms")
        val installer = RomInstaller(root)
        try {
            installer.install(entry("$base/blocked"), { }, { false })
            fail("InstallException bekleniyordu")
        } catch (e: InstallException) {
            assertTrue(e.message!!.contains("web sayfası"))
        }
        assertFalse(installer.isReady("android7"))
    }

    @Test
    fun pageWithoutLinkFails() {
        val installer = RomInstaller(File(tmp.root, "roms"))
        try {
            installer.install(entry("$base/nopage", "mediafire"), { }, { false })
            fail("InstallException bekleniyordu")
        } catch (e: InstallException) {
            assertTrue(e.message!!.contains("bağlantısı bulunamadı"))
        }
    }

    @Test
    fun shaMismatchDeletesZipAndFails() {
        val root = File(tmp.root, "roms")
        val installer = RomInstaller(root)
        try {
            installer.install(entry("$base/file.zip", sha = "0".repeat(64)), { }, { false })
            fail("InstallException bekleniyordu")
        } catch (e: InstallException) {
            assertTrue(e.message!!.contains("SHA-256"))
        }
        assertFalse(File(root, "android7.zip").exists())
        assertFalse(installer.isReady("android7"))
    }

    @Test
    fun resumeUsesRangeAndProducesIdenticalFile() {
        val root = File(tmp.root, "roms").also { it.mkdirs() }
        File(root, "android7.zip.part").writeBytes(zipBytes.copyOf(100_000))
        val installer = RomInstaller(root)
        installer.install(entry("$base/file.zip", sha = sha(zipBytes)), { }, { false })
        assertTrue("Range başlığı gönderilmeli: $rangeHeaders", rangeHeaders.contains("bytes=100000-"))
        assertTrue(installer.isReady("android7"))
        assertEquals("merhaba", File(root, "android7/dir/sub/readme.txt").readText())
    }

    @Test
    fun cancelKeepsPartialFile() {
        val root = File(tmp.root, "roms")
        val installer = RomInstaller(root)
        var calls = 0
        try {
            installer.install(entry("$base/file.zip"), { }, { ++calls > 5 })
            fail("CancelledException bekleniyordu")
        } catch (e: CancelledException) {
            // beklenen
        }
        val part = File(root, "android7.zip.part")
        assertTrue(part.isFile && part.length() > 0 && part.length() < zipBytes.size)
        assertFalse(installer.isReady("android7"))
        assertEquals(part.length(), installer.partialBytes("android7"))
    }

    @Test
    fun missingLinkFails() {
        try {
            RomInstaller(File(tmp.root, "roms")).install(entry(""), { }, { false })
            fail("InstallException bekleniyordu")
        } catch (e: InstallException) {
            assertTrue(e.message!!.contains("henüz eklenmedi"))
        }
    }

    @Test
    fun friendlyMessages() {
        assertTrue(Errors.friendly(UnknownHostException("x")).contains("İnternet"))
        assertEquals("merhaba", Errors.friendly(InstallException("merhaba")))
    }
}

private class Req(val path: String, val headers: Map<String, String>)

private class Resp(val code: Int, val type: String, val body: ByteArray, val extra: Map<String, String> = emptyMap())

/** Testler için çok küçük HTTP sunucusu. com.sun.net.httpserver Android test derleyicisinde görünmediği için ServerSocket kullanır. */
private class MiniServer(private val handler: (Req) -> Resp) {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort

    @Volatile
    private var running = true

    init {
        Thread {
            while (running) {
                val c = try { socket.accept() } catch (e: Exception) { break }
                Thread { serve(c) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun serve(c: Socket) {
        c.use { s ->
            try {
                val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val first = input.readLine() ?: return
                val path = first.split(" ").getOrElse(1) { "/" }
                val headers = HashMap<String, String>()
                while (true) {
                    val l = input.readLine() ?: break
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                val r = handler(Req(path, headers))
                val head = StringBuilder("HTTP/1.1 ${r.code} X\r\nContent-Type: ${r.type}\r\nContent-Length: ${r.body.size}\r\nConnection: close\r\n")
                r.extra.forEach { (k, v) -> head.append("$k: $v\r\n") }
                head.append("\r\n")
                val out = s.getOutputStream()
                out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
                out.write(r.body)
                out.flush()
            } catch (e: Exception) {
                // istemci bağlantıyı erken kapatmış olabilir (iptal testi)
            }
        }
    }

    fun stop() {
        running = false
        try { socket.close() } catch (e: Exception) { }
    }
}
