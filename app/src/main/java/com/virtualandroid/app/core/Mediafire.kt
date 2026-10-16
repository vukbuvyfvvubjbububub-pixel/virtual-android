package com.virtualandroid.app.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64

/**
 * MediaFire "dosya sayfası" bağlantısından gerçek indirme bağlantısını çıkarır.
 *
 * DİKKAT: MediaFire bunun için resmi bir API sunmuyor. Sayfa yapısı değişirse burası
 * bozulabilir. O zaman en sağlam çözüm dosyayı GitHub Releases'e koyup versions.json'da
 * "source": "direct" kullanmaktır.
 */
object Mediafire {
    private val scrambled = Regex("""data-scrambled-url\s*=\s*["']([^"']+)["']""")
    private val hrefDirect = Regex(
        """href\s*=\s*["'](https?://download[0-9a-z-]*\.mediafire\.com/[^"'\s]+)["']""",
        RegexOption.IGNORE_CASE
    )
    private val bareDirect = Regex(
        """https?://download[0-9a-z-]*\.mediafire\.com/[^"'\s<>\\]+""",
        RegexOption.IGNORE_CASE
    )

    /** Sayfa HTML'inden doğrudan indirme bağlantısını bulur, yoksa null döner. */
    fun extractDirectUrl(html: String): String? {
        scrambled.find(html)?.let { m ->
            try {
                val decoded = String(Base64.getDecoder().decode(m.groupValues[1].trim()), Charsets.UTF_8)
                if (decoded.startsWith("http")) return decoded
            } catch (_: IllegalArgumentException) {
                // base64 değil, diğer yöntemlere geç
            }
        }
        hrefDirect.find(html)?.let { return it.groupValues[1].replace("&amp;", "&") }
        bareDirect.find(html)?.let { return it.value.replace("&amp;", "&") }
        return null
    }

    /**
     * pageUrl bir HTML sayfası döndürürse içinden indirme bağlantısını çıkarır.
     * Zaten dosya döndürüyorsa (zip vb.) pageUrl'yi olduğu gibi geri verir.
     */
    fun resolve(pageUrl: String): String {
        val conn = Http.open(pageUrl, accept = "text/html,application/xhtml+xml,*/*")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw InstallException("MediaFire sayfası açılamadı (HTTP $code). Dosya silinmiş olabilir.")
            val type = conn.contentType.orEmpty()
            if (!type.contains("html", ignoreCase = true)) return pageUrl
            val html = readLimited(conn.inputStream, 3_000_000)
            return extractDirectUrl(html) ?: throw InstallException(
                "MediaFire sayfasında indirme bağlantısı bulunamadı. Dosya silinmiş veya MediaFire sayfa yapısını değiştirmiş olabilir."
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(stream: InputStream, max: Int): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        stream.use { input ->
            while (out.size() < max) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        return out.toString("UTF-8")
    }
}
